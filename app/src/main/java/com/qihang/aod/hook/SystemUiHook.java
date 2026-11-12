package com.qihang.aod.hook;

import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * SystemUI (含 miui.systemui.plugin) 拦截管线：
 * 统管 ScrimController 场景感知、硬件淡黑遮罩拦截、原生时钟透明度压制与屏下指纹图标 (FOD) 显隐联动。
 */
public final class SystemUiHook {

    private SystemUiHook() {}

    public static void install(ClassLoader cl) {
        HookUtils.log("开始安装 SystemUI hooks...");

        // 2.1 ScrimController 场景与遮罩透出
        HookUtils.hookMethod(
                HookUtils.findClass("com.android.systemui.statusbar.phone.ScrimController", cl),
                "updateScrims",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            StateCoordinator.onScrim(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                }
        );

        // 2.2 阻止锁屏联动动画把壁纸涂黑
        HookUtils.hookAll(cl, "com.android.keyguard.panel.KeyguardPanelViewController", "doWallpaperBlackAnim",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!StateCoordinator.isAodEnabled(StateCoordinator.sContext)) return;
                        HookUtils.log("doWallpaperBlackAnim -> 已完全拦截（杜绝系统黑屏动画图层）");
                        param.setResult(null);
                    }
                });

        // 2.3 保持 DozeServiceHostInjector 的 setDozeAfterScreenOff(false)
        HookUtils.hookAll(cl, "com.android.keyguard.injector.DozeServiceHostInjector", "updateDozeAfterScreenOff",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!StateCoordinator.isAodEnabled(StateCoordinator.sContext)) return;
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

        // 2.4 彻底拦截 DisplayStateShaderController 硬件淡黑遮罩与黑屏着色器指令
        XC_MethodHook blockShaderFade = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isAodEnabled(StateCoordinator.sContext)) return;
                HookUtils.log("DisplayStateShaderController." + param.method.getName() + " -> 已彻底拦截（阻断 SurfaceFlinger 硬件淡黑着色器）");
                if ("showScreenFadeSurface".equals(param.method.getName())) {
                    param.setResult(Boolean.FALSE);
                } else {
                    param.setResult(null);
                }
            }
        };
        HookUtils.hookAll(cl, "com.android.keyguard.screenfade.DisplayStateShaderController", "showScreenFadeSurface", blockShaderFade);
        HookUtils.hookAll(cl, "com.android.keyguard.screenfade.DisplayStateShaderController", "setScreenFadeToSf", blockShaderFade);
        HookUtils.hookAll(cl, "com.android.keyguard.screenfade.DisplayStateShaderController", "setLayerWithAOD", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isAodEnabled(StateCoordinator.sContext)) return;
                HookUtils.log("DisplayStateShaderController.setLayerWithAOD -> 已拦截（阻断 needBlockBrightness）");
                param.setResult(null);
            }
        });

        installClockDrawGate(cl);

        // 2.9 监听屏下指纹图标 (GXZW / FOD) 显隐
        try {
            XC_MethodHook fodHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String mName = param.method.getName();
                        boolean visible;
                        if ("onGxzwIconChanged".equals(mName)) {
                            boolean transparent = (boolean) param.args[0];
                            visible = !transparent;
                        } else if ("dismissFingerpirntIcon".equals(mName) || "dismissGxzwIconView".equals(mName)) {
                            visible = false;
                        } else if ("setGxzwIconOpaque".equals(mName)) {
                            visible = true;
                        } else {
                            return;
                        }
                        HookUtils.log("FOD 指纹图标状态变化: " + mName + " -> visible=" + visible);
                        StateCoordinator.broadcastFodVisible(StateCoordinator.sContext, visible);
                    } catch (Throwable t) {
                        HookUtils.log("FOD 指纹图标状态回调异常: " + t);
                    }
                }
            };

            HookUtils.hookAll(cl, "com.android.keyguard.injector.DozeServiceHostInjector", "onGxzwIconChanged", fodHook);
            HookUtils.hookAll(cl, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView", "dismissFingerpirntIcon", fodHook);
            HookUtils.hookAll(cl, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView", "setGxzwIconOpaque", fodHook);
            HookUtils.hookAll(cl, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView", "dismissGxzwIconView", fodHook);
            HookUtils.log("FOD 指纹图标显隐 Hook 挂载成功");
        } catch (Throwable t) {
            HookUtils.log("挂载 FOD 指纹图标 Hook 异常: " + t);
        }

        // 2.10 监听生物识别直接唤醒并解锁 (BiometricUnlockController)，抢跑通知 StateCoordinator 零延迟进入桌面
        try {
            XC_MethodHook bioUnlockHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int mode = -1;
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                            mode = (Integer) param.args[0];
                        }
                        HookUtils.log("BiometricUnlockController.startWakeAndUnlock mode=" + mode);
                        if (mode == 1 || mode == 2 || mode == 5 || mode == 6 || mode == 7) {
                            StateCoordinator.onBiometricWakeIntent();
                        }
                    } catch (Throwable t) {
                        HookUtils.log("BiometricUnlockController Hook 回调异常: " + t);
                    }
                }
            };
            HookUtils.hookAll(cl, "com.android.systemui.statusbar.phone.BiometricUnlockController", "startWakeAndUnlock", bioUnlockHook);
            HookUtils.log("BiometricUnlockController Hook 挂载成功");
        } catch (Throwable t) {
            HookUtils.log("挂载 BiometricUnlockController Hook 异常: " + t);
        }

        // 2.11 拦截锁屏到 AOD 联动时钟动画的启动与完成握手
        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.ClockAnimTracker", "onCancel",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        sCancelDepth.set(sCancelDepth.get() + 1);
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int depth = sCancelDepth.get();
                        sCancelDepth.set(depth > 1 ? depth - 1 : 0);
                    }
                });

        HookUtils.hookAll(cl, "com.android.keyguard.injector.DozeServiceHostInjector", "sendCommand",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length >= 2
                                    && param.args[0] instanceof Integer && "clock_show".equals(param.args[1])) {
                                int state = (Integer) param.args[0];
                                if (state == 0) {
                                    StateCoordinator.onLinkageAnimStarted("sendCommand clock_show=0");
                                } else if (state == 1) {
                                    if (StateCoordinator.isLinkageAnimating() && isSynchronousCancelOrSetup()) {
                                        HookUtils.log("sendCommand clock_show=1 ignored (synchronous cancel/setup during linkage)");
                                        return;
                                    }
                                    StateCoordinator.onLinkageAnimFinished("sendCommand clock_show=1");
                                }
                            }
                        } catch (Throwable t) {
                            HookUtils.log("DozeServiceHostInjector.sendCommand hook 异常: " + t);
                        }
                    }
                });

        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.AnimationHelper", "notifyPluginAnimationToAodFinished",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (StateCoordinator.isLinkageAnimating() && isSynchronousCancelOrSetup()) {
                            HookUtils.log("notifyPluginAnimationToAodFinished blocked (synchronous cancel/setup during linkage)");
                            param.setResult(null);
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (StateCoordinator.isLinkageAnimating() && isSynchronousCancelOrSetup()) {
                            return;
                        }
                        StateCoordinator.onLinkageAnimFinished("notifyPluginAnimationToAodFinished");
                    }
                });

        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.ClockBaseAnimation", "doAnimationToAod",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length == 5) {
                                boolean toAod = Boolean.TRUE.equals(param.args[0]);
                                boolean fromKeyguard = Boolean.TRUE.equals(param.args[2]);
                                boolean needAnim = Boolean.TRUE.equals(param.args[3]);
                                if (toAod && fromKeyguard && needAnim) {
                                    sDoAnimationDepth.set(sDoAnimationDepth.get() + 1);
                                    param.setObjectExtra("tracked_5arg", Boolean.TRUE);
                                    StateCoordinator.onLinkageAnimStarted("ClockBaseAnimation.doAnimationToAod (5-arg)");
                                } else if (!toAod) {
                                    StateCoordinator.onLinkageAnimFinished("ClockBaseAnimation.doAnimationToAod (toAod=false)");
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getObjectExtra("tracked_5arg"))) {
                            int depth = sDoAnimationDepth.get();
                            sDoAnimationDepth.set(depth > 1 ? depth - 1 : 0);
                        }
                    }
                });

        HookUtils.log("SystemUI hooks 安装成功");
    }

    private static final ThreadLocal<Integer> sDoAnimationDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };
    private static final ThreadLocal<Integer> sCancelDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };

    private static boolean isSynchronousCancelOrSetup() {
        return sDoAnimationDepth.get() > 0 || sCancelDepth.get() > 0;
    }

    private static final java.util.Set<java.lang.reflect.Method> sClockDrawMethods =
            new java.util.HashSet<java.lang.reflect.Method>();

    /** MIUIAod may be loaded by the plugin loader as well as its own package loader. */
    public static synchronized void installClockDrawGate(ClassLoader cl) {
        Class<?> clazz = HookUtils.findClass("com.miui.clock.allInOne.TimeView", cl);
        if (clazz == null) return;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"onDraw".equals(method.getName()) || sClockDrawMethods.contains(method)) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (ViewTreeSweeper.shouldSuppressNative((View) param.thisObject)) {
                            param.setObjectExtra("suppressed", Boolean.TRUE);
                            param.setResult(null);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getObjectExtra("suppressed"))) return;
                        if (param.hasThrowable()) return;
                        ViewTreeSweeper.onNativeClockDrawn((View) param.thisObject);
                    }
                });
                sClockDrawMethods.add(method);
                HookUtils.log("Clock draw gate installed: " + clazz + " loader=" + cl);
            } catch (Throwable t) { HookUtils.log("Clock draw hook failed", t); }
        }
    }

}
