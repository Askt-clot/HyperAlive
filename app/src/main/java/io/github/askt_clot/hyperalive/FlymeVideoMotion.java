package io.github.askt_clot.hyperalive;

/** Flyme 9 video clock. The source position is shared across all three scenes. */
final class FlymeVideoMotion implements WallpaperMotion {
    private static final long NORMAL_NANOS = 7_000_000_000L;
    private static final long STOP_NANOS = 8_000_000_000L;
    private static final float SLOW_SPEED = .3333f;

    private final long loopEndUs;
    private long lastNanos = -1;
    private long activityStartNanos = -1;
    private long nextTickNanos = -1;
    private double sourceUs;
    private float speed = 1f;
    private boolean visible;
    private boolean continuous;
    private boolean running = true;
    private boolean tick;

    FlymeVideoMotion(long loopEndUs) {
        if (loopEndUs <= 0) throw new IllegalArgumentException("Invalid video loop endpoint");
        this.loopEndUs = loopEndUs;
    }

    void visible(boolean value) {
        if (visible == value) return;
        visible = value;
        pause();
        if (visible) restartActivity();
    }

    void continuous(boolean value) {
        if (continuous == value) return;
        continuous = value;
        if (continuous) restartActivity();
    }

    private void restartActivity() {
        activityStartNanos = -1;
        nextTickNanos = -1;
        speed = 1f;
        running = true;
    }

    long sourceTimeUs() {
        return (long) sourceUs % loopEndUs;
    }

    boolean takeTick() {
        boolean result = tick;
        tick = false;
        return result;
    }

    @Override
    public void change(int scene, boolean animate) {
        restartActivity();
    }

    @Override
    public void follow(float x, float y) {}

    @Override
    public void pause() {
        lastNanos = -1;
        nextTickNanos = -1;
        tick = false;
    }

    @Override
    public boolean active() {
        return visible && (continuous || running);
    }

    @Override
    public void advance(long nowNanos, float ignored) {
        tick = false;
        if (!visible) return;
        if (activityStartNanos < 0) activityStartNanos = nowNanos;
        if (lastNanos >= 0 && nowNanos < lastNanos) {
            lastNanos = nowNanos;
            nextTickNanos = nowNanos;
            return;
        }
        lastNanos = nowNanos;
        long ageNanos = Math.max(0, nowNanos - activityStartNanos);
        if (!continuous && ageNanos >= STOP_NANOS) {
            speed = SLOW_SPEED;
            running = false;
            return;
        }
        if (!active()) return;
        speed = continuous || ageNanos <= NORMAL_NANOS ? 1f
                : 1f + (SLOW_SPEED - 1f) * (ageNanos - NORMAL_NANOS)
                        / (STOP_NANOS - NORMAL_NANOS);
        int framesPerSecond = (int) (speed * 30 + 30);
        if (nextTickNanos < 0 || nowNanos >= nextTickNanos) {
            // Advance one source sample at a time so a late frame cannot jump ahead.
            sourceUs = (sourceUs + 1_000_000d * speed / framesPerSecond) % loopEndUs;
            long interval = 1_000_000_000L / framesPerSecond;
            nextTickNanos = nextTickNanos < 0 ? nowNanos + interval
                    : Math.max(nextTickNanos + interval, nowNanos);
            tick = true;
        }
    }
}
