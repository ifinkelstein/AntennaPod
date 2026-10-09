package de.danoeh.antennapod.net.download.service.episode.adscan;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
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
    private static final long MAX_DURATION_MS = 3L * 60 * 60 * 1000;
    // With a 5 minute exponential backoff, five attempts span roughly 75 minutes
    private static final int MAX_ATTEMPTS = 5;
    private static final String TRANSCRIPT_CACHE_DIR = "adscan-transcripts";

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
        Constraints.Builder constraints = new Constraints.Builder().setRequiresBatteryNotLow(true);
        if (UserPreferences.isAllowMobileEpisodeDownload()) {
            constraints.setRequiredNetworkType(NetworkType.CONNECTED);
        } else {
            constraints.setRequiredNetworkType(NetworkType.UNMETERED);
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AdScanWorker.class)
                .addTag(WORK_TAG)
                .setConstraints(constraints.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putLong(WORK_DATA_MEDIA_ID, media.getId()).build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG + media.getId(),
                ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        long mediaId = getInputData().getLong(WORK_DATA_MEDIA_ID, -1);
        FeedMedia media = DBReader.getFeedMedia(mediaId);
        if (media == null || media.getLocalFileUrl() == null) {
            return Result.success();
        }
        if (!isEnabled()) {
            // Stay pending so the scan resumes once the feature is configured again
            return Result.success();
        }
        File file = new File(media.getLocalFileUrl());
        if (!file.exists()) {
            return Result.success();
        }
        long downloadDate = media.getDownloadDate();
        if (media.getDuration() > MAX_DURATION_MS) {
            finish(mediaId, AdScan.STATE_SKIPPED, downloadDate, Collections.emptyList());
            return Result.success();
        }

        DeepInfraClient client = new DeepInfraClient(UserPreferences.getDeepInfraApiKey(),
                UserPreferences.getAdSkipTranscriptionUrl(), UserPreferences.getAdSkipTranscriptionModel(),
                UserPreferences.getAdSkipChatUrl(), UserPreferences.getAdSkipChatModel());
        File workDir = new File(getApplicationContext().getCacheDir(), "adscan-" + mediaId);
        try {
            List<AudioChunker.Chunk> chunks = new ArrayList<>();
            if (AudioChunker.needsChunking(file)) {
                FileUtils.deleteQuietly(workDir);
                if (!workDir.mkdirs()) {
                    throw new IOException("Could not create work directory");
                }
                chunks = AudioChunker.split(file, workDir);
            } else {
                chunks.add(new AudioChunker.Chunk(file, 0, media.getMimeType()));
            }

            // Transcription is the expensive step, so finished chunks are cached and not paid for again on retry
            List<TranscriptSegment> transcript = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                if (isStopped()) {
                    return Result.retry();
                }
                File cached = transcriptCacheFile(mediaId, downloadDate, i);
                List<TranscriptSegment> chunkTranscript = readCachedTranscript(cached);
                if (chunkTranscript == null) {
                    AudioChunker.Chunk chunk = chunks.get(i);
                    chunkTranscript = client.transcribe(chunk.file, chunk.mimeType, chunk.offsetMs);
                    writeCachedTranscript(cached, chunkTranscript);
                }
                transcript.addAll(chunkTranscript);
            }
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

    private File transcriptCacheFile(long mediaId, long downloadDate, int chunkIndex) {
        File dir = new File(getApplicationContext().getCacheDir(), TRANSCRIPT_CACHE_DIR);
        return new File(dir, mediaId + "-" + downloadDate + "-" + chunkIndex + ".json");
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
