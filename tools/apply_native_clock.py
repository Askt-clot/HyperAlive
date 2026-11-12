import os

# 1. Update ClockSettings.java
clock_settings_path = r'D:\ButtonHyperAlive\HyperAlive-debug\app\src\main\java\com\qihang\aod\ClockSettings.java'
with open(clock_settings_path, 'r', encoding='utf-8', errors='ignore') as f:
    cs_content = f.read()

# Replace enabled = true with enabled = false
cs_content = cs_content.replace(
    'public boolean enabled = true;',
    'public boolean enabled = false; // 全面使用系统原生时钟（false = 系统原生时钟）'
)
cs_content = cs_content.replace(
    'sp.getBoolean(KEY_CLOCK_ENABLED, true)',
    'sp.getBoolean(KEY_CLOCK_ENABLED, false)'
)
cs_content = cs_content.replace(
    'b.getBoolean(KEY_CLOCK_ENABLED, true)',
    'b.getBoolean(KEY_CLOCK_ENABLED, false)'
)
cs_content = cs_content.replace(
    'public static boolean isClockEnabled(Context ctx, int mode) {\n        return get(ctx, mode).enabled;\n    }',
    'public static boolean isClockEnabled(Context ctx, int mode) {\n        return false; // 全面使用系统原生时钟\n    }'
)

with open(clock_settings_path, 'w', encoding='utf-8') as f:
    f.write(cs_content)
print('ClockSettings.java updated.')

# 2. Update ViewTreeSweeper.java
sweeper_path = r'D:\ButtonHyperAlive\HyperAlive-debug\app\src\main\java\com\qihang\aod\hook\ViewTreeSweeper.java'
sweeper_code = '''package com.qihang.aod.hook;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.qihang.aod.FlymeClockView;

import de.robv.android.xposed.XposedBridge;

/**
 * 视图层渲染巡查器
 * 当前已全面启用系统原生时钟，杜绝“偷梁换柱”注入导致的双时钟与防烧屏错位。
 * 本巡查器仅负责：
 * 1. 发现并清理历史残留或误注入的 FlymeClockView，将其彻底设为 GONE；
 * 2. 消除 AOD 下不透明深黑背景，确保底层 Alive 壁纸 Surface 透显；
 * 3. 绝不对系统原生时钟（KeyguardClockContainer、AllInOneSingleClock、LinkageStyleClockView 等）进行任何压制或隐藏。
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

        // 3. 递归遍历子节点，原生时钟容器原封不动保留，遵循系统原生调度
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sweepInternal(g.getChildAt(i), aodEnabled);
            }
        }
    }

    public static void ensureFlymeClock(View v) {
        // 全面启用原生时钟，不再向任何视图容器注入 FlymeClockView
        if (v instanceof ViewGroup) {
            View existing = ((ViewGroup) v).findViewWithTag("qihang_flyme_clock");
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
        }
    }

    public static void ensureFlymeClockInAod(ViewGroup parent) {
        // 全面启用原生时钟，不再向 AODView 注入 FlymeClockView
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
with open(sweeper_path, 'w', encoding='utf-8') as f:
    f.write(sweeper_code)
print('ViewTreeSweeper.java updated.')

# 3. Update AodHook.java
aod_hook_path = r'D:\ButtonHyperAlive\HyperAlive-debug\app\src\main\java\com\qihang\aod\hook\AodHook.java'
with open(aod_hook_path, 'r', encoding='utf-8', errors='ignore') as f:
    aod_content = f.read()

# Replace ensureFlymeClockInAod call in onAttachedToWindow
aod_content = aod_content.replace(
'''                    if (v instanceof ViewGroup && StateCoordinator.isAodEnabled(v.getContext())) {
                        ViewTreeSweeper.ensureFlymeClockInAod((ViewGroup) v);
                    }
                    ViewTreeSweeper.startSweep(StateCoordinator.sAodRoot, true);
                    HookUtils.log("AODView.onAttachedToWindow 完成透传时钟挂载");''',
'''                    ViewTreeSweeper.startSweep(StateCoordinator.sAodRoot, true);
                    HookUtils.log("AODView.onAttachedToWindow 背景透明化完成（原生时钟模式）");'''
)
with open(aod_hook_path, 'w', encoding='utf-8') as f:
    f.write(aod_content)
print('AodHook.java updated.')

# 4. Update SystemUiHook.java
sysui_hook_path = r'D:\ButtonHyperAlive\HyperAlive-debug\app\src\main\java\com\qihang\aod\hook\SystemUiHook.java'
with open(sysui_hook_path, 'r', encoding='utf-8', errors='ignore') as f:
    sysui_content = f.read()

# Remove clockAlphaHook definition and usage
old_clock_alpha = '''        // 2.5 仅在「应用个性化时钟」或「关闭息屏显示且处于黑屏态」时压制原生时钟透明度
        XC_MethodHook clockAlphaHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = StateCoordinator.sContext != null ? StateCoordinator.sContext : StateCoordinator.systemContext();
                boolean sleeping = StateCoordinator.isSleepingActive();
                boolean aodEnabled = StateCoordinator.isAodEnabled(ctx);
                if (sleeping && !aodEnabled) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Float) {
                        param.args[0] = 0.0f;
                    }
                    return;
                }
                if (StateCoordinator.isFullScreenAod(ctx)) {
                    return;
                }
                boolean customLock = StateCoordinator.isCustomClockEnabled(ctx, 1);
                boolean customAod = StateCoordinator.isCustomClockEnabled(ctx, 0);
                boolean override = sleeping ? customAod : customLock;
                if (override) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Float) {
                        param.args[0] = 0.0f;
                    }
                }
            }
        };
        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.ClockBaseAnimation", "updateTimeViewAlpha", clockAlphaHook);
        HookUtils.hookAll(cl, "com.android.keyguard.clock.animation.allinone.AllInOneClockAnimation", "updateTimeViewAlpha", clockAlphaHook);'''

new_clock_alpha = '''        // 2.5 全面使用系统原生时钟：不再强行压制 updateTimeViewAlpha 为 0.0f，
        // 彻底释放原生时钟生命周期，让系统官方时钟正常跑 HyperOS 淡入淡出动画。'''

sysui_content = sysui_content.replace(old_clock_alpha, new_clock_alpha)
with open(sysui_hook_path, 'w', encoding='utf-8') as f:
    f.write(sysui_content)
print('SystemUiHook.java updated.')

# 5. Update StateCoordinator.java
state_coord_path = r'D:\ButtonHyperAlive\HyperAlive-debug\app\src\main\java\com\qihang\aod\hook\StateCoordinator.java'
with open(state_coord_path, 'r', encoding='utf-8', errors='ignore') as f:
    sc_content = f.read()

sc_content = sc_content.replace(
'''    public static boolean isCustomClockEnabled(Context ctx, int mode) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                if (mode == 0 && isFullScreenAod(c)) {
                    return false;
                }
                return ClockSettings.isClockEnabled(c, mode);
            }
        } catch (Throwable ignored) {}
        return false;
    }''',
'''    public static boolean isCustomClockEnabled(Context ctx, int mode) {
        // 全面启用原生时钟模式，始终返回 false
        return false;
    }'''
)
with open(state_coord_path, 'w', encoding='utf-8') as f:
    f.write(sc_content)
print('StateCoordinator.java updated.')
