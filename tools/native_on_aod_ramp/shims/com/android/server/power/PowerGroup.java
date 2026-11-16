package com.android.server.power;

import de.robv.android.xposed.XposedBridge;

public class PowerGroup {
    public int mGroupId = 0;
    public int mWakefulness = 1; // AWAKE

    public void setWakefulnessLocked(int next, long eventTime, int uid, int reason, int opUid, String opPkg, String details) {
        XposedBridge.dispatch(
                PowerGroup.class,
                "setWakefulnessLocked",
                new Class<?>[]{int.class, long.class, int.class, int.class, int.class, String.class, String.class},
                this,
                new Object[]{next, eventTime, uid, reason, opUid, opPkg, details},
                a -> {
                    this.mWakefulness = (Integer) a[0];
                    return null;
                });
    }
}
