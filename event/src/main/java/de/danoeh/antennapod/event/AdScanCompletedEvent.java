package de.danoeh.antennapod.event;

public class AdScanCompletedEvent {
    public final long mediaId;

    public AdScanCompletedEvent(long mediaId) {
        this.mediaId = mediaId;
    }
}
