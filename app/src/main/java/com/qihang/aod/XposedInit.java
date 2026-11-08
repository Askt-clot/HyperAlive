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
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    public static final String ACTION_MODE = "com.qihang.aod.CMD";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ANIMATE = "animate";
    public static final String EXTRA_STATE = "state";

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
    private static FlymeClockView sFlymeClock;
    private static final List<WeakReference<FlymeClockView>> sClocks = new ArrayList<>();
    private static volatile long sSleepAtMs;
    private static volatile long sWakeAtMs;
    private static volatile boolean sAsleep;

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
            if ("android".equals(lp.packageName)) {
                hookSystemServer(lp.classLoader);
            }
            if ("com.android.systemui".equals(lp.packageName)
                    || "miui.systemui.plugin".equals(lp.packageName)
                    || "com.miui.aod".equals(lp.packageName)) {
                hookSystemUi(lp.classLoader);
                hookAod(lp.classLoader);
            }
        } catch (Throwable t) {
            log("handleLoadPackage 异常 " + lp.packageName);
            XposedBridge.log(t);
        }
    }

    // =========================================================================
    // 1. system_server 通路：权限放行 + 电源前馈 + 硬件状态重定向
    // =========================================================================

    private void hookSystemServer(ClassLoader cl) {
        log("开始安装 system_server hooks...");
        hookPowerAodSignal(cl);

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

        // 1.4 拦截 DisplayPowerController / DisplayPowerState.setScreenState：熄屏进入 AOD 时严禁将屏幕硬件置为 STATE_OFF (1)
        // 彻底杜绝底层屏幕硬件先断电 (power mode Off=0) 再重新点亮 (Doze=1) 导致的物理屏幕黑闪！
        XC_MethodHook screenStateHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object stateObj = param.args[0];
                    if (stateObj instanceof Integer) {
                        int state = (Integer) stateObj;
                        if (state == 1 && sAsleep) { // 1 = Display.STATE_OFF
                            log(param.thisObject.getClass().getSimpleName() + ".setScreenState: 拦截 STATE_OFF(1) -> 强制重定向为 STATE_DOZE(3) (消除硬件断电黑闪)");
                            param.args[0] = 3; // 3 = Display.STATE_DOZE
                        }
                    }
                }
            }
        };
        hookAll(cl, "com.android.server.display.DisplayPowerController", "setScreenState", screenStateHook);
        hookAll(cl, "com.android.server.display.DisplayPowerState", "setScreenState", screenStateHook);
        hookAll(cl, "com.android.server.display.DisplayPowerState", "prepareColorFade", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (sAsleep) {
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

    private static Handler mainHandler() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    private static volatile int sCachedFsAod = -1;
    private static volatile boolean sFsAodObserverRegistered;

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
                    log("ContentObserver: full_screen_aod_on 状态切换 -> " + fs + " (1=和锁屏样式一致/全屏AOD, 0=自定义/启航息屏)");
                    recompute(ctx);
                    if (sAodRoot != null) {
                        sweep(sAodRoot);
                    }
                }
            };
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("full_screen_aod_on"),
                    false, observer);
            sFsAodObserverRegistered = true;
            log("full_screen_aod_on ContentObserver 注册成功");
        } catch (Throwable t) {
            log("注册 full_screen_aod_on ContentObserver 异常: " + t);
        }
    }

    private static void onSleepIntent() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sAsleep && now - sSleepAtMs < 400) return;
        sAsleep = true;
        sSleepAtMs = now;
        propSet(PROP_SLEEP_AT, String.valueOf(now));
        propSet(PROP_WAKE_AT, "0");
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
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

        // 2.2 阻止锁屏联动动画把壁纸涂黑（彻底拦截：杜绝所有黑屏图层与动画，保持 Surface 可见）
        hookAll(cl, "com.android.keyguard.panel.KeyguardPanelViewController", "doWallpaperBlackAnim",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("doWallpaperBlackAnim -> 已完全拦截（杜绝系统黑屏动画图层）");
                        param.setResult(null);
                    }
                });

        // 2.3 保持 DozeServiceHostInjector 的 setDozeAfterScreenOff(false)，实现无黑闪零 OFF 联动
        hookAll(cl, "com.android.keyguard.injector.DozeServiceHostInjector", "updateDozeAfterScreenOff",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
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

        // 2.4 彻底拦截 DisplayStateShaderController 硬件淡黑遮罩与黑屏着色器指令（根除 80ms 黑闪核心链路）
        XC_MethodHook blockShaderFade = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
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
                // 阻断 needBlockBrightness=1 向 SF 传递，SurfaceFlinger 永不触发 block aod backlight 压制！
                log("DisplayStateShaderController.setLayerWithAOD -> 已拦截（阻断 needBlockBrightness）");
                param.setResult(null);
            }
        });

        // 2.5 仅在「对应场景启用了魅族时钟」时压制原生时钟透明度，杜绝双时钟叠加；
        //     关闭魅族时钟时必须放行，否则原生时钟（含分钟）会被整体归零而残缺。
        XC_MethodHook clockAlphaHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = sContext != null ? sContext : systemContext();
                if (isFullScreenAod(ctx)) {
                    return; // 全屏AOD下完全放行原生锁屏/息屏时钟透明度，由系统接管
                }
                boolean sleeping = isSleepingActive();
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

        log("SystemUI hooks 安装成功");
    }

    // =========================================================================
    // 3. AOD 模块通路：透出超级壁纸样式 + 彻底清空 AODView 黑色背景与 mask
    // =========================================================================

    private void hookAod(ClassLoader cl) {
        log("开始安装 AOD 模块 hooks...");

        // 3.1 拦截 DozeHost.disableShowWallpaper，阻止系统在息屏时通知 WM 关闭壁纸 Surface
        hookAll(cl, "com.miui.aod.DozeHost", "disableShowWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                log("DozeHost.disableShowWallpaper -> 已拦截（保持壁纸 Surface 显示）");
                param.setResult(null);
            }
        });

        // 3.2 伪装壁纸支持 AOD 环境模式 (isWallpaperSupportsAmbientMode -> true)
        hookAll(cl, "com.miui.aod.util.WallPaperUtils", "isWallpaperSupportsAmbientMode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        });

        // 3.3 伪装正在使用超级壁纸样式（防止 AOD 进入纯黑全屏模式；全屏AOD下放行原生设置）
        hookAll(cl, "com.miui.aod.Utils", "isUsingSuperWallpaperStyle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = sContext != null ? sContext : systemContext();
                if (isFullScreenAod(ctx)) {
                    return; // 全屏AOD(和锁屏样式一致)下放行系统原生判断
                }
                param.setResult(Boolean.TRUE);
            }
        });

        // 3.4 阻止 AODView 背景被设置成不透明纯黑（覆盖所有 setBackground 相关重载）
        XC_MethodHook clearBgHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
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
                try {
                    View v = (View) param.thisObject;
                    v.setBackground(null);
                    log("AODView.onReuseAodView 复用，已强制置空背景");
                } catch (Throwable ignored) {}
            }
        });

        // 3.5 拦截 AODView 的 mask 遮罩动画，防止全屏黑色 mask 压盖在壁纸上方
        XC_MethodHook blockMaskHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
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
                    v.setBackground(null);
                    sAodRoot = v.getRootView();
                    ensureClockReceiver(v.getContext());
                    if (v instanceof ViewGroup) {
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
                if (!isFullScreenAod(probeCtx)) {
                    armHomeSleepMask("scrim " + prev + "->" + state);
                }
            }
            startClockProbe("scrim=" + state);
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

        // 息屏下强制消除前置与后置 scrim 遮罩，杜绝纯黑覆盖
        if (sScrimMode == 0 || isSleepingActive()) {
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

    private static void recompute(Context ctx) {
        if (ctx == null) return;
        boolean doze = isDozeLike(ctx);
        boolean sleeping = isSleepingActive() || doze;
        boolean fsAod = isFullScreenAod(ctx);
        int mode;
        if (sleeping) {
            mode = fsAod ? 1 : 0; // 全屏AOD下壁纸保持锁屏全屏态(mode=1)，自定义样式下切启航息屏(mode=0)
        } else {
            mode = sScrimMode;
        }
        boolean sleepingChanged = (sleeping != sLastSleeping);
        sLastSleeping = sleeping;
        if (mode == sLastMode && !sleepingChanged) return;

        sLastMode = mode;
        log("-> mode=" + mode + " (scrim=" + sStateName + " doze=" + doze + " sleeping=" + sleeping + " fsAod=" + fsAod + ")");
        if (mode == 0 && "UNLOCKED".equals(sStateName)) {
            // 熄屏前馈比 KEYGUARD 场景早约 100ms 到达，提前遮住锁屏层
            if (!fsAod) {
                armHomeSleepMask("sleep from UNLOCKED");
            }
        } else if (mode != 0) {
            releaseHomeSleepMask("mode=" + mode);
        }
        startClockProbe("mode=" + mode);
        broadcastMode(ctx, mode, mode == 0 ? "doze" : (fsAod && sleeping ? "doze_fsaod" : (sStateName != null ? sStateName : "KEYGUARD")));
        updateClocksVisibility((mode == 0 || mode == 1) && !(fsAod && sleeping), mode);

        if (mode == 0) {
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

    private static void sweep(View v) {
        if (v == null) return;
        // 清理纯黑背景，使下层壁纸透出
        if (isDarkBackground(v)) {
            v.setBackground(null);
            log("已清除遮挡黑底: " + v.getClass().getSimpleName() + " id=0x" + Integer.toHexString(v.getId()));
        }

        boolean sleeping = isSleepingActive();
        Context ctx = sContext != null ? sContext : (v.getContext() != null ? v.getContext() : systemContext());
        boolean fsAod = isFullScreenAod(ctx);
        boolean customClockAod = !fsAod && isCustomClockEnabled(ctx, 0);
        boolean customClockLock = isCustomClockEnabled(ctx, 1);
        boolean customClock = sleeping ? customClockAod : customClockLock;

        String cls = v.getClass().getSimpleName();
        String idName = idNameOf(v);

        // 已注入的 Flyme 时钟处理
        if (v instanceof FlymeClockView || "qihang_flyme_clock".equals(v.getTag())) {
            if (!customClock || sLastMode == 2 || (fsAod && sleeping)) {
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
        // 息屏且关闭魅族时钟时同样交给系统：锁屏→息屏可沿用初期时钟，
        // 桌面→息屏则不会被模块抢先放出锁屏大时钟。
        if (isKeyguardClockShell(cls, idName)) {
            if (sleeping) {
                if (fsAod) {
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
            if (sleeping && fsAod) {
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
            if (sleeping) { // 息屏 AOD 态：确保 AOD 根容器保持可见
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1.0f);
            } else { // 唤醒锁屏态：隐匿 AOD 浮层
                v.setVisibility(View.INVISIBLE);
                v.setAlpha(0.0f);
            }
        } else if (isNativeAodClock(v)) {
            // 走到这里的一定是「AOD 专属装饰/部件」（彩带 color_ribbons_container、渐变、
            // 缩略图卡片、AOD 电池等）——锁屏时钟容器已在上面被分流，不会被误伤。
            // 非息屏态必须隐藏，否则这些装饰层会在锁屏上透出（表现为屏幕下方交织彩带）。
            if (sleeping) {
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
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if ((customClockAod && !fsAod) || customClockLock) {
                ensureFlymeClock(v);
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                sweep(g.getChildAt(i));
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
            if (c != null && c.isAttachedToWindow() && c.getVisibility() == View.VISIBLE) {
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
                    try {
                        ClockSettings.Config aod = ClockSettings.fromIntentAod(intent);
                        ClockSettings.Config lock = ClockSettings.fromIntentLock(intent);
                        ClockSettings.saveLocal(context, aod, lock);
                    } catch (Throwable ignored) {}
                    int currentMode = sLastMode >= 0 ? sLastMode : 0;
                    updateClocksVisibility(currentMode == 0 || currentMode == 1, currentMode);
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
                sFlymeClock = clock;
                registerClock(clock);
                clock.setClockMode(sLastMode >= 0 ? sLastMode : 0, false);
                log("FlymeClockView 成功注入容器: " + root.getClass().getSimpleName() + " (entry=" + entry + ")");
            } catch (Throwable t) {
                log("注入 FlymeClockView 失败: " + t);
            }
        } else if (existing instanceof FlymeClockView) {
            FlymeClockView clock = (FlymeClockView) existing;
            sFlymeClock = clock;
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
                sFlymeClock = clock;
                registerClock(clock);
                clock.setClockMode(sLastMode >= 0 ? sLastMode : 0, false);
                log("成功在 AODView 注入 FlymeClockView!");
            } catch (Throwable t) {
                log("注入 FlymeClockView 失败: " + t);
            }
        } else if (existing instanceof FlymeClockView) {
            FlymeClockView clock = (FlymeClockView) existing;
            sFlymeClock = clock;
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

    // ---- DIAG：16ms 时钟可见性探针（已验证通过，关闭探针） ----
    private static final boolean DIAG_PROBE = false;
    private static long sProbeUntil;
    private static String sProbeLast;

    private static void startClockProbe(String why) {
        if (!DIAG_PROBE) return;
        long now = android.os.SystemClock.uptimeMillis();
        boolean running = sProbeUntil > now;
        sProbeUntil = now + 1200;
        log("PROBE start " + why + " sleeping=" + isSleepingActive());
        if (running) return;
        sProbeLast = null;
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    View r = sAodRoot;
                    if (r != null) {
                        StringBuilder sb = new StringBuilder();
                        probeWalk(r, sb, 1.0f);
                        String s = sb.toString();
                        if (!s.equals(sProbeLast)) {
                            sProbeLast = s;
                            log("PROBE state=" + sStateName + " sleeping=" + isSleepingActive() + " :: " + s);
                        }
                    }
                } catch (Throwable t) {
                    log("PROBE err " + t);
                }
                if (android.os.SystemClock.uptimeMillis() < sProbeUntil) {
                    mainHandler().postDelayed(this, 16);
                }
            }
        });
    }

    private static void probeWalk(View v, StringBuilder sb, float parentAlpha) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        float a = parentAlpha * v.getAlpha() * v.getTransitionAlpha();
        if (a < 0.01f) return;
        String id = idNameOf(v);
        String cls = v.getClass().getSimpleName();
        boolean clocky = (id != null && id.toLowerCase().contains("clock"))
                || cls.contains("Clock") || cls.contains("Keyguard");
        if (clocky && v.getWidth() > 0 && v.getHeight() > 0) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            sb.append(cls).append('/').append(id)
                    .append(" a=").append(String.format(java.util.Locale.US, "%.2f", a))
                    .append(" y=").append(loc[1]).append(" h=").append(v.getHeight())
                    .append("; ");
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                probeWalk(g.getChildAt(i), sb, a);
            }
        }
    }

    /** 只有这些 ScrimState 代表锁屏主体仍在显示，可由模块补回完整的原生时/分容器。 */
    private static boolean isNativeKeyguardClockScene() {
        if (sStateName == null) return false;
        String state = sStateName.toUpperCase();
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
            return st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND || st == Display.STATE_OFF;
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

    private static void broadcastMode(Context ctx, int mode, String state) {
        Intent i = new Intent(ACTION_MODE);
        i.setPackage(PKG_SELF);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_ANIMATE, true);
        i.putExtra(EXTRA_STATE, state);
        sendBroadcastSmart(ctx, i);
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
