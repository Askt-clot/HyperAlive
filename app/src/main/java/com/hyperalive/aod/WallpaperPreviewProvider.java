package com.hyperalive.aod;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only wallpaper previews for the two HyperOS personalization hosts. */
public final class WallpaperPreviewProvider extends ContentProvider {
    public static final String AUTHORITY = "com.hyperalive.aod.wallpaperpreview";
    public static final String DISPLAY_PREVIEW_PATH = "preview";
    public static final String DYNAMIC_PREVIEW_PATH = "dynamic-preview";
    public static final String THEME_MANAGER = "com.android.thememanager";
    public static final String AOD_EDITOR = "com.miui.aod";

    @Override public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            PhotoDraftStore.triggerStartupRecoveryAsync(ctx);
        }
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = requirePreviewContext(mode);
        boolean home = "/home".equals(uri.getPath());
        if (!home && !"/lock".equals(uri.getPath())) throw new FileNotFoundException("Unknown preview slot: " + uri.getPath());
        return openPhoto(context, uri, home, getOwnedWallpaper(context, home));
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (path.size() != 2) return super.openAssetFile(uri, mode);
        boolean dynamicOnly = DYNAMIC_PREVIEW_PATH.equals(path.get(0));
        if (!dynamicOnly && !DISPLAY_PREVIEW_PATH.equals(path.get(0))) {
            return super.openAssetFile(uri, mode);
        }
        boolean home = "home".equals(path.get(1));
        if (!home && !"lock".equals(path.get(1))) throw new FileNotFoundException("Unknown preview slot");

        Context context = requirePreviewContext(mode);
        WallpaperInfo info = getOwnedWallpaper(context, home);
        if (info != null && QihangDynamicWallpaper.class.getName().equals(info.getServiceName())) {
            String asset = DynamicWallpapers.thumbnailAsset(DynamicWallpapers.appliedEntry(context).variant);
            try {
                return context.getAssets().openFd(asset);
            } catch (IOException e) {
                FileNotFoundException error = new FileNotFoundException("Dynamic preview unavailable: " + asset);
                error.initCause(e);
                throw error;
            }
        }
        if (dynamicOnly) throw new FileNotFoundException("HyperAlive dynamic wallpaper is inactive");
        return new AssetFileDescriptor(openPhoto(context, uri, home, info),
                0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private Context requirePreviewContext(String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only preview");
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Context unavailable");
        String caller = getCallingPackage();
        if (!isSystemEditor(context, caller)) {
            throw new SecurityException("Wallpaper previews are restricted to system personalization (" + caller + ")");
        }
        PhotoDraftStore.awaitStartupRecoveryIfNonMainThread(context);
        return context;
    }

    private ParcelFileDescriptor openPhoto(Context context, Uri uri, boolean home, WallpaperInfo info)
            throws FileNotFoundException {
        String service = info == null ? null : info.getServiceName();
        if (!QihangWallpaper.class.getName().equals(service)
                && !(home && QihangHomeWallpaper.class.getName().equals(service))) {
            throw new FileNotFoundException("HyperAlive photo wallpaper is inactive: " + (home ? "home" : "lock"));
        }

        String name = home && LastSet.isHomeDistinct(context) ? PhotoStore.HOME : PhotoStore.LOCK;
        File file = PhotoStore.file(context, name);
        if (!file.isFile() || file.length() <= 1024) file = PhotoStore.deFile(context, name);
        // 若桌面独立照片未落盘，回退到主锁屏照片
        if (home && (!file.isFile() || file.length() <= 1024)) {
            file = PhotoStore.file(context, PhotoStore.LOCK);
            if (!file.isFile() || file.length() <= 1024) file = PhotoStore.deFile(context, PhotoStore.LOCK);
        }
        if (file.isFile() && file.length() > 1024) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        // 无落盘照片时使用默认照片。
        return openPipeHelper(uri, "image/jpeg", null, context, (pipe, ignoredUri, mime, extras, ctx) -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe)) {
                Bitmap bitmap = null;
                if (home && LastSet.isHomeDistinct(ctx)) {
                    bitmap = new WallpaperSource().getHome(ctx, false);
                }
                if (bitmap == null) {
                    bitmap = new WallpaperSource().get(ctx, false);
                }
                if (bitmap != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
            } catch (IOException e) {
                Log.w(Root.TAG, "系统个性化预览读取中断", e);
            }
        });
    }

    private static boolean isSystemEditor(Context context, String caller) {
        if (!THEME_MANAGER.equals(caller) && !AOD_EDITOR.equals(caller)) return false;
        try {
            int flags = context.getPackageManager().getApplicationInfo(caller, 0).flags;
            return (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static WallpaperInfo getOwnedWallpaper(Context context, boolean home) {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(context);
            int which = home ? WallpaperManager.FLAG_SYSTEM : WallpaperManager.FLAG_LOCK;
            WallpaperInfo info = manager.getWallpaperInfo(which);
            // 锁屏槽未独立指定壁纸时跟随系统桌面槽
            if (!home && info == null && manager.getWallpaperId(which) < 0) {
                info = manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM);
            }
            return info != null && context.getPackageName().equals(info.getPackageName()) ? info : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override public String getType(Uri uri) { return "image/*"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
