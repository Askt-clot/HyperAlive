# -*- coding: utf-8 -*-
"""首页瓦片 + 编辑器预览：先放兜底图，再异步换成"当前真实画面"。"""
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


patch('UiActivity.java', [
    ("""    /** 在线程里跑特权操作，结果写进状态日志。 */""",
     """    /**
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
    }

    /** 在线程里跑特权操作，结果写进状态日志。 */"""),
])

patch('MainActivity.java', [
    ("""        item.addView(iv, new LinearLayout.LayoutParams(-1, ui.dp(232)));""",
     """        item.addView(iv, new LinearLayout.LayoutParams(-1, ui.dp(232)));
        // 预览 = 当前真实画面（熄屏=启航实帧 / 锁屏=当前锁屏壁纸 / 桌面=当前桌面壁纸）
        attachLivePreview(iv, scene);"""),
])

patch('EditorActivity.java', [
    ("""        previewImage.setImageDrawable(previewFor(s));""",
     """        previewImage.setImageDrawable(previewFor(s));
        attachLivePreview(previewImage, s);"""),
])
print('ok')