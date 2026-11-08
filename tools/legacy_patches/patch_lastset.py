# -*- coding: utf-8 -*-
"""预览改为「模块里最后一次设置」：UiActivity 不再向引擎取帧；编辑器初值来自 LastSet；应用时记录。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:46].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:80])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ---- UiActivity：直接用 LastSet 的图，不再取引擎帧 ----
patch('UiActivity.java', [
    ("""    /**
     * 先放兜底图，再异步换成"当前真实画面"。
     *
     * <p>熄屏/锁屏：向壁纸引擎要一帧实帧（就是屏幕上真正在画的东西）；
     * 桌面：优先系统桌面壁纸槽位（动态壁纸拿不到时用该壁纸自带效果图），都不行才用引擎帧。
     */
    protected void attachLivePreview(android.widget.ImageView iv, int mode) {
        new Thread(() -> {
            android.graphics.Bitmap bmp;
            if (mode == Motion.HOME) {
                bmp = LivePreview.systemWallpaper(this, Motion.HOME);
                if (bmp == null) {
                    bmp = LivePreview.fallback(this, Motion.HOME);
                }
                if (bmp == null) {
                    bmp = LivePreview.render(this, Motion.HOME, 2000);
                }
            } else {
                bmp = LivePreview.render(this, mode, 2500);
                if (bmp == null) {
                    bmp = LivePreview.fallback(this, mode);
                }
            }
            if (bmp == null) {
                return;
            }
            final android.graphics.Bitmap out = bmp;
            runOnUiThread(() -> iv.setImageBitmap(out));
        }, "qihang-preview-" + mode).start();
    }""",
     """    /**
     * 展示图 = 「最后一次在模块里设置的壁纸」（见 {@link LastSet}）：
     * 熄屏=用最后设置的锁屏照片按启航真实几何合成，锁屏=最后设的锁屏照片，桌面=最后设的桌面。
     * 不去读系统实时状态，所以桌面被其它应用改过也不影响展示。
     */
    protected void attachLivePreview(android.widget.ImageView iv, int mode) {
        new Thread(() -> {
            final android.graphics.Bitmap bmp = LastSet.preview(this, mode);
            if (bmp == null) {
                return;
            }
            runOnUiThread(() -> iv.setImageBitmap(bmp));
        }, "qihang-preview-" + mode).start();
    }"""),
])

# ---- 编辑器：初值来自 LastSet；应用时记录 ----
patch('EditorActivity.java', [
    ("""            desktopSource = wallpaper != null ? 0 : (followLock ? 1 : (homeBmp != null ? 2 : 1));""",
     """            // 初值 = 最后一次在模块里设置的状态（没从壁纸库带组件进来时）
            desktopSource = wallpaper != null ? LastSet.KIND_DYNAMIC : LastSet.kind(this);
            if (desktopSource == LastSet.KIND_DYNAMIC && wallpaper == null) {
                String comp = LastSet.component(this);
                if (!comp.isEmpty()) {
                    wallpaper = comp;
                }
            }
            followLock = desktopSource == LastSet.KIND_FOLLOW;"""),
    ("""        toast("已应用");""",
     """        LastSet.save(this, desktopSource, wallpaper);
        toast("已应用");"""),
])
print('ok')