# -*- coding: utf-8 -*-
"""切档时同步更换"读哪张照片"：编辑器把当前的桌面来源告知预览，切档即用对应照片。"""
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
    ("""    private String photoName;

    /** 预览该读哪张照片：熄屏/锁屏=锁屏照片；桌面=跟随锁屏时用锁屏照片，独立照片时用自己的。 */
    private String photoForScene() {
        if (mode == Motion.LOCK || mode == Motion.AOD) {
            return PhotoStore.LOCK;
        }
        int kind = LastSet.kind(getContext());""",
     """    private String photoName;
    /** 桌面来源（编辑器传入的当前选择）；-1 = 用模块记录里的值（首页瓦片）。 */
    private int homeKind = -1;

    /** 编辑器用：告诉预览"桌面现在是什么来源"，切档时据此换照片。 */
    void setHomeKind(int kind) {
        homeKind = kind;
    }

    /** 预览该读哪张照片：熄屏/锁屏=锁屏照片；桌面=跟随锁屏时用锁屏照片，独立照片时用自己的。 */
    private String photoForScene() {
        if (mode == Motion.LOCK || mode == Motion.AOD) {
            return PhotoStore.LOCK;
        }
        int kind = homeKind >= 0 ? homeKind : LastSet.kind(getContext());"""),
    ("""    void setScene(int scene, boolean animate) {
        mode = scene;
        if (renderer != null) {
            renderer.setMode(scene, animate);
        }""",
     """    void setScene(int scene, boolean animate) {
        mode = scene;
        photoName = photoForScene();        // ★ 切档必须同时换照片，否则锁屏会延续桌面那张
        if (renderer != null) {
            renderer.setPreviewPhoto(photoName);
            renderer.setMode(scene, animate);
        }"""),
])

patch('EditorActivity.java', [
    ("""        previewImage.setScene(s);
        previewImage.startScene();""",
     """        previewImage.setHomeKind(desktopSource);
        previewImage.setScene(s);
        previewImage.startScene();"""),
])
print('ok')