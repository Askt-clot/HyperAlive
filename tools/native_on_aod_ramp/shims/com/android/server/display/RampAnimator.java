package com.android.server.display;

import java.util.ArrayList;
import java.util.List;

public final class RampAnimator {
    public interface Listener {
        void onAnimationEnd();
    }

    public static final class DualRampAnimator {
        private final DisplayPowerState mPowerState;
        private DisplayPowerControllerImpl mImpl;
        private Listener mListener;
        public boolean mFirstTime = false;
        public boolean mAnimating = false;
        public float mCurrentValue = 0.50f;
        public float mAnimatedValue = 0.50f;
        public float mTargetValue = 0.50f;
        public float mCurrentSdrValue = 0.50f;
        public float mAnimatedSdrValue = 0.50f;
        public float mTargetSdrValue = 0.50f;
        public float mRate = 0.0f;
        public float mSdrRate = 0.0f;
        public int snapCount = 0;
        public boolean throwNextAnimateTo = false;
        public final List<Float> brightnessSteps = new ArrayList<>();
        public final List<Float> sdrBrightnessSteps = new ArrayList<>();

        public DualRampAnimator(DisplayPowerState powerState) {
            this.mPowerState = powerState;
            this.mCurrentValue = powerState.getScreenBrightness();
            this.mAnimatedValue = this.mCurrentValue;
            this.mTargetValue = this.mCurrentValue;
            this.mCurrentSdrValue = powerState.getSdrScreenBrightness();
            this.mAnimatedSdrValue = this.mCurrentSdrValue;
            this.mTargetSdrValue = this.mCurrentSdrValue;
        }

        public void setImpl(DisplayPowerControllerImpl impl) { this.mImpl = impl; }
        public void setListener(Listener l) { this.mListener = l; }
        public boolean isAnimating() { return mAnimating; }

        public boolean animateTo(float target, float sdrTarget, float rate, boolean ignoreLimits, float luxDelta) {
            if (throwNextAnimateTo) {
                throwNextAnimateTo = false;
                throw new RuntimeException("injected animateTo failure");
            }
            float effectiveRate = mImpl != null
                    ? mImpl.updateRampRate("mScreenBrightness", mCurrentValue, target, rate)
                    : rate;
            float effectiveSdrRate = mImpl != null
                    ? mImpl.updateRampRate("mSdrScreenBrightness", mCurrentSdrValue, sdrTarget, rate)
                    : rate;

            if (mFirstTime || (effectiveRate <= 0.0f && effectiveSdrRate <= 0.0f)) {
                if (!mFirstTime && target == mCurrentValue && sdrTarget == mCurrentSdrValue) return false;
                mFirstTime = false;
                mRate = 0.0f;
                mSdrRate = 0.0f;
                mTargetValue = target;
                mCurrentValue = target;
                mAnimatedValue = target;
                mTargetSdrValue = sdrTarget;
                mCurrentSdrValue = sdrTarget;
                mAnimatedSdrValue = sdrTarget;
                mAnimating = false;
                snapCount++;
                mPowerState.setScreenBrightness(target, sdrTarget);
                brightnessSteps.add(target);
                sdrBrightnessSteps.add(sdrTarget);
                return true;
            }
            if (!mAnimating || effectiveRate > mRate
                    || (target <= mCurrentValue && mCurrentValue <= mTargetValue)
                    || (mTargetValue <= mCurrentValue && mCurrentValue <= target)) {
                mRate = effectiveRate;
            }
            if (!mAnimating || effectiveSdrRate > mSdrRate
                    || (sdrTarget <= mCurrentSdrValue && mCurrentSdrValue <= mTargetSdrValue)
                    || (mTargetSdrValue <= mCurrentSdrValue && mCurrentSdrValue <= sdrTarget)) {
                mSdrRate = effectiveSdrRate;
            }
            boolean changed = (mTargetValue != target) || (mTargetSdrValue != sdrTarget);
            mTargetValue = target;
            mTargetSdrValue = sdrTarget;
            if (!mAnimating && (target != mCurrentValue || sdrTarget != mCurrentSdrValue)) {
                mAnimating = true;
                mAnimatedValue = mCurrentValue;
                mAnimatedSdrValue = mCurrentSdrValue;
            }
            return changed;
        }

        public void stepVsync(float dtSeconds) {
            if (!mAnimating) return;
            if (mImpl != null) {
                mRate = mImpl.updateRampRate("mScreenBrightness", mCurrentValue, mTargetValue, mRate);
                mSdrRate = mImpl.updateRampRate("mSdrScreenBrightness", mCurrentSdrValue, mTargetSdrValue, mSdrRate);
            }
            if (mRate <= 0.0f) {
                mAnimatedValue = mTargetValue;
                snapCount++;
            } else {
                float amount = mRate * dtSeconds;
                if (mTargetValue > mCurrentValue) {
                    mAnimatedValue = Math.min(mAnimatedValue + amount, mTargetValue);
                } else {
                    mAnimatedValue = Math.max(mAnimatedValue - amount, mTargetValue);
                }
            }

            if (mSdrRate <= 0.0f) {
                mAnimatedSdrValue = mTargetSdrValue;
            } else {
                float sdrAmount = mSdrRate * dtSeconds;
                if (mTargetSdrValue > mCurrentSdrValue) {
                    mAnimatedSdrValue = Math.min(mAnimatedSdrValue + sdrAmount, mTargetSdrValue);
                } else {
                    mAnimatedSdrValue = Math.max(mAnimatedSdrValue - sdrAmount, mTargetSdrValue);
                }
            }

            mCurrentValue = mAnimatedValue;
            mCurrentSdrValue = mAnimatedSdrValue;
            mPowerState.setScreenBrightness(mCurrentValue, mCurrentSdrValue);
            brightnessSteps.add(mCurrentValue);
            sdrBrightnessSteps.add(mCurrentSdrValue);

            if (mCurrentValue == mTargetValue && mCurrentSdrValue == mTargetSdrValue) {
                mAnimating = false;
                if (mListener != null) {
                    mListener.onAnimationEnd();
                }
            }
        }
    }
}
