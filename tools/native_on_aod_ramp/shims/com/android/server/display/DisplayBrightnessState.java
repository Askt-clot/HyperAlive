package com.android.server.display;

import com.android.server.display.brightness.BrightnessReason;

public final class DisplayBrightnessState {
    public static final float BRIGHTNESS_NOT_SET = -1.0f;
    public static final float CUSTOM_ANIMATION_RATE_NOT_SET = -1.0f;

    private final float mBrightness;
    private final float mHdrBrightness;
    private final BrightnessReason mBrightnessReason;
    private final String mDisplayBrightnessStrategyName;
    private final boolean mShouldUseAutoBrightness;
    private final boolean mIsSlowChange;
    private final float mMaxBrightness;
    private final float mMinBrightness;
    private final float mCustomAnimationRate;
    private final boolean mShouldUpdateScreenBrightnessSetting;
    private final int mBrightnessAdjustmentFlag;
    private final boolean mIsUserInitiatedChange;
    private final int mBrightnessMaxReason;

    private DisplayBrightnessState(Builder b) {
        this.mBrightness = b.mBrightness;
        this.mHdrBrightness = b.mHdrBrightness;
        this.mBrightnessReason = b.mBrightnessReason;
        this.mDisplayBrightnessStrategyName = b.mDisplayBrightnessStrategyName;
        this.mShouldUseAutoBrightness = b.mShouldUseAutoBrightness;
        this.mIsSlowChange = b.mIsSlowChange;
        this.mMaxBrightness = b.mMaxBrightness;
        this.mMinBrightness = b.mMinBrightness;
        this.mCustomAnimationRate = b.mCustomAnimationRate;
        this.mShouldUpdateScreenBrightnessSetting = b.mShouldUpdateScreenBrightnessSetting;
        this.mBrightnessAdjustmentFlag = b.mBrightnessAdjustmentFlag;
        this.mIsUserInitiatedChange = b.mIsUserInitiatedChange;
        this.mBrightnessMaxReason = b.mBrightnessMaxReason;
    }

    public float getBrightness() { return mBrightness; }
    public float getHdrBrightness() { return mHdrBrightness; }
    public BrightnessReason getBrightnessReason() { return mBrightnessReason; }
    public int getBrightnessReasonCode() { return mBrightnessReason != null ? mBrightnessReason.getReason() : 0; }
    public String getDisplayBrightnessStrategyName() { return mDisplayBrightnessStrategyName; }
    public boolean getShouldUseAutoBrightness() { return mShouldUseAutoBrightness; }
    public boolean isSlowChange() { return mIsSlowChange; }
    public float getMaxBrightness() { return mMaxBrightness; }
    public float getMinBrightness() { return mMinBrightness; }
    public float getCustomAnimationRate() { return mCustomAnimationRate; }
    public boolean shouldUpdateScreenBrightnessSetting() { return mShouldUpdateScreenBrightnessSetting; }
    public int getBrightnessAdjustmentFlag() { return mBrightnessAdjustmentFlag; }
    public boolean isUserInitiatedChange() { return mIsUserInitiatedChange; }
    public int getBrightnessMaxReason() { return mBrightnessMaxReason; }

    public static final class Builder {
        private float mBrightness = BRIGHTNESS_NOT_SET;
        private float mHdrBrightness = BRIGHTNESS_NOT_SET;
        private BrightnessReason mBrightnessReason = new BrightnessReason();
        private String mDisplayBrightnessStrategyName = "InvalidBrightnessStrategy";
        private boolean mShouldUseAutoBrightness = false;
        private boolean mIsSlowChange = false;
        private float mMaxBrightness = 1.0f;
        private float mMinBrightness = 0.0f;
        private float mCustomAnimationRate = CUSTOM_ANIMATION_RATE_NOT_SET;
        private boolean mShouldUpdateScreenBrightnessSetting = false;
        private int mBrightnessAdjustmentFlag = 0;
        private boolean mIsUserInitiatedChange = false;
        private int mBrightnessMaxReason = 0;

