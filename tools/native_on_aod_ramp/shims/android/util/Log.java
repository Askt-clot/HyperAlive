package android.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Log {
    public static final List<String> LOGS = new CopyOnWriteArrayList<>();

    public static int i(String tag, String msg) {
        LOGS.add("I/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        LOGS.add("W/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg) {
        LOGS.add("E/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        LOGS.add("E/" + tag + ": " + msg + " | " + t);
        return 0;
    }
}
