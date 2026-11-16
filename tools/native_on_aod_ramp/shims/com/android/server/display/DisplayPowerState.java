package com.android.server.display;

import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;

public class DisplayPowerState {
    public static final class HardwareEvent {
        public final String kind; // SET_DISPLAY_STATE, BACKLIGHT_ON, UPDATE_DOZE_BRIGHTNESS, BRIGHTNESS_WHILE_OFF, COLOR_FADE
        public final int state;
        public final float value;
        public final float sdrValue;

        HardwareEvent(String kind, int state, float value, float sdrValue) {
            this.kind = kind;
            this.state = state;
            this.value = value;
            this.sdrValue = sdrValue;
        }

        @Override
        public String toString() {
            return kind + "(state=" + state + ",val=" + value + ",sdr=" + sdrValue + ")";
        }
    }

    public final int mDisplayId;
    private int mScreenState = 2;
    private float mScreenBrightness = 0.50f;
    private float mSdrScreenBrightness = 0.50f;
    public final List<Integer> stateTransitions = new ArrayList<>();
    public final List<HardwareEvent> hardwareEvents = new ArrayList<>();
    public int colorFadePreparedCount = 0;

    public DisplayPowerState() {
        this(0);
    }

    public DisplayPowerState(int displayId) {
        this.mDisplayId = displayId;
    }

    public int getScreenState() { return mScreenState; }
    public float getScreenBrightness() { return mScreenBrightness; }
    public float getSdrScreenBrightness() { return mSdrScreenBrightness; }

    public void setScreenBrightness(float brightness) {
        setScreenBrightness(brightness, brightness);
    }

    public void setScreenBrightness(float brightness, float sdrBrightness) {
        this.mScreenBrightness = brightness;
        this.mSdrScreenBrightness = sdrBrightness;
        if (mScreenState == 2) {
            hardwareEvents.add(new HardwareEvent("BACKLIGHT_ON", mScreenState, brightness, sdrBrightness));
        } else if (mScreenState == 3 || mScreenState == 4) {
            hardwareEvents.add(new HardwareEvent("UPDATE_DOZE_BRIGHTNESS", mScreenState, brightness, sdrBrightness));
        } else {
            hardwareEvents.add(new HardwareEvent("BRIGHTNESS_WHILE_OFF", mScreenState, brightness, sdrBrightness));
        }
    }

    public void setScreenState(int state, int reason) {
        XposedBridge.dispatch(
                DisplayPowerState.class,
                "setScreenState",
                new Class<?>[]{int.class, int.class},
                this,
                new Object[]{state, reason},
                a -> {
                    int st = (Integer) a[0];
                    this.mScreenState = st;
                    stateTransitions.add(st);
                    hardwareEvents.add(new HardwareEvent("SET_DISPLAY_STATE", st, mScreenBrightness, mSdrScreenBrightness));
                    return null;
                });
    }

    public boolean prepareColorFade(Object ctx, int mode) {
        Object res = XposedBridge.dispatch(
                DisplayPowerState.class,
                "prepareColorFade",
                new Class<?>[]{Object.class, int.class},
                this,
                new Object[]{ctx, mode},
                a -> {
                    colorFadePreparedCount++;
                    hardwareEvents.add(new HardwareEvent("COLOR_FADE", mScreenState, mode, mode));
                    return Boolean.TRUE;
                });
        return Boolean.TRUE.equals(res);
    }
}
