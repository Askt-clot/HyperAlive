# -*- coding: utf-8 -*-
"""补 hook：让 SystemUI 认为"支持 AOD 壁纸"。
链路（此前逆向结论）：WallpaperRepositoryImpl 读 secure doze_always_on_wallpaper_enabled=1 后，
读 MiuiSystemUI 的 integer config_dozeSupportsAodWallpaperOverride(0x7f0c0044)=**-1**
→ 落进 bool==null 分支 → 兜底读 framework-res 的 bool config_earcFeatureDisabled_default(0x01110176)=false
→ 判定"不支持 AOD 壁纸" → AOD 背景走黑底（就是我们看到的 0.5s 黑）。
这里直接把 getInteger(0x7f0c0044) 改成 1（= 支持），另外把 getBoolean(0x01110176) 兜底为 true。
Resources / ResourcesImpl 两种实现都挂。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\XposedInit.java'
s = io.open(P, encoding='utf-8').read()

anchor = """    /** 从 WindowManagerGlobal 拿 SystemUI 的根视图（ScrimController 上找不到 View 时的兜底）。 */"""
if s.count(anchor) != 1:
    raise SystemExit('锚点不唯一')

addition = """    /** MiuiSystemUI: integer/config_dozeSupportsAodWallpaperOverride。 */
    private static final int ID_AOD_WP_OVERRIDE = 0x7f0c0044;
    /** framework-res: bool/config_earcFeatureDisabled_default（小米笔误兜底的那个 EARC 开关）。 */
    private static final int ID_EARC_BOOL = 0x01110176;

    /** 让 SystemUI 判定"支持 AOD 壁纸"：AOD 背景才会用壁纸而不是黑底。 */
    private static void hookAodWallpaperSupport() {
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

    /** 从 WindowManagerGlobal 拿 SystemUI 的根视图（ScrimController 上找不到 View 时的兜底）。 */"""

s = s.replace(anchor, addition)
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('插入完成，还需在 SystemUI 初始化处调用 hookAodWallpaperSupport()')
# 找出 SystemUI 侧初始化（注册 sweep 的地方）作为调用点
import re
for m in re.finditer(r'startSweep\(sAodRoot\)|sScrimHooked|log\("scrim', s):
    line = s[:m.start()].count('\n') + 1
    print('候选调用点 line', line, ':', s.split('\n')[line - 1].strip()[:80])