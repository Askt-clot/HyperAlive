package android.hardware.display;

public final class DisplayManagerInternal {
    public static final class DisplayPowerRequest {
        public static final int POLICY_OFF = 0;
        public static final int POLICY_DOZE = 1;
        public static final int POLICY_DIM = 2;
        public static final int POLICY_BRIGHT = 3;

        public int policy = POLICY_BRIGHT;
        public int policyReason = 0;
        public float dozeScreenBrightness = Float.NaN;
        public int dozeScreenState = 0;
        public int dozeScreenStateReason = 0;
        public boolean useNormalBrightnessForDoze = false;
        public boolean boostScreenBrightness = false;
        public float screenBrightnessOverride = Float.NaN;
    }
}
