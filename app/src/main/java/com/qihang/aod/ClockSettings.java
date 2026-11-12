package com.qihang.aod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 魅族时钟样式与自定义参数配置管理器。
 *
 * 支持：
 *   - 熄屏（AOD）与锁屏（LOCK）独立配置与个性化
 *   - 样式：0=双行堆叠（默认），1=单行居中，2=纵向拉伸，3=极简现代，4=典雅衬线
 *   - 颜色：默认 0xFFFFFFFF（纯白），支持预设色彩或任意 HEX
 *   - 缩放倍率：0.7f ~ 1.4f（默认 1.0f）
 *   - 可变字重：250 ~ 900（默认 500）
 *   - 垂直高度偏移：-40dp ~ +40dp（默认 0dp，基准为启航圆心 33.5% 高度）
 *   - 农历显示开关：AOD 默认关闭，锁屏默认开启
 */
public class ClockSettings {

    private static final String TAG = "ClockSettings";

    public static final int STYLE_DOUBLE_STACK = 0;      // 灵动双行（经典，官方半窄体 SFPRO）
    public static final int STYLE_SINGLE_LINE = 1;       // 灵动单行（水平居中 HH:mm，官方半窄体 SFPRO）
    public static final int STYLE_VERTICAL_STRETCH = 2;  // 纵向拉伸（Flyme 标志性高挑拉伸字体 SixCaps）
    public static final int STYLE_DISTINCTIVE_SELF = 3;  // 极简现代（Flyme 现代无衬线大字 FlymeNumber）
    public static final int STYLE_SERIF = 4;             // 典雅衬线（Flyme 艺术衬线时钟 DMSerifDisplay）
    public static final int STYLE_COUNT = 5;

    public static final String PREF_NAME = "qihang_clock";
    public static final String KEY_CLOCK_ENABLED = "clock_enabled";
    public static final String KEY_STYLE = "clock_style";
    public static final String KEY_COLOR = "clock_color";
    public static final String KEY_SCALE = "clock_scale";
    public static final String KEY_WEIGHT = "clock_weight";
    public static final String KEY_OFFSET_Y = "clock_offset_y";
    public static final String KEY_SHOW_LUNAR = "clock_show_lunar";

    // 熄屏（AOD）独立配置键
    public static final String KEY_AOD_CLOCK_ENABLED = "aod_clock_enabled";
    public static final String KEY_AOD_STYLE = "aod_clock_style";
    public static final String KEY_AOD_COLOR = "aod_clock_color";
    public static final String KEY_AOD_SCALE = "aod_clock_scale";
    public static final String KEY_AOD_WEIGHT = "aod_clock_weight";
    public static final String KEY_AOD_OFFSET_Y = "aod_clock_offset_y";
    public static final String KEY_AOD_SHOW_LUNAR = "aod_clock_show_lunar";

    // 锁屏（LOCK）独立配置键
    public static final String KEY_LOCK_CLOCK_ENABLED = "lock_clock_enabled";
    public static final String KEY_LOCK_STYLE = "lock_clock_style";
    public static final String KEY_LOCK_COLOR = "lock_clock_color";
    public static final String KEY_LOCK_SCALE = "lock_clock_scale";
    public static final String KEY_LOCK_WEIGHT = "lock_clock_weight";
    public static final String KEY_LOCK_OFFSET_Y = "lock_clock_offset_y";
    public static final String KEY_LOCK_SHOW_LUNAR = "lock_clock_show_lunar";

    public static final String ACTION_STYLE_CHANGED = "com.qihang.aod.CLOCK_STYLE_CHANGED";
    public static final String PROVIDER_URI = "content://com.qihang.aod.clockconfig";

    // 默认预设色彩
    public static final int[] PRESET_COLORS = {
            0xFFFFFFFF, // 经典纯白 (默认)
            0xFF60A5FA, // 冰川冷蓝
            0xFFFBBF24, // 琥珀流金
            0xFFF472B6, // 晚霞桃粉
            0xFF34D399, // 薄荷冷翠
            0xFF9CA3AF  // 极简雅灰
    };

