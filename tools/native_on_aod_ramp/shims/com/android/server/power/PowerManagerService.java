package com.android.server.power;

import de.robv.android.xposed.XposedBridge;

public class PowerManagerService {
    public final Object mLock = new Object();

    public void goToSleep(long eventTime, int reason, int flags) {
        synchronized (mLock) {
            XposedBridge.dispatch(
                    PowerManagerService.class,
                    "goToSleep",
                    new Class<?>[]{long.class, int.class, int.class},
                    this,
                    new Object[]{eventTime, reason, flags},
                    a -> null);
        }
    }

    public void wakeUp(long eventTime, int reason, String details) {
        synchronized (mLock) {
            XposedBridge.dispatch(
                    PowerManagerService.class,
                    "wakeUp",
                    new Class<?>[]{long.class, int.class, String.class},
                    this,
                    new Object[]{eventTime, reason, details},
                    a -> null);
        }
    }
}
