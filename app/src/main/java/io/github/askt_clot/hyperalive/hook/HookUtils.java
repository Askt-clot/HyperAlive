package io.github.askt_clot.hyperalive.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Xposed 反射与 Hook 统一安全工具库：
 * 严密捕获一切 NoSuchMethod / ClassNotFound 异常，确保系统级进程永不因 Hook 抛错崩溃。
 */
public final class HookUtils {

    public static final String TAG = "QihangLSP";
    public static final String PKG_SELF = "io.github.askt_clot.hyperalive";
    public static final String PERM_AMBIENT = "android.permission.AMBIENT_WALLPAPER";

    private static final int MAX_XPOSED_LOG_CHARS = 1024;
    private static final ThreadLocal<Boolean> sInXposedLog = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    private static volatile boolean sXposedBridgeMissing = false;

    private HookUtils() {}

    /**
     * 隔离 XposedBridge 静态调用：未注入进程无 XposedBridge 类时仅捕获 LinkageError，
     * 避免外层 HookUtils 方法在验证期或回退路径触发未捕获异常。
     */
    private static final class XposedLogSink {
        static void write(String msg, Throwable t) {
            if (msg != null && !msg.isEmpty()) {
                XposedBridge.log(msg);
            }
            if (t != null) {
                XposedBridge.log(t);
            }
        }
    }

    public static void log(String msg) {
        if (msg == null) return;
        Log.i(TAG, msg);
        logToXposedBridge(TAG + ": " + msg, null);
    }

    public static void log(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        String text = msg != null ? (TAG + ": " + msg) : TAG;
        logToXposedBridge(text, t);
    }

    /**
     * 仅写入已注入上下文的 LSPosed 模块日志（不再重复输出 Android Log/logcat，
     * 也绝不回写 ModuleDiagLog，防止重复行与递归）。
     */
    public static void logXposedOnly(String msg) {
        if (msg == null || msg.isEmpty()) return;
        logToXposedBridge(TAG + ": " + msg, null);
    }

    static void logToXposedBridge(String msg, Throwable t) {
        if (sXposedBridgeMissing) return;
        if (Boolean.TRUE.equals(sInXposedLog.get())) return;
        sInXposedLog.set(Boolean.TRUE);
        try {
            String bounded = null;
            if (msg != null) {
                bounded = msg.replace('\r', ' ').replace('\n', ' ').trim();
                if (bounded.length() > MAX_XPOSED_LOG_CHARS) {
                    bounded = bounded.substring(0, MAX_XPOSED_LOG_CHARS - 3) + "...";
                }
            }
            XposedLogSink.write(bounded, t);
        } catch (LinkageError le) {
            sXposedBridgeMissing = true;
        } catch (Throwable ignored) {
        } finally {
            sInXposedLog.set(Boolean.FALSE);
        }
    }

    public static Class<?> findClass(String className, ClassLoader cl) {
        if (className == null || cl == null) return null;
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int hookAll(ClassLoader cl, String className, String methodName, XC_MethodHook hook) {
        Class<?> clazz = findClass(className, cl);
        if (clazz == null) {
            return 0;
        }
        return hookAllMethods(clazz, methodName, hook);
    }

    public static int hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook hook) {
        if (clazz == null || methodName == null || hook == null) return 0;
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, hook);
                    count++;
                } catch (Throwable t) {
                    log("Hook 方法 " + clazz.getName() + "." + methodName + " 失败: " + t);
                }
            }
        }
        return count;
    }

    public static boolean hookMethod(Class<?> clazz, String methodName, XC_MethodHook hook, Class<?>... paramTypes) {
        if (clazz == null || methodName == null || hook == null) return false;
        try {
            Method m = clazz.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
            return true;
        } catch (Throwable t) {
            log("精确 Hook 方法 " + clazz.getName() + "." + methodName + " 失败: " + t);
            return false;
        }
    }

    public static Object getField(Object obj, String fieldName) {
        if (obj == null || fieldName == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    public static boolean setField(Object obj, String fieldName, Object val) {
        if (obj == null || fieldName == null) return false;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, val);
                return true;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    public static String propGet(String key, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class, String.class).invoke(null, key, def);
            if (o instanceof String) {
                return (String) o;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public static void propSet(String key, String val) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            c.getMethod("set", String.class, String.class).invoke(null, key, val);
        } catch (Throwable ignored) {}
    }

    public static boolean isTarget(String pkg) {
        return PKG_SELF.equals(pkg);
    }
}
