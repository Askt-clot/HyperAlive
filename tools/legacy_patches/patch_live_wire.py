# -*- coding: utf-8 -*-
"""首页三态瓦片 + 编辑器预览改为 LivePreviewView（实时渲染），并接生命周期 start/stop。"""
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


# ---- MainActivity：瓦片里放 LivePreviewView（沿用原布局参数），并记录以便生命周期控制 ----
patch('MainActivity.java', [
    ("""        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(ui.rounded(ui.cardColor(), 14));
        iv.setClipToOutline(true);""",
     """        // 动态效果图：里面是 TextureView 实时渲染（没有管线时自动退回静态效果图）
        LivePreviewView iv = new LivePreviewView(this, scene);
        iv.setBackground(ui.rounded(ui.cardColor(), 14));
        previews.add(iv);"""),
    ("""        item.addView(iv, new LinearLayout.LayoutParams(-1, ui.dp(232)));
        // 预览 = 当前真实画面（熄屏=启航实帧 / 锁屏=当前锁屏壁纸 / 桌面=当前桌面壁纸）
        attachLivePreview(iv, scene);""",
     """        item.addView(iv, new LinearLayout.LayoutParams(-1, ui.dp(232)));"""),
    ("""    private LinearLayout sceneTiles() {""",
     """    private final java.util.List<LivePreviewView> previews = new java.util.ArrayList<>();

    @Override
    protected void onResume() {
        super.onResume();
        for (LivePreviewView v : previews) {
            v.startScene();
        }
    }

    @Override
    protected void onPause() {
        for (LivePreviewView v : previews) {
            v.stopScene();
        }
        super.onPause();
    }

    private LinearLayout sceneTiles() {"""),
])

# ---- EditorActivity：预览卡里用 LivePreviewView，切场景时 setScene ----
patch('EditorActivity.java', [
    ("""        previewImage.setImageDrawable(previewFor(s));
        attachLivePreview(previewImage, s);""",
     """        if (previewImage instanceof LivePreviewView) {
            ((LivePreviewView) previewImage).setScene(s);
        } else {
            previewImage.setImageDrawable(previewFor(s));
        }"""),
])

print('ok')