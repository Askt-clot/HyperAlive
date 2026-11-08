# -*- coding: utf-8 -*-
"""把源码回退成 2026-10-24 01:37「扩散只播一次 + 续跑」那一版。

作用是撤销其后 turn6/turn7 的全部改动（只作用于 backup/20261024_turn5_rebuilt 副本）：
  XposedInit.java     熄屏意图锁 SystemProperties 跨进程  -> 本地静态字段
  LensRenderer.java   状态推进脱离可见性条件            -> 恢复条件式；countTransitionFrames 回 preview 分支
  Motion.java         HOME_BLUR 0                       -> 16
  QihangWallpaper.java 跨 Engine 共享 sLockExpandShown/aodOwner -> 各自 sawLockSinceAod/lockAtMs

每处都断言命中，未命中会列在最后的 FAILED 里（不会静默漏改）。
"""
import io
import os
import re

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '..', 'backup', '20261024_turn5_rebuilt', 'module', 'src', 'com', 'qihang', 'aod')
fails = []


def load(fn):
    return io.open(os.path.join(SRC, fn), encoding='utf-8').read()


def save(fn, s):
    io.open(os.path.join(SRC, fn), 'w', encoding='utf-8', newline='\n').write(s)


def sub(fn, pat, rep, label, regex=False):
    s = load(fn)
    if regex:
        new, n = re.subn(pat, rep, s, count=1, flags=re.DOTALL)
    else:
        n = s.count(pat)
        new = s.replace(pat, rep, 1)
    if n == 0:
        fails.append('%s: %s' % (fn, label))
        print('  !! %s' % label)
        return
    save(fn, new)
    print('  ok %s' % label)


# ---------------------------------------------------------------- XposedInit
print('== XposedInit.java ==')
sub('XposedInit.java',
    'long at = propGetLong(PROP_SLEEP_AT, 0L);\n'
    '        return at > 0 && android.os.SystemClock.uptimeMillis() - at < 2500;',
    'return sAsleep && android.os.SystemClock.uptimeMillis() - sSleepAtMs < 2500;',
    '熄灭意图锁回退为本地字段')
sub('XposedInit.java',
    '        propSet(PROP_SLEEP_AT, String.valueOf(now));   // ★ 跨进程共享，SystemUI 侧才压得住 KEYGUARD\n',
    '', '去掉熄屏写属性')
sub('XposedInit.java', '        propSet(PROP_SLEEP_AT, "0");\n', '', '去掉唤醒清属性')

# ------------------------------------------------------------------- Motion
print('== Motion.java ==')
sub('Motion.java',
    r'/\*\*\s*\*\s*桌面态 blur\..*?\*/\s*static final float HOME_BLUR = 0f;',
    'static final float HOME_BLUR = 16f;', 'HOME_BLUR 回退为 16', regex=True)

# ------------------------------------------------------------- LensRenderer
print('== LensRenderer.java ==')
sub('LensRenderer.java',
    'if (ok) {\n                    if (pendingPhotoReload) {',
    'if (ok && (visible || pendingMode == Motion.AOD || dumpRequested)) {\n'
    '                    if (pendingPhotoReload) {',
    '渲染循环条件恢复')
sub('LensRenderer.java',
    r'// ★ 指令处理与状态推进\*\*不依赖可见性\*\*。.*?'
    r'if \(needDraw && !EGL14\.eglSwapBuffers\(eglDisplay, eglSurface\)\) \{\n'
    r'\s*Log\.w\(TAG, "eglSwapBuffers 失败 0x" \+ Integer\.toHexString\(EGL14\.eglGetError\(\)\)\);\n'
    r'\s*\}\n                \}',
    'applyPendingMode();\n'
    '                    drawFrame(System.nanoTime());\n'
    '                    drewFrame = true;\n'
    '                    if (previewSurface != null) {\n'
    '                        countTransitionFrames();\n'
    '                        logPreview(nowNanos0());\n'
    '                    }\n'
    '                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {\n'
    '                        Log.w(TAG, "eglSwapBuffers 失败 0x" + Integer.toHexString(EGL14.eglGetError()));\n'
    '                    }\n'
    '                }',
    '状态推进恢复可见性条件', regex=True)
sub('LensRenderer.java',
    '        // 给主线程看的最新过渡状态（决定「续跑」还是「直接切」）\n'
    '        boolean run = motion.animating();\n'
    '        transitionRunning = run;\n'
    '        transitionRemainMs = run ? motion.remainingMs(System.nanoTime()) : 0;\n'
    '        if (run) {',
    '        if (motion.animating()) {',
    'countTransitionFrames 恢复原样')

# ----------------------------------------------------------- QihangWallpaper
print('== QihangWallpaper.java ==')
sub('QihangWallpaper.java',
    r'\n    /\*\*\n     \* 跨 Engine（同进程）共享：.*?private static volatile long sLockExpandShownAt;\n',
    '\n', '去掉跨 Engine 共享字段', regex=True)
