# -*- coding: utf-8 -*-
"""编辑器预览卡：用 LivePreviewView 取代 ImageView，并接 onResume/onPause。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:50].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    private ImageView previewImage;""",
    """    private LivePreviewView previewImage;""")

rep("""        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));""",
    """        previewImage = new LivePreviewView(this, scene);
        card.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));""")

rep("""        previewImage.setScaleType(s == Motion.AOD
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        previewImage.setImageDrawable(previewFor(s));
        attachLivePreview(previewImage, s);""",
    """        previewImage.setScene(s);
        previewImage.startScene();""")

rep("""    private void notImplemented(String name) {""",
    """    @Override
    protected void onResume() {
        super.onResume();
        if (previewImage != null) {
            previewImage.startScene();
        }
    }

    @Override
    protected void onPause() {
        if (previewImage != null) {
            previewImage.stopScene();
        }
        super.onPause();
    }

    private void notImplemented(String name) {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')