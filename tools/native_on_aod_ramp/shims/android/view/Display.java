package android.view;

public final class Display {
    public static final int DEFAULT_DISPLAY = 0;
    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_OFF = 1;
    public static final int STATE_ON = 2;
    public static final int STATE_DOZE = 3;
    public static final int STATE_DOZE_SUSPEND = 4;

    public static boolean isDozeState(int state) {
        return state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
    }

    public static String stateToString(int state) {
        switch (state) {
            case STATE_OFF: return "OFF";
            case STATE_ON: return "ON";
            case STATE_DOZE: return "DOZE";
            case STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            default: return "STATE_" + state;
        }
    }
}