        public static Builder from(DisplayBrightnessState s) {
            Builder b = new Builder();
            b.mBrightness = s.mBrightness;
            b.mHdrBrightness = s.mHdrBrightness;
            BrightnessReason r = new BrightnessReason();
            r.set(s.mBrightnessReason);
            b.mBrightnessReason = r;
            b.mDisplayBrightnessStrategyName = s.mDisplayBrightnessStrategyName;
            b.mShouldUseAutoBrightness = s.mShouldUseAutoBrightness;
            b.mIsSlowChange = s.mIsSlowChange;
            b.mMaxBrightness = s.mMaxBrightness;
            b.mMinBrightness = s.mMinBrightness;
            b.mCustomAnimationRate = s.mCustomAnimationRate;
            b.mShouldUpdateScreenBrightnessSetting = s.mShouldUpdateScreenBrightnessSetting;
            b.mBrightnessAdjustmentFlag = s.mBrightnessAdjustmentFlag;
            b.mIsUserInitiatedChange = s.mIsUserInitiatedChange;
            b.mBrightnessMaxReason = s.mBrightnessMaxReason;
            return b;
        }

        public float getBrightness() { return mBrightness; }
        public Builder setBrightness(float v) { this.mBrightness = v; return this; }
        public float getHdrBrightness() { return mHdrBrightness; }
        public Builder setHdrBrightness(float v) { this.mHdrBrightness = v; return this; }
        public BrightnessReason getBrightnessReason() { return mBrightnessReason; }
        public Builder setBrightnessReason(BrightnessReason r) {
            this.mBrightnessReason = (r != null) ? r : new BrightnessReason();
            return this;
        }
        public Builder setBrightnessReason(int r) { return setBrightnessReason(new BrightnessReason(r)); }
        public String getDisplayBrightnessStrategyName() { return mDisplayBrightnessStrategyName; }
        public Builder setDisplayBrightnessStrategyName(String n) { this.mDisplayBrightnessStrategyName = n; return this; }
        public boolean getShouldUseAutoBrightness() { return mShouldUseAutoBrightness; }
        public Builder setShouldUseAutoBrightness(boolean v) { this.mShouldUseAutoBrightness = v; return this; }
        public boolean isSlowChange() { return mIsSlowChange; }
        public Builder setIsSlowChange(boolean s) { this.mIsSlowChange = s; return this; }
        public float getMaxBrightness() { return mMaxBrightness; }
        public Builder setMaxBrightness(float v) { this.mMaxBrightness = v; return this; }
        public float getMinBrightness() { return mMinBrightness; }
        public Builder setMinBrightness(float v) { this.mMinBrightness = v; return this; }
        public float getCustomAnimationRate() { return mCustomAnimationRate; }
        public Builder setCustomAnimationRate(float v) { this.mCustomAnimationRate = v; return this; }
        public boolean shouldUpdateScreenBrightnessSetting() { return mShouldUpdateScreenBrightnessSetting; }
        public Builder setShouldUpdateScreenBrightnessSetting(boolean v) { this.mShouldUpdateScreenBrightnessSetting = v; return this; }
        public int getBrightnessAdjustmentFlag() { return mBrightnessAdjustmentFlag; }
        public Builder setBrightnessAdjustmentFlag(int v) { this.mBrightnessAdjustmentFlag = v; return this; }
        public boolean isUserInitiatedChange() { return mIsUserInitiatedChange; }
        public Builder setIsUserInitiatedChange(boolean v) { this.mIsUserInitiatedChange = v; return this; }
        public int getBrightnessMaxReason() { return mBrightnessMaxReason; }
        public Builder setBrightnessMaxReason(int v) { this.mBrightnessMaxReason = v; return this; }

        public DisplayBrightnessState build() { return new DisplayBrightnessState(this); }
    }
}
