package com.android.server.display.brightness.strategy;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import com.android.server.display.DisplayBrightnessState;

public class DozeBrightnessStrategy {
    public static final class StubImpl {
        public float mLastDozeBrightness = Float.NaN;
        public float mMinDozeBrightnessFloat = 0.015f;
        public float mMaxDozeBrightnessFloat = 0.080f;
        public float autoDozeBrightness = Float.NaN;
        public boolean mIsFullAod = false;

        public boolean getFullAodState() { return mIsFullAod; }
        public float getAodScreenBrightness() { return autoDozeBrightness; }

        public float clampDozeBrightness(float doze) {
            if (Float.isNaN(doze)) {
                return !Float.isNaN(mLastDozeBrightness) ? mLastDozeBrightness : mMinDozeBrightnessFloat;
            }
            float min = Float.isNaN(mMinDozeBrightnessFloat) ? 0.0f : mMinDozeBrightnessFloat;
            float max = Float.isNaN(mMaxDozeBrightnessFloat) ? 1.0f : mMaxDozeBrightnessFloat;
            float clamped = Math.max(min, Math.min(max, doze));
            mLastDozeBrightness = clamped;
            return clamped;
        }
    }

    public final StubImpl mStub = new StubImpl();
    public Object customStub = null;
    public float mDefaultDozeBrightness = 0.025f;

    public Object getStrategyStub() { return customStub != null ? customStub : mStub; }
    public String getName() { return "DozeBrightnessStrategy"; }

    public DisplayBrightnessState updateBrightness(DisplayPowerRequest req) {
        float b;
        if (mStub.mIsFullAod) {
            b = mStub.getAodScreenBrightness();
            if (!Float.isNaN(b)) {
                mStub.mLastDozeBrightness = b;
            } else {
                b = !Float.isNaN(mStub.mLastDozeBrightness)
                        ? mStub.mLastDozeBrightness
                        : mStub.mMinDozeBrightnessFloat;
            }
        } else {
            b = mStub.clampDozeBrightness(req.dozeScreenBrightness);
        }
        return new DisplayBrightnessState.Builder()
                .setBrightness(b)
                .setBrightnessReason(2)
                .setDisplayBrightnessStrategyName(getName())
                .setIsSlowChange(false)
                .build();
    }
}
