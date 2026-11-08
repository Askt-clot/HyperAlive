# -*- coding: utf-8 -*-
"""MiSettingsUi 颜色参数化：跟随系统深色模式；新增 dot()/tile()/bigRow() 首页元素。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\MiSettingsUi.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


# 字段
rep("    final boolean system;",
    "    final boolean system;\n    /** 跟随系统深色模式。 */\n    final boolean night;")

# 构造签名 + 全部调用点
rep("private MiSettingsUi(Context base, Resources res, Theme theme, ClassLoader loader, boolean system) {",
    "private MiSettingsUi(Context base, Resources res, Theme theme, ClassLoader loader, boolean system,\n"
    "                        boolean night) {")
rep("MiSettingsUi ui = new MiSettingsUi(base, res, theme, loader, true);",
    "MiSettingsUi ui = new MiSettingsUi(base, res, theme, loader, true, isNight(base));")
rep("return new MiSettingsUi(base, base.getResources(), base.getTheme(), base.getClassLoader(), false);",
    "return new MiSettingsUi(base, base.getResources(), base.getTheme(), base.getClassLoader(), false,\n"
    "                    isNight(base));")

# 夜间判定 + 颜色访问器（插在 create 前）
rep("    static MiSettingsUi create(Context base) {",
    """    static boolean isNight(Context ctx) {
        try {
            return (ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 卡片底色。 */
    int cardColor() {
        return night ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** 主文字色。 */
    int titleColor() {
        return night ? 0xFFF2F2F2 : 0xFF101010;
    }

    /** 次要文字色。 */
    int summaryColor() {
        return night ? 0xFF9A9A9E : 0xFF8C8C8C;
    }

    /** 分组标题色。 */
    int groupColor() {
        return night ? 0xFF8E8E93 : 0xFF7A7A7A;
    }

    static MiSettingsUi create(Context base) {""")

# 颜色替换
rep("tv.setTextColor(0xFF101010);", "tv.setTextColor(titleColor());")
rep("tv.setTextColor(0xFF8C8C8C);", "tv.setTextColor(summaryColor());")
rep("tv.setTextColor(0xFF7A7A7A);", "tv.setTextColor(groupColor());")
s = s.replace("return 0xFF101010;", "return titleColor();")
rep("bg.setColor(0xFFFFFFFF);", "bg.setColor(cardColor());")
rep('card.getClass().getMethod("setCardBackgroundColor", int.class).invoke(card, 0xFFFFFFFF);',
    'card.getClass().getMethod("setCardBackgroundColor", int.class).invoke(card, cardColor());')
rep("t.setTextColor(0xFF101010);", "t.setTextColor(titleColor());", 2)
rep("s.setTextColor(0xFF8C8C8C);", "s.setTextColor(summaryColor());", 2)
rep("row.setBackgroundColor(0xFFFFFFFF);", "row.setBackgroundColor(cardColor());")
rep("return system ? 0xFFF2F3F5 : 0xFFF2F3F5;", "return night ? 0xFF000000 : 0xFFF2F3F5;")

# 新增：绿点 / 大按钮行 / 圆形底
rep("    int pageBackground() {",
    """    /** 状态绿点 + 文字（首页顶部「● LSP 已激活  ● ROOT 已授权」）。 */
    View dot(String text) {
        LinearLayout box = new LinearLayout(base);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        View circle = new View(base);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF34C759);
        circle.setBackground(g);
        box.addView(circle, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(titleColor());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(6));
        box.addView(tv, tp);
        return box;
    }

    /** 圆角方底（放预览图/图标用）。 */
    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 全宽按钮卡内容：可选图标 + 居中标题（可选副标题）。 */
    View bigRow(String title, String subtitle, android.graphics.drawable.Drawable icon) {
        LinearLayout box = new LinearLayout(base);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(6), 0, dp(6));
        ImageView iv = new ImageView(base);
        if (icon != null) {
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
            ip.setMarginEnd(dp(8));
            box.addView(iv, ip);
        }
        LinearLayout text = new LinearLayout(base);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(base);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(titleColor());
        text.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView stv = new TextView(base);
            stv.setText(subtitle);
            stv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            stv.setTextColor(summaryColor());
            text.addView(stv);
        }
        box.addView(text, new LinearLayout.LayoutParams(-2, -2));
        return box;
    }

    int pageBackground() {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')