package de.danoeh.antennapod.net.download.service.episode.adscan;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.event.AdScanCompletedEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.AdScan;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.net.download.service.R;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import org.apache.commons.io.FileUtils;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AdScanWorker extends Worker {
    private static final String TAG = "AdScanWorker";
    public static final String WORK_TAG = "adScan";
    private static final String WORK_DATA_MEDIA_ID = "media_id";
    // Cost guard: a 6 hour episode costs about 8 cents to transcribe
    private static final long MAX_DURATION_MS = 6L * 60 * 60 * 1000;
    // With a 5 minute exponential backoff, five attempts span roughly 75 minutes
    private static final int MAX_ATTEMPTS = 5;
    private static final String TRANSCRIPT_CACHE_DIR = "adscan-transcripts";
    public static final String PROGRESS_STAGE = "stage";
    public static final String PROGRESS_PART = "part";
    public static final String PROGRESS_PARTS = "parts";
    public static final String STAGE_TRANSCRIBING = "transcribing";
    public static final String STAGE_DETECTING = "detecting";

    public AdScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static boolean isEnabled() {
        return UserPreferences.isAdSkipEnabled() && !UserPreferences.getDeepInfraApiKey().isEmpty();
    }

    public static boolean canScan(@Nullable FeedMedia media) {
        return isEnabled() && media != null && media.isDownloaded() && media.getLocalFileUrl() != null
                && media.getMimeType() != null && media.getMimeType().startsWith("audio/");
    }

    /**
     * Whether a scan would produce something new, i.e. this download was never scanned or its scan did not finish.
     */
    @WorkerThread
    public static boolean needsScan(FeedMedia media) {
        if (!canScan(media)) {
            return false;
        }
        AdScan scan = DBReader.loadAdScan(media.getId());
        return scan == null || !scan.isValidFor(media)
                || scan.getState() == AdScan.STATE_PENDING || scan.getState() == AdScan.STATE_FAILED;
    }

    /**
     * Scans every downloaded episode of the feed that has not been scanned yet.
     *
     * @return the number of episodes that were queued
     */
    @WorkerThread
    public static int enqueueDownloadedEpisodes(Context context, Feed feed) {
        if (!isEnabled()) {
            return 0;
        }
        List<FeedItem> items = DBReader.getFeedItemList(feed,
                new FeedItemFilter(FeedItemFilter.DOWNLOADED), SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
        int count = 0;
        for (FeedItem item : items) {
            if (needsScan(item.getMedia())) {
                enqueue(context, item.getMedia());
                count++;
            }
        }
        return count;
    }

    /**
     * Re-queues scans that are waiting or failed, e.g. after the API key or endpoints were changed.
     */
    @WorkerThread
    public static int resumeUnfinished(Context context) {
        if (!isEnabled()) {
            return 0;
        }
        int count = 0;
        for (AdScan scan : DBReader.getAdScansByState(AdScan.STATE_PENDING, AdScan.STATE_FAILED)) {
            FeedMedia media = DBReader.getFeedMedia(scan.getMediaId());
            if (scan.isValidFor(media) && canScan(media)) {
                enqueue(context, media);
                count++;
            }
        }
        Log.d(TAG, "Resumed " + count + " unfinished scans");
        return count;
    }

    @WorkerThread
    public static void enqueue(Context context, FeedMedia media) {
        if (!canScan(media)) {
            return;
        }
        try {
            DBWriter.setAdScan(new AdScan(media.getId(), AdScan.STATE_PENDING, media.getDownloadDate(),
                    Collections.emptyList())).get();
        } catch (Exception e) {
            Log.e(TAG, "Could not store pending scan", e);
        }
        try {
            OneTimeWorkRequest request = buildRequest(media.getId(), UserPreferences.isAllowMobileEpisodeDownload());
            // KEEP: a scan that is already queued or running continues instead of being started twice
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(media.getId()),
                    ExistingWorkPolicy.KEEP, request);
        } catch (RuntimeException e) {
            // Callers run in downloads and UI actions; a scheduling problem must not take them down
            Log.e(TAG, "Could not schedule ad scan for " + media.getEpisodeTitle(), e);
            DBWriter.setAdScan(new AdScan(media.getId(), AdScan.STATE_FAILED, media.getDownloadDate(),
                    Collections.emptyList()));
        }
    }

    /**
     * Not expedited: expedited work cannot require battery-not-low, and WorkManager throws when building it.
     * Long requests are protected by running in the foreground instead, see {@link #tryRunInForeground}.
     */
    static OneTimeWorkRequest buildRequest(long mediaId, boolean allowMobileData) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(allowMobileData ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                .build();
        return new OneTimeWorkRequest.Builder(AdScanWorker.class)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putLong(WORK_DATA_MEDIA_ID, mediaId).build())
                .build();
    }

    public static String uniqueWorkName(long mediaId) {
        return WORK_TAG + mediaId;
    }

    public static LiveData<List<WorkInfo>> observe(Context context, long mediaId) {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(uniqueWorkName(mediaId));
    }

    private ForegroundInfo createForegroundInfo(@Nullable String episodeTitle) {
        Context context = getApplicationContext();
        Notification notification = new NotificationCompat.Builder(context, NotificationUtils.CHANNEL_ID_DOWNLOADING)
                .setContentTitle(context.getString(R.string.ad_scan_notification_title))
                .setContentText(episodeTitle)
                .setSmallIcon(R.drawable.ic_notification_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            return new ForegroundInfo(R.id.notification_ad_scan, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(R.id.notification_ad_scan, notification);
    }

    /**
     * Runs the scan as a foreground service so Doze and the 10 minute job limit cannot cut off a request.
     * Android refuses this while the app is in the background; the scan then continues as a normal job.
     */
    private void tryRunInForeground(String episodeTitle) {
        try {
            setForegroundAsync(createForegroundInfo(episodeTitle)).get();
        } catch (Exception e) {
            Log.d(TAG, "Running ad scan in background: " + e.getMessage());
        }
    }

    private void reportProgress(String stage, int part, int parts) {
        setProgressAsync(new Data.Builder().putString(PROGRESS_STAGE, stage)
                .putInt(PROGRESS_PART, part).putInt(PROGRESS_PARTS, parts).build());
    }

    @NonNull
    @Override
    public Result doWork() {
        long mediaId = getInputData().getLong(WORK_DATA_MEDIA_ID, -1);
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || media.getLocalFileUrl() == null) {
            Log.w(TAG, "Not scanning media " + mediaId + ": no longer downloaded");
            return Result.success();
        }
        if (!isEnabled()) {
            // Stay pending so the scan resumes once the feature is configured again
            Log.w(TAG, "Not scanning " + media.getEpisodeTitle() + ": ad skipping is off or has no API key");
            return Result.success();
        }
        File file = new File(media.getLocalFileUrl());
        long downloadDate = media.getDownloadDate();
        if (!file.exists()) {
            Log.w(TAG, "Not scanning " + media.getEpisodeTitle() + ": file is missing " + file);
            finish(mediaId, AdScan.STATE_SKIPPED, downloadDate, Collections.emptyList());
            return Result.success();
        }
        if (media.getDuration() > MAX_DURATION_MS) {
            Log.w(TAG, "Not scanning " + media.getEpisodeTitle() + ": longer than "
                    + MAX_DURATION_MS / 3_600_000 + " hours");
            finish(mediaId, AdScan.STATE_SKIPPED, downloadDate, Collections.emptyList());
            return Result.success();
        }
        Log.d(TAG, "Scanning " + media.getEpisodeTitle());

        DeepInfraClient client = new DeepInfraClient(UserPreferences.getDeepInfraApiKey(),
                UserPreferences.getAdSkipTranscriptionUrl(), UserPreferences.getAdSkipTranscriptionModel(),
                UserPreferences.getAdSkipChatUrl(), UserPreferences.getAdSkipChatModel());
        tryRunInForeground(media.getEpisodeTitle());
        // Per-run directory: a stopped run may still be cleaning up while its replacement starts
        File workDir = new File(getApplicationContext().getCacheDir(), "adscan-" + mediaId + "-" + getId());
        try {
            List<AudioChunker.Chunk> chunks = splitIntoChunks(file, media, workDir);

            // Transcription is the expensive step, so finished chunks are cached and not paid for again on retry
            List<TranscriptSegment> transcript = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                if (isStopped()) {
                    return Result.retry();
                }
                reportProgress(STAGE_TRANSCRIBING, i + 1, chunks.size());
                AudioChunker.Chunk chunk = chunks.get(i);
                File cached = transcriptCacheFile(mediaId, downloadDate, chunk);
                List<TranscriptSegment> chunkTranscript = readCachedTranscript(cached);
                if (chunkTranscript == null) {
                    chunkTranscript = client.transcribe(chunk.file, chunk.mimeType, chunk.offsetMs);
                    writeCachedTranscript(cached, chunkTranscript);
                }
                transcript.addAll(chunkTranscript);
            }
            if (isStopped()) {
                return Result.retry();
            }
            reportProgress(STAGE_DETECTING, 0, 0);
            // Saved even if the run was stopped meanwhile: the requests are paid for and storing is instant
            List<AdSegment> segments = new AdDetector(client).detect(transcript);

            FeedMedia current = DBReader.getFeedMedia(mediaId);
            if (!isSameDownload(current, downloadDate)) {
                deleteTranscriptCache(mediaId);
                return Result.success();
            }
            if (current.getItem() == null || current.getItem().getTranscriptUrl() == null) {
                TranscriptUtils.storeTranscript(current, AdDetector.toPodcastIndexJson(transcript));
            }
            finish(mediaId, AdScan.STATE_DONE, downloadDate, segments);
            EventBus.getDefault().post(new AdScanCompletedEvent(mediaId));
            Log.d(TAG, "Found " + segments.size() + " segments in " + current.getEpisodeTitle());
            return Result.success();
        } catch (AudioChunker.UnsupportedFormatException e) {
            Log.w(TAG, "Skipping ad scan: " + e.getMessage());
            finish(mediaId, AdScan.STATE_SKIPPED, downloadDate, Collections.emptyList());
            return Result.success();
        } catch (DeepInfraClient.ApiException e) {
            Log.e(TAG, "Request failed", e);
            if (e.isAuthError() || e.isPaymentError()) {
                // Every other scan would fail the same way. Leave them pending; changing the settings resumes them.
                WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);
                EventBus.getDefault().post(new MessageEvent(getApplicationContext().getString(
                        e.isPaymentError() ? R.string.ad_skip_payment_required : R.string.ad_skip_key_invalid)));
                return Result.failure();
            }
            return retryOrFail(mediaId, downloadDate, e.isRetryable());
        } catch (IOException e) {
            Log.e(TAG, "Ad scan failed", e);
            return retryOrFail(mediaId, downloadDate, true);
        } finally {
            FileUtils.deleteQuietly(workDir);
        }
    }

    private Result retryOrFail(long mediaId, long downloadDate, boolean retryable) {
        if (retryable && getRunAttemptCount() + 1 < MAX_ATTEMPTS) {
            return Result.retry();
        }
        // Keep cached transcripts: a later resume only needs to redo what failed
        store(mediaId, AdScan.STATE_FAILED, downloadDate, Collections.emptyList());
        return Result.failure();
    }

    private static boolean isSameDownload(FeedMedia media, long downloadDate) {
        return media != null && media.getLocalFileUrl() != null && media.getDownloadDate() == downloadDate;
    }

    private void finish(long mediaId, int state, long downloadDate, List<AdSegment> segments) {
        store(mediaId, state, downloadDate, segments);
        deleteTranscriptCache(mediaId);
    }

    private void store(long mediaId, int state, long downloadDate, List<AdSegment> segments) {
        if (!isSameDownload(DBReader.getFeedMedia(mediaId), downloadDate)) {
            return;
        }
        try {
            DBWriter.setAdScan(new AdScan(mediaId, state, downloadDate, segments)).get();
        } catch (Exception e) {
            Log.e(TAG, "Could not store scan result", e);
        }
    }

    private static List<AudioChunker.Chunk> splitIntoChunks(File file, FeedMedia media, File workDir)
            throws IOException {
        List<AudioChunker.Chunk> wholeFile = Collections.singletonList(
                new AudioChunker.Chunk(file, 0, media.getMimeType()));
        boolean tooBig = AudioChunker.needsChunking(file);
        boolean tooLong = media.getDuration() * 1000L > AudioChunker.MAX_CHUNK_DURATION_US;
        if (!tooBig && !tooLong) {
            return wholeFile;
        }
        FileUtils.deleteQuietly(workDir);
        if (!workDir.mkdirs()) {
            throw new IOException("Could not create work directory");
        }
        try {
            return AudioChunker.split(file, workDir);
        } catch (AudioChunker.UnsupportedFormatException e) {
            if (tooBig) {
                throw e;
            }
            // Splitting only shortens requests; a file under the upload limit can still be sent whole
            return wholeFile;
        }
    }

    private File transcriptCacheFile(long mediaId, long downloadDate, AudioChunker.Chunk chunk) {
        File dir = new File(getApplicationContext().getCacheDir(), TRANSCRIPT_CACHE_DIR);
        // Offset and size identify the chunk even if the chunking parameters change between runs
        return new File(dir, mediaId + "-" + downloadDate + "-" + chunk.offsetMs + "-" + chunk.file.length() + ".json");
    }

    private void deleteTranscriptCache(long mediaId) {
        File[] files = new File(getApplicationContext().getCacheDir(), TRANSCRIPT_CACHE_DIR).listFiles();
        if (files == null) {
            return;
        }
        for (File cached : files) {
            if (cached.getName().startsWith(mediaId + "-")) {
                FileUtils.deleteQuietly(cached);
            }
        }
    }

    @Nullable
    private static List<TranscriptSegment> readCachedTranscript(File file) {
        if (!file.exists()) {
            return null;
        }
        try {
            JSONArray array = new JSONArray(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
            List<TranscriptSegment> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONArray segment = array.getJSONArray(i);
                result.add(new TranscriptSegment(segment.getLong(0), segment.getLong(1), segment.getString(2), ""));
            }
            return result;
        } catch (IOException | JSONException e) {
            FileUtils.deleteQuietly(file);
            return null;
        }
    }

    private static void writeCachedTranscript(File file, List<TranscriptSegment> transcript) {
        JSONArray array = new JSONArray();
        for (TranscriptSegment segment : transcript) {
            array.put(new JSONArray().put(segment.getStartTime()).put(segment.getEndTime()).put(segment.getWords()));
        }
        try {
            FileUtils.writeStringToFile(file, array.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Could not cache transcript", e);
        }
    }
}
