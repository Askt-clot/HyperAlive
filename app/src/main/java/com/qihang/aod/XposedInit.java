package com.qihang.aod;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 启航息屏 —— LSPosed 入口。
 *
 * <p>只做三件事（对应三条系统通路）：
 * <ol>
 *   <li><b>权限</b>：{@code PackageManagerService.checkPermission} 对
 *       {@code AMBIENT_WALLPAPER} + 目标包直接返回 GRANTED；
 *       另外放行 {@code WallpaperManagerService} 的两道壁纸设置门禁。</li>
 *   <li><b>黑底</b>：息屏时 SystemUI 的 AOD 视图树上有两个 {@code #ff000000} 背景，
 *       每 500ms 幂等清扫，让下层壁纸透出。</li>
 *   <li><b>事件源</b>：{@code ScrimController.updateScrims} 的场景名 → 广播 mode 0/1/2
 *       给本模块壁纸，同时把「桌面/锁屏/息屏」命令转发给魅族原生 Alive 壁纸。</li>
 * </ol>
 *
 * 注意：本类不得有任何会在类初始化时触碰 Looper / Context 的静态字段
 * （XposedInit 的静态初始化会在每个注入进程执行，包括 system_server）。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "QihangAod";

    static final String PERM_AMBIENT = "android.permission.AMBIENT_WALLPAPER";
    static final String PKG_SELF = "com.qihang.aod";
    static final String PKG_ALIVE = "com.flyme.alivewallpaper";
    /** 需要放行 AMBIENT_WALLPAPER 与壁纸门禁的包。 */
    /**
     * 需要放行壁纸门禁的包。{@code com.miui.miwallpaper} 是系统「图片壁纸」服务，
     * 放行它才能把桌面槽位切到图片壁纸（用于验证「圆圈内容跟随桌面自定义壁纸」）。
     */
    static final String[] TARGET_PKGS = {PKG_SELF, PKG_ALIVE, "com.poc.alive",
            "com.miui.miwallpaper", "com.miui.aod", "miui.systemui.plugin"};

    /** 模块壁纸的三态广播。 */
    public static final String ACTION_MODE = "com.qihang.aod.CMD";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ANIMATE = "animate";
    public static final String EXTRA_STATE = "state";
    /** 魅族 Alive 壁纸的命令通道。 */
    static final String ACTION_ALIVE = "com.meizu.flyme.alivepaper";
    static final String EXTRA_ALIVE_CMD = "alive_command";

    // ---- 懒加载的状态（禁止静态初始化里碰 Looper） ----
    private static Handler sMain;
    private static View sAodRoot;
    private static Runnable sSweep;
    private static FlymeClockView sFlymeClock;
    private static final java.util.List<java.lang.ref.WeakReference<FlymeClockView>> sClocks = new java.util.ArrayList<>();

    private static synchronized void registerClock(FlymeClockView clock) {
        if (clock == null) return;
        boolean found = false;
        java.util.Iterator<java.lang.ref.WeakReference<FlymeClockView>> it = sClocks.iterator();
        while (it.hasNext()) {
            FlymeClockView c = it.next().get();
            if (c == null) {
                it.remove();
            } else if (c == clock) {
                found = true;
            }
        }
        if (!found) {
            sClocks.add(new java.lang.ref.WeakReference<>(clock));
        }
    }

    private static synchronized boolean hasActiveClock() {
        for (java.lang.ref.WeakReference<FlymeClockView> ref : sClocks) {
            FlymeClockView c = ref.get();
            if (c != null && c.isAttachedToWindow()) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void updateClocksVisibility(boolean visible) {
        updateClocksVisibility(visible, visible ? (sScrimMode == 0 ? 0 : 1) : 2);
    }

    private static synchronized void updateClocksVisibility(boolean visible, int mode) {
        java.util.Iterator<java.lang.ref.WeakReference<FlymeClockView>> it = sClocks.iterator();
        while (it.hasNext()) {
            FlymeClockView c = it.next().get();
            if (c == null) {
                it.remove();
            } else {
                if (visible) {
                    c.setVisibility(View.VISIBLE);
                    c.bringToFront();
                    c.setClockMode(mode, true);
                    c.updateTime();
                } else {
                    c.setClockMode(mode, true);
                }
            }
        }
    }

    // ───────────────────────── 壁纸快照（圆圈内容跟随桌面壁纸） ─────────────────────────
    /**
     * App 自己的 mount namespace 里 /data/system 不可见（su -M 也救不回来），
     * 所以由 SystemUI 侧（uid=system、namespace 正确）读系统壁纸文件，
     * 写进模块可读的目录，再通知壁纸引擎重载。
     */
    static final String SNAP_SRC = "/data/system/users/0/wallpaper";

    private static void snapWallpaper(Context ctx) {
        byte[] data = readFileAsSystem(SNAP_SRC);
        if (data == null || data.length < 1024) {
            log("壁纸快照失败：读不到 " + SNAP_SRC + " (" + (data == null ? -1 : data.length) + ")");
            return;
        }
        String[] targets = {
                "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/storage/emulated/0/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/local/tmp/qihang_wp.bin",
        };
        for (String t : targets) {
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(t);
                os.write(data);
                os.close();
                log("壁纸快照已写 " + t + " (" + data.length + "B)");
                break;
            } catch (Throwable e) {
                log("壁纸快照写失败 " + t + " : " + e);
            }
        }
        if (ctx != null) {
            Intent back = new Intent("com.qihang.aod.SNAP_DONE");
            back.setPackage("com.qihang.aod");
            sendBroadcastSmart(ctx, back);
        }
    }

    /** 先直接读（uid=system 有权限），失败再走 su -M。 */
    private static byte[] readFileAsSystem(String path) {
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            log("直接读 " + path + " 失败: " + t);
        }
        try {
            Process p = new ProcessBuilder("su", "-M", "-c", "cat " + path).start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            try (java.io.InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            p.waitFor();
            return bos.toByteArray();
        } catch (Throwable t) {
            log("su 读 " + path + " 失败: " + t);
            return null;
        }
    }

    private static boolean sSnapInstalled;

    private static void installSnapReceiver(Context ctx) {
        if (sSnapInstalled || ctx == null) {
            return;
        }
        sSnapInstalled = true;
        try {
            IntentFilter f = new IntentFilter("com.qihang.aod.SNAP");
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        snapWallpaper(c);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }, f, Context.RECEIVER_EXPORTED);
            log("壁纸快照接收器已注册");
        } catch (Throwable t) {
            log("注册壁纸快照接收器失败: " + t);
        }
    }

