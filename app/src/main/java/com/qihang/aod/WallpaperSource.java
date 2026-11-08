package com.qihang.aod;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * 启航圆圈的内容来源 = **当前桌面壁纸**（不再单独设置照片）。
 *
 * <p><b>规则</b>：桌面壁纸是<b>自定义（静态图片）壁纸</b>时，圆圈内容跟随它；
 * <b>魅族 Alive 这类动态壁纸不参与跟随</b>（拿不到它的帧，也刻意不跟）。
 *
 * <p>取图优先序：
 * <ol>
 *   <li>{@link WallpaperManager}（自定义/图片壁纸直接给到位图，**不需要 root**）；
 *       动态壁纸这里会拿不到 → 不跟随，保持上一次的自定义壁纸或内置图；</li>
 *   <li>SystemUI/system_server 快照 → 系统壁纸文件 {@code /data/system/users/0/wallpaper}（兜底）；</li>
 *   <li>内置兜底图 {@code assets/photo/default.jpg}。</li>
 * </ol>
 * 自定义壁纸用 {@code getWallpaperId(FLAG_SYSTEM)} 做变更检测（换壁纸立刻跟随）。
 */
final class WallpaperSource {

    private static final String TAG = Root.TAG;
    static final String SYSTEM_WALLPAPER = "/data/system/users/0/wallpaper";

    private Bitmap cached;
    private String cachedStamp;
    private String origin = "未加载";
    private int width;
    private int height;

    String origin() {
        return origin + " (" + width + "x" + height + ")";
    }

    private int cachedWallpaperId = -1;
    /** lock_photo.png 的 mtime（换照片后要重新读）。 */
    private long cachedPickedStamp = -2;
    /** frame_photo.png 的 mtime（留光相框专属照片）。 */
    private long cachedFrameStamp = -2;
    /** home_photo.png 的 mtime 与桌面跟随模式（决定独立桌面壁纸纹理）。 */
    private Bitmap cachedHome;
    private long cachedHomeStamp = -2;
    private int cachedHomeKind = -1;

    /** 检查锁屏/桌面壁纸或跟随开关是否发生变化（未变时无需重传 GL 纹理）。 */
    synchronized boolean hasChanged(Context ctx) {
        if (cached == null) {
            return true;
        }
        int id = wallpaperId(ctx);
        long lockStamp = PhotoStore.stamp(ctx, PhotoStore.LOCK);
        long homeStamp = PhotoStore.stamp(ctx, PhotoStore.HOME);
        long frameStamp = PhotoStore.stamp(ctx, PhotoStore.FRAME);
        int homeKind = LastSet.kind(ctx);
        return (id != -1 && id != cachedWallpaperId)
                || lockStamp != cachedPickedStamp
                || homeStamp != cachedHomeStamp
                || frameStamp != cachedFrameStamp
                || homeKind != cachedHomeKind;
    }

    /**
     * 取独立桌面壁纸位图（仅当「桌面不跟随锁屏」且存在 {@code home_photo.png} 时返回非 null；
     * 跟随锁屏时返回 null，渲染器直接复用锁屏纹理）。
     */
    synchronized Bitmap getHome(Context ctx, boolean force) {
        int kind = LastSet.kind(ctx);
        long homeStamp = PhotoStore.stamp(ctx, PhotoStore.HOME);
        if (kind != LastSet.KIND_PHOTO || !PhotoStore.has(ctx, PhotoStore.HOME)) {
            if (cachedHome != null) {
                cachedHome.recycle();
                cachedHome = null;
            }
            cachedHomeKind = kind;
            cachedHomeStamp = homeStamp;
            return null;
        }
        if (!force && cachedHome != null && !cachedHome.isRecycled()
                && kind == cachedHomeKind && homeStamp == cachedHomeStamp) {
            return cachedHome;
        }
        Bitmap bmp = PhotoStore.load(ctx, PhotoStore.HOME);
        if (bmp == null) {
            return cachedHome;
        }
        if (cachedHome != null && cachedHome != bmp) {
            cachedHome.recycle();
        }
        cachedHome = bmp;
        cachedHomeKind = kind;
        cachedHomeStamp = homeStamp;
        Log.i(TAG, "独立桌面壁纸已加载 " + bmp.getWidth() + "x" + bmp.getHeight());
        return cachedHome;
    }