sub('QihangWallpaper.java',
    r'\n        /\*\*\n         \* 我是不是「展示 AOD 的那个 Engine」.*?private volatile boolean aodOwner;\n',
    '\n', '去掉 aodOwner 字段', regex=True)
sub('QihangWallpaper.java',
    'long now = android.os.SystemClock.uptimeMillis();\n'
    '            boolean changed = next != mode;\n'
    '            if (changed) {\n'
    '                if (next == Motion.AOD) {\n'
    '                    sawLockSinceAod = false;              // 又熄屏了 → 下一轮重新计\n'
    '                    aodOwner = isVisible();               // 熄屏瞬间可见的 = AOD 展示者\n'
    '                    sLockExpandShown = false;             // 清掉上一轮的"锁屏扩散已展示"\n'
    '                    sLockExpandShownAt = 0;\n'
    '                } else if (next == Motion.LOCK && isVisible()) {\n'
    '                    // ★ 只有**可见的** Engine 才算"用户看到了锁屏扩散"（跨 Engine 共享）\n'
    '                    sawLockSinceAod = true;\n'
    '                    lockAtMs = now;\n'
    '                    sLockExpandShown = true;\n'
    '                    sLockExpandShownAt = now;\n'
    '                }\n'
    '            }',
    'long now = android.os.SystemClock.uptimeMillis();\n'
    '            boolean changed = next != mode;\n'
    '            if (changed) {\n'
    '                if (next == Motion.AOD) {\n'
    '                    sawLockSinceAod = false;      // 又熄屏了 → 下一轮重新计\n'
    '                } else if (next == Motion.LOCK) {\n'
    '                    sawLockSinceAod = true;\n'
    '                    lockAtMs = now;               // tr0 从这里开始计时\n'
    '                }\n'
    '            } else if (next == Motion.LOCK && lockAtMs == 0) {\n'
    '                lockAtMs = now;\n'
    '            }',
    'applyCommand 状态记录回退')
sub('QihangWallpaper.java',
    '            if (next == Motion.HOME && sLockExpandShown) {\n'
    '                long elapsed = now - sLockExpandShownAt;',
    '            if (next == Motion.HOME && sawLockSinceAod) {\n'
    '                long elapsed = now - lockAtMs;',
    'applyCommand HOME 判定回退')
sub('QihangWallpaper.java',
    'android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);\n'
    '            if (d == null) {\n'
    '                return;\n'
    '            }\n'
    '            int st = d.getState();',
    '// 当时（turn5）的写法：**只有当前可见的 Engine 才响应显示状态直驱**。\n'
    '            if (!isVisible()) {\n'
    '                return;\n'
    '            }\n'
    '            android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);\n'
    '            if (d == null) {\n'
    '                return;\n'
    '            }\n'
    '            int st = d.getState();',
    'applyDisplayState 可见性检查回退')
sub('QihangWallpaper.java',
    r'                if \(dozing\) \{\n'
    r'                // 熄屏：由\*\*当下可见\*\*的那个 Engine 负责.*?if \(!isVisible\(\)\) \{\n'
    r'                    return;\n'
    r'                \}\n',
    '                if (cur != Motion.AOD) {\n',
    'applyDisplayState 熄屏分支回退',
    regex=True)
sub('QihangWallpaper.java',
    r'                // 唤醒：只有「AOD 展示者」负责即亮即动。\n'
    r'                // \*\*这里不能用 isVisible\(\)\*\*.*?if \(!aodOwner\) \{\n'
    r'                    return;\n'
    r'                \}\n',
    '', 'applyDisplayState 唤醒分支回退', regex=True)
sub('QihangWallpaper.java',
    '            if (renderer != null) {\n'
    '                renderer.setVisible(visible);\n'
    '                // ★ 「谁是 AOD 展示者」必须在这里判定，而不是熄屏瞬间。\n'
    '                // 熄屏那一刹那可见的是**桌面**槽位（它在放收缩动画），而 AOD 实际由\n'
    '                // **锁屏**槽位展示 —— 系统是在熄屏后才把层切过去的。所以：AOD 期间谁可见，\n'
    '                // 谁才是 AOD 展示者；"即亮即动"的直驱只由它负责。\n'
    '                if (renderer.currentMode() == Motion.AOD) {\n'
    '                    aodOwner = visible;\n'
    '                }\n'
    '            }',
    '            if (renderer != null) {\n'
    '                renderer.setVisible(visible);\n'
    '            }',
    'onVisibilityChanged 回退')

print('')
if fails:
    print('FAILED (%d):' % len(fails))
    for f in fails:
        print('  - ' + f)
else:
    print('全部撤销成功')