# -*- coding: utf-8 -*-

system_ui_hook = '''package com.qihang.aod.hook;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;

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

        // 2.5 熄屏态 (AOD/物理黑屏) 下压制原生锁屏时钟透明度为 0，杜绝息屏下锁屏时钟残留
        XC_MethodHook clockAlphaHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = StateCoordinator.sContext != null ? StateCoordinator.sContext : StateCoordinator.systemContext();
                boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;
                if (sleeping && !StateCoordinator.isFullScreenAod(ctx)) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Float) {
                        param.args[0] = 0.0f;
                    }
                }
            }
        };
        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.ClockBaseAnimation", "updateTimeViewAlpha", clockAlphaHook);
        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.allinone.AllInOneClockAnimation", "updateTimeViewAlpha", clockAlphaHook);

        // 2.6 伪装 SystemUI 正在使用超级壁纸样式 (触发系统原生 aodSuperWallpaperViewAlphaAnim 将锁屏时钟淡出至 0)
        XC_MethodHook superWallpaperHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = StateCoordinator.sContext != null ? StateCoordinator.sContext : StateCoordinator.systemContext();
                if (StateCoordinator.isAodEnabled(ctx) && !StateCoordinator.isFullScreenAod(ctx)) {
                    param.setResult(Boolean.TRUE);
                }
            }
        };
        HookUtils.hookAll(cl, "com.miui.keyguard.KeyguardCommonSettingObserver", "getAodUsingSuperWallpaperStyle", superWallpaperHook);

        // 2.7 杜绝息屏态下原生锁屏时钟 (TimeView) 绘制，精确识别锁屏父容器，杜绝双时钟重叠
        XC_MethodHook suppressTimeViewDrawHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;
                if (!sleeping) return;
                View v = (View) param.thisObject;
                ViewParent p = v.getParent();
                while (p instanceof View) {
                    View pv = (View) p;
                    String id = StateCoordinator.idNameOf(pv);
                    String cls = pv.getClass().getName();
                    if ("miui_keyguard_clock_container".equals(id)
                            || "miui_keyguard_foreground_clock_container".equals(id)
                            || "keyguard_panel_view".equals(id)
                            || cls.contains("AllInOneHourClock")
                            || cls.contains("AllInOneMinuteClock")
                            || cls.contains("KeyguardClockContainer")) {
                        param.setResult(null); // 息屏态下彻底跳过锁屏 TimeView 绘制
                        return;
                    }
                    if ("aod_root_view".equals(id) || cls.contains("AODView") || cls.contains("AllInOneSingleClock")) {
                        return; // 原生 AOD 时钟放行正常绘制并支持防烧屏位移
                    }
                    p = pv.getParent();
                }
            }
        };
        HookUtils.hookAll(cl, "com.miui.clock.allInOne.TimeView", "onDraw", suppressTimeViewDrawHook);

        // 2.8 拦截 KeyguardClockContainer 的 visibility 与 alpha 设置
        XC_MethodHook blockLockClockVisible = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;
                if (sleeping && param.args != null && param.args.length > 0) {
                    param.args[0] = View.INVISIBLE;
                }
            }
        };
        HookUtils.hookAll(cl, "com.android.keyguard.clock.KeyguardClockContainer", "setVisibility", blockLockClockVisible);

        XC_MethodHook blockLockClockAlpha = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;
                if (sleeping && param.args != null && param.args.length > 0) {
                    param.args[0] = 0.0f;
                }
            }
        };
        HookUtils.hookAll(cl, "com.android.keyguard.clock.KeyguardClockContainer", "setAlpha", blockLockClockAlpha);
        HookUtils.hookAll(cl, "com.android.keyguard.clock.KeyguardClockContainer", "updateAlpha", blockLockClockAlpha);

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
                        if (mode == 1 || mode == 2 || mode == 6) {
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

        HookUtils.log("SystemUI hooks 安装成功");
    }
}
'''

