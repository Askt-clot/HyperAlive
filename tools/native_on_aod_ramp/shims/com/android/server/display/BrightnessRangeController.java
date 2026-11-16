package com.android.server.display;

public class BrightnessRangeController {
    public float min = 0.002f;
    public float max = 1.0f;
    public boolean returnNaNMin = false;
    public boolean returnNaNMax = false;

    public float getCurrentBrightnessMin() { return returnNaNMin ? Float.NaN : min; }
    public float getCurrentBrightnessMax() { return returnNaNMax ? Float.NaN : max; }
}
