package de.danoeh.antennapod.ui.chapters;

import android.content.Context;

import androidx.annotation.Nullable;
import de.danoeh.antennapod.model.feed.AdScan;
import de.danoeh.antennapod.model.feed.AdSegment;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AdChapterOverlay {
    static final long SNAP_MS = 1000;
    static final long MAX_GAP_MS = 12000;

    private AdChapterOverlay() {
    }

    @Nullable
    public static List<Chapter> apply(@Nullable List<Chapter> chapters, FeedMedia media, Context context) {
        if (!UserPreferences.isAdSkipEnabled()) {
            return chapters;
        }
        AdScan scan = DBReader.loadAdScan(media.getId());
        if (scan == null || scan.getState() != AdScan.STATE_DONE || !scan.isValidFor(media)
                || scan.getSegments().isEmpty()) {
            return chapters;
        }
        return overlay(chapters, scan.getSegments(), media.getDuration(), media.getEpisodeTitle(), context);
    }

    static List<Chapter> overlay(@Nullable List<Chapter> chapters, List<AdSegment> segments, long duration,
                                 String episodeTitle, Context context) {
        List<Chapter> base = new ArrayList<>();
        if (chapters != null) {
            for (Chapter chapter : chapters) {
                if (!chapter.isSkippable()) {
                    base.add(chapter);
                }
            }
        }
        if (base.isEmpty()) {
            base.add(new Chapter(0, episodeTitle, null, null));
        }

        List<AdSegment> ranges = new ArrayList<>();
        for (AdSegment segment : segments) {
            long start = segment.getStartMs() <= SNAP_MS ? 0 : segment.getStartMs();
            long end = segment.getEndMs();
            if (duration > 0 && end >= duration - SNAP_MS) {
                end = duration;
            }
            if (end > start) {
                ranges.add(new AdSegment(start, end, segment.getKind(), segment.getConfidence()));
            }
        }
        Collections.sort(ranges, (a, b) -> Long.compare(a.getStartMs(), b.getStartMs()));
        closeShortGaps(ranges);

        List<Chapter> result = new ArrayList<>();
        for (Chapter chapter : base) {
            if (!isInsideRange(chapter.getStart(), ranges)) {
                result.add(copy(chapter, chapter.getStart(), null, chapter.getTitle()));
            }
        }
        for (int i = 0; i < ranges.size(); i++) {
            AdSegment range = ranges.get(i);
            result.add(copy(null, range.getStartMs(), range.getKind(), titleFor(range.getKind(), context)));
            boolean reachesEnd = duration > 0 && range.getEndMs() >= duration;
            boolean nextFollows = i + 1 < ranges.size()
                    && ranges.get(i + 1).getStartMs() - range.getEndMs() <= SNAP_MS;
            if (!reachesEnd && !nextFollows) {
                Chapter resumed = lastChapterBefore(base, range.getEndMs() + SNAP_MS);
                String title = resumed != null ? resumed.getTitle() : episodeTitle;
                result.add(copy(resumed, range.getEndMs(), null, title));
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.getStart(), b.getStart()));
        return result;
    }

    /**
     * A few seconds between two marked sections are almost always the tail of the first ad (the transcript
     * lines are coarse), not show content, so the break is treated as one block.
     */
    static void closeShortGaps(List<AdSegment> ranges) {
        for (int i = 0; i + 1 < ranges.size(); i++) {
            AdSegment range = ranges.get(i);
            long nextStart = ranges.get(i + 1).getStartMs();
            long gap = nextStart - range.getEndMs();
            if (gap > 0 && gap <= MAX_GAP_MS) {
                ranges.set(i, new AdSegment(range.getStartMs(), nextStart, range.getKind(), range.getConfidence()));
            }
        }
    }

    private static boolean isInsideRange(long time, List<AdSegment> ranges) {
        for (AdSegment range : ranges) {
            if (time >= range.getStartMs() - SNAP_MS && time < range.getEndMs() + SNAP_MS) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Chapter lastChapterBefore(List<Chapter> chapters, long time) {
        Chapter result = null;
        for (Chapter chapter : chapters) {
            if (chapter.getStart() <= time && (result == null || chapter.getStart() >= result.getStart())) {
                result = chapter;
            }
        }
        return result;
    }

    private static String titleFor(String kind, Context context) {
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

    private static Chapter copy(@Nullable Chapter source, long start, @Nullable String skipKind, String title) {
        Chapter chapter = new Chapter(start, title,
                source != null ? source.getLink() : null, source != null ? source.getImageUrl() : null);
        if (source != null) {
            chapter.setId(source.getId());
            chapter.setChapterId(source.getChapterId());
        }
        chapter.setSkipKind(skipKind);
        return chapter;
    }
}
