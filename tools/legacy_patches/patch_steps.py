# -*- coding: utf-8 -*-
"""给应用的每一步加进度日志，定位卡死点。"""
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


rep("""            sb.append(" · 桌面槽位=").append(Root.setWallpaper(this, "com.miui.miwallpaper",
                    "com.miui.miwallpaper.wallpaperservice.ImageWallpaper", 1));""",
    """            append("① 桌面槽位→系统静态壁纸");
            sb.append(" · 桌面槽位=").append(Root.setWallpaper(this, "com.miui.miwallpaper",
                    "com.miui.miwallpaper.wallpaperservice.ImageWallpaper", 1));""")

rep("""            wm.setBitmap(send, null, true, WallpaperManager.FLAG_SYSTEM);""",
    """            append("② 缩放后 " + send.getWidth() + "x" + send.getHeight() + "，开始 setBitmap");
            wm.setBitmap(send, null, true, WallpaperManager.FLAG_SYSTEM);
            append("③ setBitmap 返回");""")

rep("""            String desktopNote = applyDesktop();""",
    """            append("…应用开始");
            String desktopNote = applyDesktop();
            append("…桌面完成，接着设锁屏槽位");""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')