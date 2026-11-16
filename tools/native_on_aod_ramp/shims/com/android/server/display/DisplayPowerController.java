package com.android.server.display;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.Looper;
import com.android.server.display.brightness.DisplayBrightnessController;
import de.robv.android.xposed.XposedBridge;

public class DisplayPowerController {
    public static final class ColorFadeAnimator {
        public boolean started = false;
        public boolean isStarted() { return started; }
    }

    public final Object mDpcLock = new Object();
    public final int mDisplayId;
    public Handler mHandler = new Handler(Looper.getMainLooper());
    public final DisplayPowerState mPowerState;
    public final DisplayBrightnessController mDisplayBrightnessController;
    public final BrightnessRangeController mBrightnessRangeController = new BrightnessRangeController();
    public final RampAnimator.DualRampAnimator mScreenBrightnessRampAnimator;
    public final DisplayPowerControllerImpl mDisplayPowerControllerImpl;
    public DisplayPowerRequest mPowerRequest = new DisplayPowerRequest();
    public boolean mPendingScreenOff = false;
    public final ColorFadeAnimator mColorFadeOffAnimator = new ColorFadeAnimator();
    public Object mPendingScreenOffUnblocker = null;
    public Object mPendingScreenOnUnblocker = null;
    public Object mPendingScreenOnUnblockerByDisplayOffload = null;
    public boolean mWaitingForScreenOnFromDozeReady = false;
    public float mScreenBrightnessRangeMinimum = 0.002f;
    public float mScreenBrightnessRangeMaximum = 1.0f;
    public int mPreviousPolicy = 3;
    public int sendUpdatePowerStateCalls = 0;
    public boolean suppressPrepareColorFadeRetry = false;

    public DisplayPowerController(int displayId) {
        this.mDisplayId = displayId;
        this.mPowerState = new DisplayPowerState(displayId);
        this.mDisplayBrightnessController = new DisplayBrightnessController(displayId);
        this.mDisplayPowerControllerImpl = new DisplayPowerControllerImpl(displayId, this);
        this.mScreenBrightnessRampAnimator = new RampAnimator.DualRampAnimator(this.mPowerState);
        this.mScreenBrightnessRampAnimator.setImpl(this.mDisplayPowerControllerImpl);
        this.mScreenBrightnessRampAnimator.setListener(this::sendUpdatePowerState);
    }

    public void sendUpdatePowerState() {
        synchronized (mDpcLock) {
            sendUpdatePowerStateCalls++;
            updatePowerStateInternal();
        }
    }

    public void updatePowerStateInternal() {
        synchronized (mDpcLock) {
            int targetState;
            int reason = mPowerRequest.policyReason;
            boolean performScreenOffTransition = false;
            switch (mPowerRequest.policy) {
                case 0:
                    targetState = 1;
                    performScreenOffTransition = true;
                    break;
                case 1:
                    targetState = mPowerRequest.dozeScreenState != 0 ? mPowerRequest.dozeScreenState : 3;
                    break;
                default:
                    targetState = 2;
                    break;
            }

            int oldScreenState = mPowerState.getScreenState();
            animateScreenStateChange(targetState, reason, performScreenOffTransition);
            int screenState2 = mPowerState.getScreenState();

            DisplayBrightnessState dbs = mDisplayBrightnessController.updateBrightness(
                    mPowerRequest, screenState2, null, false, false);
            float targetB = (dbs != null) ? dbs.getBrightness() : -1.0f;
            float hdrB = (dbs != null && dbs.getHdrBrightness() >= 0f) ? dbs.getHdrBrightness() : targetB;
            float minB = mBrightnessRangeController.getCurrentBrightnessMin();
            float maxB = mBrightnessRangeController.getCurrentBrightnessMax();
            mScreenBrightnessRangeMinimum = minB;
            mScreenBrightnessRangeMaximum = maxB;
            if (targetB >= 0f && !Float.isNaN(minB) && !Float.isNaN(maxB)) {
                targetB = Math.max(minB, Math.min(maxB, targetB));
            }
            if (hdrB >= 0f && !Float.isNaN(minB) && !Float.isNaN(maxB)) {
                hdrB = Math.max(minB, Math.min(maxB, hdrB));
            }

            float curB = mPowerState.getScreenBrightness();
            float curSdrB = mPowerState.getSdrScreenBrightness();
            boolean z15 = mPendingScreenOff || screenState2 == 3 || screenState2 == 4;
            if (targetB >= 0f && (hdrB != curB || targetB != curSdrB || mScreenBrightnessRampAnimator.isAnimating())) {
                if (z15) {
                    mDisplayPowerControllerImpl.updateBrightnessChangeStatus(
                            false, screenState2, dbs != null && dbs.isSlowChange(),
                            curB, hdrB, curSdrB, targetB,
                            mPowerRequest.policy, mPreviousPolicy, oldScreenState);
                    animateScreenBrightness(hdrB, targetB, 0.0f, false);
                } else {
                    mDisplayPowerControllerImpl.updateBrightnessChangeStatus(
                            true, screenState2, dbs != null && dbs.isSlowChange(),
                            curB, hdrB, curSdrB, targetB,
                            mPowerRequest.policy, mPreviousPolicy, oldScreenState);
                    float rate = (dbs != null && dbs.isSlowChange()) ? 0.25f : 1.0f;
                    animateScreenBrightness(hdrB, targetB, rate, false);
                }
            }
            mPreviousPolicy = mPowerRequest.policy;
        }
    }

