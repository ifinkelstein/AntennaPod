package de.danoeh.antennapod.ui.episodeslist;

import android.content.Context;
import android.util.Log;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.net.download.service.episode.adscan.AdScanWorker;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Starts ad scans for episodes that are already on the device.
 */
public final class AdScanActions {
    private static final String TAG = "AdScanActions";

    private AdScanActions() {
    }

    public static boolean canScanAny(List<FeedItem> items) {
        for (FeedItem item : items) {
            if (AdScanWorker.canScan(item.getMedia())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans the given episodes, also those that were scanned before.
     */
    public static void scanEpisodes(Context context, List<FeedItem> items) {
        Context appContext = context.getApplicationContext();
        List<FeedItem> episodes = new ArrayList<>(items);
        runSafely(appContext, () -> {
            int count = 0;
            for (FeedItem item : episodes) {
                if (AdScanWorker.canScan(item.getMedia())) {
                    AdScanWorker.enqueue(appContext, item.getMedia());
                    count++;
                }
            }
            showResult(appContext, count);
        });
    }

    /**
     * Scans downloaded episodes of the feed that were not scanned yet.
     */
    public static void scanFeed(Context context, Feed feed) {
        Context appContext = context.getApplicationContext();
        runSafely(appContext, () ->
                showResult(appContext, AdScanWorker.enqueueDownloadedEpisodes(appContext, feed)));
    }

    /**
     * An exception on a bare background thread kills the app, so failures are reported instead.
     */
    private static void runSafely(Context context, Runnable action) {
        Schedulers.io().scheduleDirect(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not start ad detection", e);
                EventBus.getDefault().post(new MessageEvent(context.getString(R.string.ad_scan_start_failed)));
            }
        });
    }

    private static void showResult(Context context, int count) {
        String message = count == 0 ? context.getString(R.string.detect_ads_nothing_to_scan)
                : context.getResources().getQuantityString(R.plurals.detect_ads_queued_message, count, count);
        EventBus.getDefault().post(new MessageEvent(message));
    }
}
