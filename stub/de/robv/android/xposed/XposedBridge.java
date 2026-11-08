package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译期桩：运行时由 LSPosed 框架提供真实实现。
 * 只用最底层 API —— XposedBridge.hookMethod(Member, XC_MethodHook)。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }
}