    public static final String[] PRESET_COLOR_NAMES = {
            "纯白", "冷蓝", "流金", "桃粉", "冷翠", "雅灰"
    };

    public static class Config {
        public boolean enabled = false; // 默认原生；用户可分别启用 AOD / 锁屏魅族时钟
        public int style = STYLE_DOUBLE_STACK;
        public int color = 0xFFFFFFFF;
        public float scale = 1.0f;
        public int weight = 500;
        public int offsetY = 0;
        public boolean showLunar = false; // 魅族官方 AOD 熄屏默认关闭农历，只显示「月日 + 星期」

        public boolean isSameStyle(Config o) {
            if (o == null) return false;
            return enabled == o.enabled
                    && style == o.style
                    && color == o.color
                    && Math.abs(scale - o.scale) < 0.001f
                    && weight == o.weight
                    && offsetY == o.offsetY
                    && showLunar == o.showLunar;
        }

        public Config copy() {
            Config c = new Config();
            c.enabled = this.enabled;
            c.style = this.style;
            c.color = this.color;
            c.scale = this.scale;
            c.weight = this.weight;
            c.offsetY = this.offsetY;
            c.showLunar = this.showLunar;
            return c;
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

    /** 读取熄屏配置（直读 SP，优先 aod_*，回退 clock_*） */
    public static Config getAodDirect(Context ctx) {
        Config c = new Config();
        if (ctx != null) {
            try {
                Context safe = getSafeContext(ctx);
                SharedPreferences sp = (safe != null ? safe : ctx).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                boolean defEnabled = sp.getBoolean(KEY_CLOCK_ENABLED, false);
                int defStyle = sp.getInt(KEY_STYLE, STYLE_DOUBLE_STACK);
                int defColor = sp.getInt(KEY_COLOR, 0xFFFFFFFF);
                float defScale = sp.getFloat(KEY_SCALE, 1.0f);
                int defWeight = sp.getInt(KEY_WEIGHT, 500);
                int defOffset = sp.getInt(KEY_OFFSET_Y, 0);
                boolean defLunar = sp.getBoolean(KEY_SHOW_LUNAR, false);

                c.enabled = sp.getBoolean(KEY_AOD_CLOCK_ENABLED, defEnabled);
                c.style = sp.getInt(KEY_AOD_STYLE, defStyle);
                c.color = sp.getInt(KEY_AOD_COLOR, defColor);
                c.scale = sp.getFloat(KEY_AOD_SCALE, defScale);
                c.weight = sp.getInt(KEY_AOD_WEIGHT, defWeight);
                c.offsetY = sp.getInt(KEY_AOD_OFFSET_Y, defOffset);
                c.showLunar = sp.getBoolean(KEY_AOD_SHOW_LUNAR, defLunar);
            } catch (Throwable ignored) {
            }
        }
        return c;
    }

    /** 读取锁屏配置（直读 SP，优先 lock_*，回退 clock_*） */
    public static Config getLockDirect(Context ctx) {
        Config c = new Config();
        if (ctx != null) {
            try {
                Context safe = getSafeContext(ctx);
                SharedPreferences sp = (safe != null ? safe : ctx).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                boolean defEnabled = sp.getBoolean(KEY_CLOCK_ENABLED, false);
                int defStyle = sp.getInt(KEY_STYLE, STYLE_DOUBLE_STACK);
                int defColor = sp.getInt(KEY_COLOR, 0xFFFFFFFF);
                float defScale = sp.getFloat(KEY_SCALE, 1.0f);
                int defWeight = sp.getInt(KEY_WEIGHT, 500);
                int defOffset = sp.getInt(KEY_OFFSET_Y, 0);
                boolean defLunar = sp.getBoolean(KEY_SHOW_LUNAR, false);

                c.enabled = sp.getBoolean(KEY_LOCK_CLOCK_ENABLED, defEnabled);
                c.style = sp.getInt(KEY_LOCK_STYLE, defStyle);
                c.color = sp.getInt(KEY_LOCK_COLOR, defColor);
                c.scale = sp.getFloat(KEY_LOCK_SCALE, defScale);
                c.weight = sp.getInt(KEY_LOCK_WEIGHT, defWeight);
                c.offsetY = sp.getInt(KEY_LOCK_OFFSET_Y, defOffset);
                c.showLunar = sp.getBoolean(KEY_LOCK_SHOW_LUNAR, defLunar);
            } catch (Throwable ignored) {
            }
        }
        return c;
    }

    public static Config getDirect(Context ctx) {
        return getAodDirect(ctx);
    }

    public static Config getDirect(Context ctx, int mode) {
        return (mode == Motion.LOCK) ? getLockDirect(ctx) : getAodDirect(ctx);
    }

    public static Config get(Context ctx) {
        return get(ctx, Motion.AOD);
    }

    public static Config getAod(Context ctx) {
        return get(ctx, Motion.AOD);
    }

    public static Config getLock(Context ctx) {
        return get(ctx, Motion.LOCK);
    }

    private static boolean sExternalConfigLoaded;

    public static Config get(Context ctx, int mode) {
        if (ctx == null) return new Config();
        if ("com.qihang.aod".equals(ctx.getPackageName())) {
            return getDirect(ctx, mode);
        }
        boolean isLock = (mode == Motion.LOCK);
        String keyStyle = isLock ? KEY_LOCK_STYLE : KEY_AOD_STYLE;
        // SystemUI 或其他外部宿主：首先检查本地是否有持久化缓存
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (sExternalConfigLoaded && (sp.contains(keyStyle) || sp.contains(KEY_STYLE))) {
                return isLock ? getLockDirect(ctx) : getAodDirect(ctx);
            }
        } catch (Throwable ignored) {
        }
        // 若本地暂无，通过 ContentProvider 向 com.qihang.aod 跨进程同步
        try {
            Bundle b = ctx.getContentResolver().call(Uri.parse(PROVIDER_URI), "getConfig", null, null);
            if (b != null) {
                Config aod = extractFromBundle(b, true);
                Config lock = extractFromBundle(b, false);
                saveLocal(ctx, aod, lock);
                sExternalConfigLoaded = true;
                if (b.containsKey(AodStyleSettings.KEY_STYLE)) {
                    try {
                        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putInt(AodStyleSettings.KEY_STYLE, b.getInt(AodStyleSettings.KEY_STYLE)).apply();
                    } catch (Throwable ignored) {}
                }
                return isLock ? lock : aod;
            }
        } catch (Throwable t) {
            Log.w(TAG, "从 Provider 获取时钟配置失败: " + t);
        }
        return new Config();
    }

