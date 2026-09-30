package de.danoeh.antennapod.net.download.service.episode.adscan;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
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
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.net.download.service.R;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import org.apache.commons.io.FileUtils;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AdScanWorker extends Worker {
    private static final String TAG = "AdScanWorker";
    public static final String WORK_TAG = "adScan";
    private static final String WORK_DATA_MEDIA_ID = "media_id";
    private static final long MAX_DURATION_MS = 3L * 60 * 60 * 1000;
    private static final int MAX_ATTEMPTS = 3;

    public AdScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static boolean isEnabled() {
        return UserPreferences.isAdSkipEnabled() && !UserPreferences.getDeepInfraApiKey().isEmpty();
    }

    public static void enqueue(Context context, FeedMedia media) {
        if (!isEnabled() || media.getLocalFileUrl() == null
                || media.getMimeType() == null || !media.getMimeType().startsWith("audio/")) {
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
            store(mediaId, AdScan.STATE_SKIPPED, media.getDownloadDate(), Collections.emptyList());
            return Result.success();
        }
        File file = new File(media.getLocalFileUrl());
        if (!file.exists()) {
            return Result.success();
        }
        long downloadDate = media.getDownloadDate();
        if (media.getDuration() > MAX_DURATION_MS) {
            store(mediaId, AdScan.STATE_SKIPPED, downloadDate, Collections.emptyList());
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

            List<TranscriptSegment> transcript = new ArrayList<>();
            for (AudioChunker.Chunk chunk : chunks) {
                if (isStopped()) {
                    return Result.retry();
                }
                transcript.addAll(client.transcribe(chunk.file, chunk.mimeType, chunk.offsetMs));
            }
            List<AdSegment> segments = new AdDetector(client).detect(transcript);

            FeedMedia current = DBReader.getFeedMedia(mediaId);
            if (!isSameDownload(current, downloadDate)) {
                return Result.success();
            }
            if (current.getItem() == null || current.getItem().getTranscriptUrl() == null) {
                TranscriptUtils.storeTranscript(current, AdDetector.toPodcastIndexJson(transcript));
            }
            store(mediaId, AdScan.STATE_DONE, downloadDate, segments);
            EventBus.getDefault().post(new AdScanCompletedEvent(mediaId));
            Log.d(TAG, "Found " + segments.size() + " segments in " + current.getEpisodeTitle());
            return Result.success();
        } catch (AudioChunker.UnsupportedFormatException e) {
            Log.w(TAG, "Skipping ad scan: " + e.getMessage());
            store(mediaId, AdScan.STATE_SKIPPED, downloadDate, Collections.emptyList());
            return Result.success();
        } catch (DeepInfraClient.ApiException e) {
            Log.e(TAG, "Request failed", e);
            if (e.isAuthError()) {
                store(mediaId, AdScan.STATE_FAILED, downloadDate, Collections.emptyList());
                WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);
                EventBus.getDefault().post(new MessageEvent(
                        getApplicationContext().getString(R.string.ad_skip_key_invalid)));
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
        store(mediaId, AdScan.STATE_FAILED, downloadDate, Collections.emptyList());
        return Result.failure();
    }

    private static boolean isSameDownload(FeedMedia media, long downloadDate) {
        return media != null && media.getLocalFileUrl() != null && media.getDownloadDate() == downloadDate;
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
}
