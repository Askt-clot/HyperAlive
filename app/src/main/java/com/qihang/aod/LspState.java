package com.qihang.aod;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LSPosed 激活状态判定与异常防御：
 * 1. 统一状态枚举 State (ENABLED / DISABLED / UNKNOWN)；
 * 2. 唯有当前用户的实时 LSPosed DB 明确为 enabled=0 时才判定为 DISABLED，唯有 DISABLED 允许触发自动壁纸恢复；
 * 3. enabled=-1（未配置/缺记录）、作用域不全、lspd未运行、无Root或SQL/IO异常等均归入 UNKNOWN，绝不误触发壁纸回退；
 * 4. 自动恢复仅在确证没有独立锁屏壁纸且当前槽位确属 HyperAlive 时执行；
 * 5. 自动恢复仅针对 FLAG_LOCK 通过 getBuiltInDrawable + setBitmap 恢复静态壁纸（核对返回 ID > 0），绝不擦除桌面。
 */
public final class LspState {

    private static final String TAG = Root.TAG;
    private static final String PROP_LSP_ACTIVE = "sys.qihang.lsp_active";

    private static final AtomicBoolean sFallbackRunning = new AtomicBoolean(false);
    private static volatile long sLastCheckTime = 0;

    public enum State {
        ENABLED,
        DISABLED,
        UNKNOWN
    }

    private LspState() {}

    /**
     * 核心桩方法：
     * 由 XposedInit 在 LSPosed 注入本应用 (com.qihang.aod) 时 Hook 替换返回 true。
     * 若 LSPosed 未激活或本模块在管理器中被关闭，则原生返回 false。
     */
    public static boolean isLsposedHooked() {
        return false;
    }