    public static int getAodVisualStyle(Context ctx) {
        if (ctx == null) return AodStyleSettings.STYLE_SET_SAIL;
        if ("com.qihang.aod".equals(ctx.getPackageName())) {
            return AodStyleSettings.getStyle(ctx);
        }
        try {
            Bundle b = ctx.getContentResolver().call(Uri.parse(PROVIDER_URI), "getConfig", null, null);
            if (b != null && b.containsKey(AodStyleSettings.KEY_STYLE)) {
                int style = b.getInt(AodStyleSettings.KEY_STYLE, AodStyleSettings.STYLE_SET_SAIL);
                try {
                    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(AodStyleSettings.KEY_STYLE, style).apply();
                } catch (Throwable ignored) {}
                return style;
            }
        } catch (Throwable ignored) {
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (sp.contains(AodStyleSettings.KEY_STYLE)) {
                return sp.getInt(AodStyleSettings.KEY_STYLE, AodStyleSettings.STYLE_SET_SAIL);
            }
        } catch (Throwable ignored) {
        }
        return AodStyleSettings.STYLE_SET_SAIL;
    }

    private static Config extractFromBundle(Bundle b, boolean aod) {
        Config c = new Config();
        String kEnabled = aod ? KEY_AOD_CLOCK_ENABLED : KEY_LOCK_CLOCK_ENABLED;
        String kStyle = aod ? KEY_AOD_STYLE : KEY_LOCK_STYLE;
        String kColor = aod ? KEY_AOD_COLOR : KEY_LOCK_COLOR;
        String kScale = aod ? KEY_AOD_SCALE : KEY_LOCK_SCALE;
        String kWeight = aod ? KEY_AOD_WEIGHT : KEY_LOCK_WEIGHT;
        String kOffset = aod ? KEY_AOD_OFFSET_Y : KEY_LOCK_OFFSET_Y;
        String kLunar = aod ? KEY_AOD_SHOW_LUNAR : KEY_LOCK_SHOW_LUNAR;

        boolean defEnabled = b.getBoolean(KEY_CLOCK_ENABLED, false);
        int defStyle = b.getInt(KEY_STYLE, STYLE_DOUBLE_STACK);
        int defColor = b.getInt(KEY_COLOR, 0xFFFFFFFF);
        float defScale = b.getFloat(KEY_SCALE, 1.0f);
        int defWeight = b.getInt(KEY_WEIGHT, 500);
        int defOffset = b.getInt(KEY_OFFSET_Y, 0);
        boolean defLunar = b.getBoolean(KEY_SHOW_LUNAR, false);

        c.enabled = b.getBoolean(kEnabled, defEnabled);
        c.style = b.getInt(kStyle, defStyle);
        c.color = b.getInt(kColor, defColor);
        c.scale = b.getFloat(kScale, defScale);
        c.weight = b.getInt(kWeight, defWeight);
        c.offsetY = b.getInt(kOffset, defOffset);
        c.showLunar = b.getBoolean(kLunar, defLunar);
        return c;
    }

