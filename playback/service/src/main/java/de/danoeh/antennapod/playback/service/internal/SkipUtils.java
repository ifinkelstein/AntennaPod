package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.util.Log;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.greenrobot.eventbus.EventBus;

import java.util.List;
import java.util.Set;

public final class SkipUtils {
    private static final String TAG = "SkipUtils";

    private SkipUtils() {
    }

    /**
     * Returns the position to start playback at, taking into account the configured skip intro time.
     * Uses media's saved position if > 0.
     */
    public static long skipIntroIfNecessary(Context context, FeedMedia media) {
        if (media.getItem() == null || media.getItem().getFeed() == null
                || media.getItem().getFeed().getPreferences() == null) {
            return media.getPosition();
        }
        int skipIntro = media.getItem().getFeed().getPreferences().getFeedSkipIntro();
        long duration = media.getDuration();
        long startPosition = media.getPosition();
        if (skipIntro > 0 && media.getPosition() < skipIntro * 1000L
                && (skipIntro * 1000L < duration || duration <= 0)) {
            startPosition = skipIntro * 1000L;
        }
        if (startPosition != media.getPosition()) {
            Log.d(TAG, "skipIntro " + media.getEpisodeTitle());
            EventBus.getDefault().post(new MessageEvent(
                    context.getResources().getQuantityString(R.plurals.pref_feed_skip_intro_snackbar,
                            (int) (startPosition / 1000), (int) (startPosition / 1000))));
        }
        return startPosition;
    }

    /**
     * Returns true and notifies the user if the ending should be
     * skipped given the current position, duration and playback speed.
     */
    public static boolean skipEndingIfNecessary(Context context, FeedMedia media,
                                                long position, long duration, float speed) {
        if (media.getItem() == null || media.getItem().getFeed() == null
                || media.getItem().getFeed().getPreferences() == null) {
            return false;
        }
        FeedPreferences preferences = media.getItem().getFeed().getPreferences();
        int skipEnd = preferences.getFeedSkipEnding();
        long remainingTime = duration - position;
        if (skipEnd > 0
                && skipEnd * 1000L < duration
                && (remainingTime - (skipEnd * 1000L) > 0)
                && ((remainingTime - skipEnd * 1000L) < (speed * 1000))) {
            Log.d(TAG, "skipEndingIfNecessary: Skipping remaining " + (duration - position));
            EventBus.getDefault().post(
                    new MessageEvent(
                            context.getResources().getQuantityString(
                                    R.plurals.pref_feed_skip_ending_snackbar, skipEnd, skipEnd)));
            return true;
        }
        return false;
    }

    public interface UndoHandler {
        boolean undo(long position);
    }

    public static long skipAdIfNecessary(Context context, FeedMedia media, long position, long duration,
                                         Set<Long> ignoredChapterStarts, UndoHandler undoHandler) {
        if (!UserPreferences.isAdSkipEnabled()) {
            return -1;
        }
        List<Chapter> chapters = media.getChapters();
        int index = Chapter.getAfterPosition(chapters, (int) position);
        if (index < 0) {
            return -1;
        }
        Set<String> kinds = UserPreferences.getAdSkipKinds();
        Chapter chapter = chapters.get(index);
        if (!isSkipped(chapter, kinds) || ignoredChapterStarts.contains(chapter.getStart())) {
            return -1;
        }
        long target = duration;
        for (int i = index + 1; i < chapters.size(); i++) {
            if (!isSkipped(chapters.get(i), kinds) || ignoredChapterStarts.contains(chapters.get(i).getStart())) {
                target = chapters.get(i).getStart();
                break;
            }
        }
        if (target - position < 1000) {
            return -1;
        }
        int skippedSeconds = (int) ((target - position) / 1000);
        Log.d(TAG, "skipAdIfNecessary: skipping " + skippedSeconds + "s in " + media.getEpisodeTitle());
        final long chapterStart = chapter.getStart();
        EventBus.getDefault().post(new MessageEvent(
                context.getResources().getQuantityString(R.plurals.ad_skipped_snackbar, skippedSeconds, skippedSeconds),
                ctx -> {
                    // Ignore first: seeking back into the section triggers an immediate skip check
                    ignoredChapterStarts.add(chapterStart);
                    if (!undoHandler.undo(position)) {
                        ignoredChapterStarts.remove(chapterStart);
                    }
                },
                context.getString(R.string.undo)));
        return target;
    }

    /**
     * Start of the next section after the position that would be skipped, or -1.
     */
    public static long nextAdStart(FeedMedia media, long position, Set<Long> ignoredChapterStarts) {
        List<Chapter> chapters = media.getChapters();
        if (!UserPreferences.isAdSkipEnabled() || chapters == null) {
            return -1;
        }
        Set<String> kinds = UserPreferences.getAdSkipKinds();
        for (Chapter chapter : chapters) {
            if (chapter.getStart() > position && isSkipped(chapter, kinds)
                    && !ignoredChapterStarts.contains(chapter.getStart())) {
                return chapter.getStart();
            }
        }
        return -1;
    }

    private static boolean isSkipped(Chapter chapter, Set<String> kinds) {
        return chapter.isSkippable() && kinds.contains(chapter.getSkipKind());
    }
}
