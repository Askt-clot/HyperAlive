package com.qihang.aod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 跨进程时钟配置 Provider。
 * 允许 SystemUI 在无需 root 权限下直接获取本模块保存的时钟个性化配置。
 */
public class ClockConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("getConfig".equals(method)) {
            ClockSettings.Config aod = ClockSettings.getAodDirect(getContext());
            ClockSettings.Config lock = ClockSettings.getLockDirect(getContext());
            Bundle b = new Bundle();

            // 熄屏独立配置
            b.putInt(AodStyleSettings.KEY_STYLE, AodStyleSettings.getStyle(getContext()));
            b.putInt(ClockSettings.KEY_AOD_STYLE, aod.style);
            b.putInt(ClockSettings.KEY_AOD_COLOR, aod.color);
            b.putFloat(ClockSettings.KEY_AOD_SCALE, aod.scale);
            b.putInt(ClockSettings.KEY_AOD_WEIGHT, aod.weight);
            b.putInt(ClockSettings.KEY_AOD_OFFSET_Y, aod.offsetY);
            b.putBoolean(ClockSettings.KEY_AOD_SHOW_LUNAR, aod.showLunar);

            // 锁屏独立配置
            b.putInt(ClockSettings.KEY_LOCK_STYLE, lock.style);
            b.putInt(ClockSettings.KEY_LOCK_COLOR, lock.color);
            b.putFloat(ClockSettings.KEY_LOCK_SCALE, lock.scale);
            b.putInt(ClockSettings.KEY_LOCK_WEIGHT, lock.weight);
            b.putInt(ClockSettings.KEY_LOCK_OFFSET_Y, lock.offsetY);
            b.putBoolean(ClockSettings.KEY_LOCK_SHOW_LUNAR, lock.showLunar);

            // 旧版/通用兼容键（默认取 AOD）
            b.putInt(ClockSettings.KEY_STYLE, aod.style);
            b.putInt(ClockSettings.KEY_COLOR, aod.color);
            b.putFloat(ClockSettings.KEY_SCALE, aod.scale);
            b.putInt(ClockSettings.KEY_WEIGHT, aod.weight);
            b.putInt(ClockSettings.KEY_OFFSET_Y, aod.offsetY);
            b.putBoolean(ClockSettings.KEY_SHOW_LUNAR, aod.showLunar);
            return b;
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