    /**
     * 检查 system_server 是否已成功加载本模块并写入激活标记。
     */
    public static boolean isSystemServerHooked() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object val = sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_LSP_ACTIVE, "0");
            return "1".equals(val);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 统一查询 LSPosed 状态：
     * 1. 具有 ROOT 时：直接查询当前用户对应 LSPosed WAL 数据库 modules_config.db；
     *    - 唯有明确 enabled=0 时 -> 确证 DISABLED（哪怕进程内残存旧 Hook 或 system_server 残留属性，也以此为准）；
     *    - enabled=1 且 lspd 运行且作用域完整 -> ENABLED；
     *    - enabled=-1（未配置/缺少记录）、lspd 退出或作用域缺失、SQL 失败 -> UNKNOWN（绝不返回 DISABLED）；
     * 2. 无 ROOT 时：根据内存 Hook 和系统属性兜底，无法确证时返回 UNKNOWN。
     */
    public static State queryLspState(Context ctx) {
        if (ctx != null && Root.hasRoot()) {
            try {
                int userId = android.os.Process.myUid() / 100000;
                String db = Root.appProcess(ctx, "com.qihang.aod.root.LspDb", "-get", ctx.getPackageName(), String.valueOf(userId));
                if (db != null && !db.contains("ERR")) {
                    if (db.contains("enabled=0")) {
                        Log.i(TAG, "LSPosed DB 明确为停用 enabled=0 (userId=" + userId + ", out=" + db.trim() + ")");
                        return State.DISABLED;
                    }
                    if (db.contains("enabled=1")) {
                        boolean inScope = db.contains("system") && db.contains("com.android.systemui");
                        boolean lspd = !Root.su("ps -A | grep -c ' lspd$'", 8000).trim().equals("0");
                        if (lspd && inScope) {
                            return State.ENABLED;
                        }
                        Log.w(TAG, "LSPosed DB enabled=1 但环境未就绪 (lspd=" + lspd + ", inScope=" + inScope + ")");
                        return State.UNKNOWN;
                    }
                    if (db.contains("enabled=-1")) {
                        Log.i(TAG, "LSPosed DB 缺少模块记录 enabled=-1 (userId=" + userId + ")");
                        return State.UNKNOWN;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "查询 LSPosed DB 异常", t);
                return State.UNKNOWN;
            }
            return State.UNKNOWN;
        }

        if (!Root.hasRoot()) {
            if (isLsposedHooked() || isSystemServerHooked()) {
                return State.ENABLED;
            }
            return State.UNKNOWN;
        }

        return State.UNKNOWN;
    }

    /**
     * 检查当前系统锁屏壁纸是否确由 HyperAlive 占用。
     * 防御独立锁屏误判：
     * 1. 锁屏如果显式指定了独立动态壁纸服务，直接核对是否为本模块包名；
     * 2. 检查锁屏是否有独立槽位：系统服务的 getWallpaperIdForUser(FLAG_LOCK) 仅在无独立槽时返回 -1；
     *    若返回独立 ID >= 0 或查询抛出异常，立即保持现状（返回 false）；
     * 3. 唯有在 getWallpaperId(FLAG_LOCK) 成功返回 -1（确无独立槽）时，才核验共享桌面槽是否为 HyperAlive。
     */
    public static boolean isLockWallpaperOccupiedByHyperAlive(Context ctx) {
        if (ctx == null) return false;
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) return false;

            // 1. 锁屏如果显式指定了独立动态壁纸服务
            android.app.WallpaperInfo lockInfo = wm.getWallpaperInfo(android.app.WallpaperManager.FLAG_LOCK);
            if (lockInfo != null) {
                return ctx.getPackageName().equals(lockInfo.getPackageName());
            }

            // 2. 检查是否存在独立锁屏槽位：
            // getWallpaperId(FLAG_LOCK) 成功返回 -1 才是真正无独立槽；ID >= 0 或抛异常立即保持现状
            int lockId;
            try {
                lockId = wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK);
            } catch (Throwable t) {
                Log.w(TAG, "获取 lockId 异常，保持现状", t);
                return false;
            }

            if (lockId != -1) {
                Log.i(TAG, "锁屏已配置独立壁纸 (lockId=" + lockId + " != -1)，非模块占用");
                return false;
            }

            // 3. 唯有在确无独立锁屏（lockId == -1）时，才核验共享系统槽是否为 HyperAlive 动态壁纸
            android.app.WallpaperInfo globalInfo = wm.getWallpaperInfo();
            if (globalInfo != null && ctx.getPackageName().equals(globalInfo.getPackageName())) {
                String service = globalInfo.getServiceName();
                if (service != null && service.contains("QihangWallpaper")) {
                    Log.i(TAG, "锁屏无独立壁纸 (lockId=-1)，共享系统槽确为 HyperAlive 动态壁纸");
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isLockWallpaperOccupiedByHyperAlive 异常，保持现状", t);
        }
        return false;
    }

    /**
     * 仅恢复锁屏槽位的系统默认原生静态壁纸（FLAG_LOCK），绝不擦除桌面（FLAG_SYSTEM）。
     * 优先通过 WallpaperManager.getBuiltInDrawable 获取系统内置默认壁纸并写入 FLAG_LOCK，
     * 严格核对 setBitmap 返回 ID > 0，避免写失败误报成功。
     */
    public static boolean restoreLockDefaultWallpaper(Context ctx) {
        if (ctx == null) return false;
        Log.i(TAG, "开始恢复锁屏默认原生静态壁纸 (仅针对 FLAG_LOCK)...");
        try {
            android.app.WallpaperManager wm = (android.app.WallpaperManager)
                    ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) {
                Log.w(TAG, "WallpaperManager 为空");
                return false;
            }
            android.graphics.drawable.Drawable d = null;
            try {
                d = wm.getBuiltInDrawable(android.app.WallpaperManager.FLAG_LOCK);
                if (d != null) {
                    Log.i(TAG, "已获取到系统 FLAG_LOCK 默认壁纸 Drawable");
                }
            } catch (Throwable t) {
                Log.w(TAG, "getBuiltInDrawable(FLAG_LOCK) 异常", t);
            }
            if (d == null) {
                try {
                    d = wm.getBuiltInDrawable(android.app.WallpaperManager.FLAG_SYSTEM);
                    if (d != null) {
                        Log.i(TAG, "已回退获取到系统 FLAG_SYSTEM 默认壁纸 Drawable");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "getBuiltInDrawable(FLAG_SYSTEM) 异常", t);
                }
            }
            if (d != null) {
                android.graphics.Bitmap bmp = drawableToBitmap(d);
                if (bmp != null) {
                    int newId = wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK);
                    if (newId > 0) {
                        Log.i(TAG, "已成功通过 setBitmap 将系统默认壁纸设置到 FLAG_LOCK (newId=" + newId + ")，未影响桌面壁纸");
                        return true;
                    } else {
                        Log.w(TAG, "setBitmap(FLAG_LOCK) 返回非正 ID: " + newId + "，设置失败");
                        return false;
                    }
                } else {
                    Log.w(TAG, "默认 Drawable 转 Bitmap 失败，不执行擦除操作");
                }
            } else {
                Log.w(TAG, "未能获取到任何系统内置默认壁纸 (getBuiltInDrawable 为空)，不执行擦除操作");
            }
        } catch (Throwable t) {
            Log.e(TAG, "restoreLockDefaultWallpaper 失败", t);
        }
        return false;
    }

    private static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            if (bmp != null) return bmp;
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = 1080;
            h = 2400;
        }
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            drawable.setBounds(0, 0, w, h);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            Log.w(TAG, "drawableToBitmap 转换异常", t);
            return null;
        }
    }

    /**
     * 异步检查并在确证 DISABLED 时自愈恢复锁屏壁纸。
     * 在 worker 线程排队前原子占用 in-flight guard，避免 UI 和壁纸引擎并发触发 su 或重复写入临时 dex。
     */
    public static void checkFallbackIfInactiveAsync(final Context context) {
        if (context == null) return;
        final Context ctx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        long now = SystemClock.elapsedRealtime();
        if (now - sLastCheckTime < 5000L) return;
        if (!sFallbackRunning.compareAndSet(false, true)) {
            return;
        }
        sLastCheckTime = now;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doCheckFallbackIfInactive(ctx);
                } finally {
                    sFallbackRunning.set(false);
                }
            }
        }, "qihang-lsp-fallback").start();
    }

    /**
     * 同步检查并在确证 DISABLED 时执行恢复，所有 I/O 和复核离开主线程。
     */
    public static boolean checkFallbackIfInactiveSync(Context ctx) {
        if (ctx == null) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - sLastCheckTime < 5000L) return false;
        if (!sFallbackRunning.compareAndSet(false, true)) {
            return false;
        }
        sLastCheckTime = now;
        try {
            return doCheckFallbackIfInactive(ctx);
        } finally {
            sFallbackRunning.set(false);
        }
    }

    private static boolean doCheckFallbackIfInactive(Context ctx) {
        if (ctx == null) return false;
        if (!isLockWallpaperOccupiedByHyperAlive(ctx)) {
            return false;
        }
        State state = queryLspState(ctx);
        if (state != State.DISABLED) {
            if (state == State.UNKNOWN) {
                Log.i(TAG, "LSPosed 状态未知/未配置，保持现状，绝不触发壁纸回退");
            }
            return false;
        }
        // 执行前二次复核
        if (!isLockWallpaperOccupiedByHyperAlive(ctx)) {
            Log.i(TAG, "执行前复核：当前锁屏壁纸非 HyperAlive 占用，取消回退");
            return false;
        }
        State recheck = queryLspState(ctx);
        if (recheck != State.DISABLED) {
            Log.i(TAG, "执行前复核：LSPosed 状态已不是 DISABLED (" + recheck + ")，取消回退");
            return false;
        }
        Log.e(TAG, "★ 确证 LSPosed 明确停用 (enabled=0) 且锁屏由 HyperAlive 占用，立即自动恢复默认锁屏壁纸...");
        boolean ok = restoreLockDefaultWallpaper(ctx);
        Log.i(TAG, "锁屏默认壁纸恢复结果: " + ok);
        return ok;
    }
}