// ───────────── system_server 侧壁纸快照（只有它能看见 /data/system） ─────────────
    private static boolean sSysSnapInstalled;

    private static Context sysContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(cur);
        } catch (Throwable t) {
            log("拿 system context 失败: " + t);
            return null;
        }
    }

    private static void installSystemSnap() {
        if (sSysSnapInstalled) {
            return;
        }
        Context ctx = sysContext();
        if (ctx == null) {
            return;
        }
        sSysSnapInstalled = true;
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        snapAsSystem(c);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }, new IntentFilter("com.qihang.aod.SNAP"), Context.RECEIVER_EXPORTED);
            log("system_server 壁纸快照接收器已注册");
        } catch (Throwable t) {
            log("注册 system_server 快照接收器失败: " + t);
        }
    }

    private static void snapAsSystem(Context ctx) {
        byte[] data = null;
        try (java.io.InputStream in = new java.io.FileInputStream(SNAP_SRC)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            data = bos.toByteArray();
        } catch (Throwable t) {
            log("system_server 读壁纸失败: " + t);
        }
        if (data == null || data.length < 1024) {
            log("system_server 读壁纸过短: " + (data == null ? -1 : data.length));
            return;
        }
        log("system_server 读到壁纸 " + data.length + "B");
        String[] targets = {
                "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/local/tmp/qihang_wp.bin",
                "/data/data/com.qihang.aod/files/wp_snapshot.bin",
        };
        boolean wrote = false;
        for (String t : targets) {
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(t)) {
                os.write(data);
                log("壁纸快照已写 " + t);
                wrote = true;
                break;
            } catch (Throwable e) {
                log("壁纸快照写失败 " + t + " : " + e);
            }
        }
        if (!wrote) {
            // 兜底：分块广播（Binder 上限 1MB，每块 640KB）
            int chunk = 640 * 1024;
            int total = (data.length + chunk - 1) / chunk;
            for (int i = 0; i < total; i++) {
                int from = i * chunk;
                int to = Math.min(data.length, from + chunk);
                Intent it = new Intent("com.qihang.aod.SNAP_DATA");
                it.setPackage("com.qihang.aod");
                it.putExtra("idx", i);
                it.putExtra("total", total);
                it.putExtra("len", data.length);
                it.putExtra("chunk", java.util.Arrays.copyOfRange(data, from, to));
                sendBroadcastSmart(ctx, it);
            }
            log("壁纸快照改走分块广播: " + total + " 块");
        }
    }
    private static int sSweepCount;
    private static int sClearedLogged;
    private static android.content.Context sSweepCtx;
    private static int sLastMode = -1;
    private static int sScrimMode = 1;
    private static String sStateName;
    private static Runnable sReconcile;
    private static boolean sDisplayRegistered;
    private static Context sContext;
    private static volatile boolean sLoggedGate;

    static String cmd(int mode) {
        if (mode == 0) {
            return "action_alive_aod";
        }
        if (mode == 1) {
            return "action_alive_lock";
        }
        return "action_alive_launcher";
    }

    static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {}
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {}
    }

    static boolean isTarget(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String t : TARGET_PKGS) {
            if (t.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<Integer> sTargetUids = new java.util.concurrent.CopyOnWriteArraySet<>();

    static boolean isTargetUid(int uid) {
        if (uid <= 10000) {
            return false;
        }
        if (sTargetUids.contains(uid)) {
            return true;
        }
        try {
            Context ctx = sysContext();
            if (ctx != null) {
                String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                if (pkgs != null) {
                    for (String p : pkgs) {
                        if (isTarget(p)) {
                            sTargetUids.add(uid);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int getUidFromProcessRecord(Object app) {
        if (app == null) return -1;
        try {
            Field f = app.getClass().getField("uid");
            return f.getInt(app);
        } catch (Throwable t) {
            try {
                Method m = app.getClass().getMethod("getUid");
                return (Integer) m.invoke(app);
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static String getPkgFromProcessRecord(Object app) {
        if (app == null) return null;
        try {
            Field f = app.getClass().getField("info");
            Object info = f.get(app);
            if (info instanceof android.content.pm.ApplicationInfo) {
                return ((android.content.pm.ApplicationInfo) info).packageName;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field f = app.getClass().getField("processName");
            Object pn = f.get(app);
            if (pn instanceof String) return (String) pn;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isTargetInArgs(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg == null) continue;
            if (arg instanceof Integer && isTargetUid((Integer) arg)) return true;
            if (arg instanceof String && isTarget((String) arg)) return true;
            if (arg instanceof Intent) {
                Intent it = (Intent) arg;
                if (isTarget(it.getPackage())) return true;
                if (it.getComponent() != null && isTarget(it.getComponent().getPackageName())) return true;
            }
            int uid = getUidFromProcessRecord(arg);
            if (uid > 0 && isTargetUid(uid)) return true;
            String pkg = getPkgFromProcessRecord(arg);
            if (pkg != null && isTarget(pkg)) return true;
        }
        return false;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            if ("android".equals(lp.packageName)) {
                hookSystemServer(lp.classLoader);
            } else if ("com.android.systemui".equals(lp.packageName) || "miui.systemui.plugin".equals(lp.packageName)) {
                hookSystemUi(lp.classLoader);
            } else if (PKG_ALIVE.equals(lp.packageName)) {
                hookAliveWallpaper(lp.classLoader);
            }
        } catch (Throwable t) {
            log("handleLoadPackage 失败 " + lp.packageName);
            XposedBridge.log(t);
        }
    }

    /**
     * 修复魅族 Alive 动态壁纸在超 1080p 屏幕（如 1200x2608）上熄屏→锁屏右侧和底部出现宽黑边的问题：
     *
     * <p>原因：{@code MzUserAwareWallpaper$UserAwareEngine} 在 {@code onCreate} 中通过
     * {@code h.T()}（=1080f）把缓冲区分辨率按 {@code 1080 / realWidth} 缩水为 {@code 1080x2347}，
     * 并调用 {@code SurfaceHolder.setFixedSize(1080, 2347)}。在 HyperOS 熄屏唤醒到锁屏的缩放过渡中，
     * SurfaceFlinger 的 {@code Wallpaper BBQ wrapper} 变换矩阵按物理屏幕 {@code 1200x2608} 计算
     * {@code scale=1.0485, tx=-29, ty=-51}，覆盖了原本 {@code 1.1111} 的拉伸倍率，导致右、下边缘
     * 分别缺失 ~97px 和 ~198px 形成黑边。
     *
     * <p>解法：直接解除 {@code 1080} 限制，让魅族壁纸引擎以设备原生全屏分辨率（如 {@code 1200x2608}）
     * 创建 Surface 与渲染视口，彻底消除任何状态下的黑边。
     */
    private void hookAliveWallpaper(ClassLoader cl) {
        // 1) 解除渲染器基类 h.T() 及所有子类重写的 T() 短边上限（1080f / 896.4f）
        XC_MethodHook maxResHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(10800.0f);
            }
        };
        for (String clsName : new String[]{
                "com.meizu.flyme.sdkstage.wallpaper.h",
                "x1.m",
                "y1.b",
                "i2.b"
        }) {
            hook(cl, clsName, "T", new Class[0], maxResHook);
        }

        // 2) UserAwareEngine.y(w, h) 直接返回原始物理宽度 w，x(w, h) 直接返回原始物理高度 h
        hook(cl, "com.meizu.flyme.sdkstage.wallpaper.MzUserAwareWallpaper$UserAwareEngine",
                "y", new Class[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int w = (Integer) param.args[0];
                        if (w > 0) {
                            param.setResult(w);
                        }
                    }
                });

        hook(cl, "com.meizu.flyme.sdkstage.wallpaper.MzUserAwareWallpaper$UserAwareEngine",
                "x", new Class[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int h = (Integer) param.args[1];
                        if (h > 0) {
                            param.setResult(h);
                        }
                    }
                });

        // 3) 兜底拦截 BaseSurfaceHolder.setFixedSize(w, h)：若仍收到缩水尺寸则提升为真实物理屏幕尺寸
        hook(cl, "com.android.internal.view.BaseSurfaceHolder",
                "setFixedSize", new Class[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int w = (Integer) param.args[0];
                        int h = (Integer) param.args[1];
                        int[] real = queryRealScreenSize();
                        if (real != null && real[0] > 0 && real[1] > 0) {
                            int rw = real[0];
                            int rh = real[1];
                            if (w > 0 && h > 0 && (w < rw || h < rh)) {
                                log("Alive setFixedSize 提升为原生全屏分辨率: "
                                        + w + "x" + h + " -> " + rw + "x" + rh);
                                param.args[0] = rw;
                                param.args[1] = rh;
                            } else {
                                log("Alive setFixedSize 保持原生全屏分辨率: " + w + "x" + h);
                            }
                        }
                    }
                });

        // 4) 修正 k2.q.h(Context) 硬编码 1080x2400 的息屏显示区域 Rect(324, 504, 756, 936)，
        //    使其居中于真实物理屏幕宽度 (centerX = rw / 2) 与 33.5% 屏高锚点 (centerY = rh * 0.335f)，
        //    彻底解决超 1080p 屏幕动态壁纸息屏偏左及与预览位置不一致的问题。
        hook(cl, "k2.q", "h", new Class[]{Context.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int[] real = queryRealScreenSize();
                        if (real != null && real[0] > 0 && real[1] > 0) {
                            int rw = real[0];
                            int rh = real[1];
                            int cx = rw / 2;
                            int cy = Math.round(rh * 0.335f);
                            int half = Math.round(rw * 0.2f);
                            param.setResult(new android.graphics.Rect(
                                    cx - half, cy - half, cx + half, cy + half));
                        }
                    }
                });

        // 5) 放行非魅族 ROM 的息屏使能检查 k2.q.o() 与 k2.q.D(Context)
        XC_MethodHook trueHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        };
        hook(cl, "k2.q", "o", new Class[0], trueHook);
        hook(cl, "k2.q", "D", new Class[]{Context.class}, trueHook);

        log("AliveWallpaper 全屏原生分辨率与息屏居中 hook 已安装");
    }

    private static int[] queryRealScreenSize() {
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            Object info = dmgCls.getMethod("getDisplayInfo", int.class).invoke(dmg, Display.DEFAULT_DISPLAY);
            if (info != null) {
                int lw = (Integer) info.getClass().getField("logicalWidth").get(info);
                int lh = (Integer) info.getClass().getField("logicalHeight").get(info);
                if (lw > 0 && lh > 0) {
                    return new int[]{Math.min(lw, lh), Math.max(lw, lh)};
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{Math.min(dm.widthPixels, dm.heightPixels),
                        Math.max(dm.widthPixels, dm.heightPixels)};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 通路 1：权限
    // =====================================================================

    /**
     * 熄屏意图：goToSleep 那一刻置位。用来压掉熄屏流程里 SystemUI 误报的「回到锁屏」——
     * 实测熄屏头 0.5~1s 内 display 仍是 ON，ScrimController 会报 KEYGUARD，
     * 若照单全收就会把刚起步的收缩打断成反向扩散（现象="缩到一半又弹开再缩回"）。
     */
    private static volatile boolean sAsleep;
    private static volatile long sSleepAtMs;
    private static volatile long sWakeAtMs;
    /** SystemUI 侧已确认进入 AOD 的那次 sleep 时间戳；一旦到达 AOD，后续离开 AOD 即视为真唤醒。 */
    private static volatile long sClearedSleepAt;
    /** SystemUI 侧 Scrim 从 AOD(0) 切出到 KEYGUARD/UNLOCKED 的时刻，防止 doze 滞后压住亮屏。 */
    private static volatile long sWakeFromAodAtMs;
    private static long sLastFullAodMs;

    /**
     * 熄屏/亮屏**前馈**（取代旧的「延后熄屏」）：在 PowerManagerService 的状态变化点上，
     * 立刻把目标形态告诉壁纸引擎，让「收缩动画」与「屏幕亮度渐变」**并行**，
     * 而不是等 SystemUI 慢半拍再串行。
     *
     * <p>这里**只广播、不拦截**：不延后熄屏——延后只是把黑屏往后挪，不是连贯。
     */
    private void hookPowerAodSignal(ClassLoader cl) {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                String n = p.method.getName();
                if (n.startsWith("goToSleep")) {
                    onSleepIntent();
                } else if (n.startsWith("wakeUp") || n.startsWith("wakePowerGroup")) {
                    boolean isBiometric = false;
                    if (p.args != null) {
                        for (Object arg : p.args) {
                            if (arg instanceof String && ((String) arg).contains("BIOMETRIC")) {
                                isBiometric = true;
                                break;
                            }
                            if (arg instanceof Integer && ((Integer) arg) == 17) {
                                isBiometric = true;
                                break;
                            }
                        }
                    }
                    if (isBiometric) {
                        log("指纹解锁唤醒(BIOMETRIC) → 前馈 mode=2 (HOME)");
                        onBiometricWakeIntent();
                        return;
                    }
                    onWakeIntent();
                }
            }
        };
        int n = 0;
        for (String clsName : new String[]{
                "com.android.server.power.PowerManagerService",
                "com.android.server.power.PowerManagerService$BinderService"
        }) {
            try {
                Class<?> pm = Class.forName(clsName, false, cl);
                for (Method m : pm.getDeclaredMethods()) {
                    String name = m.getName();
                    if (!name.startsWith("goToSleep")
                            && !name.startsWith("wakeUp")
                            && !name.startsWith("wakePowerGroup")) {
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, cb);
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                log("hookPowerAodSignal " + clsName + " 失败: " + t);
            }
        }
        log("电源前馈 hook 已挂 goToSleep/wakeUp/wakePowerGroup x" + n);
    }

    private static Handler mainHandler() {
        Handler h = sMain;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            sMain = h;
        }
        return h;
    }

    /** 熄屏瞬间：记意图 + 立刻让引擎开始收缩（屏幕此刻还在 ON，全屏 AOD 下亮度随后渐变）。 */
    private static void onSleepIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        // 同一轮熄屏里 goToSleep 的多个重载会紧挨着回调，400ms 防重即可，不挡快速亮熄屏
        if (sAsleep && now - sSleepAtMs < 400) {
            return;
        }
        sAsleep = true;
        sSleepAtMs = now;
        propSet(PROP_SLEEP_AT, String.valueOf(now));
        propSet(PROP_WAKE_AT, "0");
        boolean land = isLandscape();
        propSet(PROP_SLEEP_LAND, land ? "1" : "0");
        log("熄屏前馈：goToSleep (landscape=" + land + ") → 立即 arm 收缩");
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx == null) {
                    return;
                }
                // 广播先发（关键路径），Settings 读写放最后
                broadcastAlive(ctx, 0);
                broadcastMode(ctx, 0, "power");
                sLastMode = 0;
                ensureFullAod(ctx);
            }
        });
    }

    /** 亮屏瞬间：立刻清掉熄屏锁并回到锁屏态（即亮即动，不等 SystemUI 慢半拍）。 */
    private static void onWakeIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        long propAt = propGetLong(PROP_SLEEP_AT, 0L);
        if (!sAsleep && propAt == 0L && now - sWakeAtMs < 300) {
            return;
        }
        sAsleep = false;
        sSleepAtMs = 0;
        sWakeAtMs = now;
        if (propAt != 0L) {
            propSet(PROP_SLEEP_AT, "0");
        }
        propSet(PROP_SLEEP_LAND, "0");
        propSet(PROP_WAKE_AT, String.valueOf(now));
        log("亮屏前馈：wakeUp → 立即广播 mode=1");
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx == null) {
                    return;
                }
                broadcastAlive(ctx, 1);
                broadcastMode(ctx, 1, "wake");
                sLastMode = 1;
            }
        });
    }

    /** 指纹直达桌面唤醒：立即清掉熄屏锁并广播 mode=2 桌面态（触发 tr2 扩散）。 */
    private static void onBiometricWakeIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        sAsleep = false;
        sSleepAtMs = 0;
        sWakeAtMs = now;
        propSet(PROP_SLEEP_AT, "0");
        propSet(PROP_SLEEP_LAND, "0");
        propSet(PROP_WAKE_AT, String.valueOf(now));
        log("指纹唤醒前馈：wakeUp BIOMETRIC → 立即广播 mode=2");
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx == null) {
                    return;
                }
                broadcastAlive(ctx, 2);
                broadcastMode(ctx, 2, "biometric_wake");
                sLastMode = 2;
            }
        });
    }

    /**
     * 熄屏意图的**跨进程**时间戳。
     *
     * <p>XposedInit 会被注入多个进程，静态字段每进程各一份。
     * {@code onSleepIntent()} 跑在 system_server，而 {@code recompute()} 跑在 SystemUI，
     * 所以熄屏时刻落到 SystemProperties，供 SystemUI 压掉进入 AOD 之前的短暂 UNLOCKED/KEYGUARD 抖动；
     * 一旦 ScrimController 已经到达 AOD（或 wakeUp 已触发），立即解除熄屏锁，绝不吞掉真亮屏。
     */
    private static final String PROP_SLEEP_AT = "sys.qihang.sleep_at";
    private static final String PROP_WAKE_AT = "sys.qihang.wake_at";
    private static final String PROP_SLEEP_LAND = "sys.qihang.sleep_land";

    private static boolean isLandscape() {
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            Object info = dmgCls.getMethod("getDisplayInfo", int.class).invoke(dmg, Display.DEFAULT_DISPLAY);
            if (info != null) {
                int rotation = (Integer) info.getClass().getField("rotation").get(info);
                if (rotation == 1 || rotation == 3) {
                    return true;
                }
                int lw = (Integer) info.getClass().getField("logicalWidth").get(info);
                int lh = (Integer) info.getClass().getField("logicalHeight").get(info);
                if (lw > lh) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void propSet(String k, String v) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            c.getMethod("set", String.class, String.class).invoke(null, k, v);
        } catch (Throwable t) {
            log("propSet(" + k + ") 失败: " + t);
        }
    }

    private static long propGetLong(String k, long def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class, String.class).invoke(null, k, "");
            if (o instanceof String) {
                String s = ((String) o).trim();
                if (s.length() > 0) {
                    return Long.parseLong(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /**
     * 熄屏状态跨进程判定：
     * 只要 sleep 时间戳晚于或等于 wake 时间戳，即表示处于「熄屏 / 进入熄屏流程中」。
     * 此时无条件锁定为 mode=0 (AOD)，彻底压制从横屏游戏或应用熄屏时 ScrimController 误报 KEYGUARD (mode=1)
     * 导致的反向播放扩散动画并卡在全屏 AOD 的 bug！
     */
    private static boolean isSleepingActive() {
        long at = propGetLong(PROP_SLEEP_AT, 0L);
        long wakeAt = propGetLong(PROP_WAKE_AT, 0L);
        if (at <= 0) {
            return false;
        }
        return at >= wakeAt;
    }

    /**
     * 亮屏意图跨进程锁：goToSleep / wakeUp 发生在 system_server，而 display doze 状态在 SystemUI 会滞后 ~200ms。
     * 在 wakeUp 发生后的 1200ms 内，判定为正在亮屏中，防止 isDozeLike 误将壁纸掐回 mode=0。
     */
    private static boolean wakeIntentActive() {
        long now = android.os.SystemClock.uptimeMillis();
        long at = propGetLong(PROP_SLEEP_AT, 0L);
        long wakeAt = propGetLong(PROP_WAKE_AT, 0L);
        if (wakeAt <= 0 || wakeAt <= at) {
            return false;
        }
        return now - wakeAt >= 0 && now - wakeAt < 1200;
    }

    /**
     * 全屏息屏显示（full AOD）是「锁屏→熄屏」连贯的**物理基础**：
     * panel 走 ON→亮度渐变→DOZE，不再经过 OFF（纯黑），收缩动画才全程可见。
     * 系统会在 AOD 退出时把开关重置，所以由每秒 reconcile 幂等写回。
     */
    private static void ensureFullAod(Context ctx) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastFullAodMs < 5000) {
            return;
        }
        sLastFullAodMs = now;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            if (android.provider.Settings.Secure.getInt(cr, "full_screen_aod_on", 0) != 1) {
                android.provider.Settings.Secure.putInt(cr, "full_screen_aod_on", 1);
                log("full_screen_aod_on -> 1（连贯性基础）");
            }
            if (android.provider.Settings.Secure.getInt(cr, "aod_using_super_wallpaper", 0) != 1) {
                android.provider.Settings.Secure.putInt(cr, "aod_using_super_wallpaper", 1);
            }
        } catch (Throwable t) {
            log("ensureFullAod 失败: " + t);
        }
    }

    /** system_server 的 system context（懒加载；勿在静态初始化里建 Handler/Looper）。 */
    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            Object c = at.getMethod("getSystemContext").invoke(t);
            return c instanceof Context ? (Context) c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookSystemServer(ClassLoader cl) {
        hookPowerAodSignal(cl);

        // 1.0 横屏应用熄屏：DisplayPowerControllerImpl 默认会等待 1000ms 的 screen off animator 导致游戏界面冻结 1 秒；
        // 检测到横屏时直接跳过等待，实现立即黑屏
        hook(cl, "com.android.server.display.DisplayPowerControllerImpl", "setWaitingScreenOffAnimator",
                new Class[]{boolean.class}, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean wait = (Boolean) param.args[0];
                        if (wait && isLandscape()) {
                            log("横屏熄屏：跳过 wait screen off animator，直接黑屏");
                            param.args[0] = false;
                        }
                    }
                });

        // 1.1 AMBIENT_WALLPAPER：纯 signature 权限，第三方永远拿不到 —— 直接判为已授予
        hook(cl, "com.android.server.pm.PackageManagerService", "checkPermission",
                new Class[]{String.class, String.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        installSystemSnap();
                        Object a0 = param.args[0];
                        Object a1 = param.args[1];
                        if (PERM_AMBIENT.equals(a0) && isTarget((String) a1)) {
                            param.setResult(0);
                            if (!sLoggedGate) {
                                sLoggedGate = true;
                                log("GRANT AMBIENT_WALLPAPER -> " + a1 + " (PackageManagerService)");
                            }
                        } else if (PERM_AMBIENT.equals(a1) && isTarget((String) a0)) {
                            param.setResult(0);
                            if (!sLoggedGate) {
                                sLoggedGate = true;
                                log("GRANT AMBIENT_WALLPAPER -> " + a0 + " (PackageManagerService)");
                            }
                        }
                    }
                });

        // 1.2 以 uid=0 / 自身 uid 设置壁纸时的两道副产品门禁
        hook(cl, "com.android.server.wallpaper.WallpaperManagerService",
                "enforcePackageBelongsToUid", new Class[]{String.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isTarget((String) param.args[0])) {
                            log("bypass enforcePackageBelongsToUid(" + param.args[0] + ")");
                            param.setResult(null);
                        }
                    }
                });

        hook(cl, "com.android.server.wallpaper.WallpaperManagerService",
                "isSetWallpaperAllowed", new Class[]{String.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isTarget((String) param.args[0])) {
                            log("bypass isSetWallpaperAllowed(" + param.args[0] + ")");
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });

        // 1.3 拦截 HyperOS Greezer 内核冻结机制（彻底阻止壁纸 UID 进入 cgroup frozen 状态）
        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezeUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                    int uid = (Integer) param.args[0];
                    if (isTargetUid(uid)) {
                        log("拦截 FreezeUtils.freezeUid uid=" + uid);
                        param.setResult(Boolean.FALSE);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezePid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0) {
                    if (param.args.length == 1 && param.args[0] instanceof Integer) {
                        int pid = (Integer) param.args[0];
                        int uid = getUidForPid(pid);
                        if (isTargetUid(uid)) {
                            log("拦截 FreezeUtils.freezePid pid=" + pid + " uid=" + uid);
                            param.setResult(Boolean.FALSE);
                        }
                    } else if (param.args.length >= 2 && param.args[0] instanceof Integer) {
                        int uid = (Integer) param.args[0];
                        if (isTargetUid(uid)) {
                            log("拦截 FreezeUtils.freezePid uid=" + uid);
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeUidCgroup", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                    int uid = (Integer) param.args[0];
                    if (isTargetUid(uid)) {
                        log("拦截 GreezeManagerService.freezeUidCgroup uid=" + uid);
                        param.setResult(null);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                    int uid = (Integer) param.args[0];
                    if (isTargetUid(uid)) {
                        log("拦截 GreezeManagerService.freezeUid uid=" + uid);
                        param.setResult(null);
                    }
                }
            }
        });

        // 1.4 拦截广播拦截检查，杜绝 "BroadcastQueue: Greezer Denial" 丢弃前馈广播
        hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "isRestrictReceiver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isTargetInArgs(param.args)) {
                    log("放行 GreezeManagerService.isRestrictReceiver");
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        hookAll(cl, "com.android.server.am.BroadcastQueueModernStubImpl", "checkReceiverIfRestricted", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isTargetInArgs(param.args)) {
                    log("放行 BroadcastQueueModernStubImpl.checkReceiverIfRestricted");
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        hookAll(cl, "com.android.server.am.BroadcastQueueModernStubImpl", "checkSkipReceiver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isTargetInArgs(param.args)) {
                    log("放行 BroadcastQueueModernStubImpl.checkSkipReceiver");
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        // 1.5 进程保活 / 防休眠黑名单白名单机制
        hook(cl, "com.android.server.am.ProcessPolicy", "updateDynamicWhiteList",
                new Class[]{Context.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (res instanceof java.util.Map) {
                            java.util.Map map = (java.util.Map) res;
                            map.put(PKG_SELF, Boolean.TRUE);
                            map.put(PKG_ALIVE, Boolean.TRUE);
                        }
                    }
                });
    }

    // =====================================================================
    // 通路 2 + 3：AOD 黑底清扫 + 三态事件源
    // =====================================================================

    private void hookSystemUi(ClassLoader cl) {
        hook(cl, "com.android.systemui.statusbar.phone.ScrimController", "updateScrims",
                new Class[0], new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            onScrim(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
        hookAod(cl);
        log("SystemUI hooks installed (including AOD)");
    }

    private void hookAod(ClassLoader cl) {
        log("开始安装 AOD 增强 hooks...");
        // 1. 拦截 DozeHost.disableShowWallpaper，阻止系统在息屏时通知 WM 隐藏壁纸 Surface 并铺黑底
        hook(cl, "com.miui.aod.DozeHost", "disableShowWallpaper", new Class[0], new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                log("DozeHost.disableShowWallpaper -> 已拦截（保持壁纸 Surface 显示）");
                param.setResult(null);
            }
        });

        // 2. 告诉 AOD 模块与 SystemUI：当前正在使用超级壁纸样式（阻止铺全黑遮罩与断流）
        hook(cl, "com.miui.aod.Utils", "isUsingSuperWallpaperStyle",
                new Class[]{Context.class}, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });

        hook(cl, "com.miui.aod.AODView", "onAttachedToWindow", new Class[0], new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    ViewGroup aodView = (ViewGroup) param.thisObject;
                    ensureFlymeClockInAod(aodView);
                } catch (Throwable t) {
                    log("AODView.onAttachedToWindow hook 异常: " + t);
                }
            }
        });

        for (String clockCls : new String[]{
                "com.miui.aod.linkagestyle.LinkageStyleClockView",
                "com.miui.aod.widget.SuperWallpaperClockView",
                "com.miui.aod.widget.HorizontalClockView",
                "com.miui.aod.widget.OnelineClockView",
                "com.miui.aod.widget.VerticalClockView"
        }) {
            hook(cl, clockCls, "onAttachedToWindow", new Class[0], new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        View v = (View) param.thisObject;
                        v.setVisibility(View.GONE);
                        v.setAlpha(0f);
                        ViewParent p = v.getParent();
                        if (p instanceof ViewGroup) {
                            ensureFlymeClockInAod((ViewGroup) p);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        hook(cl, "com.miui.aod.AODView", "handleUpdateTime", new Class[0], new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    ViewGroup aodView = (ViewGroup) param.thisObject;
                    View c = aodView.findViewWithTag("qihang_flyme_clock");
                    if (c instanceof FlymeClockView) {
                        ((FlymeClockView) c).updateTime();
                    }
                } catch (Throwable ignored) {}
            }
        });
        log("com.miui.aod hook 安装成功");
    }

    private static void ensureFlymeClockInAod(ViewGroup parent) {
        if (parent == null) return;
        View existing = parent.findViewWithTag("qihang_flyme_clock");
        if (hasActiveClock()) {
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
            return;
        }
        if (existing == null) {
            try {
                FlymeClockView clock = new FlymeClockView(parent.getContext());
                clock.setTag("qihang_flyme_clock");
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                parent.addView(clock, lp);
                clock.bringToFront();
                sFlymeClock = clock;
                registerClock(clock);
                log("成功在 AODView 注入 FlymeClockView!");
            } catch (Throwable t) {
                log("注入 FlymeClockView 失败: " + t);
            }
        } else {
            existing.setVisibility(View.VISIBLE);
            if (existing instanceof FlymeClockView) {
                sFlymeClock = (FlymeClockView) existing;
                registerClock(sFlymeClock);
                sFlymeClock.bringToFront();
                sFlymeClock.reloadConfig();
                sFlymeClock.updateTime();
            }
        }
    }

    private static void onScrim(Object scrim) {
        String state = readState(scrim);
        if (state == null) {
            return;
        }
        if (sStateName == null || !sStateName.equals(state)) {
            int prevScrim = sScrimMode;
            sStateName = state;
            sScrimMode = modeOf(state);
            log("scrim state=" + state + " raw=" + sScrimMode);
            if (sScrimMode == 0) {
                // ScrimController 已进入 AOD
                long at = propGetLong(PROP_SLEEP_AT, 0L);
                if (at > 0) {
                    sClearedSleepAt = at;
                }
            } else if (prevScrim == 0) {
                // ScrimController 从 AOD(0) 切出到 KEYGUARD/UNLOCKED：用户正在唤醒屏幕！
                long at = propGetLong(PROP_SLEEP_AT, 0L);
                if (at > 0) {
                    sClearedSleepAt = at;
                }
                sWakeFromAodAtMs = android.os.SystemClock.uptimeMillis();
                propSet(PROP_WAKE_AT, String.valueOf(sWakeFromAodAtMs));
            }
        }
        View root = findView(scrim);
        Context ctx = sContext != null ? sContext : (root != null ? root.getContext() : findContext(scrim));
        if (ctx != null) {
            sContext = ctx;
            hookAodWallpaperSupport();
            ensureDisplayListener(ctx);
            startReconcile();
            installSnapReceiver(ctx);
        }
        if (root != null) {
            // 必须用窗口根视图：ScrimController 上拿到的是 ScrimView，AOD 黑底在它的祖先树里
            sAodRoot = root.getRootView();
        }
        if (sScrimMode == 0) {
            try {
                Field f = scrim.getClass().getDeclaredField("mBehindAlpha");
                f.setAccessible(true);
                f.setFloat(scrim, 0.0f);
            } catch (Throwable ignored) {
            }
        }
        recompute(ctx);
    }

    /**
     * 把「ScrimController 场景 + 显示是否 doze」折算成壁纸模式并广播。
     *
     * <p>实测：屏幕已经 doze 了，ScrimController 仍会报 KEYGUARD（甚至 UNLOCKED），
     * 所以 **doze 时无条件进入 AOD(0)**（除非刚刚从 AOD 唤醒、display 状态尚未从 DOZE 切完），
     * 否则才用 scrim 场景。
     */
    private static void recompute(Context ctx) {
        if (ctx == null) {
            return;
        }
        ensureFullAod(ctx);
        boolean doze = isDozeLike(ctx);
        long now = android.os.SystemClock.uptimeMillis();
        boolean wakingFromAod = (sScrimMode != 0
                && sWakeFromAodAtMs > 0
                && now - sWakeFromAodAtMs >= 0
                && now - sWakeFromAodAtMs < 1200)
                || wakeIntentActive();
        boolean sleeping = isSleepingActive() || (doze && !wakingFromAod);
        int mode = (sleeping && !wakingFromAod) ? 0 : sScrimMode;
        if (mode == sLastMode) {
            return;
        }
        sLastMode = mode;
        log("-> mode=" + mode + " (scrim=" + sStateName + " doze=" + doze + " sleeping=" + sleeping + ")");
        broadcastAlive(ctx, mode);
        if (mode == 0 || mode == 1) {
            // mode 0 = AOD, mode 1 = 锁屏。两者都需要显示 Flyme 时钟，保持位置与样式无缝一致
            sSweepCtx = ctx;
            broadcastMode(ctx, mode, mode == 0 ? "doze" : (sStateName != null ? sStateName : "KEYGUARD"));
            stopSweep();
            startSweep(sAodRoot, mode == 0);
            updateClocksVisibility(true, mode);
        } else {
            stopSweep();
            updateClocksVisibility(false, mode);
            broadcastMode(ctx, mode, "UNLOCKED");
        }
    }

    /** 每秒重算一次：显示状态变化不一定有回调，轮询最稳。 */
    private static void startReconcile() {
        if (sReconcile != null) {
            return;
        }
        Handler h = sMain;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            sMain = h;
        }
        sReconcile = new Runnable() {
            @Override
            public void run() {
                try {
                    recompute(sContext);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                if (sReconcile != null) {
                    sMain.postDelayed(this, 1000);
                }
            }
        };
        h.postDelayed(sReconcile, 1000);
        log("reconcile 轮询已启动");
    }

    private static boolean isDozeLike(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return false;
            }
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) {
                return false;
            }
            int st = d.getState();
            return st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND || st == Display.STATE_OFF;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 显示状态变化（亮/灭/DOZE）时也要重新判定，否则会停在错误的那一态。 */
    private static void ensureDisplayListener(Context ctx) {
        if (sDisplayRegistered) {
            return;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    try {
                        if (displayId != Display.DEFAULT_DISPLAY) {
                            return;
                        }
                        recompute(sContext);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }, sMain == null ? (sMain = new Handler(Looper.getMainLooper())) : sMain);
            sDisplayRegistered = true;
            log("display listener registered");
        } catch (Throwable t) {
            log("registerDisplayListener 失败: " + t);
        }
    }

    private static int modeOf(String state) {
        String s = state.toUpperCase();
        if (s.contains("AOD") || s.contains("PULSING") || s.contains("DOZE")) {
            return 0;
        }
        if (s.contains("KEYGUARD") || s.contains("BOUNCER")) {
            return 1;
        }
        return 2;
    }

    private static String readState(Object scrim) {
        for (String name : new String[]{"mState", "state", "mScrimState"}) {
            Object v = getField(scrim, name);
            if (v != null) {
                String s = v instanceof Enum ? ((Enum<?>) v).name() : String.valueOf(v);
                if (s.length() > 0) {
                    return s;
                }
            }
        }
        // 兜底：找类型名含 ScrimState 的字段
        for (Class<?> c = scrim.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isEnum() && f.getType().getName().contains("ScrimState")) {
                    Object v = getField(scrim, f.getName());
                    if (v instanceof Enum) {
                        return ((Enum<?>) v).name();
                    }
                }
            }
        }
        return null;
    }

    private static void sendBroadcastSmart(Context ctx, Intent intent) {
        if (ctx == null || intent == null) {
            return;
        }
        try {
            // 当运行在 system_server (UID 1000) 中时，Android 14+ 限制向普通应用发无用户属性的广播
            if (android.os.Process.myUid() == 1000) {
                try {
                    Class<?> uhClass = Class.forName("android.os.UserHandle");
                    Field allField = uhClass.getField("ALL");
                    Object userAll = allField.get(null);
                    Method m = Context.class.getMethod("sendBroadcastAsUser", Intent.class, uhClass);
                    m.invoke(ctx, intent, userAll);
                    return;
                } catch (Throwable t) {
                    log("sendBroadcastAsUser 失败，回退 sendBroadcast: " + t);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            log("sendBroadcast 失败: " + t);
        }
    }

    private static void broadcastMode(Context ctx, int mode, String state) {
        Intent i = new Intent(ACTION_MODE);
        i.setPackage(PKG_SELF);
        // 前台广播：熄屏流程里普通广播会排队几百毫秒才投递（实测 450~500ms），
        // 前台标志让收缩指令与屏幕亮度渐变同步开始。
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_ANIMATE, true);
        i.putExtra(EXTRA_STATE, state);
        sendBroadcastSmart(ctx, i);
    }

    private static void broadcastAlive(Context ctx, int mode) {
        Intent i = new Intent(ACTION_ALIVE);
        i.setPackage(PKG_ALIVE);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(EXTRA_ALIVE_CMD, cmd(mode));
        sendBroadcastSmart(ctx, i);
    }

    // ---- AOD 黑底清扫（每 500ms 幂等） ----

    private static void startSweep(View root, final boolean isAod) {
        if (root != null) {
            sAodRoot = root.getRootView();
        }
        updateClocksVisibility(true, isAod ? 0 : 1);
        if (sSweep != null) {
            return;
        }
        Handler h = sMain;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            sMain = h;
        }
        sSweepCount = 0;
        sClearedLogged = 0;
        sSweep = new Runnable() {
            @Override
            public void run() {
                int cleared = 0;
                try {
                    View r = sAodRoot;
                    if (r == null) {
                        r = rootViewFromWindowManager();
                        sAodRoot = r;
                    }
                    if (r != null) {
                        cleared = sweep(r, isAod);
                    }
                    if (sSweepCount < 8) {
                        log("AOD/Keyguard sweep #" + sSweepCount + " root="
                                + (r == null ? "null" : r.getClass().getSimpleName())
                                + " isAod=" + isAod + " cleared=" + cleared);
                        sSweepCount++;
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                if (cleared > 0) {
                    log("AOD 黑底已清（第 " + sSweepCount + " 轮）");
                }
                if (sSweep != null) {
                    // 熄屏前 ~1.5s 用 60ms 快扫，尽量让熄屏早亮起；之后 500ms 省电
                    sMain.postDelayed(this, (isAod && sSweepCount < 25) ? 60 : 500);
                }
            }
        };
        h.post(sSweep);
    }

    /** MiuiSystemUI: integer/config_dozeSupportsAodWallpaperOverride。 */
    private static final int ID_AOD_WP_OVERRIDE = 0x7f0c0044;
    /** framework-res: bool/config_earcFeatureDisabled_default（小米笔误兜底的那个 EARC 开关）。 */
    private static final int ID_EARC_BOOL = 0x01110176;

    private static boolean sAodWpSupportHooked;

    /** 让 SystemUI 判定"支持 AOD 壁纸"：AOD 背景才会用壁纸而不是黑底。 */
    private static void hookAodWallpaperSupport() {
        if (sAodWpSupportHooked) {
            return;
        }
        sAodWpSupportHooked = true;
        boolean ok = false;
        for (String cls : new String[]{"android.content.res.Resources",
                "android.content.res.ResourcesImpl"}) {
            try {
                Class<?> c = Class.forName(cls);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (m.getParameterCount() != 1
                            || m.getParameterTypes()[0] != int.class) {
                        continue;
                    }
                    if (!"getInteger".equals(n) && !"getBoolean".equals(n)) {
                        continue;
                    }
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            int id = (Integer) p.args[0];
                            if (id == ID_AOD_WP_OVERRIDE) {
                                p.setResult(1);      // 1 = 支持 AOD 壁纸
                                log("AOD 壁纸开关: getInteger(0x7f0c0044) -> 1");
                            } else if (id == ID_EARC_BOOL) {
                                p.setResult(Boolean.TRUE);
                                log("AOD 壁纸开关: getBoolean(0x01110176) -> true");
                            }
                        }
                    });
                    ok = true;
                }
            } catch (Throwable t) {
                log("hookAodWallpaperSupport " + cls + " 失败: " + t);
            }
        }
        log("hookAodWallpaperSupport " + (ok ? "已挂" : "未挂到任何方法"));
    }

    /** 从 WindowManagerGlobal 拿 SystemUI 的根视图（ScrimController 上找不到 View 时的兜底）。 */
    private static View rootViewFromWindowManager() {
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object inst = cls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method m = cls.getMethod("getRootViews");
            Object list = m.invoke(inst);
            if (list instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) list;
                for (Object o : l) {
                    if (o instanceof View) {
                        return (View) o;
                    }
                }
            }
        } catch (Throwable t) {
            log("rootViewFromWindowManager 失败: " + t);
        }
        return null;
    }

    private static void stopSweep() {
        if (sSweep != null) {
            sMain.removeCallbacks(sSweep);
            sSweep = null;
            log("AOD/Keyguard sweep stopped");
        }
    }

    private static int sweep(View v, boolean isAod) {
        if (v == null) {
            return 0;
        }
        int n = 0;
        if (isAod && isDarkBackground(v)) {
            if (sClearedLogged < 10) {
                sClearedLogged++;
                log("clear " + v.getClass().getSimpleName() + " id=0x"
                        + Integer.toHexString(v.getId()) + " bg=" + Integer.toHexString(((ColorDrawable) v.getBackground()).getColor())
                        + " size=" + v.getWidth() + "x" + v.getHeight());
            }
            v.setBackground(null);
            n++;
        }
        if (sSweepCount <= 2) {
            String cls = v.getClass().getName();
            String entry = null;
            try {
                int id = v.getId();
                if (id != View.NO_ID && v.getResources() != null) {
                    entry = v.getResources().getResourceEntryName(id);
                }
            } catch (Throwable ignored) {}
            if (cls.toLowerCase().contains("clock") || cls.toLowerCase().contains("keyguard") || cls.toLowerCase().contains("aod") || (entry != null && (entry.contains("clock") || entry.contains("aod")))) {
                log("InspectView: " + cls + " id=" + entry + " vis=" + v.getVisibility() + " alpha=" + v.getAlpha());
            }
        }
        // 如果视图已被隐藏，跳过深层检查
        if (v.getVisibility() == View.GONE && v.getAlpha() == 0f) {
            return n;
        }
        // 隐藏小米官方原生 AOD / 锁屏时钟与缩略图控件
        if (isNativeAodClock(v)) {
            v.setVisibility(View.GONE);
            v.setAlpha(0f);
            return n;
        }
        if (v instanceof ViewGroup) {
            // 挂载魅族 Flyme 时钟（仅检查 ViewGroup 容器）
            ensureFlymeClock(v);
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += sweep(g.getChildAt(i), isAod);
            }
        }
        return n;
    }

    private static boolean isNativeAodClock(View v) {
        if (v == null || v instanceof FlymeClockView || "qihang_flyme_clock".equals(v.getTag())) {
            return false;
        }
        String cls = v.getClass().getName();
        // ★ 核心修复：通知栏顶部的时间、日期、运营商等控件绝对不能隐藏
        if (cls.contains("Notification") || cls.contains("Header") || cls.contains("Carrier")) {
            return false;
        }
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                String entry = v.getResources().getResourceEntryName(id);
                if (entry != null) {
                    if (entry.contains("gradient_layout")
                            || entry.contains("clock_horizontal_container")
                            || entry.contains("clock_container")
                            || entry.contains("clock2_container")
                            || entry.contains("single_clock_container")
                            || entry.contains("image_selector_shower")
                            || "aod_battery_layout".equals(entry)
                            || "aod_battery_icon".equals(entry)
                            || "aod_battery_digital".equals(entry)
                            || "miui_keyguard_clock_container".equals(entry)
                            || "miui_keyguard_foreground_clock_container".equals(entry)
                            || "color_ribbons_container".equals(entry)
                            || "keyguard_clock".equals(entry)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return cls.contains("AodContainerView")
                || cls.contains("LinkageStyleClockView")
                || cls.contains("SuperWallpaperClockView")
                || cls.contains("KeyguardClockContainer")
                || cls.contains("AODBatteryMeterView")
                || cls.contains("ClassicClockView");
    }

    private static void ensureFlymeClock(View v) {
        if (!(v instanceof ViewGroup)) {
            return;
        }
        boolean isTargetContainer = false;
        String cls = v.getClass().getName();
        String entry = null;
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                entry = v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {
        }

        if (entry != null) {
            if ("keyguard_translation_info".equals(entry)) {
                isTargetContainer = true;
            } else if ("keyguard_info_layer".equals(entry)) {
                ViewGroup g = (ViewGroup) v;
                if (findChildByEntry(g, "keyguard_translation_info") == null) {
                    isTargetContainer = true;
                }
            }
        }

        // 优先且仅注入到锁屏通用层 keyguard_translation_info（它在息屏 AOD 和锁屏 Keyguard 全程常驻可见）
        // 仅在 keyguard 容器未挂载时，才作为兜底注入到 AODView
        if (!isTargetContainer && !hasActiveClock()) {
            if (cls.contains("AodView") || cls.contains("aod_root")) {
                isTargetContainer = true;
            } else if ("aod_root_view".equals(entry) || "aod_mode_layout_horizontal".equals(entry)) {
                isTargetContainer = true;
            }
        }

        if (!isTargetContainer) {
            return;
        }

        ViewGroup root = (ViewGroup) v;
        View existing = root.findViewWithTag("qihang_flyme_clock");
        if (existing == null) {
            try {
                FlymeClockView clock = new FlymeClockView(root.getContext());
                clock.setTag("qihang_flyme_clock");
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                root.addView(clock, lp);
                clock.bringToFront();
                sFlymeClock = clock;
                registerClock(clock);
                log("FlymeClockView 成功注入容器: " + root.getClass().getSimpleName() + " (entry=" + entry + ")");
            } catch (Throwable t) {
                log("注入 FlymeClockView 失败: " + t);
            }
        } else if (existing instanceof FlymeClockView) {
            FlymeClockView clock = (FlymeClockView) existing;
            sFlymeClock = clock;
            registerClock(clock);
            if (sLastMode == 0 || sLastMode == 1) {
                clock.setVisibility(View.VISIBLE);
                clock.bringToFront();
                clock.reloadConfig();
                clock.updateTime();
            }
        }
    }

    private static View findChildByEntry(ViewGroup g, String targetEntry) {
        if (g == null || targetEntry == null) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            try {
                int id = child.getId();
                if (id != View.NO_ID && child.getResources() != null) {
                    if (targetEntry.equals(child.getResources().getResourceEntryName(id))) {
                        return child;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isDarkBackground(View v) {
        Drawable d = v.getBackground();
        if (!(d instanceof ColorDrawable)) {
            return false;
        }
        int color = ((ColorDrawable) d).getColor();
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return a == 255 && r < 24 && g < 24 && b < 24;
    }

    // =====================================================================
    // 反射工具
    // =====================================================================

    private static void hook(ClassLoader cl, String className, String method, Class<?>[] sig,
                             XC_MethodHook cb) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            Method m = c.getDeclaredMethod(method, sig);
            m.setAccessible(true);
            Member member = m;
            XposedBridge.hookMethod(member, cb);
            log("hook " + className + "#" + method + " OK");
        } catch (Throwable t) {
            log("hook " + className + "#" + method + " 失败: " + t);
        }
    }

    private static void hookAll(ClassLoader cl, String className, String method, XC_MethodHook cb) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            int count = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(method)) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, cb);
                    count++;
                }
            }
            log("hookAll " + className + "#" + method + " OK (count=" + count + ")");
        } catch (Throwable t) {
            log("hookAll " + className + "#" + method + " 失败: " + t);
        }
    }

    private static int getUidForPid(int pid) {
        try {
            Method m = android.os.Process.class.getMethod("getUidForPid", int.class);
            return (Integer) m.invoke(null, pid);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static Object getField(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static View findView(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (View.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                    Object v = getField(o, f.getName());
                    if (v instanceof View) {
                        return (View) v;
                    }
                }
            }
        }
        return null;
    }

    private static Context findContext(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object v = getField(o, f.getName());
                if (v instanceof Context) {
                    return (Context) v;
                }
                if (v instanceof View) {
                    Context ctx = ((View) v).getContext();
                    if (ctx != null) {
                        return ctx;
                    }
                }
            }
        }
        return null;
    }
}