    public static Config fromIntentAod(Intent intent) {
        Config c = new Config();
        boolean defEnabled = intent.getBooleanExtra(KEY_CLOCK_ENABLED, true);
        int defStyle = intent.getIntExtra(KEY_STYLE, STYLE_DOUBLE_STACK);
        int defColor = intent.getIntExtra(KEY_COLOR, 0xFFFFFFFF);
        float defScale = intent.getFloatExtra(KEY_SCALE, 1.0f);
        int defWeight = intent.getIntExtra(KEY_WEIGHT, 500);
        int defOffset = intent.getIntExtra(KEY_OFFSET_Y, 0);
        boolean defLunar = intent.getBooleanExtra(KEY_SHOW_LUNAR, false);

        c.enabled = intent.getBooleanExtra(KEY_AOD_CLOCK_ENABLED, defEnabled);
        c.style = intent.getIntExtra(KEY_AOD_STYLE, defStyle);
        c.color = intent.getIntExtra(KEY_AOD_COLOR, defColor);
        c.scale = intent.getFloatExtra(KEY_AOD_SCALE, defScale);
        c.weight = intent.getIntExtra(KEY_AOD_WEIGHT, defWeight);
        c.offsetY = intent.getIntExtra(KEY_AOD_OFFSET_Y, defOffset);
        c.showLunar = intent.getBooleanExtra(KEY_AOD_SHOW_LUNAR, defLunar);
        return c;
    }

    public static Config fromIntentLock(Intent intent) {
        Config c = new Config();
        boolean defEnabled = intent.getBooleanExtra(KEY_CLOCK_ENABLED, true);
        int defStyle = intent.getIntExtra(KEY_STYLE, STYLE_DOUBLE_STACK);
        int defColor = intent.getIntExtra(KEY_COLOR, 0xFFFFFFFF);
        float defScale = intent.getFloatExtra(KEY_SCALE, 1.0f);
        int defWeight = intent.getIntExtra(KEY_WEIGHT, 500);
        int defOffset = intent.getIntExtra(KEY_OFFSET_Y, 0);
        boolean defLunar = intent.getBooleanExtra(KEY_SHOW_LUNAR, false);

        c.enabled = intent.getBooleanExtra(KEY_LOCK_CLOCK_ENABLED, defEnabled);
        c.style = intent.getIntExtra(KEY_LOCK_STYLE, defStyle);
        c.color = intent.getIntExtra(KEY_LOCK_COLOR, defColor);
        c.scale = intent.getFloatExtra(KEY_LOCK_SCALE, defScale);
        c.weight = intent.getIntExtra(KEY_LOCK_WEIGHT, defWeight);
        c.offsetY = intent.getIntExtra(KEY_LOCK_OFFSET_Y, defOffset);
        c.showLunar = intent.getBooleanExtra(KEY_LOCK_SHOW_LUNAR, defLunar);
        return c;
    }

