package de.danoeh.antennapod.ui.episodeslist;

import android.content.Context;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.AdScan;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.download.service.episode.adscan.AdScanWorker;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.Collections;
import java.util.List;

public final class AdScanDialogs {
    private static final String TAG = "AdScanDialogs";

    private AdScanDialogs() {
    }

    /**
     * Lists the marked sections of one episode and offers a rescan.
     */
    public static void showEpisodeResult(Context context, FeedItem item) {
        FeedMedia media = item.getMedia();
        AdScan scan = media != null ? media.getAdScan() : null;
        StringBuilder message = new StringBuilder(AdScanUi.describe(context, scan, null));
        if (scan != null) {
            for (AdSegment segment : scan.getSegments()) {
                message.append("\n\n").append(context.getString(R.string.ad_scan_segment_line,
                        AdScanUi.kindLabel(context, segment.getKind()),
                        AdScanUi.formatDuration(segment.getStartMs()),
                        AdScanUi.formatDuration(segment.getEndMs()),
                        AdScanUi.formatDuration(segment.getEndMs() - segment.getStartMs()),
                        context.getString(AdScanUi.isSkipped(segment)
                                ? R.string.ad_scan_segment_skipped : R.string.ad_scan_segment_kept)));
            }
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ad_scan_details_title)
                .setMessage(message)
                .setPositiveButton(R.string.ad_scan_rescan, (dialog, which) ->
                        AdScanActions.scanEpisodes(context, Collections.singletonList(item)))
                .setNegativeButton(R.string.close_label, null)
                .show();
    }

    /**
     * Summarizes the scans of all downloaded episodes of a podcast and offers to scan the rest.
     */
    public static void showFeedStatus(Context context, Feed feed) {
        Single.fromCallable(() -> DBReader.getFeedItemList(feed,
                        new FeedItemFilter(FeedItemFilter.DOWNLOADED), SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> showFeedStatus(context, feed, items),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void showFeedStatus(Context context, Feed feed, List<FeedItem> items) {
        int downloaded = 0;
        int done = 0;
        int running = 0;
        int failed = 0;
        int notScannable = 0;
        int sections = 0;
        long skippedMs = 0;
        StringBuilder episodes = new StringBuilder();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (!AdScanWorker.canScan(media)) {
                continue;
            }
            downloaded++;
            AdScan scan = media.getAdScan();
            if (scan != null && scan.getState() == AdScan.STATE_DONE) {
                done++;
                sections += scan.getSegments().size();
                skippedMs += AdScanUi.skippedMs(scan.getSegments());
            } else if (scan != null && scan.getState() == AdScan.STATE_PENDING) {
                running++;
            } else if (scan != null && scan.getState() == AdScan.STATE_FAILED) {
                failed++;
            } else if (scan != null) {
                notScannable++;
            }
            episodes.append("\n\n").append(item.getTitle()).append("\n")
                    .append(AdScanUi.describe(context, scan, null));
        }
        int notScanned = downloaded - done - running - failed - notScannable;
        // Matches what AdScanWorker.enqueueDownloadedEpisodes picks up
        int remaining = notScanned + failed;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ad_scan_details_title)
                .setNegativeButton(R.string.close_label, null);
        if (downloaded == 0) {
            builder.setMessage(R.string.ad_scan_feed_none_downloaded).show();
            return;
        }
        String summary = context.getString(R.string.ad_scan_feed_summary, downloaded, done, running,
                notScanned, failed + notScannable)
                + "\n" + context.getResources().getQuantityString(R.plurals.ad_scan_feed_total,
                        sections, sections, AdScanUi.formatDuration(skippedMs));
        builder.setMessage(summary + episodes);
        if (remaining > 0) {
            builder.setPositiveButton(context.getResources().getQuantityString(
                    R.plurals.ad_scan_feed_scan_remaining, remaining, remaining),
                    (dialog, which) -> AdScanActions.scanFeed(context, feed));
        }
        builder.show();
    }
}
