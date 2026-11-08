# -*- coding: utf-8 -*-
"""应用顺序改成：先落桌面（setBitmap），再设锁屏槽位（SetWall which=2）。
之前 SetWall(2) 刚重绑完引擎就紧接大图 setBitmap，实测会互相等（卡死无返回）。
另：setBitmap 前把图缩到屏宽以内，减少管道负载。"""
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


rep("""        run0("应用", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("锁屏=").append(Root.setWallpaper(this, getPackageName(),
                    getPackageName() + ".QihangWallpaper", 2));
            sendReloadPhoto();""",
    """        run0("应用", () -> {
            StringBuilder sb = new StringBuilder();
            // ★ 先落桌面，再设锁屏槽位：反过来时 SetWall(which=2) 刚重绑完壁纸引擎，
            //   紧接着的大图 setBitmap 会和它互相等（实测卡住无返回）。
            String desktopNote = applyDesktop();
            sb.append(desktopNote.isEmpty() ? "" : desktopNote.substring(2));
            sb.append(sb.length() > 0 ? " · " : "").append("锁屏=").append(Root.setWallpaper(this,
                    getPackageName(), getPackageName() + ".QihangWallpaper", 2));
            sendReloadPhoto();""")

rep("""            // 桌面按"最后一次选择"落地：0=动态壁纸 1=跟随锁屏 2=独立照片
            android.graphics.Bitmap home = desktopSource == 1 ? lockBmp
                    : desktopSource == 2 ? homeBmp : null;
            if (desktopSource == 0 && fromLibrary && wallpaper != null) {
                sb.append(" · 桌面=动态壁纸 ").append(setDesktopFromLibrary());
            } else if (desktopSource == 0) {
                // 上一次设的是动态壁纸、本次又没选照片 → 不要偷偷换成动态壁纸
                sb.append(" · 桌面=保持不动（先点「壁纸」选一张桌面照片）");
            } else if (home != null) {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(this);
                    // 桌面槽位若还是"动态壁纸组件"，先切回系统静态壁纸，否则 setBitmap 可能不生效
                    if (wm.getWallpaperInfo() != null) {
                        String r = Root.setWallpaper(this, "com.miui.miwallpaper",
                                "com.miui.miwallpaper.wallpaperservice.ImageWallpaper", 1);
                        sb.append(" · 桌面先切静态(").append(r).append(")");
                    }
                    int before = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                    wm.setBitmap(home, null, true, WallpaperManager.FLAG_SYSTEM);
                    int after = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                    sb.append(" · 壁纸id ").append(before).append("→").append(after)
                            .append(before == after ? "（没变！）" : "（已换）");
                    sb.append(" · 桌面=").append(desktopSource == 1 ? "跟随锁屏" : "独立照片")
                            .append(" 已设置");
                } catch (Throwable t) {
                    sb.append(" · 桌面设置失败：").append(t);
                }
            } else {
                sb.append(" · 桌面=还没选照片，保持不动");
            }
            return sb.toString();
        });""",
    """            return sb.toString();
        });""")

rep("""    /** 从壁纸库点进来的那张动态壁纸 → 应用到桌面槽位。 */""",
    """    /** 落桌面：返回一段以" · "开头的说明（空串=桌面保持不动）。 */
    private String applyDesktop() {
        android.graphics.Bitmap home = desktopSource == 1 ? lockBmp
                : desktopSource == 2 ? homeBmp : null;
        if (desktopSource == 0 && fromLibrary && wallpaper != null) {
            return " · 桌面=动态壁纸 " + setDesktopFromLibrary();
        }
        if (desktopSource == 0) {
            // 上一次设的是动态壁纸、本次又没选照片 → 不要偷偷换成动态壁纸
            return " · 桌面=保持不动（先点「壁纸」选一张桌面照片）";
        }
        if (home == null) {
            return " · 桌面=还没选照片，保持不动";
        }
        StringBuilder sb = new StringBuilder();
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            // 桌面槽位若还是"动态壁纸组件"，先切回系统静态壁纸，否则 setBitmap 可能不生效
            if (wm.getWallpaperInfo() != null) {
                sb.append(" · 桌面先切静态(").append(Root.setWallpaper(this, "com.miui.miwallpaper",
                        "com.miui.miwallpaper.wallpaperservice.ImageWallpaper", 1)).append(")");
            }
            android.graphics.Bitmap send = home;
            int maxW = getResources().getDisplayMetrics().widthPixels;
            if (home.getWidth() > maxW) {          // 缩到屏宽，减少管道负载
                send = android.graphics.Bitmap.createScaledBitmap(home, maxW,
                        Math.max(1, home.getHeight() * maxW / home.getWidth()), true);
            }
            int before = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            wm.setBitmap(send, null, true, WallpaperManager.FLAG_SYSTEM);
            int after = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            sb.append(" · 壁纸id ").append(before).append("→").append(after)
                    .append(before == after ? "（没变！）" : "（已换）");
            sb.append(" · 桌面=").append(desktopSource == 1 ? "跟随锁屏" : "独立照片").append(" 已设置");
        } catch (Throwable t) {
            sb.append(" · 桌面设置失败：").append(t);
        }
        return sb.toString();
    }

    /** 从壁纸库点进来的那张动态壁纸 → 应用到桌面槽位。 */""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')