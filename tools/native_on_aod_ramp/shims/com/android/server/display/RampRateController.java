package com.android.server.display;

import de.robv.android.xposed.XposedBridge;

public class RampRateController {
    public static final class RateStateRecord {
        public int mModifier = 0;
        public long mStartDozeDimmingTimeMills = 0L;
        public int mRateType = 0;

        public void addRateModifier(int m) { mModifier |= m; }
        public void clearRateModifier(int mask) { mModifier &= ~mask; }
        public void setDozeDimmingTimeMills(long t) { mStartDozeDimmingTimeMills = t; }
        public boolean appliedDozeDimming() { return (mModifier & 4) != 0; }
        public void setRateType(int t) { mRateType = t; }
        public void setRateTypeIfNeeded(int t) { if (mRateType == 0) mRateType = t; }
    }

    public final RateStateRecord mRateRecord = new RateStateRecord();
    public DisplayPowerControllerImpl mDisplayPowerControllerImpl;
    public float mBrightness = 0.5f;
    public float mSdrBrightness = 0.5f;
    public float mTargetBrightness = 0.5f;
    public float mTargetSdrBrightness = 0.5f;
    public int mCurrentPolicy = 3;
    public int mPreviousPolicy = 3;
    public int mPreviousDisplayState = 2;
    public int mDisplayState = 2;
    public boolean mSlowChange = false;
    public int mAnimationDurationDozeDimming = 450;
    public float mBrightnessRampRateSlow = 0.25f;
    public float mBrightnessRampRateFast = 1.0f;

    public void updateBrightnessState(boolean animating, boolean readyToAnimate, boolean slowChange,
                                      float brightness, float sdrBrightness, float targetBrightness,
                                      float targetSdrBrightness, int currentPolicy, int previousPolicy,
                                      int oldDisplayState, int displayState) {
        XposedBridge.dispatch(
                RampRateController.class,
                "updateBrightnessState",
                new Class<?>[]{boolean.class, boolean.class, boolean.class, float.class, float.class,
                        float.class, float.class, int.class, int.class, int.class, int.class},
                this,
                new Object[]{animating, readyToAnimate, slowChange, brightness, sdrBrightness,
                        targetBrightness, targetSdrBrightness, currentPolicy, previousPolicy, oldDisplayState, displayState},
                a -> {
                    this.mSlowChange = (Boolean) a[2];
                    this.mBrightness = (Float) a[3];
                    this.mSdrBrightness = (Float) a[4];
                    this.mTargetBrightness = (Float) a[5];
                    this.mTargetSdrBrightness = (Float) a[6];
                    this.mCurrentPolicy = (Integer) a[7];
                    this.mPreviousPolicy = (Integer) a[8];
                    this.mPreviousDisplayState = (Integer) a[9];
                    this.mDisplayState = (Integer) a[10];
                    // Stock MIUI clears modifier 4 unless full AOD or !readyToAnimate
                    this.mRateRecord.clearRateModifier(4);
                    return null;
                });
    }

    public float updateBrightnessRate(String name, float currentBrightness, float targetBrightness, float rate) {
        Object res = XposedBridge.dispatch(
                RampRateController.class,
                "updateBrightnessRate",
                new Class<?>[]{String.class, float.class, float.class, float.class},
                this,
                new Object[]{name, currentBrightness, targetBrightness, rate},
                a -> {
                    String prop = (String) a[0];
                    float cur = (Float) a[1];
                    float tgt = (Float) a[2];
                    float r = (Float) a[3];
                    this.mRateRecord.setRateType(0);
                    if (this.mRateRecord.appliedDozeDimming()) {
                        this.mRateRecord.setRateTypeIfNeeded(6);
                        if (Math.round(cur * 16383f) == Math.round(tgt * 16383f)) {
                            return mSlowChange ? mBrightnessRampRateSlow : mBrightnessRampRateFast;
                        }
                        float startBase = "mSdrScreenBrightness".equals(prop) ? mSdrBrightness : mBrightness;
                        if (tgt < startBase && startBase > 0f && tgt > 0f) {
                            double coeff = (Math.log(tgt / startBase) * 1000.0d) / Math.max(1, mAnimationDurationDozeDimming);
                            return (float) Math.abs(coeff * cur);
                        } else if (tgt > startBase && startBase > 0f && tgt > 0f) {
                            double coeff = (Math.log(tgt / startBase) * 1000.0d) / Math.max(1, mAnimationDurationDozeDimming);
                            return (float) Math.abs(coeff * cur);
                        }
                    }
                    return r;
                });
        return (Float) res;
    }
}
