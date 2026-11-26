package com.hyperalive.aod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** The two photo textures are saved together in credential and device protected storage. */
final class PhotoTextureSettings {
    static final int NONE = 0;
    static final int STRAIGHT = 1;
    static final int WAVE = 2;
    static final int FROSTED = 3;
    static final String KEY = "photo_textures";
    private static final String PREFS = "hyperalive_photo_effects";

    private PhotoTextureSettings() {}

    static boolean valid(int selection) {
        return selection >= 0 && selection <= 15;
    }

    static int lock(int selection) {
        return selection & 3;
    }

    static int home(int selection) {
        return (selection >>> 2) & 3;
    }

    static boolean hasIndependentStyles(int selection) {
        return lock(selection) != home(selection);
    }

    static int selection(int lock, int home) {
        return lock | (home << 2);
    }

    static int get(Context context) {
        try {
            SharedPreferences device = prefs(DirectBootContext.get(context));
            int value = device.contains(KEY) ? device.getInt(KEY, NONE)
                    : prefs(context).getInt(KEY, NONE);
            return valid(value) ? value : NONE;
        } catch (RuntimeException error) {
            return NONE;
        }
    }

    static boolean saveStrict(Context context, int selection) {
        if (!valid(selection) || context == null) return false;
        try {
            Context device = DirectBootContext.get(context);
            boolean deviceSaved = device == context
                    || prefs(device).edit().putInt(KEY, selection).commit();
            boolean userSaved = prefs(context).edit().putInt(KEY, selection).commit();
            return deviceSaved && userSaved;
        } catch (RuntimeException error) {
            Log.w(Root.TAG, "保存照片纹理配置失败", error);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
