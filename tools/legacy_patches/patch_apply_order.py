# -*- coding: utf-8 -*-
"""应用顺序：壁纸库选的动态壁纸优先于「跟随锁屏」的照片。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()

a = """            android.graphics.Bitmap home = followLock ? lockBmp : homeBmp;
            if (home != null) {
                try {
                    WallpaperManager.getInstance(this).setBitmap(home, null, true,
                            WallpaperManager.FLAG_SYSTEM);
                    sb.append(" · 桌面=").append(followLock ? "跟随锁屏" : "独立照片").append(" 已设置");
                } catch (Throwable t) {
                    sb.append(" · 桌面设置失败：").append(t);
                }
            } else if (wallpaper != null) {
                sb.append(" · 桌面=").append(setDesktopFromLibrary());
            } else {
                sb.append(" · 桌面=未选照片，跳过");
            }"""
b = """            // 优先级：壁纸库选的动态壁纸（本次刚选的） > 跟随锁屏 > 桌面独立照片
            android.graphics.Bitmap home = followLock ? lockBmp : homeBmp;
            if (wallpaper != null && homeBmp == null) {
                sb.append(" · 桌面=动态壁纸 ").append(setDesktopFromLibrary());
            } else if (home != null) {
                try {
                    WallpaperManager.getInstance(this).setBitmap(home, null, true,
                            WallpaperManager.FLAG_SYSTEM);
                    sb.append(" · 桌面=").append(followLock ? "跟随锁屏" : "独立照片").append(" 已设置");
                } catch (Throwable t) {
                    sb.append(" · 桌面设置失败：").append(t);
                }
            } else {
                sb.append(" · 桌面=未选照片，跳过");
            }"""
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')