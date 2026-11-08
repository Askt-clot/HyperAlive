# -*- coding: utf-8 -*-
"""编辑器三处修正：onNewIntent 处理 scene、顶栏避开状态栏、息屏预览用 FIT_CENTER。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


# 1) 顶栏避开状态栏
rep("""        bar.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), 0);""",
    """        bar.setPadding(ui.dp(20), statusBarHeight() + ui.dp(14), ui.dp(20), 0);""")

# 2) 预览缩放：息屏 FIT，其余 CROP
rep("""        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);""",
    """        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);""")

rep("""        previewImage.setImageDrawable(previewFor(s));""",
    """        previewImage.setScaleType(s == Motion.AOD
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        previewImage.setImageDrawable(previewFor(s));""")

# 3) 处理 onNewIntent（launchMode=singleTop 下复用实例，否则场景不刷新）
rep("""    private void buildEditor() {""",
    """    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int s = intent.getIntExtra(EXTRA_SCENE, scene);
        if (s != scene && tabs != null) {
            selectScene(s);
        }
    }

    /** 状态栏高度（顶栏要避开它）。 */
    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) {
                return getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
        }
        return ui.dp(28);
    }

    private void buildEditor() {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')