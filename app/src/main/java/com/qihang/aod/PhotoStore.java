package com.qihang.aod;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 编辑器里选的照片（锁屏 / 桌面）落盘与读取。
 *
 * <p>存放位置：{@code /sdcard/Android/data/com.qihang.aod/files/{lock,home}_photo.png}
 * —— 壁纸引擎（同进程）直接读，adb 也能推图调试。
 */
final class PhotoStore {

    static final String LOCK = "lock_photo.png";
    static final String HOME = "home_photo.png";
    static final String FRAME = "frame_photo.png";

    private PhotoStore() {
    }

    static File file(Context c, String name) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) {
            dir = c.getFilesDir();
        }
        return new File(dir, name);
    }

    static File rawFile(Context c, String name) {
        return file(c, "raw_" + name);
    }

    static boolean hasRaw(Context c, String name) {
        File f = rawFile(c, name);
        return f.exists() && f.length() > 1024;
    }

    static boolean has(Context c, String name) {
        File f = file(c, name);
        return f.exists() && f.length() > 1024;
    }

    /** 文件修改时间（当版本号用，没有则 -1）。 */
    static long stamp(Context c, String name) {
        File f = file(c, name);
        return f.exists() ? f.lastModified() : -1;
    }

    static Bitmap load(Context c, String name) {
        if (!has(c, name)) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(file(c, name).getAbsolutePath());
        } catch (Throwable t) {
            Log.w(Root.TAG, "读照片失败 " + name, t);
            return null;
        }
    }

    private static void atomicReplace(File tmp, File dst) throws IOException {
        if (dst.exists() && !dst.delete()) {
            Log.w(Root.TAG, "旧文件删不掉：" + dst);
        }
        if (!tmp.renameTo(dst)) {
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            tmp.delete();
        }
    }

    /** 将裁切完成后的位图原子写入目标照片文件（lock_photo.png / home_photo.png）。 */
    static void save(Context c, Bitmap bmp, String name) throws IOException {
        if (bmp == null) {
            throw new IOException("位图为空");
        }
        File dst = file(c, name);
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new IOException("无法保存裁切结果");
            }
        }
        atomicReplace(tmp, dst);
        Log.i(Root.TAG, "照片已保存 " + dst + " " + bmp.getWidth() + "x" + bmp.getHeight());
    }

    static void copyFile(File src, File dst) throws IOException {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        atomicReplace(tmp, dst);
    }

    /**
     * 将相册选中的 Uri 暂存到私有目录 {@code photo-xxxx.image} 供 {@link PhotoCropActivity} 裁切。
     * 同时保留原始未裁切相片到 raw_{target}，支持日后复位到全屏原图。
     */
    static File stageFromUri(Context c, Uri uri) throws IOException {
        return stageFromUri(c, uri, LOCK);
    }

    static File stageFromUri(Context c, Uri uri, String targetName) throws IOException {
        File staged = File.createTempFile("photo-", ".image", c.getFilesDir());
        try {
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(staged)) {
                if (in == null) {
                    throw new IOException("无法读取照片");
                }
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 100L * 1024 * 1024) {
                        throw new IOException("照片不能超过 100 MB");
                    }
                    out.write(buf, 0, n);
                }
            }
            android.graphics.ImageDecoder.Source src =
                    android.graphics.ImageDecoder.createSource(staged);
            Bitmap probe = android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, input) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                float scale = Math.min(1f, 64f / Math.max(w, h));
                decoder.setTargetSize(
                        Math.max(1, (int) (w * scale)),
                        Math.max(1, (int) (h * scale)));
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            probe.recycle();
            if (targetName != null) {
                try {
                    copyFile(staged, rawFile(c, targetName));
                } catch (Throwable t) {
                    Log.w(Root.TAG, "保存原始相片失败", t);
                }
                // 更换新相片后，清除旧相片的取景偏好，确保第一次自动取景使用 OpenAlive 默认值 (0.5f, 0.5f, 1.0f)
                c.getSharedPreferences("photo_crop_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("x_" + targetName)
                        .remove("y_" + targetName)
                        .remove("zoom_" + targetName)
                        .apply();
            }
            return staged;
        } catch (Throwable t) {
            staged.delete();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("无法解析图片：" + t.getMessage(), t);
        }
    }

    /** 从相册 uri 导入：按屏幕尺寸降采样后存 PNG（临时文件 + rename 原子替换）。 */
    static Bitmap importFrom(Context c, Uri uri, String name) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int maxSide = Math.max(c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while (maxSide > 0 && Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) {
            throw new IOException("解码失败：" + uri);
        }
        File dst = file(c, name);
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        atomicReplace(tmp, dst);
        Log.i(Root.TAG, "照片已保存 " + dst + " " + bmp.getWidth() + "x" + bmp.getHeight()
                + "（原图 " + bounds.outWidth + "x" + bounds.outHeight + " sample=" + sample + "）");
        return bmp;
    }

    /** 给系统相册用的 image/* Intent（ACTION_PICK 优先，退化到 OPEN_DOCUMENT）。 */
    static android.content.Intent pickerIntent() {
        android.content.Intent pick = new android.content.Intent(
                android.content.Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pick.setType("image/*");
        if (Build.VERSION.SDK_INT >= 19) {
            pick.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return pick;
    }

    /**
     * 将当前已有的照片（优先原始未裁切相片 raw_{name}）暂存到私有目录 {@code photo-xxxx.image}，
     * 以便直接进入 {@link PhotoCropActivity} 调整取景与裁切，并支持随时复位到最初铺满全屏的原图。
     */
    static File stageExistingOrFallback(Context c, String name) {
        File staged = null;
        try {
            staged = File.createTempFile("photo-", ".image", c.getFilesDir());
            File raw = rawFile(c, name);
            if (raw.exists() && raw.length() > 1024) {
                copyFile(raw, staged);
                return staged;
            }
            File existing = file(c, name);
            if (existing.exists() && existing.length() > 1024) {
                copyFile(existing, staged);
                try {
                    copyFile(existing, raw);
                } catch (Throwable ignored) {
                }
                return staged;
            }
            // 兜底：优先尝试加载默认示例图 / 系统壁纸
            Bitmap bmp = null;
            try (InputStream in = c.getAssets().open("photo/default.jpg")) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Throwable ignored) {
            }
            if (bmp == null) {
                WallpaperManager wm = WallpaperManager.getInstance(c);
                Drawable d = wm.getDrawable();
                if (d instanceof BitmapDrawable) {
                    bmp = ((BitmapDrawable) d).getBitmap();
                }
            }
            if (bmp != null) {
                try (OutputStream out = new FileOutputStream(staged)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                try {
                    copyFile(staged, raw);
                } catch (Throwable ignored) {
                }
                return staged;
            }
        } catch (Throwable t) {
            Log.e(Root.TAG, "stageExistingOrFallback 失败", t);
            if (staged != null) {
                staged.delete();
            }
        }
        return null;
    }

    /**
     * 载入预设示例相片，同步写入原始相片并清除历史取景偏移。
     */
    static boolean loadDefaultExample(Context c, String name) {
        try (InputStream in = c.getAssets().open("photo/default.jpg")) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null) {
                save(c, bmp, name);
                File raw = rawFile(c, name);
                try (OutputStream os = new FileOutputStream(raw)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                } catch (Throwable ignored) {
                }
                bmp.recycle();
                c.getSharedPreferences("photo_crop_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("x_" + name)
                        .remove("y_" + name)
                        .remove("zoom_" + name)
                        .apply();
                return true;
            }
        } catch (Throwable t) {
            Log.w(Root.TAG, "loadDefaultExample 失败", t);
        }
        return false;
    }

    /**
     * 将留光相框相片同步为当前锁屏壁纸，并应用 OpenAlive 默认取景范围 (0.5, 0.5, 1.0)。
     * 当用户更换壁纸或切换到留光样式时调用。
     */
    static boolean syncFrameToLockWithDefaultCrop(Context c) {
        try {
            File sourceRaw = rawFile(c, LOCK);
            File sourceFile = (sourceRaw.exists() && sourceRaw.length() > 1024) ? sourceRaw : file(c, LOCK);
            if (sourceFile == null || !sourceFile.exists() || sourceFile.length() <= 1024) {
                sourceFile = stageExistingOrFallback(c, LOCK);
            }
            if (sourceFile == null || !sourceFile.exists()) {
                Log.w(Root.TAG, "syncFrameToLockWithDefaultCrop: 找不到锁屏壁纸源文件");
                return false;
            }

            File frameRaw = rawFile(c, FRAME);
            copyFile(sourceFile, frameRaw);

            Point screen = new Point();
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealSize(screen);
            }
            int sw = screen.x > 0 ? screen.x : c.getResources().getDisplayMetrics().widthPixels;
            int sh = screen.y > 0 ? screen.y : c.getResources().getDisplayMetrics().heightPixels;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(frameRaw.getAbsolutePath(), opts);
            int bw = opts.outWidth > 0 ? opts.outWidth : sw;
            int bh = opts.outHeight > 0 ? opts.outHeight : sh;
            try {
                android.media.ExifInterface exif =
                        new android.media.ExifInterface(frameRaw.getAbsolutePath());
                int orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
                if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90
                        || orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270
                        || orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE
                        || orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE) {
                    int tmp = bw;
                    bw = bh;
                    bh = tmp;
                }
            } catch (Throwable ignored) {
            }

            PhotoViewport vp = new PhotoViewport();
            vp.setFreeMove(true);
            vp.dimensions(bw, bh, sw, sh);
            vp.resetDefault(true);

            Bitmap exported = PhotoCropActivity.export(frameRaw, vp, sw, sh, true);
            try {
                save(c, exported, FRAME);
            } finally {
                exported.recycle();
            }

            // 清除旧的自定义取景偏好，确保换壁纸后首次打开调整取景直接走 resetDefault 官方默认取景范围
            c.getSharedPreferences("photo_crop_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("x_" + FRAME)
                    .remove("y_" + FRAME)
                    .remove("zoom_" + FRAME)
                    .apply();

            Log.i(Root.TAG, "留光相框相片已自动跟随壁纸，并应用 OpenAlive 默认取景范围 (0.5, 0.5, 1.0)");
            return true;
        } catch (Throwable t) {
            Log.e(Root.TAG, "syncFrameToLockWithDefaultCrop 失败", t);
            return false;
        }
    }
}