    private void animateScreenStateChange(int target, int reason, boolean performScreenOffTransition) {
        XposedBridge.dispatch(
                DisplayPowerController.class,
                "animateScreenStateChange",
                new Class<?>[]{int.class, int.class, boolean.class},
                this,
                new Object[]{target, reason, performScreenOffTransition},
                a -> {
                    int tgt = (Integer) a[0];
                    int rsn = (Integer) a[1];
                    boolean offAnim = (Boolean) a[2];
                    if (mPendingScreenOff && tgt != 1) {
                        if (setScreenState(1, rsn)) {
                            mPendingScreenOff = false;
                        }
                    }
                    mDisplayPowerControllerImpl.setWaitingScreenOffAnimator(offAnim);
                    if (tgt == 2) {
                        setScreenState(2, rsn);
                        return null;
                    }
                    if (tgt == 3) {
                        if (!mScreenBrightnessRampAnimator.isAnimating() || mPowerState.getScreenState() != 2) {
                            setScreenState(3, rsn);
                        }
                        return null;
                    }
                    if (tgt == 4) {
                        if (!mScreenBrightnessRampAnimator.isAnimating() || mPowerState.getScreenState() == 4) {
                            if (mPowerState.getScreenState() != 4) {
                                setScreenState(3, rsn);
                                setScreenState(4, rsn);
                            }
                        }
                        return null;
                    }
                    mPendingScreenOff = true;
                    boolean ok = mPowerState.prepareColorFade(null, 2);
                    if (!ok && !suppressPrepareColorFadeRetry) {
                        setScreenState(1, rsn);
                    } else if (ok) {
                        setScreenState(1, rsn);
                    }
                    return null;
                });
    }

    private boolean setScreenState(int state, int reason) {
        Object res = XposedBridge.dispatch(
                DisplayPowerController.class,
                "setScreenState",
                new Class<?>[]{int.class, int.class},
                this,
                new Object[]{state, reason},
                a -> {
                    int st = (Integer) a[0];
                    int rsn = (Integer) a[1];
                    mDisplayPowerControllerImpl.setScreenState(st);
                    if (mPowerState.getScreenState() != st) {
                        mPowerState.setScreenState(st, rsn);
                    }
                    return Boolean.TRUE;
                });
        return Boolean.TRUE.equals(res);
    }

    private void animateScreenBrightness(float target, float sdrTarget, float rate, boolean ignoreAnimationLimits) {
        XposedBridge.dispatch(
                DisplayPowerController.class,
                "animateScreenBrightness",
                new Class<?>[]{float.class, float.class, float.class, boolean.class},
                this,
                new Object[]{target, sdrTarget, rate, ignoreAnimationLimits},
                a -> {
                    mScreenBrightnessRampAnimator.animateTo(
                            (Float) a[0], (Float) a[1], (Float) a[2], (Boolean) a[3], -1.0f);
                    return null;
                });
    }
}