view_tree_sweeper = '''package com.qihang.aod.hook;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.qihang.aod.FlymeClockView;

import de.robv.android.xposed.XposedBridge;

/**
 * 视图层渲染巡查器
 * 全面启用系统原生时钟架构，负责：
 * 1. 发现并清理历史残留或误注入的 FlymeClockView，将其彻底设为 GONE；
 * 2. 消除 AOD 容器的纯黑不透明背景，确保底层 Alive 壁纸 Surface 透显；
 * 3. 在息屏 AOD 态下压制原生锁屏时钟容器 (keyguard_panel_view、miui_keyguard_clock_container、miui_keyguard_foreground_clock_container)，
 *    使其 INVISIBLE 且 alpha=0，杜绝与 AOD 联动原生时钟重叠；
 * 4. 在亮屏/锁屏/桌面态下恢复锁屏容器 VISIBLE 且 alpha=1.0，保证锁屏时钟原生呈现。
 */
public final class ViewTreeSweeper {

    private static Runnable sSweep;

    private ViewTreeSweeper() {}

    public static void sweep(View root) {
        if (root == null) return;
        boolean aodEnabled = StateCoordinator.isAodEnabled(root.getContext());
        sweepInternal(root, aodEnabled);
    }

    public static void startSweep(View root, final boolean isAod) {
        if (root != null) {
            StateCoordinator.sAodRoot = root.getRootView();
        }
        if (sSweep != null) return;

        sSweep = new Runnable() {
            private int count = 0;
            @Override
            public void run() {
                try {
                    View r = StateCoordinator.sAodRoot;
                    if (r != null) {
                        sweep(r);
                    }
                    count++;
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                if (count < (isAod ? 20 : 10)) {
                    StateCoordinator.mainHandler().postDelayed(this, isAod ? 100 : 200);
                } else {
                    sSweep = null;
                }
            }
        };
        StateCoordinator.mainHandler().post(sSweep);
    }

    public static void stopSweep() {
        if (sSweep != null) {
            StateCoordinator.mainHandler().removeCallbacks(sSweep);
            sSweep = null;
        }
    }

    private static void sweepInternal(View v, boolean aodEnabled) {
        if (v == null) return;

        // 1. 若检测到注入的 FlymeClockView，彻底隐藏，绝不与原生时钟发生重叠
        if (v instanceof FlymeClockView || "qihang_flyme_clock".equals(v.getTag())) {
            v.setVisibility(View.GONE);
            return;
        }

        // 2. 清除 AOD 容器的纯黑不透明背景，确保底层 Alive 动态壁纸透显
        if (aodEnabled && isDarkBackground(v)) {
            v.setBackground(null);
        }

        // 3. 息屏与锁屏时钟的显隐精准协同：
        //    AOD 态时压制锁屏时钟容器，杜绝与 AOD 层的 AllInOneSingleClock 发生双时钟重叠；
        //    亮屏锁屏态时立即恢复锁屏时钟容器。
        String idName = StateCoordinator.idNameOf(v);
        if ("miui_keyguard_clock_container".equals(idName)
                || "miui_keyguard_foreground_clock_container".equals(idName)
                || "keyguard_panel_view".equals(idName)) {
            boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;
            if (sleeping && aodEnabled) {
                if (v.getVisibility() != View.INVISIBLE) {
                    v.setVisibility(View.INVISIBLE);
                }
                if (v.getAlpha() != 0.0f) {
                    v.setAlpha(0.0f);
                }
            } else {
                if (v.getVisibility() != View.VISIBLE) {
                    v.setVisibility(View.VISIBLE);
                }
                if (v.getAlpha() < 1.0f) {
                    v.setAlpha(1.0f);
                }
            }
        }

        // 4. 递归遍历子节点
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sweepInternal(g.getChildAt(i), aodEnabled);
            }
        }
    }

    public static void ensureFlymeClock(View v) {
        if (v instanceof ViewGroup) {
            View existing = ((ViewGroup) v).findViewWithTag("qihang_flyme_clock");
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
        }
    }

    public static void ensureFlymeClockInAod(ViewGroup parent) {
        if (parent != null) {
            View existing = parent.findViewWithTag("qihang_flyme_clock");
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
        }
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
}
'''

with open('D:/ButtonHyperAlive/HyperAlive-debug/app/src/main/java/com/qihang/aod/hook/SystemUiHook.java', 'w', encoding='utf-8') as f:
    f.write(system_ui_hook)

with open('D:/ButtonHyperAlive/HyperAlive-debug/app/src/main/java/com/qihang/aod/hook/ViewTreeSweeper.java', 'w', encoding='utf-8') as f:
    f.write(view_tree_sweeper)

print('Both SystemUiHook.java and ViewTreeSweeper.java written successfully!')
