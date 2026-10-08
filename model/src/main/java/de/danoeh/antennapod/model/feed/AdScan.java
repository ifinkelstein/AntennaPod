package de.danoeh.antennapod.model.feed;

import java.util.List;

public class AdScan {
    public static final int STATE_PENDING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_FAILED = 3;
    public static final int STATE_SKIPPED = 4;

    private final long mediaId;
    private final int state;
    private final long downloadDate;
    private final List<AdSegment> segments;

    public AdScan(long mediaId, int state, long downloadDate, List<AdSegment> segments) {
        this.mediaId = mediaId;
        this.state = state;
        this.downloadDate = downloadDate;
        this.segments = segments;
    }

    public long getMediaId() {
        return mediaId;
    }

    public int getState() {
        return state;
    }

    public long getDownloadDate() {
        return downloadDate;
    }

    public List<AdSegment> getSegments() {
        return segments;
    }

    public boolean isValidFor(FeedMedia media) {
        return media != null && media.getDownloadDate() == downloadDate;
    }
}
