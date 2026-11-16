package com.android.server.display;

import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;

public class DisplayPowerControllerImpl {
    private final int mDisplayId;
    public final RampRateController mRampRateController = new RampRateController();
    public final DisplayPowerController mDisplayPowerController;
    public boolean mWaitingScreenOffAnimator = false;
    public int waitingAnimatorTrueCount = 0;
    public int updateBrightnessCalls = 0;
    public final List<Boolean> waitingAnimatorHistory = new ArrayList<>();

    public DisplayPowerControllerImpl(int displayId, DisplayPowerController dpc) {
        this.mDisplayId = displayId;
        this.mDisplayPowerController = dpc;
        this.mRampRateController.mDisplayPowerControllerImpl = this;
    }

    public boolean isWaitingScreenOffAnimator() {
        return mWaitingScreenOffAnimator;
    }

    public void setScreenState(int state) {
        XposedBridge.dispatch(
                DisplayPowerControllerImpl.class,
                "setScreenState",
                new Class<?>[]{int.class},
                this,
                new Object[]{state},
                a -> null);
    }

    public void setWaitingScreenOffAnimator(boolean performScreenOffTransition) {
        if (mDisplayId != 0) return;
        if (mWaitingScreenOffAnimator != performScreenOffTransition) {
            mWaitingScreenOffAnimator = performScreenOffTransition;
            waitingAnimatorHistory.add(performScreenOffTransition);
            if (performScreenOffTransition) waitingAnimatorTrueCount++;
        }
    }

    public void updateBrightnessChangeStatus(boolean animating, int displayState, boolean slowChange,
                                             float currentBrightness, float targetBrightness,
                                             float currentSdrBrightness, float targetSdrBrightness,
                                             int displayPolicy, int previousPolicy, int oldDisplayState) {
        mRampRateController.updateBrightnessState(animating, animating, slowChange,
                currentBrightness, currentSdrBrightness, targetBrightness, targetSdrBrightness,
                displayPolicy, previousPolicy, oldDisplayState, displayState);
    }

    public float updateRampRate(String name, float currentBrightness, float targetBrightness, float rate) {
        Object res = XposedBridge.dispatch(
                DisplayPowerControllerImpl.class,
                "updateRampRate",
                new Class<?>[]{String.class, float.class, float.class, float.class},
                this,
                new Object[]{name, currentBrightness, targetBrightness, rate},
                a -> mRampRateController.updateBrightnessRate(
                        (String) a[0], (Float) a[1], (Float) a[2], (Float) a[3]));
        return (Float) res;
    }

    public void updateBrightness() {
        updateBrightnessCalls++;
        if (mDisplayPowerController != null) {
            mDisplayPowerController.sendUpdatePowerState();
        }
    }
}
