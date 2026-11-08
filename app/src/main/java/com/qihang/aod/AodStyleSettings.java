package com.qihang.aod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 息屏 AOD 视觉风格配置管理器。
 *
 * 支持：
 *   0 = 启航（默认，圆形透镜，中心相框自转，全屏大圆爆发扩散）
 *   1 = 留光（魅族经典，-5°流光拍立得双层相框遮罩，优雅绽放铺满全屏）
 */
public final class AodStyleSettings {

    public static final String PREF_NAME = "qihang_aod_style";
    public static final String KEY_STYLE = "aod_style";

    public static final int STYLE_SET_SAIL = 0;    // 启航
    public static final int STYLE_LEAVE_LIGHT = 1; // 留光

    public static final String ACTION_STYLE_CHANGED = "com.qihang.aod.AOD_STYLE_CHANGED";

    private static volatile int sCachedStyle = -1;
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> sListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private AodStyleSettings() {}

    static void addListener(Runnable r) {
        if (r != null && !sListeners.contains(r)) {
            sListeners.add(r);
        }
    }

    static void removeListener(Runnable r) {
        if (r != null) {
            sListeners.remove(r);
        }
    }

    static Context getSafeContext(Context c) {
        if (c == null) return null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                if (!c.isDeviceProtectedStorage()) {
                    return c.createDeviceProtectedStorageContext();
                }
            } catch (Throwable ignored) {
            }
        }
        return c;
    }

    public static int getStyle(Context context) {
        int cached = sCachedStyle;
        if (cached == STYLE_SET_SAIL || cached == STYLE_LEAVE_LIGHT) {
            return cached;
        }
        if (context == null) {
            return STYLE_SET_SAIL;
        }
        try {
            Context safe = getSafeContext(context);
            if (safe != null) {
                SharedPreferences sp = safe.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                if (sp.contains(KEY_STYLE)) {
                    int style = sp.getInt(KEY_STYLE, STYLE_SET_SAIL);
                    if (style != STYLE_LEAVE_LIGHT) {
                        style = STYLE_SET_SAIL;
                    }
                    sCachedStyle = style;
                    return style;
                }
            }
        } catch (Throwable ignored) {}
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            int style = sp.getInt(KEY_STYLE, STYLE_SET_SAIL);
            if (style != STYLE_LEAVE_LIGHT) {
                style = STYLE_SET_SAIL;
            }
            sCachedStyle = style;
            return style;
        } catch (Throwable t) {
            return STYLE_SET_SAIL;
        }
    }

    public static void setStyle(Context context, int style) {
        int norm = (style == STYLE_LEAVE_LIGHT) ? STYLE_LEAVE_LIGHT : STYLE_SET_SAIL;
        sCachedStyle = norm;
        if (context == null) {
            return;
        }
        try {
            Context safe = getSafeContext(context);
            if (safe != null) {
                safe.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_STYLE, norm).commit();
                safe.getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_STYLE, norm).commit();
            }
        } catch (Throwable ignored) {}
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_STYLE, norm).commit();

            SharedPreferences clockSp = context.getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE);
            clockSp.edit().putInt(KEY_STYLE, norm).commit();

            Intent intent = new Intent(ACTION_STYLE_CHANGED);
            intent.putExtra(KEY_STYLE, norm);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        for (Runnable r : sListeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
