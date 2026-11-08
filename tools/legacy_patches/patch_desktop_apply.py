# -*- coding: utf-8 -*-
"""① 回首页/进页面时刷新预览读的照片（否则瓦片还是旧图，看着像"没替换"）；
② 桌面应用时：若槽位是动态壁纸组件，先切成静态 ImageWallpaper 再 setBitmap，并回读校验。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:44].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:80])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ① LivePreviewView.startScene：每次进页面都按最新状态重定照片
patch('LivePreviewView.java', [
    ("""    void startScene() {
        active = true;
        Bitmap fallback = staticFallback();""",
     """    void startScene() {
        active = true;
        // 每次进页面都按"最后一次设置"重定照片：否则新选的图不会出现在瓦片里，
        // 看起来就像"桌面壁纸没被替换"。
        String fresh = photoForScene();
        if (renderer != null && !java.util.Objects.equals(fresh, photoName)) {
            photoName = fresh;
            renderer.setPreviewPhoto(fresh);
        } else if (renderer == null) {
            photoName = fresh;
        }
        Bitmap fallback = staticFallback();"""),
])

# ② EditorActivity：桌面应用更稳 + 回读校验
patch('EditorActivity.java', [
    ("""            } else if (home != null) {
                try {
                    WallpaperManager.getInstance(this).setBitmap(home, null, true,
                            WallpaperManager.FLAG_SYSTEM);""",
     """            } else if (home != null) {
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
                            .append(before == after ? "（没变！）" : "（已换）");"""),
])
print('ok')