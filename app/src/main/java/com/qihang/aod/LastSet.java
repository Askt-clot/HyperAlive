package com.qihang.aod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

/**
 * 「最后一次在模块里设置的壁纸」——首页三态瓦片与编辑器预览都按它展示，
 * 不去读系统实时状态（桌面被系统/其它应用改过也不影响展示）。
 *
 * <p>记录内容：桌面来源 kind（0=动态壁纸 1=跟随锁屏 2=独立照片）+ 动态壁纸组件；
 * 图片本身就在 {@link PhotoStore} 的 lock_photo.png / home_photo.png。
 */
final class LastSet {

    private static final String PREFS = "qihang_last";
    private static final String KEY_KIND = "home_kind";
    private static final String KEY_COMPONENT = "home_component";

    static final int KIND_DYNAMIC = 0;
    static final int KIND_FOLLOW = 1;
    static final int KIND_PHOTO = 2;

    private LastSet() {
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

    /** 应用时调用：记下这次给桌面设了什么。同时写入 Device Protected 存储以支持开机 Direct Boot。 */
    static void save(Context c, int kind, String component) {
        try {
            Context safe = getSafeContext(c);
            if (safe != null) {
                safe.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(KEY_KIND, kind)
                        .putString(KEY_COMPONENT, component == null ? "" : component)
                        .commit();
            }
        } catch (Throwable ignored) {}
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_KIND, kind)
                    .putString(KEY_COMPONENT, component == null ? "" : component)
                    .commit();
        } catch (Throwable ignored) {}
        android.util.Log.i(Root.TAG, "记住最后一次设置：桌面来源=" + kind + " 组件=" + component);
    }

    static int kind(Context c) {
        if (c == null) return KIND_FOLLOW;
        try {
            Context safe = getSafeContext(c);
            if (safe != null) {
                android.content.SharedPreferences sp = safe.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (sp.contains(KEY_KIND)) {
                    return sp.getInt(KEY_KIND, KIND_FOLLOW);
                }
            }
        } catch (Throwable ignored) {}
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_KIND, KIND_FOLLOW);
        } catch (Throwable ignored) {
            return KIND_FOLLOW;
        }
    }

    /** 桌面是否使用了不跟随锁屏的独立图片壁纸。 */
    static boolean isHomeDistinct(Context c) {
        return kind(c) == KIND_PHOTO && PhotoStore.has(c, PhotoStore.HOME);
    }

    static String component(Context c) {
        if (c == null) return "";
        try {
            Context safe = getSafeContext(c);
            if (safe != null) {
                android.content.SharedPreferences sp = safe.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (sp.contains(KEY_COMPONENT)) {
                    return sp.getString(KEY_COMPONENT, "");
                }
            }
        } catch (Throwable ignored) {}
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COMPONENT, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 某个场景的展示图 = 最后一次在模块里设置的效果。 */
    static Bitmap preview(Context c, int mode) {
        if (kind(c) == KIND_DYNAMIC) {
            String comp = component(c);
            Drawable d = LibraryActivity.thumbnailFor(c, comp);
            Bitmap b = LivePreview.toBitmap(d);
            if (b != null) {
                return b;
            }
        }
        if (mode == Motion.AOD) {
            return LivePreview.aod(c);              // 启航：用最后设置的锁屏照片按真实几何合成
        }
        if (mode == Motion.LOCK) {
            return PhotoStore.load(c, PhotoStore.LOCK);
        }
        switch (kind(c)) {
            case KIND_PHOTO:
                return PhotoStore.load(c, PhotoStore.HOME);
            default:
                return PhotoStore.load(c, PhotoStore.LOCK);   // 跟随锁屏
        }
    }
}