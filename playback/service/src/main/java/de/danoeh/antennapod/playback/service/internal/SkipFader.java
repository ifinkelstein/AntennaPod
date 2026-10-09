package de.danoeh.antennapod.playback.service.internal;

import android.os.Handler;
import android.os.SystemClock;

/**
 * Ramps a volume multiplier for short fades around skipped sections. Runs on the handler's thread.
 */
public class SkipFader {
    public interface VolumeSink {
        void setFadeVolume(float volume);
    }

    private static final long STEP_MS = 20;

    private final Handler handler;
    private final VolumeSink sink;
    private float volume = 1;
    private float from;
    private float to;
    private long startTime;
    private long duration;
    private Runnable onDone;
    private boolean fadingOut;

    private final Runnable step = this::step;

    public SkipFader(Handler handler, VolumeSink sink) {
        this.handler = handler;
        this.sink = sink;
    }

    public boolean isFadingOut() {
        return fadingOut;
    }

    public boolean isActive() {
        return duration > 0;
    }

    public void fadeOut(long durationMs, Runnable onDone) {
        fadingOut = true;
        start(0, durationMs, onDone);
    }

    public void fadeIn(long durationMs) {
        fadingOut = false;
        start(1, durationMs, null);
    }

    /**
     * Stops any fade and jumps to the given volume.
     */
    public void set(float newVolume) {
        handler.removeCallbacks(step);
        duration = 0;
        fadingOut = false;
        onDone = null;
        apply(newVolume);
    }

    private void start(float target, long durationMs, Runnable done) {
        handler.removeCallbacks(step);
        from = volume;
        to = target;
        startTime = SystemClock.uptimeMillis();
        duration = Math.max(durationMs, 1);
        onDone = done;
        step();
    }

    private void step() {
        float fraction = Math.min(1f, (SystemClock.uptimeMillis() - startTime) / (float) duration);
        apply(from + (to - from) * fraction);
        if (fraction < 1) {
            handler.postDelayed(step, STEP_MS);
            return;
        }
        duration = 0;
        fadingOut = false;
        Runnable done = onDone;
        onDone = null;
        if (done != null) {
            done.run();
        }
    }

    private void apply(float newVolume) {
        volume = newVolume;
        sink.setFadeVolume(newVolume);
    }
}
