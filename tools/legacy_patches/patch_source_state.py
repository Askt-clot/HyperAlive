# -*- coding: utf-8 -*-
"""桌面来源状态：0=壁纸库选的动态壁纸 1=跟随锁屏 2=独立照片，按最后一次操作决定。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    /** 桌面是否跟随锁屏（默认跟随）。 */
    private boolean followLock = true;""",
    """    /** 桌面是否跟随锁屏（默认跟随）。 */
    private boolean followLock = true;
    /** 桌面来源：0=壁纸库选的动态壁纸 1=跟随锁屏 2=独立照片。 */
    private int desktopSource = 1;""")

rep("""            lockBmp = PhotoStore.load(this, PhotoStore.LOCK);
            homeBmp = PhotoStore.load(this, PhotoStore.HOME);
            buildEditor();""",
    """            lockBmp = PhotoStore.load(this, PhotoStore.LOCK);
            homeBmp = PhotoStore.load(this, PhotoStore.HOME);
            desktopSource = wallpaper != null ? 0 : (followLock ? 1 : (homeBmp != null ? 2 : 1));
            buildEditor();""")

rep("""        String wp = intent.getStringExtra(EXTRA_WP);
        if (wp != null) {
            wallpaper = wp;
        }""",
    """        String wp = intent.getStringExtra(EXTRA_WP);
        if (wp != null) {
            wallpaper = wp;
            desktopSource = 0;      // 刚从壁纸库选了动态壁纸 → 桌面就放它
            followLock = false;
        }""")

# 预览：按桌面来源取图
rep("""            android.graphics.Bitmap picked = (s == Motion.LOCK || followLock) ? lockBmp : homeBmp;""",
    """            android.graphics.Bitmap picked = s == Motion.LOCK ? lockBmp
                    : desktopSource == 1 ? lockBmp : desktopSource == 2 ? homeBmp : null;""")

# 跟随锁屏开关
rep("""                    new String[]{"ic_lock", "flip_lock_screen_icon"}, 0xFF7EB6FF, v -> {
                        followLock = !followLock;
                        prefs.edit().putBoolean("home_follow_lock", followLock).apply();
                        append("桌面跟随锁屏 = " + followLock);
                        selectScene(Motion.HOME);
                    });""",
    """                    new String[]{"ic_lock", "flip_lock_screen_icon"}, 0xFF7EB6FF, v -> {
                        followLock = !followLock;
                        if (followLock) {
                            desktopSource = 1;
                        } else if (homeBmp != null) {
                            desktopSource = 2;
                        }
                        prefs.edit().putBoolean("home_follow_lock", followLock).apply();
                        append("桌面跟随锁屏 = " + followLock + "（来源=" + desktopSource + "）");
                        selectScene(Motion.HOME);
                    });""")

# 选了桌面照片 → 来源=2
rep("""                homeBmp = bmp;
                followLock = false;""",
    """                homeBmp = bmp;
                followLock = false;
                desktopSource = 2;""")

# 应用：按来源落地
rep("""            // 优先级：壁纸库选的动态壁纸（本次刚选的） > 跟随锁屏 > 桌面独立照片
            android.graphics.Bitmap home = followLock ? lockBmp : homeBmp;
            if (wallpaper != null && homeBmp == null) {
                sb.append(" · 桌面=动态壁纸 ").append(setDesktopFromLibrary());
            } else if (home != null) {""",
    """            // 桌面按"最后一次选择"落地：0=动态壁纸 1=跟随锁屏 2=独立照片
            android.graphics.Bitmap home = desktopSource == 1 ? lockBmp
                    : desktopSource == 2 ? homeBmp : null;
            if (desktopSource == 0 && wallpaper != null) {
                sb.append(" · 桌面=动态壁纸 ").append(setDesktopFromLibrary());
            } else if (home != null) {""")

rep("""                    sb.append(" · 桌面=").append(followLock ? "跟随锁屏" : "独立照片").append(" 已设置");""",
    """                    sb.append(" · 桌面=").append(desktopSource == 1 ? "跟随锁屏" : "独立照片")
                            .append(" 已设置");""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')