package de.danoeh.antennapod.playback.service.internal;

import android.os.Handler;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SkipFaderTest {
    private float volume = 1;
    private final SkipFader fader = new SkipFader(new Handler(Looper.getMainLooper()), v -> volume = v);

    @Test
    public void fadeOutReachesSilenceThenCallsBack() {
        AtomicBoolean done = new AtomicBoolean();
        fader.fadeOut(300, () -> done.set(true));
        assertTrue(fader.isFadingOut());
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS);
        assertTrue(volume > 0.2f && volume < 0.8f);
        assertFalse(done.get());
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        assertEquals(0, volume, 0.001f);
        assertTrue(done.get());
        assertFalse(fader.isFadingOut());
    }

    @Test
    public void fadeInFromSilence() {
        fader.set(0);
        fader.fadeIn(300);
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        assertEquals(1, volume, 0.001f);
        assertFalse(fader.isActive());
    }

    @Test
    public void setCancelsRunningFade() {
        AtomicBoolean done = new AtomicBoolean();
        fader.fadeOut(300, () -> done.set(true));
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        fader.set(1);
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        assertEquals(1, volume, 0.001f);
        assertFalse(done.get());
    }
}
