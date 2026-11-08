# -*- coding: utf-8 -*-
"""修三处冲突：① MainActivity 去掉 setImageBitmap；② EditorActivity 字段/创建/selectScene 收尾；③ LivePreviewView 加兜底图接口。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs, optional=False):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    changed = False
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:44].replace('\n', '\\n')))
        if c == 0 and optional:
            continue
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:80])
        s = s.replace(a, b)
        changed = True
    if changed:
        io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ① MainActivity：静态兜底图改为交给 LivePreviewView
patch('MainActivity.java', [
    ("""        try (InputStream in = getAssets().open(asset)) {
            iv.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Throwable t) {
            Log.w(TAG, "效果图缺失 " + asset, t);
        }""",
     """        iv.setFallbackAsset(asset);"""),
])

# ② EditorActivity
patch('EditorActivity.java', [
    ("""    private ImageView previewImage;""",
     """    private LivePreviewView previewImage;"""),
    ("""        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));""",
     """        previewImage = new LivePreviewView(this, scene);
        card.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));"""),
    ("""        previewImage.setScaleType(s == Motion.AOD
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (previewImage instanceof LivePreviewView) {
            ((LivePreviewView) previewImage).setScene(s);
        } else {
            previewImage.setImageDrawable(previewFor(s));
        }""",
     """        previewImage.setScene(s);
        previewImage.startScene();"""),
    ("""    private void notImplemented(String name) {""",
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

    private void notImplemented(String name) {"""),
], optional=True)

# ③ LivePreviewView：兜底图 + 首帧前先显示它
patch('LivePreviewView.java', [
    ("""    /** 换场景（编辑器切三态用）。 */""",
     """    /** 渲染还没出帧时先显示的效果图（assets 里的兜底）。 */
    void setFallbackAsset(String asset) {
        try (java.io.InputStream in = getContext().getAssets().open(asset)) {
            still.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in));
        } catch (Throwable t) {
            android.util.Log.w(Root.TAG, "效果图缺失 " + asset, t);
        }
    }

    /** 换场景（编辑器切三态用）。 */"""),
    ("""        still.setVisibility(GONE);
        texture.setVisibility(VISIBLE);
        if (texture.isAvailable()) {
            attach(texture.getSurfaceTexture());
        }""",
     """        texture.setVisibility(VISIBLE);
        if (still.getDrawable() != null) {
            // 首帧出来之前先顶着效果图（约 0.7s 后撤掉）
            still.setVisibility(VISIBLE);
            postDelayed(() -> still.setVisibility(GONE), 700);
        } else {
            still.setVisibility(GONE);
        }
        if (texture.isAvailable()) {
            attach(texture.getSurfaceTexture());
        }"""),
])
print('ok')