    /** 桌面（FLAG_SYSTEM）壁纸 id：自定义壁纸换了它就变。 */
    private int wallpaperId(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Method m = WallpaperManager.class.getMethod("getWallpaperId", int.class);
            Object v = m.invoke(wm, WallpaperManager.FLAG_SYSTEM);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 取当前壁纸位图；{@code force=false} 且壁纸没换时复用缓存。 */
    synchronized Bitmap get(Context ctx, boolean force) {
        int id = wallpaperId(ctx);
        long picked = PhotoStore.stamp(ctx, PhotoStore.LOCK);
        long frameStamp = PhotoStore.stamp(ctx, PhotoStore.FRAME);
        if (!force && cached != null && id == cachedWallpaperId && picked == cachedPickedStamp
                && frameStamp == cachedFrameStamp
                && id != -1) {
            return cached;
        }
        // 0) 用户在编辑器里选过的锁屏照片（相册）——最高优先级
        Bitmap bmp = PhotoStore.load(ctx, PhotoStore.LOCK);
        String from = "锁屏照片（相册选择）";
        // 1) 没选过：自定义/静态壁纸，WallpaperManager 直接拿（动态壁纸这里拿不到）
        if (bmp == null) {
            bmp = readFromWallpaperManager(ctx);
            from = "桌面自定义壁纸";
        }
        if (bmp == null) {
            Log.i(TAG, "WallpaperManager 拿不到（多半桌面是动态壁纸，如魅族 Alive）→ 圆圈内容不跟随");
            bmp = readFromSnapshot(ctx);
            from = "SystemUI 快照";
        }
        if (bmp == null) {
            bmp = readFromSystemFile();
            from = "系统壁纸文件";
        }
        if (bmp == null) {
            bmp = readAsset(ctx);
            from = "内置兜底图";
        }
        if (bmp == null) {
            return cached;
        }
        if (cached != null && cached != bmp) {
            cached.recycle();
        }
        cached = bmp;
        cachedWallpaperId = id;
        cachedPickedStamp = picked;
        cachedFrameStamp = frameStamp;
        cachedStamp = stamp();
        origin = from;
        width = bmp.getWidth();
        height = bmp.getHeight();
        Log.i(TAG, "圆圈内容来源=" + from + " " + width + "x" + height + " wpId=" + id);
        return cached;
    }

    /** 壁纸文件指纹（root，全局 mount namespace）。 */
    String stamp() {
        try {
            String out = Root.suRaw(8000, "-M", "-c", "stat -c %s:%Y " + SYSTEM_WALLPAPER);
            if (out != null && out.contains(":")) {
                return out.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    static final String[] CANDIDATES = {
            "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
            "/storage/emulated/0/Android/data/com.qihang.aod/files/wp_snapshot.bin",
            "/data/local/tmp/qihang_wp.bin",
    };

    /** 优先用 SystemUI 快照（App 的 namespace 看不到 /data/system）。 */
    private Bitmap readFromSnapshot(Context ctx) {
        for (String path : CANDIDATES) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists() || f.length() < 1024) {
                    continue;
                }
                byte[] data = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0;
                    while (off < data.length) {
                        int n = in.read(data, off, data.length - off);
                        if (n <= 0) {
                            break;
                        }
                        off += n;
                    }
                }
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b != null) {
                    Log.i(TAG, "圆圈内容来源=SystemUI 快照 " + path + " " + b.getWidth() + "x" + b.getHeight());
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Bitmap readFromSystemFile() {
        // App 的 mount namespace 里 /data/system 根本不存在（No such file or directory），
        // 必须 su -M 进全局 namespace；普通 su 只作兜底。
        Bitmap b = readViaRoot(true);
        if (b == null) {
            b = readViaRoot(false);
        }
        return b;
    }

    private Bitmap readViaRoot(boolean mountMaster) {
        Process p = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("su");
            if (mountMaster) {
                cmd.add("-M");
            }
            cmd.add("-c");
            cmd.add("cat " + SYSTEM_WALLPAPER);
            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            final Process fp = p;
            final String[] err = new String[1];
            Thread errReader = new Thread(() -> {
                try (InputStream es = fp.getErrorStream()) {
                    ByteArrayOutputStream eb = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int k;
                    while ((k = es.read(b)) > 0) {
                        eb.write(b, 0, k);
                    }
                    err[0] = eb.toString("UTF-8").trim();
                } catch (Throwable ignored) {
                }
            }, "wp-stderr");
            errReader.setDaemon(true);
            errReader.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2 << 20);
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            p.waitFor();
            byte[] data = bos.toByteArray();
            if (data.length < 1024) {
                errReader.join(800);
                Log.w(TAG, "系统壁纸读取过短（mountMaster=" + mountMaster + "）：exit=" + p.exitValue()
                        + " bytes=" + data.length + " stderr=" + err[0]);
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) {
                Log.w(TAG, "系统壁纸文件解不出来（" + data.length + "B，magic="
                        + (char) data[0] + (char) data[1] + (char) data[2] + (char) data[3] + "）");
            }
            return b;
        } catch (Throwable t) {
            Log.w(TAG, "读系统壁纸文件失败（mountMaster=" + mountMaster + "）: " + t);
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private Bitmap readFromWallpaperManager(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Drawable d = null;
            try {
                Method m = WallpaperManager.class.getMethod("getDrawable", int.class);
                d = (Drawable) m.invoke(wm, WallpaperManager.FLAG_SYSTEM);
            } catch (Throwable ignored) {
            }
            if (d == null) {
                d = wm.getDrawable();
            }
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null) {
                    return b;
                }
            }
            // 部分 ROM 只给了非 BitmapDrawable，兜底自己画一遍
            if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                Bitmap b = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(b);
                d.setBounds(0, 0, b.getWidth(), b.getHeight());
                d.draw(c);
                return b;
            }
        } catch (Throwable t) {
            Log.w(TAG, "WallpaperManager 取图失败: " + t);
        }
        return null;
    }

    private Bitmap readAsset(Context ctx) {
        try (InputStream in = ctx.getAssets().open("photo/default.jpg")) {
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            return null;
        }
    }
}