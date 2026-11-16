package com.hyperalive.aod.hook;

import android.content.Context;
import android.os.SystemClock;

public final class StateCoordinator {
    public static final String ACTION_MODE = "com.hyperalive.aod.CMD";
    public static final String PROP_WP_ACTIVE = "sys.qihang.wp_active";

    public static volatile boolean sAodPolicyActive = false;
    public static volatile boolean sFullScreenAod = false;
    public static volatile boolean sLockWallpaperActive = true;
    public static volatile boolean sHyperAliveActive = true;
    public static volatile boolean sAsleep = false;
    public static volatile long sSleepAtMs = 0L;
    public static volatile int sThawedCalls = 0;
    public static volatile int sQihangUid = 10200;

    public static boolean isAodPolicyActiveFast() {
        return sAodPolicyActive;
    }

    public static boolean isFullScreenAodFast() {
        return sFullScreenAod;
    }

    public static boolean isLockWallpaperActiveFast() {
        return sLockWallpaperActive;
    }

    public static boolean isHyperAliveWallpaperActiveFast() {
        return sHyperAliveActive;
    }

    public static boolean isResetAllowed() {
        return false;
    }

    public static int getQihangUid() {
        return sQihangUid;
    }

    public static boolean isQihangPid(int pid) {
        return false;
    }

    public static boolean isQihangPath(String path) {
        return false;
    }

    public static Context systemContext() {
        return null;
    }

    public static void ensureQihangThawed() {
        sThawedCalls++;
    }

    public static void invalidateWallpaperOwnedCache() {}

    public static boolean refreshWallpaperOwnershipSync(Context ctx) {
        return sLockWallpaperActive;
    }

    public static void postAsync(Runnable r) {
        if (r != null) r.run();
    }

    public static void onSleepIntent(String source) {
        if (!sLockWallpaperActive) {
            sAsleep = false;
            sSleepAtMs = 0L;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (sAsleep && now - sSleepAtMs < 400L) return;
        sAsleep = true;
        sSleepAtMs = now;
    }

    public static void onWakeIntent() {
        sAsleep = false;
        sSleepAtMs = 0L;
    }

    public static void onBiometricWakeIntent() {
        sAsleep = false;
        sSleepAtMs = 0L;
    }
}
