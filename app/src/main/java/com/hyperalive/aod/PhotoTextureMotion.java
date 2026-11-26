package com.hyperalive.aod;

/** Advance the two OpenAlive glass effects from the current visual sample. */
final class PhotoTextureMotion {
    private int selection = -1;
    private int mode;
    Track lock;
    Track home;

    void copyFrom(PhotoTextureMotion source) {
        selection = source.selection;
        mode = source.mode;
        lock = new Track(source.lock);
        home = source.home == source.lock ? lock : new Track(source.home);
    }

    void seekLockOpening(int selection, long start, long now) {
        this.selection = -1;
        update(selection, Motion.AOD, false, start);
        update(selection, Motion.LOCK, true, start);
        update(selection, Motion.LOCK, true, now);
    }

    void update(int selection, int next, boolean animate, long now) {
        if (this.selection != selection) {
            this.selection = selection;
            mode = next;
            int lockStyle = PhotoTextureSettings.lock(selection);
            int homeStyle = PhotoTextureSettings.home(selection);
            boolean shared = lockStyle == homeStyle;
            lock = new Track(lockStyle, next == Motion.LOCK || (shared && next == Motion.HOME));
            home = shared ? lock : new Track(homeStyle, next == Motion.HOME);
        } else if (mode != next) {
            if (next == Motion.AOD) {
                lock.retarget(false, animate, now);
                if (home != lock) home.retarget(false, animate, now);
            } else {
                (next == Motion.LOCK ? lock : home).retarget(true, animate, now);
            }
            mode = next;
        }
        lock.advance(now);
        if (home != lock) home.advance(now);
    }

    boolean active() {
        return lock != null && (lock.running || home.running);
    }

    static final class Track {
        private static final Motion.Curve OPEN = new Motion.Curve(.23f, .09f, .16f, 1f);
        private static final Motion.Curve CLOSE = new Motion.Curve(.1f, 0f, .3f, 1f);
        final int style;
        float strength;
        float reveal;
        float maskX;
        float maskY;
        private float fromStrength;
        private float fromReveal;
        private float target;
        private long start;
        private boolean running;

        Track(int style, boolean awake) {
            this.style = style;
            strength = reveal = target = awake && style != PhotoTextureSettings.NONE ? 1f : 0f;
        }

        private Track(Track src) {
            style = src.style;
            strength = src.strength;
            reveal = src.reveal;
            maskX = src.maskX;
            maskY = src.maskY;
            fromStrength = src.fromStrength;
            fromReveal = src.fromReveal;
            target = src.target;
            start = src.start;
            running = src.running;
        }

        void retarget(boolean awake, boolean animate, long now) {
            if (style == PhotoTextureSettings.NONE) return;
            float next = awake ? 1f : 0f;
            if (target == next && animate) return;
            advance(now);
            target = next;
            if (!animate || (strength == target && reveal == target)) {
                finish();
                return;
            }
            fromStrength = strength;
            fromReveal = reveal;
            start = now;
            running = true;
        }

        void advance(long now) {
            if (!running) return;
            boolean opening = target == 1f;
            boolean frosted = style == PhotoTextureSettings.FROSTED;
            int duration = opening ? (frosted ? 1500 : 850) : 200;
            int revealDuration = opening && !frosted
                    ? (style == PhotoTextureSettings.WAVE ? 470 : 425) : duration;
            float elapsed = Math.max(0f, (now - start) / 1_000_000f);
            if (elapsed >= duration) {
                finish();
                return;
            }
            Motion.Curve curve = frosted ? Motion.Curve.LINEAR : opening ? OPEN : CLOSE;
            strength = fromStrength + (target - fromStrength) * curve.eval(elapsed / duration);
            reveal = fromReveal + (target - fromReveal) * curve.eval(elapsed / revealDuration);
        }

        private void finish() {
            boolean pickNextMask = style == PhotoTextureSettings.FROSTED && target == 0f && strength > 0f;
            strength = reveal = target;
            running = false;
            if (pickNextMask) {
                maskX = (float) Math.random();
                maskY = (float) Math.random();
            }
        }
    }
}
