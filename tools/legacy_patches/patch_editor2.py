# -*- coding: utf-8 -*-
"""编辑器预览框：① 图片按卡片圆角裁切（三张一致）；② 卡片按手机屏幕比例等比缩放。"""
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


# 1) 预览卡：加圆角裁切 + 放进居中宿主（比例由 sizeCard 算）
rep("""        FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = ui.rounded(0xFF101012, 24);
        cardBg.setStroke(ui.dp(1), 0xFF2C2C2E);
        card.setBackground(cardBg);""",
    """        FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = ui.rounded(0xFF101012, 24);
        cardBg.setStroke(ui.dp(1), 0xFF2C2C2E);
        card.setBackground(cardBg);
        // 关键：图片要按卡片的圆角轮廓裁切，否则照片会把圆角盖成方角
        card.setClipToOutline(true);""")

rep("""        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardLp.setMargins(ui.dp(18), ui.dp(22), ui.dp(18), ui.dp(10));
        col.addView(card, cardLp);""",
    """        // 预览宿主：卡片按手机屏幕比例等比缩放后居中
        FrameLayout host = new FrameLayout(this);
        host.addView(card, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        hostLp.setMargins(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(8));
        col.addView(host, hostLp);""")

# 2) 建完后按屏幕比例定尺寸
rep("""        setContentView(root);
        selectScene(scene);
        append("进入场景=" + scene);""",
    """        setContentView(root);
        host.post(() -> sizeCard(card, host));
        selectScene(scene);
        append("进入场景=" + scene);""")

# 3) sizeCard 实现 + 预览尺寸日志
rep("""    private void notImplemented(String name) {""",
    """    /** 让预览框 = 手机屏幕的等比缩小版（1200x2608 ≈ 6.9 英寸那种长宽比）。 */
    private void sizeCard(View card, View host) {
        int aw = host.getWidth() - ui.dp(8);
        int ah = host.getHeight() - ui.dp(8);
        if (aw <= 0 || ah <= 0) {
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        double aspect = (double) dm.heightPixels / (double) dm.widthPixels;
        int w;
        int h;
        if (aw * aspect <= ah) {
            w = aw;
            h = (int) (aw * aspect);
        } else {
            h = ah;
            w = (int) (ah / aspect);
        }
        card.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        append("预览框 " + w + "x" + h + "（屏幕 " + dm.widthPixels + "x" + dm.heightPixels
                + " 等比缩放）");
    }

    private void notImplemented(String name) {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')