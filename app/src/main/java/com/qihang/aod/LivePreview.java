package com.qihang.aod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * 实时预览：向壁纸引擎要一帧**当前真实画面**。
 *
 * <p>原理：引擎（QihangWallpaper/LensRenderer）本来就有取帧能力 —— 收到广播
 * {@code dump_frame=true} 时会在下一帧 glReadPixels 落到 {@code files/gl_frame.png}。
 * 这里先发「切到目标场景 + 取帧」，再等文件更新，读回来当预览图。
 *
 * <p>落在哪个槽位就画什么：熄屏=启航圆透镜、锁屏=整张照片；所以预览与真机一致。
 */
final class LivePreview {

    private LivePreview() {
    }

    /** 熄屏/锁屏/桌面各自的兜底图：系统壁纸槽位 → 动态壁纸自带效果图。 */
    static Bitmap fallback(Context ctx, int mode) {
        if (mode == Motion.AOD) {
            return aod(ctx);                // 熄屏由我们自绘：本地按真实几何合成
        }
        Bitmap sys = systemWallpaper(ctx, mode);
        if (sys != null) {
            return sys;
        }
        if (mode == Motion.LOCK) {
            return PhotoStore.load(ctx, PhotoStore.LOCK);
        }
        // 桌面是动态壁纸：用它自己声明的效果图
        try {
            String comp = LibraryActivity.currentDesktopComponent(ctx);
            Drawable d = LibraryActivity.thumbnailFor(ctx, comp);
            Bitmap b = toBitmap(d);
            if (b != null) {
                Log.i(Root.TAG, "桌面预览=动态壁纸效果图 " + comp);
                return b;
            }
        } catch (Throwable t) {
            Log.w(Root.TAG, "取动态壁纸效果图失败", t);
        }
        return null;
    }

    /**
     * 熄屏「启航」预览：按引擎的真实几何本地合成 ——
     * 黑底 + 圆心在 16.5% 高度处、半径 0.22×屏宽 的圆，圆内是当前锁屏照片（模糊 + 压暗 0.27）。
     */
    static Bitmap aod(Context ctx) {
        Bitmap photo = PhotoStore.load(ctx, PhotoStore.LOCK);
        if (photo == null) {
            photo = systemWallpaper(ctx, Motion.LOCK);
        }
        if (photo == null) {
            return null;
        }
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = 600;
            int h = (int) (w * (double) dm.heightPixels / dm.widthPixels);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(android.graphics.Color.BLACK);
            float cx = w * 0.5f;
            float cy = h * 0.33f;
            float r = w * 0.22f;
            Bitmap square = centerSquare(photo);
            // 便宜的模糊：缩到 1/10 再放大回来
            int smallW = Math.max(4, (int) (r / 5));
            Bitmap blur = Bitmap.createScaledBitmap(square, smallW, smallW, true);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                            | android.graphics.Paint.FILTER_BITMAP_FLAG);
            p.setAlpha((int) (255 * 0.27f));
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(blur, null,
                    new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), p);
            c.restore();
            Log.i(Root.TAG, "熄屏预览=本地合成 " + w + "x" + h + " 圆心(" + cx + "," + cy + ") r=" + r);
            return out;
        } catch (Throwable t) {
            Log.w(Root.TAG, "合成熄屏预览失败", t);
            return null;
        }
    }

    /** 取图片中心正方形（圆内不做拉伸）。 */
    static Bitmap centerSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        return Bitmap.createBitmap(src, (src.getWidth() - side) / 2,
                (src.getHeight() - side) / 2, side, side);
    }

    /** 系统壁纸槽位的静态图（动态壁纸拿不到，返回 null）。 */
    static Bitmap systemWallpaper(Context ctx, int mode) {
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
            int flag = mode == Motion.LOCK
                    ? android.app.WallpaperManager.FLAG_LOCK
                    : android.app.WallpaperManager.FLAG_SYSTEM;
            Drawable d = wm.getDrawable(flag);
            Bitmap b = toBitmap(d);
            if (b != null) {
                Log.i(Root.TAG, "预览=系统壁纸槽位 flag=" + flag + " " + b.getWidth() + "x" + b.getHeight());
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    static Bitmap toBitmap(Drawable d) {
        if (d == null) {
            return null;
        }
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) {
                return b;
            }
        }
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        if (w <= 1 || h <= 1) {
            return null;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return out;
    }
}