package com.android.server.display.brightness;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.strategy.DozeBrightnessStrategy;
import de.robv.android.xposed.XposedBridge;

public class DisplayBrightnessController {
    private final int mDisplayId;
    public float mCurrentScreenBrightness = 0.50f;
    public Object mDisplayBrightnessStrategy;
    public final DozeBrightnessStrategy mDozeStrategy = new DozeBrightnessStrategy();
    public boolean mFullAod = false;
    public String forcedOverrideStrategyName = null;
    public float forcedOverrideBrightness = Float.NaN;
    public float forcedHdrBrightness = Float.NaN;
    public int forcedModifiers = 0;
    public boolean forcedShouldUpdateSetting = false;
    public boolean returnNullNext = false;

    public DisplayBrightnessController(int displayId) {
        this.mDisplayId = displayId;
    }

    public float getCurrentBrightness() { return mCurrentScreenBrightness; }
    public DozeBrightnessStrategy getDozeBrightnessStrategy() { return mDozeStrategy; }
    public boolean isAppliedDozeBrightnessStrategy() { return mDisplayBrightnessStrategy == mDozeStrategy; }

    public boolean isAppliedOverrideStrategy() {
        return "OverrideBrightnessStrategy".equals(String.valueOf(mDisplayBrightnessStrategy));
    }

    public DisplayBrightnessState updateBrightness(DisplayPowerRequest req, int targetDisplayState,
                                                 Object offloadSession, boolean bedtime, boolean charging) {
        Object res = XposedBridge.dispatch(
                DisplayBrightnessController.class,
                "updateBrightness",
                new Class<?>[]{DisplayPowerRequest.class, int.class, Object.class, boolean.class, boolean.class},
                this,
                new Object[]{req, targetDisplayState, offloadSession, bedtime, charging},
                a -> {
                    if (returnNullNext) {
                        returnNullNext = false;
                        return null;
                    }
                    DisplayPowerRequest r = (DisplayPowerRequest) a[0];
                    int st = (Integer) a[1];

                    if (forcedOverrideStrategyName != null && st != 1 && r.policy != 0) {
                        this.mDisplayBrightnessStrategy = forcedOverrideStrategyName;
                        float ob = !Float.isNaN(forcedOverrideBrightness) ? forcedOverrideBrightness : mCurrentScreenBrightness;
                        BrightnessReason br = new BrightnessReason(BrightnessReason.REASON_OVERRIDE);
                        br.addModifier(forcedModifiers);
                        return new DisplayBrightnessState.Builder()
                                .setBrightness(ob)
                                .setHdrBrightness(!Float.isNaN(forcedHdrBrightness) ? forcedHdrBrightness : DisplayBrightnessState.BRIGHTNESS_NOT_SET)
                                .setBrightnessReason(br)
                                .setDisplayBrightnessStrategyName(forcedOverrideStrategyName)
                                .setShouldUpdateScreenBrightnessSetting(forcedShouldUpdateSetting)
                                .setIsSlowChange(false)
                                .build();
                    }
                    if (!Float.isNaN(r.screenBrightnessOverride) && r.screenBrightnessOverride >= 0f && st != 1 && r.policy != 0) {
                        this.mDisplayBrightnessStrategy = "OverrideBrightnessStrategy";
                        BrightnessReason br = new BrightnessReason(BrightnessReason.REASON_OVERRIDE);
                        br.addModifier(forcedModifiers);
                        return new DisplayBrightnessState.Builder()
                                .setBrightness(r.screenBrightnessOverride)
                                .setHdrBrightness(!Float.isNaN(forcedHdrBrightness) ? forcedHdrBrightness : DisplayBrightnessState.BRIGHTNESS_NOT_SET)
                                .setBrightnessReason(br)
                                .setDisplayBrightnessStrategyName("OverrideBrightnessStrategy")
                                .setShouldUpdateScreenBrightnessSetting(forcedShouldUpdateSetting)
                                .setIsSlowChange(false)
                                .build();
                    }
                    if (r.boostScreenBrightness && st != 1 && r.policy != 0) {
                        this.mDisplayBrightnessStrategy = "BoostBrightnessStrategy";
                        BrightnessReason br = new BrightnessReason(BrightnessReason.REASON_BOOST);
                        br.addModifier(forcedModifiers);
                        return new DisplayBrightnessState.Builder()
                                .setBrightness(1.0f)
                                .setHdrBrightness(1.0f)
                                .setBrightnessReason(br)
                                .setDisplayBrightnessStrategyName("BoostBrightnessStrategy")
                                .setShouldUpdateScreenBrightnessSetting(forcedShouldUpdateSetting)
                                .setIsSlowChange(false)
                                .build();
                    }
                    if (st == 1 || r.policy == 0) {
                        this.mDisplayBrightnessStrategy = "ScreenOffBrightnessStrategy";
                        BrightnessReason br = new BrightnessReason(BrightnessReason.REASON_SCREEN_OFF);
                        br.addModifier(forcedModifiers);
                        return new DisplayBrightnessState.Builder()
                                .setBrightness(-1.0f)
                                .setHdrBrightness(-1.0f)
                                .setBrightnessReason(br)
                                .setDisplayBrightnessStrategyName("ScreenOffBrightnessStrategy")
                                .setShouldUpdateScreenBrightnessSetting(forcedShouldUpdateSetting)
                                .setIsSlowChange(false)
                                .build();
                    }
                    boolean dozeCheck = (!Float.isNaN(r.dozeScreenBrightness) && r.dozeScreenBrightness >= 0f) || mFullAod;
                    boolean normalEx = !(r.useNormalBrightnessForDoze && st != 3 && st != 4);
                    if (r.policy == 1 && normalEx && dozeCheck) {
                        this.mDisplayBrightnessStrategy = mDozeStrategy;
                        DisplayBrightnessState ds = mDozeStrategy.updateBrightness(r);
                        BrightnessReason br = new BrightnessReason(ds.getBrightnessReasonCode());
                        br.addModifier(forcedModifiers);
                        float hdrB = !Float.isNaN(forcedHdrBrightness) ? forcedHdrBrightness : ds.getHdrBrightness();
                        return DisplayBrightnessState.Builder.from(ds)
                                .setHdrBrightness(hdrB)
                                .setBrightnessReason(br)
                                .setShouldUpdateScreenBrightnessSetting(forcedShouldUpdateSetting)
                                .build();
                    }
                    this.mDisplayBrightnessStrategy = "FallbackBrightnessStrategy";
                    BrightnessReason br = new BrightnessReason(BrightnessReason.REASON_MANUAL);
                    br.addModifier(forcedModifiers);
                    return new DisplayBrightnessState.Builder()
                            .setBrightness(mCurrentScreenBrightness)
                            .setHdrBrightness(!Float.isNaN(forcedHdrBrightness) ? forcedHdrBrightness : DisplayBrightnessState.BRIGHTNESS_NOT_SET)
                            .setBrightnessReason(br)
                            .setDisplayBrightnessStrategyName("FallbackBrightnessStrategy")
                            .setShouldUpdateScreenBrightnessSetting(forcedShouldUpdateSetting)
                            .setIsSlowChange(true)
                            .build();
                });
        return (DisplayBrightnessState) res;
    }
}