    public static void saveLocal(Context ctx, Config c) {
        if (ctx == null || c == null) return;
        saveLocal(ctx, c, c);
    }

    public static void saveLocal(Context ctx, Config aod, Config lock) {
        if (ctx == null) return;
        try {
            Context safe = getSafeContext(ctx);
            if (safe != null) {
                SharedPreferences spSafe = safe.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor edSafe = spSafe.edit();
                if (aod != null) {
                    edSafe.putBoolean(KEY_CLOCK_ENABLED, aod.enabled)
                            .putInt(KEY_STYLE, aod.style)
                            .putInt(KEY_COLOR, aod.color)
                            .putFloat(KEY_SCALE, aod.scale)
                            .putInt(KEY_WEIGHT, aod.weight)
                            .putInt(KEY_OFFSET_Y, aod.offsetY)
                            .putBoolean(KEY_SHOW_LUNAR, aod.showLunar)
                            .putBoolean(KEY_AOD_CLOCK_ENABLED, aod.enabled)
                            .putInt(KEY_AOD_STYLE, aod.style)
                            .putInt(KEY_AOD_COLOR, aod.color)
                            .putFloat(KEY_AOD_SCALE, aod.scale)
                            .putInt(KEY_AOD_WEIGHT, aod.weight)
                            .putInt(KEY_AOD_OFFSET_Y, aod.offsetY)
                            .putBoolean(KEY_AOD_SHOW_LUNAR, aod.showLunar);
                }
                if (lock != null) {
                    edSafe.putBoolean(KEY_LOCK_CLOCK_ENABLED, lock.enabled)
                            .putInt(KEY_LOCK_STYLE, lock.style)
                            .putInt(KEY_LOCK_COLOR, lock.color)
                            .putFloat(KEY_LOCK_SCALE, lock.scale)
                            .putInt(KEY_LOCK_WEIGHT, lock.weight)
                            .putInt(KEY_LOCK_OFFSET_Y, lock.offsetY)
                            .putBoolean(KEY_LOCK_SHOW_LUNAR, lock.showLunar);
                }
                edSafe.commit();
            }
        } catch (Throwable ignored) {}
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            if (aod != null) {
                ed.putBoolean(KEY_CLOCK_ENABLED, aod.enabled)
                        .putInt(KEY_STYLE, aod.style)
                        .putInt(KEY_COLOR, aod.color)
                        .putFloat(KEY_SCALE, aod.scale)
                        .putInt(KEY_WEIGHT, aod.weight)
                        .putInt(KEY_OFFSET_Y, aod.offsetY)
                        .putBoolean(KEY_SHOW_LUNAR, aod.showLunar)
                        .putBoolean(KEY_AOD_CLOCK_ENABLED, aod.enabled)
                        .putInt(KEY_AOD_STYLE, aod.style)
                        .putInt(KEY_AOD_COLOR, aod.color)
                        .putFloat(KEY_AOD_SCALE, aod.scale)
                        .putInt(KEY_AOD_WEIGHT, aod.weight)
                        .putInt(KEY_AOD_OFFSET_Y, aod.offsetY)
                        .putBoolean(KEY_AOD_SHOW_LUNAR, aod.showLunar);
            }
            if (lock != null) {
                ed.putBoolean(KEY_LOCK_CLOCK_ENABLED, lock.enabled)
                        .putInt(KEY_LOCK_STYLE, lock.style)
                        .putInt(KEY_LOCK_COLOR, lock.color)
                        .putFloat(KEY_LOCK_SCALE, lock.scale)
                        .putInt(KEY_LOCK_WEIGHT, lock.weight)
                        .putInt(KEY_LOCK_OFFSET_Y, lock.offsetY)
                        .putBoolean(KEY_LOCK_SHOW_LUNAR, lock.showLunar);
            }
            ed.commit();
        } catch (Throwable t) {
            Log.w(TAG, "保存配置失败: " + t);
        }
    }

