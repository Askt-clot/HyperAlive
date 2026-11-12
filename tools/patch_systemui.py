# -*- coding: utf-8 -*-
import os

path = 'D:/ButtonHyperAlive/HyperAlive-debug/app/src/main/java/com/qihang/aod/hook/SystemUiHook.java'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

# Normalize line endings for replacement
crlf = '\r\n' in text
lines = text.splitlines()

new_lines = []
skip = False
for i, line in enumerate(lines):
    if '2.5' in line and 'updateTimeViewAlpha' in lines[min(len(lines)-1, i+26)]:
        # Start of clockAlphaHook section
        new_lines.append("        // 2.5 熄屏态 (AOD/物理黑屏) 下压制原生锁屏时钟透明度为 0，杜绝息屏下锁屏时钟残留")
        new_lines.append("        XC_MethodHook clockAlphaHook = new XC_MethodHook() {")
        new_lines.append("            @Override")
        new_lines.append("            protected void beforeHookedMethod(MethodHookParam param) {")
        new_lines.append("                Context ctx = StateCoordinator.sContext != null ? StateCoordinator.sContext : StateCoordinator.systemContext();")
        new_lines.append("                boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;")
        new_lines.append("                if (sleeping && !StateCoordinator.isFullScreenAod(ctx)) {")
        new_lines.append("                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Float) {")
        new_lines.append("                        param.args[0] = 0.0f;")
        new_lines.append("                    }")
        new_lines.append("                }")
        new_lines.append("            }")
        new_lines.append("        };")
        new_lines.append("        HookUtils.hookAll(cl, \"com.android.keyguard.clock.animation.ClockBaseAnimation\", \"updateTimeViewAlpha\", clockAlphaHook);")
        new_lines.append("        HookUtils.hookAll(cl, \"com.android.keyguard.clock.animation.allinone.AllInOneClockAnimation\", \"updateTimeViewAlpha\", clockAlphaHook);")
        new_lines.append("")
        new_lines.append("        // 2.6 伪装 SystemUI 正在使用超级壁纸样式 (触发系统原生 aodSuperWallpaperViewAlphaAnim 将锁屏时钟淡出至 0)")
        new_lines.append("        XC_MethodHook superWallpaperHook = new XC_MethodHook() {")
        new_lines.append("            @Override")
        new_lines.append("            protected void beforeHookedMethod(MethodHookParam param) {")
        new_lines.append("                Context ctx = StateCoordinator.sContext != null ? StateCoordinator.sContext : StateCoordinator.systemContext();")
        new_lines.append("                if (StateCoordinator.isAodEnabled(ctx) && !StateCoordinator.isFullScreenAod(ctx)) {")
        new_lines.append("                    param.setResult(Boolean.TRUE);")
        new_lines.append("                }")
        new_lines.append("            }")
        new_lines.append("        };")
        new_lines.append("        HookUtils.hookAll(cl, \"com.miui.keyguard.KeyguardCommonSettingObserver\", \"getAodUsingSuperWallpaperStyle\", superWallpaperHook);")
        new_lines.append("")
        new_lines.append("        // 2.7 杜绝息屏态下原生锁屏时钟容器 (KeyguardClockContainer) 绘制，双重阻断杜绝双时钟重叠")
        new_lines.append("        XC_MethodHook suppressLockClockDrawHook = new XC_MethodHook() {")
        new_lines.append("            @Override")
        new_lines.append("            protected void beforeHookedMethod(MethodHookParam param) {")
        new_lines.append("                boolean sleeping = StateCoordinator.isSleepingActive() && StateCoordinator.sLastMode != 1 && StateCoordinator.sLastMode != 2;")
        new_lines.append("                if (sleeping) {")
        new_lines.append("                    param.setResult(null);")
        new_lines.append("                }")
        new_lines.append("            }")
        new_lines.append("        };")
        new_lines.append("        HookUtils.hookAll(cl, \"com.android.keyguard.clock.KeyguardClockContainer\", \"dispatchDraw\", suppressLockClockDrawHook);")
        new_lines.append("        HookUtils.hookAll(cl, \"com.android.keyguard.clock.KeyguardClockContainer\", \"onDraw\", suppressLockClockDrawHook);")
        new_lines.append("        HookUtils.hookAll(cl, \"com.miui.clock.allInOne.AllInOneHourClock\", \"dispatchDraw\", suppressLockClockDrawHook);")
        new_lines.append("        HookUtils.hookAll(cl, \"com.miui.clock.allInOne.AllInOneHourClock\", \"onDraw\", suppressLockClockDrawHook);")
        new_lines.append("        HookUtils.hookAll(cl, \"com.miui.clock.allInOne.AllInOneMinuteClock\", \"dispatchDraw\", suppressLockClockDrawHook);")
        new_lines.append("        HookUtils.hookAll(cl, \"com.miui.clock.allInOne.AllInOneMinuteClock\", \"onDraw\", suppressLockClockDrawHook);")
        skip = True
        continue
    
    if skip:
        if '2.6' in line and 'FOD' in line or '2.8' in line and 'FOD' in line:
            skip = False
            new_lines.append("        // 2.8 监听屏下指纹图标 (GXZW / FOD) 显隐")
            continue
        elif 'AllInOneClockAnimation' in line:
            # this is the end of the old block
            continue
        elif skip and not ('AllInOneClockAnimation' in lines[max(0, i-1)]):
            continue
        else:
            skip = False

    new_lines.append(line)

eol = '\r\n' if crlf else '\n'
result = eol.join(new_lines) + eol
with open(path, 'w', encoding='utf-8') as f:
    f.write(result)
print('SystemUiHook.java successfully patched! Lines:', len(new_lines))
