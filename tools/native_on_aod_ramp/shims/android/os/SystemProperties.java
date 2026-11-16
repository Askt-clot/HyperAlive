package android.os;

import java.util.HashMap;
import java.util.Map;

public final class SystemProperties {
    public static final Map<String, String> PROPS = new HashMap<>();
    public static volatile boolean sThrowOnGet = false;

    public static String get(String key, String def) {
        if (sThrowOnGet) {
            throw new RuntimeException("injected SystemProperties.get failure");
        }
        String v = PROPS.get(key);
        return v != null ? v : def;
    }

    public static void set(String key, String val) {
        if (val == null) PROPS.remove(key);
        else PROPS.put(key, val);
    }
}