    public static void save(Context ctx, Config c) {
        save(ctx, c, c);
    }

    public static void save(Context ctx, Config aod, Config lock) {
        if (ctx != null) {
            saveLocal(ctx, aod, lock);
        }
        if (ctx != null) {
            try {
                Intent i = new Intent(ACTION_STYLE_CHANGED);
                if (aod != null) {
                    i.putExtra(KEY_CLOCK_ENABLED, aod.enabled);
                    i.putExtra(KEY_STYLE, aod.style);
                    i.putExtra(KEY_COLOR, aod.color);
                    i.putExtra(KEY_SCALE, aod.scale);
                    i.putExtra(KEY_WEIGHT, aod.weight);
                    i.putExtra(KEY_OFFSET_Y, aod.offsetY);
                    i.putExtra(KEY_SHOW_LUNAR, aod.showLunar);

                    i.putExtra(KEY_AOD_CLOCK_ENABLED, aod.enabled);
                    i.putExtra(KEY_AOD_STYLE, aod.style);
                    i.putExtra(KEY_AOD_COLOR, aod.color);
                    i.putExtra(KEY_AOD_SCALE, aod.scale);
                    i.putExtra(KEY_AOD_WEIGHT, aod.weight);
                    i.putExtra(KEY_AOD_OFFSET_Y, aod.offsetY);
                    i.putExtra(KEY_AOD_SHOW_LUNAR, aod.showLunar);
                }
                if (lock != null) {
                    i.putExtra(KEY_LOCK_CLOCK_ENABLED, lock.enabled);
                    i.putExtra(KEY_LOCK_STYLE, lock.style);
                    i.putExtra(KEY_LOCK_COLOR, lock.color);
                    i.putExtra(KEY_LOCK_SCALE, lock.scale);
                    i.putExtra(KEY_LOCK_WEIGHT, lock.weight);
                    i.putExtra(KEY_LOCK_OFFSET_Y, lock.offsetY);
                    i.putExtra(KEY_LOCK_SHOW_LUNAR, lock.showLunar);
                }
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                ctx.sendBroadcast(i);
            } catch (Throwable t) {
                Log.w(TAG, "发送广播失败", t);
            }
        }
    }

    public static boolean isClockEnabled(Context ctx, int mode) {
        return (mode == Motion.AOD || mode == Motion.LOCK) && get(ctx, mode).enabled;
    }

    public static int getStyle(Context ctx) {
        return get(ctx).style;
    }

    public static void setStyle(Context ctx, int style) {
        Config c = get(ctx);
        c.style = style;
        save(ctx, c);
    }

    public static String getStyleName(int style) {
        switch (style) {
            case STYLE_DOUBLE_STACK:
                return "灵动双行（经典）";
            case STYLE_SINGLE_LINE:
                return "灵动单行";
            case STYLE_VERTICAL_STRETCH:
                return "纵向拉伸";
            case STYLE_DISTINCTIVE_SELF:
                return "极简现代";
            case STYLE_SERIF:
                return "典雅衬线";
            default:
                return "灵动双行（经典）";
        }
    }

    public static String getStyleFontDesc(int style) {
        switch (style) {
            case STYLE_DOUBLE_STACK:
            case STYLE_SINGLE_LINE:
                return "魅族半窄体 SFPRO";
            case STYLE_VERTICAL_STRETCH:
                return "魅族拉伸 SixCaps";
            case STYLE_DISTINCTIVE_SELF:
                return "魅族现代 FlymeNumber";
            case STYLE_SERIF:
                return "魅族艺术 DMSerif";
            default:
                return "魅族半窄体 SFPRO";
        }
    }
}
