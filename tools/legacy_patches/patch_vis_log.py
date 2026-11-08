# -*- coding: utf-8 -*-
"""给引擎的可见性/表面回调加时间戳日志，用于定位"亮屏后 ~1s 才动"卡在哪一步。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\QihangWallpaper.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:46].replace('\n', '\\n')))
    if c != 1:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (renderer != null) {
                renderer.setVisible(visible);
            }
        }""",
    """        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.i(TAG, "可见性变化 visible=" + visible + " mode="
                    + (renderer == null ? "?" : renderer.currentMode()));
            if (renderer != null) {
                renderer.setVisible(visible);
            }
        }""")

rep("""        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {""",
    """        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            Log.i(TAG, "surface created（surface 重建，会重新初始化 GL）");
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')