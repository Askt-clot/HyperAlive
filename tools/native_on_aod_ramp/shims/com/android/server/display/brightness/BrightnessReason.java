package com.android.server.display.brightness;

import java.util.Objects;

public final class BrightnessReason {
    public static final int MODIFIER_DIMMED = 1;
    public static final int MODIFIER_LOW_POWER = 2;
    public static final int MODIFIER_HDR = 4;
    public static final int MODIFIER_THROTTLED = 8;
    public static final int MODIFIER_THERMAL = 1024;
    public static final int REASON_MANUAL = 1;
    public static final int REASON_DOZE = 2;
    public static final int REASON_DOZE_DEFAULT = 3;
    public static final int REASON_AUTOMATIC = 4;
    public static final int REASON_SCREEN_OFF = 5;
    public static final int REASON_OVERRIDE = 6;
    public static final int REASON_TEMPORARY = 7;
    public static final int REASON_BOOST = 8;
    public static final int REASON_FOLLOWER = 10;

    private int mReason;
    private int mModifier;
    private CharSequence mTag;

    public BrightnessReason() {}

    public BrightnessReason(int reason) {
        setReason(reason);
    }

    public void set(BrightnessReason other) {
        setReason(other == null ? 0 : other.mReason);
        setModifier(other != null ? other.mModifier : 0);
        setTag(other == null ? null : other.mTag);
    }

    public int getReason() { return mReason; }
    public void setReason(int reason) { this.mReason = reason; }
    public int getModifier() { return mModifier; }
    public void setModifier(int modifier) { this.mModifier = modifier; }
    public void addModifier(int modifier) { this.mModifier |= modifier; }
    public CharSequence getTag() { return mTag; }
    public void setTag(CharSequence tag) { this.mTag = tag; }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BrightnessReason)) return false;
        BrightnessReason o = (BrightnessReason) obj;
        return o.mReason == mReason && o.mModifier == mModifier && Objects.equals(o.mTag, mTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReason, mModifier, mTag);
    }

    @Override
    public String toString() {
        return "BrightnessReason(" + mReason + ",mod=0x" + Integer.toHexString(mModifier) + ")";
    }
}
