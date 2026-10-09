package de.danoeh.antennapod.ui.episodeslist;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.WorkInfo;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.AdScan;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.service.episode.adscan.AdScanWorker;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns ad scan state into icons and text.
 */
public final class AdScanUi {
    private AdScanUi() {
    }

    public static boolean isRelevant(@Nullable FeedMedia media) {
        return AdScanWorker.isEnabled() && AdScanWorker.canScan(media);
    }

    public static void bindRowIcon(ImageView view, @Nullable FeedMedia media) {
        AdScan scan = isRelevant(media) ? media.getAdScan() : null;
        if (scan == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setImageResource(iconFor(scan));
        view.setContentDescription(describe(view.getContext(), scan, null));
        view.setVisibility(View.VISIBLE);
    }

    @DrawableRes
    public static int iconFor(@Nullable AdScan scan) {
        if (scan == null) {
            return R.drawable.ic_skip_24dp;
        }
        switch (scan.getState()) {
            case AdScan.STATE_PENDING:
                return R.drawable.ic_hourglass;
            case AdScan.STATE_FAILED:
            case AdScan.STATE_SKIPPED:
                return R.drawable.ic_error;
            default:
                return scan.getSegments().isEmpty() ? R.drawable.ic_check : R.drawable.ic_skip_24dp;
        }
    }

    /**
     * One-line status. {@code work} adds live progress for scans that are running.
     */
    public static String describe(Context context, @Nullable AdScan scan, @Nullable WorkInfo work) {
        if (scan == null) {
            return context.getString(R.string.ad_scan_status_not_scanned);
        }
        switch (scan.getState()) {
            case AdScan.STATE_PENDING:
                return describePending(context, work);
            case AdScan.STATE_FAILED:
                return context.getString(R.string.ad_scan_status_failed);
            case AdScan.STATE_SKIPPED:
                return context.getString(R.string.ad_scan_status_skipped);
            default:
                List<AdSegment> segments = scan.getSegments();
                if (segments.isEmpty()) {
                    return context.getString(R.string.ad_scan_status_none);
                }
                return context.getResources().getQuantityString(R.plurals.ad_scan_status_found,
                        segments.size(), segments.size(), formatDuration(skippedMs(segments)));
        }
    }

    private static String describePending(Context context, @Nullable WorkInfo work) {
        if (work == null || work.getState().isFinished()) {
            return context.getString(R.string.ad_scan_status_paused);
        }
        if (work.getState() == WorkInfo.State.RUNNING) {
            Data progress = work.getProgress();
            if (AdScanWorker.STAGE_DETECTING.equals(progress.getString(AdScanWorker.PROGRESS_STAGE))) {
                return context.getString(R.string.ad_scan_status_detecting);
            }
            int parts = progress.getInt(AdScanWorker.PROGRESS_PARTS, 0);
            if (parts > 0) {
                return context.getString(R.string.ad_scan_status_transcribing,
                        progress.getInt(AdScanWorker.PROGRESS_PART, 1), parts);
            }
            return context.getString(R.string.ad_scan_status_running);
        }
        if (work.getRunAttemptCount() > 0) {
            return context.getString(R.string.ad_scan_status_retrying);
        }
        return context.getString(R.string.ad_scan_status_queued);
    }

    /**
     * Whether tapping the status should start a scan rather than show results.
     */
    public static boolean canStart(@Nullable AdScan scan, @Nullable WorkInfo work) {
        if (scan == null || scan.getState() == AdScan.STATE_FAILED) {
            return true;
        }
        return scan.getState() == AdScan.STATE_PENDING && (work == null || work.getState().isFinished());
    }

    public static boolean isSkipped(AdSegment segment) {
        Set<String> kinds = UserPreferences.getAdSkipKinds();
        return kinds.contains(segment.getKind());
    }

    public static long skippedMs(List<AdSegment> segments) {
        long total = 0;
        for (AdSegment segment : segments) {
            if (isSkipped(segment)) {
                total += segment.getEndMs() - segment.getStartMs();
            }
        }
        return total;
    }

    public static String kindLabel(Context context, String kind) {
        if (AdSegment.KIND_HOST_READ.equals(kind)) {
            return context.getString(R.string.ad_skip_chapter_title_host_read);
        } else if (AdSegment.KIND_PROMO.equals(kind)) {
            return context.getString(R.string.ad_skip_chapter_title_promo);
        } else if (AdSegment.KIND_INTRO.equals(kind)) {
            return context.getString(R.string.ad_skip_chapter_title_intro);
        } else if (AdSegment.KIND_OUTRO.equals(kind)) {
            return context.getString(R.string.ad_skip_chapter_title_outro);
        }
        return context.getString(R.string.ad_skip_chapter_title);
    }

    public static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
