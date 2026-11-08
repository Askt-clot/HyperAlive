# -*- coding: utf-8 -*-
"""A 方案：电源键熄屏延后 700ms。
按下电源键 → 系统调 PowerManagerService.goToSleep(reason=POWER_BUTTON)
  → 我们拦下：① 立刻广播 mode=0 让引擎开始「全屏→圆圈」收缩；② 把真正的 goToSleep 延后 700ms。
于是收缩动画在这 700ms 里全程可见（屏幕还亮着），缩完才真正灭屏进 AOD。
只拦电源键（GO_TO_SLEEP_REASON_POWER_BUTTON = 4），超时/其它原因不拦。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\XposedInit.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:46].replace('\n', '\\n')))
    if c != 1:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    private void hookSystemServer(ClassLoader cl) {
        // 1.1 AMBIENT_WALLPAPER：纯 signature 权限，第三方永远拿不到 —— 直接判为已授予""",
    """    /** A 方案：电源键熄屏延后 ~700ms，让「全屏→圆圈」的缩放在可见状态下播完。 */
    private static final long SCREEN_OFF_DELAY_MS = 700;
    /** 正在延后（我们自己延迟后的那次真实调用要放行）。 */
    private static boolean sDeferringSleep;

    private void hookDelayedScreenOff(ClassLoader cl) {
        try {
            Class<?> pm = Class.forName("com.android.server.power.PowerManagerService", false, cl);
            XposedBridge.hookAllMethods(pm, "goToSleep", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sDeferringSleep || !hasPowerButtonReason(p.args)) {
                        return;
                    }
                    sDeferringSleep = true;
                    // ① 先让引擎开始收缩（此刻屏幕还亮着，动画全程可见）
                    Context ctx = systemContext();
                    if (ctx != null) {
                        Intent i = new Intent(ACTION_MODE);
                        i.setPackage(PKG_SELF);
                        i.putExtra(EXTRA_MODE, 0);
                        i.putExtra(EXTRA_ANIMATE, true);
                        i.putExtra(EXTRA_STATE, "power");
                        ctx.sendBroadcast(i);
                    }
                    // ② 真正的熄屏延后
                    final Object target = p.thisObject;
                    final java.lang.reflect.Member member = p.method;
                    final Object[] args = p.args;
                    log("电源键熄屏延后 " + SCREEN_OFF_DELAY_MS + "ms（先播完收缩动画）");
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ((java.lang.reflect.Method) member).invoke(target, args);
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            } finally {
                                sDeferringSleep = false;
                            }
                        }
                    }, SCREEN_OFF_DELAY_MS);
                    p.setResult(null);   // 跳过本次立即熄屏
                }
            });
            log("延迟熄屏 hook 已挂（goToSleep，电源键 +" + SCREEN_OFF_DELAY_MS + "ms）");
        } catch (Throwable t) {
            log("hookDelayedScreenOff 失败: " + t);
        }
    }

    /** GO_TO_SLEEP_REASON_POWER_BUTTON = 4（不依赖隐藏常量，直接比数值）。 */
    private static boolean hasPowerButtonReason(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object a : args) {
            if (a instanceof Integer && ((Integer) a).intValue() == 4) {
                return true;
            }
        }
        return false;
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
        hookDelayedScreenOff(cl);
        // 1.1 AMBIENT_WALLPAPER：纯 signature 权限，第三方永远拿不到 —— 直接判为已授予""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')