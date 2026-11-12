package com.qihang.aod;

/**
 * Pure decisions shared by the power coordinator and wallpaper engines.
 * A new sleep intent owns the current cycle even while SystemUI still reports
 * UNLOCKED. A confirmed unlock may precede the display leaving DOZE.
 */
public final class TransitionPolicy {
    public static final int AOD = 0;
    public static final int LOCK = 1;
    public static final int HOME = 2;
    public static final long LOCK_EXPAND_TOTAL_MS = 1150L;

    private TransitionPolicy() {}

    /**
     * recentBiometric must describe an unlock newer than the latest sleep.
     * wakeGrace covers a confirmed wake whose display-state callback is late.
     * Neither flag is allowed to override a newer sleep intent.
     */
    public static int resolveMode(boolean sleepIntent, boolean doze,
                                  boolean wakeGrace, boolean recentBiometric,
                                  int scrimMode, boolean aodEnabled,
                                  boolean fullScreenAod) {
        if (sleepIntent) {
            return sleepingMode(aodEnabled, fullScreenAod);
        }
        if (recentBiometric) {
            return HOME;
        }
        if (doze && !wakeGrace) {
            return sleepingMode(aodEnabled, fullScreenAod);
        }
        return scrimMode == AOD ? LOCK : scrimMode;
    }

    private static int sleepingMode(boolean aodEnabled, boolean fullScreenAod) {
        return aodEnabled && !fullScreenAod ? AOD : LOCK;
    }

    /**
     * A separate home engine ignores LOCK commands, so its local renderer may
     * still be AOD (or HOME if a command was missed). Its shared, current-cycle
     * lock history supplies the progress to seek; local LOCK is not required.
     */
    public static boolean canResumeLock(boolean homeSlot, int currentMotion,
                                         boolean fromLock, long elapsedMs,
                                         boolean animating) {
        if (!fromLock || elapsedMs < 0L) {
            return false;
        }
        if (homeSlot) {
            return elapsedMs < LOCK_EXPAND_TOTAL_MS;
        }
        return currentMotion == LOCK
                && (elapsedMs < LOCK_EXPAND_TOTAL_MS || animating);
    }
}
