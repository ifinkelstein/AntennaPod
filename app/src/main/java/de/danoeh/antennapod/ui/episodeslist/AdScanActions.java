package de.danoeh.antennapod.ui.episodeslist;

import android.content.Context;

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
        Schedulers.io().scheduleDirect(() -> {
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
        Schedulers.io().scheduleDirect(() ->
                showResult(appContext, AdScanWorker.enqueueDownloadedEpisodes(appContext, feed)));
    }

    private static void showResult(Context context, int count) {
        String message = count == 0 ? context.getString(R.string.detect_ads_nothing_to_scan)
                : context.getResources().getQuantityString(R.plurals.detect_ads_queued_message, count, count);
        EventBus.getDefault().post(new MessageEvent(message));
    }
}
