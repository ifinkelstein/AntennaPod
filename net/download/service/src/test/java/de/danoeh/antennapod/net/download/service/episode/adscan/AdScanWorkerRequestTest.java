package de.danoeh.antennapod.net.download.service.episode.adscan;

import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AdScanWorkerRequestTest {

    @Test
    public void requestBuildsWithBatteryConstraint() {
        // WorkManager rejects invalid combinations (e.g. expedited + battery-not-low) only when building
        OneTimeWorkRequest request = AdScanWorker.buildRequest(42, false);
        assertTrue(request.getWorkSpec().constraints.requiresBatteryNotLow());
        assertEquals(NetworkType.UNMETERED, request.getWorkSpec().constraints.getRequiredNetworkType());
        assertEquals(42, request.getWorkSpec().input.getLong("media_id", -1));
    }

    @Test
    public void mobileDataAllowsAnyNetwork() {
        OneTimeWorkRequest request = AdScanWorker.buildRequest(1, true);
        assertEquals(NetworkType.CONNECTED, request.getWorkSpec().constraints.getRequiredNetworkType());
    }
}
