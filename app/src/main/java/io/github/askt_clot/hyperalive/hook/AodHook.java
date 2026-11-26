package io.github.askt_clot.hyperalive.hook;

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * AOD 模块 (com.miui.aod) 拦截管线：
 * 统管超级壁纸样式伪装、清空 AODView 黑色背景/遮罩，以及在 AODView 挂载时注入 Flyme 时钟。
 */
public final class AodHook {

    private AodHook() {}

    public static void install(ClassLoader cl) {
        installClockPositionGate(cl);
        HookUtils.log("开始安装 AOD 模块 hooks...");
        SystemUiHook.installClockDrawGate(cl);

        // 3.1 拦截 DozeHost.disableShowWallpaper
        HookUtils.hookAll(cl, "com.miui.aod.DozeHost", "disableShowWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isAodPolicyActiveFast()) return;
                HookUtils.log("DozeHost.disableShowWallpaper -> 已拦截（保持壁纸 Surface 显示）");
                param.setResult(null);
            }
        });

        // 3.2 伪装壁纸支持 AOD 环境模式 (isWallpaperSupportsAmbientMode -> true)
        HookUtils.hookAll(cl, "com.miui.aod.util.WallPaperUtils", "isWallpaperSupportsAmbientMode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isAodPolicyActiveFast()) return;
                param.setResult(Boolean.TRUE);
            }
        });

        // 3.3 伪装正在使用超级壁纸样式
        HookUtils.hookAll(cl, "com.miui.aod.Utils", "isUsingSuperWallpaperStyle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isAodPolicyActiveFast() || StateCoordinator.isFullScreenAodFast()) {
                    return;
                }
                param.setResult(Boolean.TRUE);
            }
        });

        // 联动时钟动画进行期间隔离静态 AOD 时钟；动画完成后或非联动息屏才放行
        HookUtils.hookAll(cl, "com.miui.aod.doze.DozeServicePluginImpl", "dealWithDoAnimationToAodMsg", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isLockWallpaperActiveFast()) return;
                try {
                    StateCoordinator.onLinkageAnimStarted("dealWithDoAnimationToAodMsg");
                } catch (Throwable t) {
                    HookUtils.log("dealWithDoAnimationToAodMsg hook 异常: " + t);
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.aod.doze.DozeServicePluginImpl", "dealWithDoAnimationToAodFinishMsg", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isLockWallpaperActiveFast()) return;
                try {
                    StateCoordinator.onLinkageAnimFinished("dealWithDoAnimationToAodFinishMsg");
                } catch (Throwable t) {
                    HookUtils.log("dealWithDoAnimationToAodFinishMsg hook 异常: " + t);
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.aod.DozeHost", "onCommand", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isLockWallpaperActiveFast()) return;
                try {
                    if (param.args != null && param.args.length >= 2
                            && "clock_show".equals(param.args[0]) && param.args[1] instanceof Integer) {
                        int state = (Integer) param.args[1];
                        if (state == 0) {
                            StateCoordinator.onLinkageAnimStarted("DozeHost.onCommand clock_show=0");
                        } else if (state == 1) {
                            StateCoordinator.onLinkageAnimFinished("DozeHost.onCommand clock_show=1");
                        }
                    }
                } catch (Throwable t) {
                    HookUtils.log("DozeHost.onCommand hook 异常: " + t);
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.aod.AODView", "setClockViewVisible", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (!StateCoordinator.isLockWallpaperActiveFast()) return;
                if (StateCoordinator.isLinkageAnimating()) {
                    param.args[0] = Boolean.FALSE;
                    return;
                }
                if (ViewTreeSweeper.isClockSleeping(v.getContext())
                        && StateCoordinator.isAodEnabled(v.getContext())
                        && !StateCoordinator.isFullScreenAod(v.getContext())
                        && !StateCoordinator.isCustomClockEnabled(v.getContext(), 0)
                        && param.args.length > 0 && param.args[0] instanceof Boolean) {
                    param.args[0] = Boolean.TRUE;
                }
            }
        });

        // AODView 复用/挂载生命周期可逆背景同步（若在 SystemUI base ClassLoader 不可见，则在 ViewTreeSweeper 首次发现真实插件类时动态补挂）
        Class<?> aodCls = HookUtils.findClass("com.miui.aod.AODView", cl);
        if (aodCls != null) {
            installHostLifecycleHooks(aodCls);
        }

        // 3.5 拦截 AODView 的 mask 遮罩动画
        XC_MethodHook blockMaskHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isAodPolicyActiveFast()) return;
                HookUtils.log("AODView mask hook -> 已拦截黑色遮罩");
                param.setResult(null);
            }
        };
        HookUtils.hookAll(cl, "com.miui.aod.AODView", "initMaskView", blockMaskHook);
        HookUtils.hookAll(cl, "com.miui.aod.AODView", "startMaskAnimation", blockMaskHook);
        HookUtils.hookAll(cl, "com.miui.aod.DozeHost", "initMaskViewIfNeeded", blockMaskHook);
        HookUtils.hookAll(cl, "com.miui.aod.DozeHost", "startMaskAnimationIfNeeded", blockMaskHook);

        HookUtils.log("AOD 模块 hooks 安装完成");
    }
    private static final HashMap<Class<?>, Method> sVisibilityMethods = new HashMap<>();
    private static final Set<Method> sVisibilityHooks = new HashSet<>();
    private static final Set<Class<?>> sHostLifecycleHooked = new HashSet<>();
    private static final WeakHashMap<View, Long> sRevealedAtSleep = new WeakHashMap<>();

    public static void installHostLifecycleHooks(View view) {
        if (view == null) return;
        installHostLifecycleHooks(view.getClass());
    }

    private static void installHostLifecycleHooks(Class<?> cls) {
        if (cls == null) return;
        String name = cls.getSimpleName();
        if (!("AODView".equals(name) || "AodView".equals(name) || "AodContainerView".equals(name))) {
            return;
        }
        synchronized (AodHook.class) {
            if (!sHostLifecycleHooked.add(cls)) return;
        }
        int reuseCount = HookUtils.hookAllMethods(cls, "onReuseAodView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                try {
                    ViewTreeSweeper.onAodHostReused(v, "hook_onReuseAodView");
                } catch (Throwable ignored) {}
            }
        });
        int attachCount = HookUtils.hookAllMethods(cls, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    StateCoordinator.updateAodRoot(v);
                    StateCoordinator.ensureClockReceiver(v.getContext());
                    ViewTreeSweeper.onAodHostAttached(v, "hook_onAttachedToWindow");
                    if (!StateCoordinator.isLockWallpaperActiveFast()) {
                        return;
                    }
                    if (v instanceof ViewGroup && StateCoordinator.isAodEnabled(v.getContext())) {
                        ViewTreeSweeper.ensureFlymeClockInAod((ViewGroup) v);
                    }
                    ViewTreeSweeper.startSweep(StateCoordinator.sAodRoot, true);
                    HookUtils.log("AODView.onAttachedToWindow 已触发可逆背景抑制与时钟挂载");
                } catch (Throwable t) {
                    HookUtils.log("AODView.onAttachedToWindow hook 异常: " + t);
                }
            }
        });
        if (AodHostBackgroundTracker.isTraceEnabled()) {
            HookUtils.log("[AOD_BG_TRACE] HOOK_AOD_CLASS up=" + android.os.SystemClock.uptimeMillis()
                    + " nano=" + System.nanoTime()
                    + " cls=" + cls.getName()
                    + " reuse=" + reuseCount
                    + " attach=" + attachCount);
        }
    }

    /** Dynamic AOD dex loaders are only known after the real view is inflated. */
    public static synchronized void installVisibilityGate(final View view) {
        if (view == null) return;
        installHostLifecycleHooks(view);
        Class<?> cls = view.getClass();
        if (sVisibilityMethods.containsKey(cls)) return;
        String name = cls.getSimpleName();
        if (!("AODView".equals(name) || "AodContainerView".equals(name)
                || name.contains("LinkageStyleClockView"))) return;
        installClockPositionGate(cls.getClassLoader());
        Method visible = null;
        try {
            visible = cls.getDeclaredMethod("setClockViewVisible", boolean.class);
            visible.setAccessible(true);
            if (sVisibilityHooks.add(visible)) {
                XposedBridge.hookMethod(visible, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (!StateCoordinator.isLockWallpaperActiveFast()) return;
                        if (StateCoordinator.isLinkageAnimating()) {
                            param.args[0] = Boolean.FALSE;
                            return;
                        }
                        if (nativeAodActive(v)) param.args[0] = Boolean.TRUE;
                    }
                });
                HookUtils.log("Runtime AOD visibility gate installed: " + cls.getName());
            }
        } catch (Throwable t) { HookUtils.log("Runtime AOD visibility gate unavailable: " + cls.getName(), t); }
        sVisibilityMethods.put(cls, visible);
    }

    private static boolean nativeAodActive(View view) {
        Context ctx = view.getContext();
        return ViewTreeSweeper.isClockSleeping(ctx) && StateCoordinator.isAodEnabled(ctx)
                && !StateCoordinator.isFullScreenAod(ctx) && !StateCoordinator.isCustomClockEnabled(ctx, 0);
    }

    private static final Set<Class<?>> sClockPositionControllers = new HashSet<>();

    /** The outgoing keyguard is already moving to the AOD position. Put the incoming
     * AOD clock at that same endpoint before its native fade starts. */
    static synchronized void installClockPositionGate(ClassLoader loader) {
        Class<?> controller = HookUtils.findClass("com.miui.aod.linkagestyle.LinkageStyleController", loader);
        if (controller == null || !sClockPositionControllers.add(controller)) return;
        HookUtils.hookMethod(controller, "aodLinkageTransAnimation", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isLockWallpaperActiveFast() || !Boolean.TRUE.equals(param.args[4])) return;
                View clock = (View) param.args[0];
                if (clock == null || !"clock_container".equals(StateCoordinator.idNameOf(clock))
                        || !nativeAodActive(clock) || StateCoordinator.isCustomClockEnabled(clock.getContext(), 1)) return;
                float from = (Float) param.args[1];
                float to = (Float) param.args[2];
                if (Float.isFinite(from) && Float.isFinite(to)) {
                    NativeClockHandoff.setDestination(clock, to);
                    if (from != to) {
                        param.args[1] = to;
                        HookUtils.log("Native AOD clock aligned before fade: " + from + " -> " + to);
                    }
                }
            }
        }, View.class, float.class, float.class, AnimatorListenerAdapter.class, boolean.class);
    }

    /** Repair an already delivered clock_show=false once per sleep/config activation. */
    public static void ensureNativeAodVisible(View view, boolean active, long cycle) {
        if (view == null || !StateCoordinator.isLockWallpaperActiveFast()) return;
        installVisibilityGate(view);
        Method method;
        synchronized (AodHook.class) {
            if (!active || StateCoordinator.isLinkageAnimating()) {
                sRevealedAtSleep.remove(view);
                return;
            }
            method = sVisibilityMethods.get(view.getClass());
            Long last = sRevealedAtSleep.get(view);
            if (method == null || (last != null && last.longValue() == cycle)) return;
        }
        try {
            method.invoke(view, true);
            // AODView caches the flag: updateClockViewVisible also reaches a newly replaced style.
            if ("AODView".equals(view.getClass().getSimpleName())) {
                Method update = view.getClass().getDeclaredMethod("updateClockViewVisible");
                update.setAccessible(true);
                update.invoke(view);
            }
            synchronized (AodHook.class) {
                sRevealedAtSleep.put(view, cycle);
            }
            HookUtils.log("Native AOD clock revealed: " + view.getClass().getSimpleName());
        } catch (Throwable t) { HookUtils.log("Native AOD reveal not ready: " + view.getClass().getSimpleName(), t); }
    }

}
