package com.hyperalive.aod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 息屏 AOD 视觉风格配置管理器。
 *
 * 支持：
 *   0 = 启航（默认，圆形透镜，中心相框自转，全屏大圆爆发扩散）
 *   1 = 留光（魅族经典，-5°流光拍立得双层相框遮罩，优雅绽放铺满全屏）
 *   2 = 星月，3 = 轻启，4 = 山脉，5 = 银河（照片遮罩）
 */
public final class AodStyleSettings {

    public static final String PREF_NAME = "qihang_aod_style";
    public static final String KEY_STYLE = "aod_style";
    static final String KEY_POSITIONS = "aod_style_positions";

    public static final int STYLE_SET_SAIL = 0;    // 启航
    public static final int STYLE_LEAVE_LIGHT = 1; // 留光
    public static final int STYLE_MOON = 2;        // 星月
    public static final int STYLE_DOOR = 3;        // 轻启
    public static final int STYLE_MOUNTAIN = 4;    // 山脉
    public static final int STYLE_GALAXY = 5;      // 银河
    private static final int STYLE_COUNT = STYLE_GALAXY + 1;
    static final float POSITION_UNITS = 10000f;
    static final float CENTER_LIMIT = 0.45f;

    public static final String ACTION_STYLE_CHANGED = "com.hyperalive.aod.AOD_STYLE_CHANGED";

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
        return DirectBootContext.get(c);
    }

    static int normalizeStyle(int style) {
        return style >= STYLE_SET_SAIL && style <= STYLE_GALAXY ? style : STYLE_SET_SAIL;
    }

    // 每轴用有符号 16 位整数保存，单位为预览宽度或高度的万分之一，Y 轴向上。
    static int packPosition(float x, float y) {
        int packedX = Math.round(Math.max(-CENTER_LIMIT, Math.min(CENTER_LIMIT, x)) * POSITION_UNITS);
        int packedY = Math.round(Math.max(-CENTER_LIMIT - Motion.AOD_Y,
                Math.min(CENTER_LIMIT - Motion.AOD_Y, y)) * POSITION_UNITS);
        return (packedX << 16) | (packedY & 0xffff);
    }

    static float positionX(int position) {
        return (position >> 16) / POSITION_UNITS;
    }

    static float positionY(int position) {
        return (short) position / POSITION_UNITS;
    }

    static int[] decodePositions(String encoded) {
        int[] positions = new int[STYLE_COUNT];
        if (encoded.isEmpty()) return positions;
        String[] values = encoded.split(",", -1);
        for (int i = 0; i < Math.min(values.length, positions.length); i++) {
            try {
                int position = Integer.parseInt(values[i]);
                positions[i] = packPosition(positionX(position), positionY(position));
            } catch (NumberFormatException ignored) {}
        }
        return positions;
    }

    static String encodePositions(int[] positions) {
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) encoded.append(',');
            encoded.append(positions[i]);
        }
        return encoded.toString();
    }

    static int[] getPositions(Context context) {
        Context safe = getSafeContext(context);
        if (safe != null) {
            try {
                String stored = safe.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_POSITIONS, null);
                if (stored != null) return decodePositions(stored);
            } catch (RuntimeException ignored) {}
        }
        try {
            if (context != null && safe != context) {
                return decodePositions(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_POSITIONS, ""));
            }
        } catch (RuntimeException ignored) {}
        return new int[STYLE_COUNT];
    }

    static boolean savePositionsStrict(Context context, String encoded) {
        if (context == null) return false;
        Context safe = getSafeContext(context);
        try {
            boolean deOk = safe == null || safe == context
                    || safe.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_POSITIONS, encoded).commit();
            boolean ceOk = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_POSITIONS, encoded).commit();
            return ceOk && deOk;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void updateCachedStyle(int style) {
        sCachedStyle = normalizeStyle(style);
    }

    static void resetCacheForTest() {
        sCachedStyle = -1;
    }

    public static int getStyle(Context context) {
        int cached = sCachedStyle;
        if (cached >= STYLE_SET_SAIL) {
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
                    int style = normalizeStyle(sp.getInt(KEY_STYLE, STYLE_SET_SAIL));
                    sCachedStyle = style;
                    return style;
                }
            }
        } catch (Throwable ignored) {}
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            int style = normalizeStyle(sp.getInt(KEY_STYLE, STYLE_SET_SAIL));
            sCachedStyle = style;
            return style;
        } catch (Throwable t) {
            return STYLE_SET_SAIL;
        }
    }

    public static void setStyle(Context context, int style) {
        setStyleStrict(context, style);
    }

    static boolean setStyleLocalStrict(Context context, int style) {
        int norm = normalizeStyle(style);
        if (context == null) {
            return false;
        }
        boolean ceOk = false;
        boolean deOk = true;
        try {
            Context safe = getSafeContext(context);
            if (safe != null && safe != context) {
                boolean p1 = safe.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_STYLE, norm).commit();
                boolean p2 = safe.getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_STYLE, norm).commit();
                deOk = p1 && p2;
            }
        } catch (Throwable ignored) {
            deOk = false;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            boolean p1 = sp.edit().putInt(KEY_STYLE, norm).commit();
            SharedPreferences clockSp = context.getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE);
            boolean p2 = clockSp.edit().putInt(KEY_STYLE, norm).commit();
            ceOk = p1 && p2;
        } catch (Throwable ignored) {
            ceOk = false;
        }
        if (ceOk && deOk) {
            sCachedStyle = norm;
            return true;
        }
        return false;
    }

    static boolean setStyleStrict(Context context, int style) {
        if (!setStyleLocalStrict(context, style)) {
            return false;
        }
        int norm = normalizeStyle(style);
        try {
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
        return true;
    }
}
