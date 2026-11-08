# -*- coding: utf-8 -*-
"""补：LivePreviewView（修正 attach 锚点）+ EditorActivity 选图后立刻刷新预览。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:44].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:90])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('LivePreviewView.java', [
    ("""    /** 渲染还没出帧时先显示的效果图（assets 里的兜底）。 */""",
     """    private String photoName;

    /** 预览该读哪张照片：熄屏/锁屏=锁屏照片；桌面=跟随锁屏时用锁屏照片，独立照片时用自己的。 */
    private String photoForScene() {
        if (mode == Motion.LOCK || mode == Motion.AOD) {
            return PhotoStore.LOCK;
        }
        int kind = LastSet.kind(getContext());
        if (kind == LastSet.KIND_PHOTO) {
            return PhotoStore.HOME;
        }
        if (kind == LastSet.KIND_FOLLOW) {
            return PhotoStore.LOCK;
        }
        return null;                    // 桌面是魅族 Alive 动态壁纸 → 走静态效果图
    }

    /** 选完照片调用：立刻换成新照片（不用再选第二次）。 */
    void reloadPhoto() {
        photoName = photoForScene();
        if (renderer != null) {
            renderer.setPreviewPhoto(photoName);
            renderer.requestPhotoReload();
        }
    }

    /** 渲染还没出帧时先显示的效果图（assets 里的兜底）。 */"""),
    ("""        texture.setVisibility(VISIBLE);
        if (still.getDrawable() != null) {
            // 首帧出来之前先顶着效果图（约 0.7s 后撤掉）
            still.setVisibility(VISIBLE);
            postDelayed(() -> still.setVisibility(GONE), 700);
        } else {
            still.setVisibility(GONE);
        }
        if (texture.isAvailable()) {
            attach(texture.getSurfaceTexture());
        }""",
     """        texture.setVisibility(VISIBLE);
        still.setVisibility(GONE);      // 占位图不再盖住效果图
        if (texture.isAvailable()) {
            attach(texture.getSurfaceTexture());
        }
        // 只有"渲染真的没起来"才把占位图顶出来（避免一片黑）
        postDelayed(this::showStillIfNoFrame, 1200);"""),
    ("""        renderer = new LensRenderer(getContext(), surface);""",
     """        renderer = new LensRenderer(getContext(), surface);
        photoName = photoForScene();
        renderer.setPreviewPhoto(photoName);"""),
    ("""    private void releaseSurface() {""",
     """    private void showStillIfNoFrame() {
        if (!active || still.getDrawable() == null) {
            return;
        }
        if (renderer == null || !renderer.hasDrawn()) {
            still.setVisibility(VISIBLE);
            android.util.Log.w(Root.TAG, "预览没出帧，退回占位图 mode=" + mode);
        }
    }

    private void releaseSurface() {"""),
])

patch('EditorActivity.java', [
    ("""            runOnUiThread(() -> selectScene(request == REQ_LOCK ? Motion.LOCK : Motion.HOME));""",
     """            runOnUiThread(() -> {
                previewImage.reloadPhoto();
                selectScene(request == REQ_LOCK ? Motion.LOCK : Motion.HOME);
                previewImage.startScene();
            });"""),
])
print('ok')