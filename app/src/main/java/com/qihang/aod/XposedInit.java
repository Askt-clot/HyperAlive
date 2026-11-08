package com.qihang.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperAlive (qihang_lsp) —— LSPosed 核心入口：
 * 1. 权限与壁纸门禁放行（system_server）
 * 2. 电源状态毫秒级前馈（goToSleep / wakeUp 立即下发三态指令）
 * 3. 拦截屏幕硬件断电（STATE_OFF 强制重定向为 STATE_DOZE）
 * 4. 彻底切断 SurfaceFlinger 背光压制信号（DisplayStateShaderController.setLayerWithAOD 阻断 needBlockBrightness）
 * 5. 阻断硬件淡黑着色器与双时钟重叠，保留原生 AOD 时钟与指纹图层
 * 6. 零黑帧、无缝息屏三态平滑联动！
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "QihangAod";
    public static final String PKG_SELF = "com.qihang.aod";
    public static final String PKG_ALIVE = "com.flyme.alivewallpaper";
    public static final String ACTION_MODE = "com.qihang.aod.CMD";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ANIMATE = "animate";
    public static final String EXTRA_STATE = "state";
    static final String ACTION_ALIVE = "com.meizu.flyme.alivepaper";
    static final String EXTRA_ALIVE_CMD = "alive_command";

    static final String PERM_AMBIENT = "android.permission.AMBIENT_WALLPAPER";
    static final String[] TARGET_PKGS = {
            PKG_SELF,
            "com.flyme.alivewallpaper",
            "com.poc.alive",
            "com.miui.miwallpaper",
            "com.miui.aod",
            "miui.systemui.plugin"
    };

    private static Handler sMain;
    private static Context sContext;
    private static View sAodRoot;
    private static Runnable sSweep;
    private static int sLastMode = -1;
    private static int sScrimMode = 1;
    private static String sStateName;
    private static boolean sDisplayRegistered;
    private static boolean sClockReceiverRegistered;
    private static final List<WeakReference<FlymeClockView>> sClocks = new ArrayList<>();
    private static volatile long sSleepAtMs;
    private static volatile long sWakeAtMs;
    private static volatile long sBiometricWakeAtMs;
    private static volatile boolean sAsleep;
    private static volatile boolean sLastFodVisible = true;

    private static final String PROP_SLEEP_AT = "sys.qihang.sleep_at";
    private static final String PROP_WAKE_AT = "sys.qihang.wake_at";

    static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {}
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {}
    }

    static boolean isTarget(String pkg) {
        if (pkg == null) return false;
        for (String t : TARGET_PKGS) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            log("handleLoadPackage: pkg=" + lp.packageName + " proc=" + lp.processName);
            if (PKG_SELF.equals(lp.packageName)) {
                hookSelf(lp.classLoader);
            }
            if ("android".equals(lp.packageName)) {
                hookSystemServer(lp.classLoader);
            }
            if ("com.android.systemui".equals(lp.packageName)
                    || "miui.systemui.plugin".equals(lp.packageName)
                    || "com.miui.aod".equals(lp.packageName)) {
                hookSystemUi(lp.classLoader);
                hookAod(lp.classLoader);
            }
            if (PKG_ALIVE.equals(lp.packageName)) {
                hookAliveWallpaper(lp.classLoader);
            }
        } catch (Throwable t) {
            log("handleLoadPackage 异常 " + lp.packageName);
            XposedBridge.log(t);
        }
    }

    private void hookAliveWallpaper(ClassLoader cl) {
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

        hook(cl, "com.meizu.flyme.sdkstage.wallpaper.MzUserAwareWallpaper$UserAwareEngine",
                "y", new Class[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int w = (Integer) param.args[0];
                        if (w > 0) param.setResult(w);
                    }
                });

        hook(cl, "com.meizu.flyme.sdkstage.wallpaper.MzUserAwareWallpaper$UserAwareEngine",
                "x", new Class[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int h = (Integer) param.args[1];
                        if (h > 0) param.setResult(h);
                    }
                });

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
                                param.args[0] = rw;
                                param.args[1] = rh;
                            }
                        }
                    }
                });

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
        } catch (Throwable ignored) {}
        try {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{Math.min(dm.widthPixels, dm.heightPixels),
                        Math.max(dm.widthPixels, dm.heightPixels)};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void hookSelf(ClassLoader cl) {
        log("开始安装 self (com.qihang.aod) hooks...");
        hookAll(cl, "com.qihang.aod.LspState", "isLsposedHooked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        });
    }

    // =========================================================================
    // 1. system_server 通路：权限放行 + 电源前馈 + 硬件状态重定向
    // =========================================================================

    private void hookSystemServer(ClassLoader cl) {
        log("开始安装 system_server hooks...");
        propSet("sys.qihang.lsp_active", "1");
        hookPowerAodSignal(cl);
        hookGreezer(cl);

        // 1.1 放行 AMBIENT_WALLPAPER 权限
        hookAll(cl, "com.android.server.pm.PackageManagerService", "checkPermission", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    if (PERM_AMBIENT.equals(param.args[0]) && isTarget((String) param.args[1])) {
                        param.setResult(0); // PERMISSION_GRANTED
                    }
                }
            }
        });

        // 1.2 放行壁纸 uid 校验
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "enforcePackageBelongsToUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && isTarget((String) param.args[0])) {
                    param.setResult(null);
                }
            }
        });

        // 1.3 放行 isSetWallpaperAllowed 门禁并修复潜在 NPE
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "isSetWallpaperAllowed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && isTarget((String) param.args[0])) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });

        // 1.3.1 追踪 WallpaperManagerService 核心壁纸切换与回退链路
        XC_MethodHook wpTraceHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("WMS.").append(param.method.getName()).append("(");
                    if (param.args != null) {
                        for (int i = 0; i < param.args.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(param.args[i]);
                        }
                    }
                    sb.append(") callerUid=").append(android.os.Binder.getCallingUid())
                      .append(" callerPid=").append(android.os.Binder.getCallingPid());
                    log(sb.toString());
                } catch (Throwable ignored) {}
            }
        };
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "bindWallpaperComponentLocked", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "clearWallpaperLocked", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "switchWallpaper", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaperComponentChecked", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaperComponent", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaper", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "onUnlockUser", wpTraceHook);
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "onSwitchWallpaperFailLocked", wpTraceHook);

        // 1.3.2 拦截系统自带壁纸应用（com.miui.miwallpaper）在开机/解锁时静默下发的 setWallpaper(null) 清空壁纸指令
        // ★ 核心规则：仅拦截 com.miui.miwallpaper 自身的静默重置行为（callerUid != 0 且 callerUid != 1000 且未开启允许重置标记）。
        // 当 callerUid == 0 (root 运维/用户通过模块主动恢复默认壁纸) 或设置了 sys.qihang.allow_reset=1 时，严禁拦截！
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isResetAllowed()) return;
                if (param.args != null && param.args.length >= 2) {
                    String pkg = String.valueOf(param.args[1]);
                    Object name = param.args[0];
                    if ("com.miui.miwallpaper".equals(pkg) && name == null) {
                        log("★ 成功拦截 com.miui.miwallpaper setWallpaper(name=null) 重置壁纸！保护启航双槽位壁纸！");
                        param.setResult(null);
                    }
                }
            }
        });
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "clearWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isResetAllowed()) return;
                if (param.args != null && param.args.length >= 1) {
                    String pkg = String.valueOf(param.args[0]);
                    if ("com.miui.miwallpaper".equals(pkg)) {
                        log("★ 成功拦截 com.miui.miwallpaper clearWallpaper！保护启航双槽位壁纸！");
                        param.setResult(null);
                    }
                }
            }
        });
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaperComponentChecked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isResetAllowed()) return;
                if (param.args != null && param.args.length >= 2) {
                    String pkg = String.valueOf(param.args[1]);
                    if ("com.miui.miwallpaper".equals(pkg)) {
                        log("★ 成功拦截 com.miui.miwallpaper setWallpaperComponentChecked！保护启航双槽位壁纸！");
                        param.setResult(null);
                    }
                }
            }
        });
        hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "onSwitchWallpaperFailLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isResetAllowed()) return;
                log("★ onSwitchWallpaperFailLocked 触发: 阻止降级为 ImageWallpaper！");
                param.setResult(null);
            }
        });

        // 1.4 拦截 DisplayPowerController / DisplayPowerState.setScreenState：熄屏进入 AOD 瞬间（1.5s 过渡窗口内）
        //     严禁将屏幕硬件置为 STATE_OFF (1)，彻底杜绝底层屏幕硬件先断电再点亮导致的物理黑闪！
        //     注意：
        //     1) 仅在系统开启了息屏显示（doze_always_on=1）时才拦截；关闭息屏显示时必须放行 STATE_OFF(1) 正常黑屏关机；
        //     2) 仅在按电源键熄屏后的前 1.5s 过渡期内拦截；当息屏模式设为「点击屏幕后显示10秒」且 10s 到期后，
        //        DozeService 下发的 STATE_OFF(1) 距离熄屏已超过 1.5s，此时必须放行让屏幕真正关闭！
        XC_MethodHook screenStateHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object stateObj = param.args[0];
                    if (stateObj instanceof Integer) {
                        int state = (Integer) stateObj;
                        long elapsed = android.os.SystemClock.uptimeMillis() - sSleepAtMs;
                        if (state == 1 && sAsleep && elapsed >= 0 && elapsed < 1500L && isAodEnabled(null)) { // 1 = Display.STATE_OFF
                            log(param.thisObject.getClass().getSimpleName() + ".setScreenState: 拦截熄屏初态 STATE_OFF(1) -> 强制重定向为 STATE_DOZE(3) (elapsed=" + elapsed + "ms)");
                            param.args[0] = 3; // 3 = Display.STATE_DOZE
                            ensureQihangThawed();
                        }
                    }
                }
            }
        };
        hookAll(cl, "com.android.server.display.DisplayPowerController", "setScreenState", screenStateHook);
        hookAll(cl, "com.android.server.display.DisplayPowerControllerImpl", "setScreenState", screenStateHook);
        hookAll(cl, "com.android.server.display.DisplayPowerState", "setScreenState", screenStateHook);
        hookAll(cl, "com.android.server.display.DisplayPowerState", "prepareColorFade", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                long elapsed = android.os.SystemClock.uptimeMillis() - sSleepAtMs;
                if (sAsleep && elapsed >= 0 && elapsed < 1500L && isAodEnabled(null)) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        log("system_server hooks 安装完成");
    }

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
                            if (arg instanceof String) {
                                String s = ((String) arg).toLowerCase();
                                if (s.contains("biometric") || s.contains("fingerprint")) {
                                    isBiometric = true;
                                    break;
                                }
                            }
                            if (arg instanceof Integer && ((Integer) arg) == 17) {
                                isBiometric = true;
                                break;
                            }
                        }
                    }
                    if (isBiometric) {
                        log("电源前馈：指纹唤醒 (BIOMETRIC) → 广播 mode=2 (HOME)");
                        onBiometricWakeIntent();
                    } else {
                        log("电源前馈：正常唤醒 (wakeUp) → 广播 mode=1 (LOCK)");
                        onWakeIntent();
                    }
                }
            }
        };

        for (String clsName : new String[]{
                "com.android.server.power.PowerManagerService",
                "com.android.server.power.PowerManagerService$BinderService"
        }) {
            try {
                Class<?> pm = Class.forName(clsName, false, cl);
                for (Method m : pm.getDeclaredMethods()) {
                    String name = m.getName();
                    if (name.startsWith("goToSleep") || name.startsWith("wakeUp") || name.startsWith("wakePowerGroup")) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, cb);
                    }
                }
            } catch (Throwable t) {
                log("hookPowerAodSignal 挂载 " + clsName + " 失败: " + t);
            }
        }
    }

    // =========================================================================
    // 1.5 Xiaomi Greezer 防冻结与进程保活核心屏障 (AurogonImmobulusMode / FreezeUtils / GreezeManagerService)
    // =========================================================================

    private static volatile int sQihangUid = -1;
    private static final java.util.Set<Integer> sQihangPids = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private static int getQihangUid() {
        if (sQihangUid > 0) return sQihangUid;
        try {
            Context ctx = sContext != null ? sContext : systemContext();
            if (ctx != null) {
                sQihangUid = ctx.getPackageManager().getPackageUid(PKG_SELF, 0);
                return sQihangUid;
            }
        } catch (Throwable ignored) {}
        return sQihangUid;
    }

    private static void updateUidFromPid(int pid) {
        if (sQihangUid > 0 || pid <= 0) return;
        try {
            java.io.File f = new java.io.File("/proc/" + pid + "/status");
            if (f.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            sQihangUid = Integer.parseInt(parts[1]);
                        }
                        break;
                    }
                }
                br.close();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isQihangPid(int pid) {
        if (pid <= 0) return false;
        if (sQihangPids.contains(pid)) return true;
        try {
            java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
            if (f.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                String cmd = br.readLine();
                br.close();
                if (cmd != null && (cmd.contains(PKG_SELF) || cmd.contains(PKG_ALIVE))) {
                    sQihangPids.add(pid);
                    updateUidFromPid(pid);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isQihangPath(String path) {
        if (path == null) return false;
        int qUid = getQihangUid();
        if (qUid > 0 && path.contains("uid_" + qUid)) return true;
        for (Integer pid : sQihangPids) {
            if (path.contains("pid_" + pid)) return true;
        }
        return false;
    }

    private static void ensureQihangThawed() {
        try {
            int qUid = getQihangUid();
            if (qUid <= 0) return;
            java.io.File uidDir = new java.io.File("/sys/fs/cgroup/apps/uid_" + qUid);
            if (!uidDir.exists() || !uidDir.isDirectory()) return;
            java.io.File[] files = uidDir.listFiles();
            if (files == null) return;
            for (java.io.File sub : files) {
                if (sub.isDirectory() && sub.getName().startsWith("pid_")) {
                    java.io.File freezeFile = new java.io.File(sub, "cgroup.freeze");
                    if (freezeFile.exists()) {
                        java.io.File eventsFile = new java.io.File(sub, "cgroup.events");
                        boolean isFrozen = false;
                        if (eventsFile.exists()) {
                            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(eventsFile));
                            String el;
                            while ((el = br.readLine()) != null) {
                                if (el.contains("frozen 1")) {
                                    isFrozen = true;
                                    break;
                                }
                            }
                            br.close();
                        }
                        if (isFrozen) {
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(freezeFile);
                            fos.write("0\n".getBytes());
                            fos.flush();
                            fos.close();
                            log("★ ensureQihangThawed: 主动解冻 " + sub.getName() + " 成功！");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("ensureQihangThawed 异常: " + t);
        }
    }

    private void hookGreezer(ClassLoader cl) {
        log("开始安装 Greezer 防冻结 hooks...");

        // 1. AurogonImmobulusMode 顶层豁免
        XC_MethodHook wpAppHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object arg0 = param.args[0];
                    if (arg0 instanceof String) {
                        String pkg = (String) arg0;
                        if (PKG_SELF.equals(pkg) || isTarget(pkg)) {
                            param.setResult(Boolean.TRUE);
                        }
                    } else if (arg0 instanceof Integer) {
                        int uid = (Integer) arg0;
                        int qUid = getQihangUid();
                        if (qUid > 0 && uid == qUid) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            }
        };
        hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "isWallPaperApp", wpAppHook);

        XC_MethodHook noRestrictHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object arg0 = param.args[0];
                    if (arg0 instanceof String) {
                        String pkg = (String) arg0;
                        if (PKG_SELF.equals(pkg) || isTarget(pkg)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            }
        };
        hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "isNoRestrictApp", noRestrictHook);
        hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "isNoRestrictFreezeable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object arg0 = param.args[0];
                    if (arg0 instanceof String && (PKG_SELF.equals(arg0) || isTarget((String) arg0))) {
                        param.setResult(Boolean.TRUE);
                        return;
                    }
                    if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                        int uid = (Integer) param.args[1];
                        if (uid > 0 && uid == getQihangUid()) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            }
        });

        // 拦截 AurogonImmobulusMode.freezeActionForImmobulus
        hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "freezeActionForImmobulus", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object info = param.args[0];
                        Field f = info.getClass().getField("mPackageName");
                        String pkg = (String) f.get(info);
                        if (PKG_SELF.equals(pkg) || isTarget(pkg)) {
                            log("★ Greezer 防冻结拦截: 阻止 AurogonImmobulusMode.freezeActionForImmobulus(pkg=" + pkg + ")！");
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        // 拦截 AurogonImmobulusMode.checkAppForImmobulusMode
        hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "checkAppForImmobulusMode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object info = param.args[0];
                        Field f = info.getClass().getField("mPackageName");
                        String pkg = (String) f.get(info);
                        if (PKG_SELF.equals(pkg) || isTarget(pkg)) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        // 覆写 AurogonImmobulusMode.getWallpaperPackageName
        hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "getWallpaperPackageName", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int qUid = getQihangUid();
                    Field lockPkg = param.thisObject.getClass().getDeclaredField("mLockWallPaperPackageName");
                    lockPkg.setAccessible(true);
                    Field lockUid = param.thisObject.getClass().getDeclaredField("mLockWallPaperUid");
                    lockUid.setAccessible(true);
                    lockPkg.set(param.thisObject, PKG_SELF);
                    if (qUid > 0) {
                        lockUid.set(param.thisObject, qUid);
                    }
                    log("★ AurogonImmobulusMode.getWallpaperPackageName: 覆写 mLockWallPaperPackageName=" + PKG_SELF + " uid=" + qUid);
                } catch (Throwable t) {
                    log("覆写 AurogonImmobulusMode 壁纸字段异常: " + t);
                }
            }
        });

        // 2. FreezeUtils 底层写入熔断（最核心屏障）
        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "writeNodeByCgroupV2", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    int pid = (Integer) param.args[0];
                    int val = (Integer) param.args[1];
                    if (val == 1 && isQihangPid(pid)) {
                        log("★ Greezer 防冻结熔断: 阻止向 cgroup.freeze 写入 1 (pid=" + pid + ")！伪装写入成功");
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "writeNode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    String path = String.valueOf(param.args[0]);
                    int val = (Integer) param.args[1];
                    if (val == 1 && isQihangPath(path)) {
                        log("★ Greezer 防冻结熔断: 阻止向 cgroup 写入 1 (path=" + path + ")！");
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezePid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    int pid = (Integer) param.args[0];
                    if (isQihangPid(pid)) {
                        log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezePid(" + pid + ")");
                        param.setResult(Boolean.FALSE);
                        return;
                    }
                    if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                        int uid = (Integer) param.args[1];
                        if (uid > 0 && uid == getQihangUid()) {
                            log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezePid(" + pid + ", uid=" + uid + ")");
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezeUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    int uid = (Integer) param.args[0];
                    if (uid > 0 && uid == getQihangUid()) {
                        log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezeUid(" + uid + ")");
                        param.setResult(Boolean.FALSE);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezeByCgroupV2", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object info = param.args[0];
                        Field f = info.getClass().getField("mPackageName");
                        String pkg = (String) f.get(info);
                        if (PKG_SELF.equals(pkg) || isTarget(pkg)) {
                            log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezeByCgroupV2(pkg=" + pkg + ")");
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        // 3. GreezeManagerService 业务拦截
        hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeAction", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    int uid = (Integer) param.args[0];
                    int pid = (Integer) param.args[1];
                    int qUid = getQihangUid();
                    if ((qUid > 0 && uid == qUid) || isQihangPid(pid)) {
                        log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeAction(uid=" + uid + " pid=" + pid + ")");
                        param.setResult(Boolean.FALSE);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    int uid = (Integer) param.args[0];
                    int qUid = getQihangUid();
                    if (qUid > 0 && uid == qUid) {
                        log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeUid(uid=" + uid + ")");
                        param.setResult(null);
                    }
                }
            }
        });

        hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeProcess", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object proc = param.args[0];
                        Field pf = proc.getClass().getField("processName");
                        String pname = (String) pf.get(proc);
                        if (PKG_SELF.equals(pname) || (pname != null && pname.startsWith(PKG_SELF))) {
                            log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeProcess(" + pname + ")");
                            param.setResult(Boolean.FALSE);
                            return;
                        }
                        Field uf = proc.getClass().getField("uid");
                        int uid = (Integer) uf.get(proc);
                        int qUid = getQihangUid();
                        if (qUid > 0 && uid == qUid) {
                            log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeProcess(uid=" + uid + ")");
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        log("Greezer 防冻结 hooks 安装完成");
    }

    private static Handler mainHandler() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    private static volatile int sCachedFsAod = -1;
    private static volatile int sCachedAodEnabled = -1;
    private static volatile boolean sFsAodObserverRegistered;

    private static boolean isAodEnabled(Context ctx) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                int val = android.provider.Settings.Secure.getInt(
                        c.getContentResolver(), "doze_always_on", 1);
                sCachedAodEnabled = val;
                return val == 1;
            }
        } catch (Throwable ignored) {}
        return sCachedAodEnabled != 0;
    }

    private static boolean isFullScreenAod(Context ctx) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                int val = android.provider.Settings.Secure.getInt(
                        c.getContentResolver(), "full_screen_aod_on", 0);
                sCachedFsAod = val;
                return val == 1;
            }
        } catch (Throwable ignored) {}
        return sCachedFsAod == 1;
    }

    private static void ensureFsAodObserver(Context ctx) {
        if (ctx == null || sFsAodObserverRegistered) return;
        try {
            android.database.ContentObserver observer = new android.database.ContentObserver(mainHandler()) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    boolean fs = isFullScreenAod(ctx);
                    boolean aodOn = isAodEnabled(ctx);
                    log("ContentObserver: AOD 设置变更 -> aodEnabled=" + aodOn + " full_screen_aod_on=" + fs);
                    recompute(ctx);
                    if (sAodRoot != null) {
                        sweep(sAodRoot);
                    }
                }
            };
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("full_screen_aod_on"),
                    false, observer);
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("doze_always_on"),
                    false, observer);
            sFsAodObserverRegistered = true;
            log("full_screen_aod_on & doze_always_on ContentObserver 注册成功");
        } catch (Throwable t) {
            log("注册 full_screen_aod_on ContentObserver 异常: " + t);
        }
    }

    private static void onSleepIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sAsleep && now - sSleepAtMs < 400) return;
        sAsleep = true;
        sSleepAtMs = now;
        sLastFodVisible = true;
        propSet("sys.qihang.fod_visible", "1");
        propSet(PROP_SLEEP_AT, String.valueOf(now));
        propSet(PROP_WAKE_AT, "0");
        ensureQihangThawed();
        mainHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
            }
        }, 1000L);
        mainHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
            }
        }, 2000L);
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
                    if (!isAodEnabled(ctx)) {
                        log("熄屏前馈：goToSleep 但系统已关闭息屏显示 (doze_always_on=0) → 维持锁屏态 mode=1，不进入 AOD");
                        broadcastMode(ctx, 1, "power_sleep_aod_off");
                        sLastMode = 1;
                        return;
                    }
                    boolean fsAod = isFullScreenAod(ctx);
                    int mode = fsAod ? 1 : 0;
                    log("熄屏前馈：goToSleep (fsAod=" + fsAod + ") → 广播 mode=" + mode + (fsAod ? " (全屏AOD锁屏态维持)" : " (AOD 回缩)"));
                    broadcastMode(ctx, mode, fsAod ? "power_sleep_fsaod" : "power_sleep");
                    sLastMode = mode;
                }
            }
        });
    }

    private static void onWakeIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        sAsleep = false;
        sSleepAtMs = 0;
        sWakeAtMs = now;
        propSet(PROP_SLEEP_AT, "0");
        propSet(PROP_WAKE_AT, String.valueOf(now));
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
                    broadcastMode(ctx, 1, "power_wake");
                    sLastMode = 1;
                }
            }
        });
    }

    private static void onBiometricWakeIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        sAsleep = false;
        sSleepAtMs = 0;
        sWakeAtMs = now;
        sBiometricWakeAtMs = now;
        propSet(PROP_SLEEP_AT, "0");
        propSet(PROP_WAKE_AT, String.valueOf(now));
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
                    broadcastMode(ctx, 2, "biometric_wake");
                    sLastMode = 2;
                }
            }
        });
    }

    // =========================================================================
    // 2. SystemUI 通路：透出真息屏壁纸 + 阻断背光压制与硬件着色器
    // =========================================================================

    private void hookSystemUi(ClassLoader cl) {
        log("开始安装 SystemUI hooks...");

        // 2.1 ScrimController 场景与遮罩透出
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

        // 2.2 阻止锁屏联动动画把壁纸涂黑（仅在开启息屏显示时拦截；关闭息屏显示时放行系统正常黑屏动画）
        hookAll(cl, "com.android.keyguard.panel.KeyguardPanelViewController", "doWallpaperBlackAnim",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAodEnabled(sContext)) return;
                        log("doWallpaperBlackAnim -> 已完全拦截（杜绝系统黑屏动画图层）");
                        param.setResult(null);
                    }
                });

        // 2.3 保持 DozeServiceHostInjector 的 setDozeAfterScreenOff(false)，实现无黑闪零 OFF 联动（仅在开启息屏显示时生效）
        hookAll(cl, "com.android.keyguard.injector.DozeServiceHostInjector", "updateDozeAfterScreenOff",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isAodEnabled(sContext)) return;
                        try {
                            Field f = param.thisObject.getClass().getDeclaredField("mPowerManager");
                            f.setAccessible(true);
                            Object pm = f.get(param.thisObject);
                            if (pm != null) {
                                Method m = pm.getClass().getMethod("setDozeAfterScreenOff", boolean.class);
                                m.invoke(pm, false);
                            }
                        } catch (Throwable ignored) {}
                    }
                });

        // 2.4 彻底拦截 DisplayStateShaderController 硬件淡黑遮罩与黑屏着色器指令（根除 80ms 黑闪核心链路，仅在开启息屏显示时拦截）
        XC_MethodHook blockShaderFade = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                log("DisplayStateShaderController." + param.method.getName() + " -> 已彻底拦截（阻断 SurfaceFlinger 硬件淡黑着色器）");
                if ("showScreenFadeSurface".equals(param.method.getName())) {
                    param.setResult(Boolean.FALSE);
                } else {
                    param.setResult(null);
                }
            }
        };
        hookAll(cl, "com.android.keyguard.screenfade.DisplayStateShaderController", "showScreenFadeSurface", blockShaderFade);
        hookAll(cl, "com.android.keyguard.screenfade.DisplayStateShaderController", "setScreenFadeToSf", blockShaderFade);
        hookAll(cl, "com.android.keyguard.screenfade.DisplayStateShaderController", "setLayerWithAOD", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                // 阻断 needBlockBrightness=1 向 SF 传递，SurfaceFlinger 永不触发 block aod backlight 压制！
                log("DisplayStateShaderController.setLayerWithAOD -> 已拦截（阻断 needBlockBrightness）");
                param.setResult(null);
            }
        });

        // 2.5 仅在「对应场景启用了魅族时钟」或「关闭息屏显示且处于熄屏态」时压制原生时钟透明度：
        //     1) 开启魅族时钟时杜绝双时钟叠加；
        //     2) 关闭系统息屏显示（doze_always_on=0）且熄屏后，压制原生锁屏时钟透明度为 0，杜绝熄屏后残留显示系统时钟！
        XC_MethodHook clockAlphaHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = sContext != null ? sContext : systemContext();
                boolean sleeping = isSleepingActive();
                boolean aodEnabled = isAodEnabled(ctx);
                if (sleeping && !aodEnabled) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Float) {
                        param.args[0] = 0.0f;
                    }
                    return;
                }
                if (isFullScreenAod(ctx)) {
                    return; // 全屏AOD下完全放行原生锁屏/息屏时钟透明度，由系统接管
                }
                boolean customLock = isCustomClockEnabled(ctx, 1);
                boolean customAod = isCustomClockEnabled(ctx, 0);
                boolean override = sleeping ? customAod : customLock;
                if (override) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Float) {
                        param.args[0] = 0.0f; // 强制锁屏时钟透明度为 0
                    }
                }
            }
        };
        hookAll(cl, "com.android.keyguard.clock.animation.ClockBaseAnimation", "updateTimeViewAlpha", clockAlphaHook);
        hookAll(cl, "com.android.keyguard.clock.animation.allinone.AllInOneClockAnimation", "updateTimeViewAlpha", clockAlphaHook);

        // 2.6 监听屏下指纹图标 (GXZW / FOD) 显隐，息屏下指纹消失后联动壁纸停止自转并休眠省电
        try {
            XC_MethodHook fodHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String mName = param.method.getName();
                        boolean visible;
                        if ("onGxzwIconChanged".equals(mName)) {
                            // DozeServiceHostInjector.onGxzwIconChanged(boolean z): z 为 mGxzwIconTransparent (true 表示透明/消失)
                            boolean transparent = (boolean) param.args[0];
                            visible = !transparent;
                        } else if ("dismissFingerpirntIcon".equals(mName) || "dismissGxzwIconView".equals(mName)) {
                            visible = false;
                        } else if ("setGxzwIconOpaque".equals(mName)) {
                            visible = true;
                        } else {
                            return;
                        }
                        log("FOD 指纹图标状态变化: " + mName + " -> visible=" + visible);
                        broadcastFodVisible(sContext, visible);
                    } catch (Throwable t) {
                        log("FOD 指纹图标状态回调异常: " + t);
                    }
                }
            };

            hookAll(cl, "com.android.keyguard.injector.DozeServiceHostInjector", "onGxzwIconChanged", fodHook);
            hookAll(cl, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView", "dismissFingerpirntIcon", fodHook);
            hookAll(cl, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView", "setGxzwIconOpaque", fodHook);
            hookAll(cl, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView", "dismissGxzwIconView", fodHook);
            log("FOD 指纹图标显隐 Hook 挂载成功");
        } catch (Throwable t) {
            log("挂载 FOD 指纹图标 Hook 异常: " + t);
        }

        log("SystemUI hooks 安装成功");
    }

    // =========================================================================
    // 3. AOD 模块通路：透出超级壁纸样式 + 彻底清空 AODView 黑色背景与 mask
    // =========================================================================

    private void hookAod(ClassLoader cl) {
        log("开始安装 AOD 模块 hooks...");

        // 3.1 拦截 DozeHost.disableShowWallpaper，阻止系统在息屏时通知 WM 关闭壁纸 Surface（仅在开启息屏显示时生效）
        hookAll(cl, "com.miui.aod.DozeHost", "disableShowWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                log("DozeHost.disableShowWallpaper -> 已拦截（保持壁纸 Surface 显示）");
                param.setResult(null);
            }
        });

        // 3.2 伪装壁纸支持 AOD 环境模式 (isWallpaperSupportsAmbientMode -> true，仅在开启息屏显示时生效)
        hookAll(cl, "com.miui.aod.util.WallPaperUtils", "isWallpaperSupportsAmbientMode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                param.setResult(Boolean.TRUE);
            }
        });

        // 3.3 伪装正在使用超级壁纸样式（防止 AOD 进入纯黑全屏模式；关闭息屏显示或全屏AOD下放行原生设置）
        hookAll(cl, "com.miui.aod.Utils", "isUsingSuperWallpaperStyle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = sContext != null ? sContext : systemContext();
                if (!isAodEnabled(ctx) || isFullScreenAod(ctx)) {
                    return; // 关闭息屏显示 或 全屏AOD(和锁屏样式一致)下放行系统原生判断
                }
                param.setResult(Boolean.TRUE);
            }
        });

        // 3.4 阻止 AODView 背景被设置成不透明纯黑（仅在开启息屏显示时生效）
        XC_MethodHook clearBgHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                param.setResult(null);
            }
        };
        hookAll(cl, "com.miui.aod.AODView", "setBackgroundColor", clearBgHook);
        hookAll(cl, "com.miui.aod.AODView", "setBackgroundResource", clearBgHook);
        hookAll(cl, "com.miui.aod.AODView", "setBackground", clearBgHook);
        hookAll(cl, "com.miui.aod.AODView", "setBackgroundDrawable", clearBgHook);

        // AODView 构造完成后立即置空背景，杜绝创建初期的瞬间黑底
        hookAll(cl, "com.miui.aod.AODView", "<init>", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                try {
                    View v = (View) param.thisObject;
                    v.setBackground(null);
                    log("AODView.<init> 构造完毕，已强制置空背景");
                } catch (Throwable ignored) {}
            }
        });

        // AODView 复用时立即置空背景
        hookAll(cl, "com.miui.aod.AODView", "onReuseAodView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                try {
                    View v = (View) param.thisObject;
                    v.setBackground(null);
                    log("AODView.onReuseAodView 复用，已强制置空背景");
                } catch (Throwable ignored) {}
            }
        });

        // 3.5 拦截 AODView 的 mask 遮罩动画，防止全屏黑色 mask 压盖在壁纸上方（仅在开启息屏显示时拦截）
        XC_MethodHook blockMaskHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAodEnabled(sContext)) return;
                log("AODView mask hook -> 已拦截黑色遮罩");
                param.setResult(null);
            }
        };
        hookAll(cl, "com.miui.aod.AODView", "initMaskView", blockMaskHook);
        hookAll(cl, "com.miui.aod.AODView", "startMaskAnimation", blockMaskHook);
        hookAll(cl, "com.miui.aod.DozeHost", "initMaskViewIfNeeded", blockMaskHook);
        hookAll(cl, "com.miui.aod.DozeHost", "startMaskAnimationIfNeeded", blockMaskHook);

        // 3.6 AODView 挂载时，确保自身背景透明并挂载 Flyme 时钟与启动定时巡检
        hookAll(cl, "com.miui.aod.AODView", "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    if (isAodEnabled(v.getContext())) {
                        v.setBackground(null);
                    }
                    sAodRoot = v.getRootView();
                    ensureClockReceiver(v.getContext());
                    if (v instanceof ViewGroup && isAodEnabled(v.getContext())) {
                        ensureFlymeClockInAod((ViewGroup) v);
                    }
                    startSweep(sAodRoot, true);
                    log("AODView.onAttachedToWindow 已触发透明化与时钟挂载");
                } catch (Throwable t) {
                    log("AODView.onAttachedToWindow hook 异常: " + t);
                }
            }
        });

        log("AOD 模块 hooks 安装完成");
    }

    private static void onScrim(Object scrim) {
        String state = readState(scrim);
        if (state == null) return;

        if (sStateName == null || !sStateName.equals(state)) {
            String prev = sStateName;
            sStateName = state;
            sScrimMode = modeOf(state);
            log("scrim state=" + state + " -> mode=" + sScrimMode);
            // 桌面熄屏：UNLOCKED → KEYGUARD 的过渡帧会把整层锁屏（大时钟/签名/充电信息）
            // 以 alpha=1 闪出约 180ms。原生系统靠黑屏淡出遮住它，模块拦截了淡黑，所以要自己遮。
            // 全屏AOD下锁屏层本就作为AOD界面展示，不可遮蔽。
            if ("UNLOCKED".equals(prev) && isSleepingActive()) {
                Context probeCtx = sContext != null ? sContext : findContext(scrim);
                if (isAodEnabled(probeCtx) && !isFullScreenAod(probeCtx)) {
                    armHomeSleepMask("scrim " + prev + "->" + state);
                }
            }
        }

        View root = findView(scrim);
        Context ctx = sContext != null ? sContext : (root != null ? root.getContext() : findContext(scrim));
        if (ctx != null) {
            sContext = ctx;
            ensureDisplayListener(ctx);
            ensureClockReceiver(ctx);
            ensureFsAodObserver(ctx);
        }
        if (root != null) {
            sAodRoot = root.getRootView();
        }

        // 息屏下强制消除前置与后置 scrim 遮罩，杜绝纯黑覆盖（仅在系统开启了息屏显示 doze_always_on=1 时执行；
        // 若用户关闭了息屏显示，必须保留系统黑屏 Scrim 遮罩，防止锁屏系统时钟在息屏后透出！）
        boolean aodEnabled = isAodEnabled(ctx);
        if (aodEnabled && (sScrimMode == 0 || isSleepingActive())) {
            for (String fName : new String[]{"mBehindAlpha", "mInFrontAlpha", "mNotificationsAlpha"}) {
                try {
                    Field f = scrim.getClass().getDeclaredField(fName);
                    f.setAccessible(true);
                    f.setFloat(scrim, 0.0f);
                } catch (Throwable ignored) {}
            }
        }

        recompute(ctx);
    }

    private static boolean sLastSleeping = false;
    private static boolean sLastAodEnabled = true;
    private static String sLastStateName = null;

    private static void recompute(Context ctx) {
        if (ctx == null) return;
        boolean aodEnabled = isAodEnabled(ctx);
        boolean doze = isDozeLike(ctx);
        boolean sleeping = isSleepingActive() || doze;
        boolean fsAod = isFullScreenAod(ctx);
        int mode;
        if (sleeping) {
            if (!aodEnabled) {
                mode = 1; // 系统关闭息屏显示时：壁纸维持锁屏态(mode=1)，不展示任何时钟
            } else {
                mode = fsAod ? 1 : 0; // 全屏AOD下壁纸保持锁屏全屏态(mode=1)，自定义样式下切启航息屏(mode=0)
            }
        } else {
            long sinceBio = android.os.SystemClock.uptimeMillis() - sBiometricWakeAtMs;
            if (sinceBio < 1200L) {
                // 指纹解锁直达桌面唤醒窗口期内，保持 mode=2 (HOME)，严禁被 ScrimController 未就绪的中间态篡改为 mode=1 (LOCK)！
                mode = 2;
            } else {
                // 唤醒初期 ScrimController.mState 可能尚未从 AOD 切到 KEYGUARD（约 40~90ms 延迟），
                // 严禁在非休眠态下下发 mode=0 (doze)，避免打断锁屏展开过渡动画！
                mode = (sScrimMode == 0) ? 1 : sScrimMode;
            }
        }
        boolean modeOrSleepChanged = (mode != sLastMode) || (sleeping != sLastSleeping) || (aodEnabled != sLastAodEnabled);
        boolean stateNameChanged = (sStateName != null && !sStateName.equals(sLastStateName));
        sLastSleeping = sleeping;
        sLastAodEnabled = aodEnabled;
        sLastStateName = sStateName;
        if (!modeOrSleepChanged && !stateNameChanged) return;

        sLastMode = mode;
        log("-> mode=" + mode + " (scrim=" + sStateName + " doze=" + doze + " sleeping=" + sleeping + " aodEnabled=" + aodEnabled + " fsAod=" + fsAod + ")");
        if (mode == 0 && "UNLOCKED".equals(sStateName)) {
            // 熄屏前馈比 KEYGUARD 场景早约 100ms 到达，提前遮住锁屏层
            if (aodEnabled && !fsAod) {
                armHomeSleepMask("sleep from UNLOCKED");
            }
        } else if (mode != 0) {
            releaseHomeSleepMask("mode=" + mode);
        }
        if (modeOrSleepChanged) {
            broadcastMode(ctx, mode, mode == 0 ? "doze" : (sleeping && !aodEnabled ? "sleep_aod_off" : (fsAod && sleeping ? "doze_fsaod" : (sStateName != null ? sStateName : "KEYGUARD"))));
        }
        boolean showFlymeClock = (mode == 0 || mode == 1) && !(sleeping && (!aodEnabled || fsAod));
        updateClocksVisibility(showFlymeClock, mode);

        if (sleeping) {
            stopSweep();
            startSweep(sAodRoot, true);
        } else {
            stopSweep();
            if (sAodRoot != null) {
                sweep(sAodRoot);
            }
        }
    }

    private static void startSweep(View root, final boolean isAod) {
        if (root != null) {
            sAodRoot = root.getRootView();
        }
        if (sSweep != null) return;

        Handler h = mainHandler();
        sSweep = new Runnable() {
            private int count = 0;
            @Override
            public void run() {
                try {
                    View r = sAodRoot;
                    if (r != null) {
                        sweep(r);
                    }
                    count++;
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                if (sSweep != null && count < 30) {
                    mainHandler().postDelayed(this, 100);
                } else if (sSweep != null) {
                    mainHandler().postDelayed(this, 1000);
                }
            }
        };
        h.post(sSweep);
    }

    private static void stopSweep() {
        if (sSweep != null) {
            mainHandler().removeCallbacks(sSweep);
            sSweep = null;
        }
    }

    private static void sweep(View root) {
        if (root == null) return;
        boolean sleeping = isSleepingActive();
        Context ctx = sContext != null ? sContext : (root.getContext() != null ? root.getContext() : systemContext());
        boolean aodEnabled = isAodEnabled(ctx);
        boolean fsAod = isFullScreenAod(ctx);
        boolean customClockAod = aodEnabled && !fsAod && isCustomClockEnabled(ctx, 0);
        boolean customClockLock = isCustomClockEnabled(ctx, 1);
        sweepInternal(root, sleeping, aodEnabled, fsAod, customClockAod, customClockLock, false);
    }

    private static void sweepInternal(View v, boolean sleeping, boolean aodEnabled, boolean fsAod,
                                      boolean customClockAod, boolean customClockLock, boolean inKeyguardClock) {
        if (v == null) return;
        // 清理纯黑背景，使下层壁纸透出（仅在开启息屏显示或亮屏态下清理）
        if ((aodEnabled || !sleeping) && isDarkBackground(v)) {
            v.setBackground(null);
            log("已清除遮挡黑底: " + v.getClass().getSimpleName() + " id=0x" + Integer.toHexString(v.getId()));
        }

        boolean customClock = sleeping ? customClockAod : customClockLock;
        String cls = v.getClass().getSimpleName();
        String idName = idNameOf(v);

        // 已注入的 Flyme 时钟处理
        if (v instanceof FlymeClockView || "qihang_flyme_clock".equals(v.getTag())) {
            if (!customClock || sLastMode == 2 || (sleeping && (!aodEnabled || fsAod))) {
                v.setVisibility(View.GONE);
                v.setAlpha(0.0f);
            } else {
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1.0f);
                ((FlymeClockView) v).bringToFront();
                ((FlymeClockView) v).setClockMode(sleeping ? 0 : 1, false);
            }
            return;
        }

        // 原生锁屏时钟「外壳」：只在真正的锁屏场景恢复可见。
        // SHADE_LOCKED / UNLOCKED 下系统会自行隐藏它；模块若继续强写 VISIBLE，
        // 会把同一份锁屏大时钟重新叠到通知卡片上。
        // 当关闭系统息屏显示（!aodEnabled）且处于熄屏态时，必须强制隐藏锁屏时钟外壳，杜绝熄屏后残留显示系统时钟！
        if (isKeyguardClockShell(cls, idName)) {
            inKeyguardClock = true;
            if (sleeping) {
                if (!aodEnabled) {
                    v.setVisibility(View.INVISIBLE);
                    v.setAlpha(0.0f);
                } else if (fsAod) {
                    // 全屏AOD：原生锁屏时钟外壳由系统负责渲染并保持可见
                    v.setVisibility(View.VISIBLE);
                    v.setAlpha(1.0f);
                } else if (customClockAod) {
                    v.setVisibility(View.INVISIBLE);
                    v.setAlpha(0.0f);
                }
            } else if (customClockLock || !isNativeKeyguardClockScene()) {
                // 锁屏外壳只允许在真正的锁屏场景出现。通知栏展开、解锁回桌面后
                // 必须主动收回，否则部分 SystemUI 版本会把这层一直留在屏幕上。
                v.setVisibility(View.INVISIBLE);
                v.setAlpha(0.0f);
            } else {
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1.0f);
            }
        } else if ("clock_animation_container".equals(idName)) {
            // 时分动画容器由锁屏与 AOD 共用。
            if (sleeping && !aodEnabled) {
                v.setVisibility(View.INVISIBLE);
                v.setAlpha(0.0f);
            } else if (sleeping && fsAod) {
                // 全屏AOD：系统原生动画容器放行可见
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1.0f);
            } else {
                boolean hide = sleeping ? customClockAod : customClockLock;
                if (hide) {
                    v.setVisibility(View.INVISIBLE);
                    v.setAlpha(0.0f);
                } else if (!sleeping && isNativeKeyguardClockScene()) {
                    v.setVisibility(View.VISIBLE);
                    v.setAlpha(1.0f);
                }
            }
        } else if (isAodRoot(cls, idName)) {
            if (sleeping && aodEnabled) { // 息屏 AOD 态（且开启了息屏显示）：确保 AOD 根容器保持可见
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1.0f);
            } else { // 唤醒锁屏态 或 关闭了息屏显示：隐匿 AOD 浮层
                v.setVisibility(View.INVISIBLE);
                v.setAlpha(0.0f);
            }
        } else if (!inKeyguardClock && isNativeAodClock(v)) {
            // 走到这里的一定是「AOD 专属装饰/部件」（彩带 color_ribbons_container、渐变、
            // 缩略图卡片、AOD 电池等）——锁屏时钟子树已通过 !inKeyguardClock 排除，绝不误伤锁屏内部时钟视图。
            // 非息屏态或关闭了息屏显示时必须隐藏，否则这些装饰层或时钟会在屏幕上透出。
            if (sleeping && aodEnabled) {
                if (fsAod) {
                    // 全屏AOD：原生 AOD 专属小部件（彩带等）必须隐藏，不压盖全屏锁屏
                    v.setVisibility(View.INVISIBLE);
                    v.setAlpha(0.0f);
                } else if (customClockAod) {
                    // 息屏且开启魅族时钟：隐藏原生 AOD 时钟文字与数字
                    v.setVisibility(View.GONE);
                    v.setAlpha(0.0f);
                } else {
                    // 息屏但关闭魅族时钟：完全恢复原生 AOD 时钟！
                    v.setVisibility(View.VISIBLE);
                    v.setAlpha(1.0f);
                }
            } else {
                v.setVisibility(View.INVISIBLE);
                v.setAlpha(0.0f);
            }
        } else if (inKeyguardClock && isNativeAodClock(v)) {
            if (!sleeping && !customClockLock && isNativeKeyguardClockScene()) {
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1.0f);
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if ((customClockAod && aodEnabled && !fsAod) || customClockLock) {
                ensureFlymeClock(v);
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                sweepInternal(g.getChildAt(i), sleeping, aodEnabled, fsAod, customClockAod, customClockLock, inKeyguardClock);
            }
        }
    }

    private static synchronized void registerClock(FlymeClockView clock) {
        if (clock == null) return;
        boolean found = false;
        Iterator<WeakReference<FlymeClockView>> it = sClocks.iterator();
        while (it.hasNext()) {
            FlymeClockView c = it.next().get();
            if (c == null) {
                it.remove();
            } else if (c == clock) {
                found = true;
            }
        }
        if (!found) {
            sClocks.add(new WeakReference<>(clock));
        }
    }

    private static synchronized boolean hasActiveClock() {
        for (WeakReference<FlymeClockView> ref : sClocks) {
            FlymeClockView c = ref.get();
            if (c != null && c.isAttachedToWindow() && c.isShown()) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void updateClocksVisibility(boolean visible, int mode) {
        boolean customClock = isCustomClockEnabled(sContext, mode);
        Iterator<WeakReference<FlymeClockView>> it = sClocks.iterator();
        while (it.hasNext()) {
            FlymeClockView c = it.next().get();
            if (c == null) {
                it.remove();
            } else {
                if (visible && customClock && mode != 2) {
                    c.setVisibility(View.VISIBLE);
                    c.bringToFront();
                    c.setClockMode(mode, true);
                    c.updateTime();
                } else {
                    c.setClockMode(mode, true);
                    c.setVisibility(View.GONE);
                }
            }
        }
    }

    private static boolean isCustomClockEnabled(Context ctx, int mode) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                if (mode == 0 && isFullScreenAod(c)) {
                    return false; // 全屏AOD下禁止魅族息屏时钟，由系统接管
                }
                return ClockSettings.isClockEnabled(c, mode);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static void ensureClockReceiver(Context ctx) {
        if (ctx == null || sClockReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ClockSettings.ACTION_STYLE_CHANGED);
            f.addAction(AodStyleSettings.ACTION_STYLE_CHANGED);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    log("收到时钟配置变更广播: " + intent.getAction());
                    if (ClockSettings.ACTION_STYLE_CHANGED.equals(intent.getAction())) {
                        try {
                            ClockSettings.Config aod = ClockSettings.fromIntentAod(intent);
                            ClockSettings.Config lock = ClockSettings.fromIntentLock(intent);
                            ClockSettings.saveLocal(context, aod, lock);
                        } catch (Throwable ignored) {}
                    } else if (AodStyleSettings.ACTION_STYLE_CHANGED.equals(intent.getAction())) {
                        try {
                            int style = intent.getIntExtra(AodStyleSettings.KEY_STYLE, AodStyleSettings.STYLE_SET_SAIL);
                            context.getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE)
                                    .edit().putInt(AodStyleSettings.KEY_STYLE, style).commit();
                        } catch (Throwable ignored) {}
                    }
                    int currentMode = sLastMode >= 0 ? sLastMode : 0;
                    boolean sleeping = isSleepingActive() || isDozeLike(context);
                    boolean aodEnabled = isAodEnabled(context);
                    boolean fsAod = isFullScreenAod(context);
                    boolean showFlymeClock = (currentMode == 0 || currentMode == 1) && !(sleeping && (!aodEnabled || fsAod));
                    updateClocksVisibility(showFlymeClock, currentMode);
                    if (sAodRoot != null) {
                        sweep(sAodRoot);
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            sClockReceiverRegistered = true;
            log("时钟配置广播接收器已注册");
            ensureFsAodObserver(ctx);
        } catch (Throwable t) {
            log("注册时钟配置广播接收器失败: " + t);
        }
    }

    private static void ensureFlymeClock(View v) {
        if (!(v instanceof ViewGroup)) return;
        boolean isTargetContainer = false;
        String cls = v.getClass().getName();
        String entry = null;
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                entry = v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {}

        if (entry != null) {
            if ("keyguard_translation_info".equals(entry)) {
                isTargetContainer = true;
            } else if ("keyguard_info_layer".equals(entry)) {
                ViewGroup g = (ViewGroup) v;
                if (findChildByEntry(g, "keyguard_translation_info") == null) {
                    isTargetContainer = true;
                }
            } else if ("keyguard_foreground_layer".equals(entry)) {
                isTargetContainer = true;
            }
        }

        if (!isTargetContainer && !hasActiveClock()) {
            if (cls.contains("AodView") || cls.contains("aod_root")) {
                isTargetContainer = true;
            } else if ("aod_root_view".equals(entry) || "aod_mode_layout_horizontal".equals(entry)) {
                isTargetContainer = true;
            }
        }

        if (!isTargetContainer) return;

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
                registerClock(clock);
                clock.setClockMode(sLastMode >= 0 ? sLastMode : 0, false);
                log("FlymeClockView 成功注入容器: " + root.getClass().getSimpleName() + " (entry=" + entry + ")");
            } catch (Throwable t) {
                log("注入 FlymeClockView 失败: " + t);
            }
        } else if (existing instanceof FlymeClockView) {
            FlymeClockView clock = (FlymeClockView) existing;
            registerClock(clock);
            clock.setClockMode(sLastMode >= 0 ? sLastMode : 0, false);
        }
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
                registerClock(clock);
                clock.setClockMode(sLastMode >= 0 ? sLastMode : 0, false);
                log("成功在 AODView 注入 FlymeClockView!");
            } catch (Throwable t) {
                log("注入 FlymeClockView 失败: " + t);
            }
        } else if (existing instanceof FlymeClockView) {
            FlymeClockView clock = (FlymeClockView) existing;
            registerClock(clock);
            clock.setClockMode(sLastMode >= 0 ? sLastMode : 0, false);
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

    // ---- 桌面 → 息屏：遮蔽锁屏整层的过渡闪现 ----
    // 实测（16ms 探针）：goToSleep 后约 110ms 进入 KEYGUARD 场景，keyguard_root_view 连同锁屏大时钟
    // 以 alpha=1 显示约 180ms，进入 AOD 场景约 85ms 后才由系统自行收起，随后 AOD 时钟再淡入，
    // 肉眼即「时钟出现两次」。这里用 transitionAlpha（系统不会改写它，且与 alpha 相乘）把锁屏层
    // 压到 0，等系统自己把锁屏层收起后立刻还原，不影响 AOD 中后续复用的锁屏时钟。
    private static final long HOME_SLEEP_MASK_MAX_MS = 2500;
    private static volatile boolean sHomeSleepMask;
    private static long sHomeSleepMaskAt;
    private static WeakReference<View> sKeyguardRootRef;
    private static Runnable sHomeSleepMaskTick;

    private static View findKeyguardRoot() {
        View cached = sKeyguardRootRef != null ? sKeyguardRootRef.get() : null;
        if (cached != null && cached.isAttachedToWindow()) return cached;
        View found = findByIdName(sAodRoot, "keyguard_root_view");
        sKeyguardRootRef = found != null ? new WeakReference<>(found) : null;
        return found;
    }

    private static View findByIdName(View v, String name) {
        if (v == null) return null;
        if (name.equals(idNameOf(v))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByIdName(g.getChildAt(i), name);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 系统自身是否仍在显示锁屏层（忽略我们设置的 transitionAlpha）。 */
    private static boolean systemShowsKeyguard(View root) {
        if (root == null || !root.isShown()) return false;
        float a = 1.0f;
        View cur = root;
        while (cur != null) {
            a *= cur.getAlpha();
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return a > 0.01f;
    }

    private static void armHomeSleepMask(String why) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!sHomeSleepMask) {
            log("桌面熄屏遮罩 ON (" + why + ")");
        }
        sHomeSleepMask = true;
        sHomeSleepMaskAt = now;
        View root = findKeyguardRoot();
        if (root != null) root.setTransitionAlpha(0.0f);
        if (sHomeSleepMaskTick != null) return;
        sHomeSleepMaskTick = new Runnable() {
            @Override
            public void run() {
                if (!sHomeSleepMask) {
                    sHomeSleepMaskTick = null;
                    return;
                }
                View r = findKeyguardRoot();
                long elapsed = android.os.SystemClock.uptimeMillis() - sHomeSleepMaskAt;
                if (!isSleepingActive()) {
                    releaseHomeSleepMask("已唤醒");
                } else if ("AOD".equalsIgnoreCase(sStateName) && !systemShowsKeyguard(r)) {
                    releaseHomeSleepMask("系统已收起锁屏层 +" + elapsed + "ms");
                } else if (elapsed > HOME_SLEEP_MASK_MAX_MS) {
                    releaseHomeSleepMask("超时 +" + elapsed + "ms");
                } else {
                    if (r != null) r.setTransitionAlpha(0.0f);
                    mainHandler().postDelayed(this, 16);
                    return;
                }
                sHomeSleepMaskTick = null;
            }
        };
        mainHandler().post(sHomeSleepMaskTick);
    }

    private static void releaseHomeSleepMask(String why) {
        if (!sHomeSleepMask) return;
        sHomeSleepMask = false;
        View root = findKeyguardRoot();
        if (root != null) root.setTransitionAlpha(1.0f);
        if (sHomeSleepMaskTick != null) {
            mainHandler().removeCallbacks(sHomeSleepMaskTick);
            sHomeSleepMaskTick = null;
        }
        log("桌面熄屏遮罩 OFF (" + why + ")");
    }

    /** 只有这些 ScrimState 代表锁屏主体仍在显示，可由模块补回完整的原生时/分容器。 */
    private static boolean isNativeKeyguardClockScene() {
        if (sStateName == null) return false;
        String state = sStateName.toUpperCase();
        if (!isSleepingActive() && ("AOD".equals(state) || "PULSING".equals(state) || "DOZE".equals(state))) {
            return true;
        }
        return "KEYGUARD".equals(state)
                || "BOUNCER".equals(state)
                || "BOUNCER_SCRIMMED".equals(state);
    }

    /** 原生锁屏时钟「外壳」容器（只在锁屏使用）；不含锁屏与 AOD 共用的 clock_animation_container。 */
    private static boolean isKeyguardClockShell(String cls, String idName) {
        return "KeyguardClockContainer".equals(cls)
                || "KeyguardClockView".equals(cls)
                || "miui_keyguard_clock_container".equals(idName)
                || "miui_keyguard_foreground_clock_container".equals(idName);
    }

    /** AOD 浮层根容器。 */
    private static boolean isAodRoot(String cls, String idName) {
        return "AodView".equals(cls) || "AODView".equals(cls) || "aod_root_view".equals(idName);
    }

    private static boolean isNativeAodClock(View v) {
        if (v == null || v instanceof FlymeClockView || "qihang_flyme_clock".equals(v.getTag())) {
            return false;
        }
        String cls = v.getClass().getName();
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
        } catch (Throwable ignored) {}
        return cls.contains("AodContainerView")
                || cls.contains("LinkageStyleClockView")
                || cls.contains("SuperWallpaperClockView")
                || cls.contains("KeyguardClockContainer")
                || cls.contains("AODBatteryMeterView")
                || cls.contains("ClassicClockView")
                || cls.contains("HorizontalClockView")
                || cls.contains("OnelineClockView")
                || cls.contains("VerticalClockView");
    }

    private static String idNameOf(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                return v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean isDarkBackground(View v) {
        Drawable d = v.getBackground();
        if (!(d instanceof ColorDrawable)) return false;
        int color = ((ColorDrawable) d).getColor();
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return a == 255 && r < 24 && g < 24 && b < 24;
    }

    private static boolean isSleepingActive() {
        long at = propGetLong(PROP_SLEEP_AT, 0L);
        long wakeAt = propGetLong(PROP_WAKE_AT, 0L);
        return at > 0 && at >= wakeAt;
    }

    private static boolean isDozeLike(Context ctx) {
        if (ctx == null) return false;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return false;
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return false;
            int st = d.getState();
            if (st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND) {
                return true;
            }
            return st == Display.STATE_OFF && isSleepingActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void ensureDisplayListener(Context ctx) {
        if (sDisplayRegistered) return;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}
                @Override
                public void onDisplayRemoved(int displayId) {}
                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        recompute(sContext);
                    }
                }
            }, mainHandler());
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
                if (s.length() > 0) return s;
            }
        }
        return null;
    }

    private static String aliveCmd(int mode) {
        if (mode == 0) return "action_alive_aod";
        if (mode == 1) return "action_alive_lock";
        return "action_alive_launcher";
    }

    private static void broadcastMode(Context ctx, int mode, String state) {
        Intent i = new Intent(ACTION_MODE);
        i.setPackage(PKG_SELF);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_ANIMATE, true);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra("fod_visible", !"0".equals(propGet("sys.qihang.fod_visible", "1")));
        sendBroadcastSmart(ctx, i);

        Intent alive = new Intent(ACTION_ALIVE);
        alive.setPackage(PKG_ALIVE);
        alive.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        alive.putExtra(EXTRA_ALIVE_CMD, aliveCmd(mode));
        sendBroadcastSmart(ctx, alive);
    }

    private static void broadcastFodVisible(Context ctx, boolean visible) {
        if (sLastFodVisible == visible) return;
        sLastFodVisible = visible;
        propSet("sys.qihang.fod_visible", visible ? "1" : "0");
        if (ctx == null) ctx = sContext != null ? sContext : systemContext();
        if (ctx == null) return;
        try {
            Intent intent = new Intent(ACTION_MODE);
            intent.setPackage(PKG_SELF);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("fod_visible", visible);
            intent.putExtra("fod_changed", true);
            sendBroadcastSmart(ctx, intent);
        } catch (Throwable t) {
            log("broadcastFodVisible 失败: " + t);
        }
    }

    private static void sendBroadcastSmart(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        try {
            if (android.os.Process.myUid() == 1000) {
                try {
                    Class<?> uhClass = Class.forName("android.os.UserHandle");
                    Field allField = uhClass.getField("ALL");
                    Object userAll = allField.get(null);
                    Method m = Context.class.getMethod("sendBroadcastAsUser", Intent.class, uhClass);
                    m.invoke(ctx, intent, userAll);
                    return;
                } catch (Throwable ignored) {}
            }
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            log("sendBroadcast 失败: " + t);
        }
    }

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

    private static void propSet(String k, String v) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            c.getMethod("set", String.class, String.class).invoke(null, k, v);
        } catch (Throwable ignored) {}
    }

    private static String propGet(String k, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class, String.class).invoke(null, k, def);
            if (o instanceof String) {
                return (String) o;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private static long propGetLong(String k, long def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class, String.class).invoke(null, k, "");
            if (o instanceof String) {
                String s = ((String) o).trim();
                if (s.length() > 0) return Long.parseLong(s);
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private static boolean isResetAllowed() {
        int caller = android.os.Binder.getCallingUid();
        if (caller == 0) {
            return true;
        }
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class, String.class).invoke(null, "sys.qihang.allow_reset", "0");
            if ("1".equals(o)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static View findView(Object obj) {
        if (obj == null) return null;
        for (String fieldName : new String[]{"mScrimInFront", "mScrimBehind", "mRootView"}) {
            Object v = getField(obj, fieldName);
            if (v instanceof View) return (View) v;
        }
        return null;
    }

    private static Context findContext(Object obj) {
        if (obj == null) return null;
        Object c = getField(obj, "mContext");
        return c instanceof Context ? (Context) c : null;
    }

    private static Object getField(Object obj, String fieldName) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void hook(ClassLoader cl, String className, String method, Class<?>[] params, XC_MethodHook cb) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            Method m = c.getDeclaredMethod(method, params);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, cb);
            log("hook 成功: " + className + "." + method);
        } catch (Throwable t) {
            log("hook 失败: " + className + "." + method + " (" + t.getMessage() + ")");
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
            log("hookAll 成功: " + className + "." + method + " x" + count);
        } catch (Throwable t) {
            log("hookAll 失败: " + className + "." + method + " (" + t.getMessage() + ")");
        }
    }
}
