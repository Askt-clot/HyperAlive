# -*- coding: utf-8 -*-
"""去掉 wm.getWallpaperInfo() / getWallpaperId()（实测这两个 getter 会卡死不返回），
改成无条件先把桌面槽位切成系统静态壁纸，再 setBitmap；校验用 su dumpsys。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:52].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""            WallpaperManager wm = WallpaperManager.getInstance(this);
            // 桌面槽位若还是"动态壁纸组件"，先切回系统静态壁纸，否则 setBitmap 可能不生效
            if (wm.getWallpaperInfo() != null) {
                sb.append(" · 桌面先切静态(").append(Root.setWallpaper(this, "com.miui.miwallpaper",
                        "com.miui.miwallpaper.wallpaperservice.ImageWallpaper", 1)).append(")");
            }""",
    """            WallpaperManager wm = WallpaperManager.getInstance(this);
            // 先把桌面槽位切回系统静态壁纸（幂等），避免"槽位是动态壁纸组件"时 setBitmap 不生效。
            // 注意：这里**不要**用 wm.getWallpaperInfo()/getWallpaperId()——实测这两个 getter
            // 在本机会卡住不返回（应用线程卡死、状态日志里连结果行都出不来）。
            sb.append(" · 桌面槽位=").append(Root.setWallpaper(this, "com.miui.miwallpaper",
                    "com.miui.miwallpaper.wallpaperservice.ImageWallpaper", 1));""")

rep("""            int before = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            wm.setBitmap(send, null, true, WallpaperManager.FLAG_SYSTEM);
            int after = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            sb.append(" · 壁纸id ").append(before).append("→").append(after)
                    .append(before == after ? "（没变！）" : "（已换）");
            sb.append(" · 桌面=").append(desktopSource == 1 ? "跟随锁屏" : "独立照片").append(" 已设置");""",
    """            wm.setBitmap(send, null, true, WallpaperManager.FLAG_SYSTEM);
            sb.append(" · 桌面=").append(desktopSource == 1 ? "跟随锁屏" : "独立照片")
                    .append(" 已设置 ").append(send.getWidth()).append("x").append(send.getHeight());""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')