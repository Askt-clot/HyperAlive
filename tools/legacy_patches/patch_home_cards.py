# -*- coding: utf-8 -*-
"""首页：动态壁纸 / Alive 壁纸 / 通知光效 各自独立成卡片并留间距；通知光效改成左对齐（图标+标题+副标题）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\MainActivity.java'
s = io.open(P, encoding='utf-8').read()

a = """        // ---- 壁纸（全宽卡片 → 二级菜单）----
        MiSettingsUi.Card wall = ui.card();
        wall.body.addView(ui.bigRow("动态壁纸", null, icon("ic_wallpaper")), rowLp());
        rowClick(wall.body.getChildAt(wall.body.getChildCount() - 1),
                v -> startActivity(new Intent(this, LibraryActivity.class)));
        wall.body.addView(ui.bigRow("Alive 壁纸", null, icon("ic_wallpaper")), rowLp());
        rowClick(wall.body.getChildAt(wall.body.getChildCount() - 1),
                v -> startActivity(EditorActivity.intent(this, Motion.LOCK)));
        addCard(wall);

        // ---- 通知光效（未实现，占位）----
        MiSettingsUi.Card more = ui.card();
        more.body.addView(ui.bigRow("通知光效", "动静之间 传达通知", icon("ic_notification")), rowLp());
        rowClick(more.body.getChildAt(more.body.getChildCount() - 1),
                v -> startActivity(new Intent(this, NotificationActivity.class)));
        addCard(more);"""

b = """        // ---- 壁纸：每项各一张卡片，彼此留间距 ----
        addSpacedCard(centerCard("动态壁纸", icon("ic_homepage_wallpaper"),
                v -> startActivity(new Intent(this, LibraryActivity.class))));
        addSpacedCard(centerCard("Alive 壁纸", icon("ic_homepage_wallpaper"),
                v -> startActivity(EditorActivity.intent(this, Motion.LOCK))));

        // ---- 通知光效（未实现，占位）----
        addSpacedCard(leftCard("通知光效", "动静之间 传达通知", icon("ic_homepage_notification"),
                v -> startActivity(new Intent(this, NotificationActivity.class))));"""
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
s = s.replace(a, b)

a2 = """    private LinearLayout.LayoutParams rowLp() {"""
b2 = """    /** 独立卡片 + 卡片间距（首页几个选项之间要有空隙）。 */
    private void addSpacedCard(MiSettingsUi.Card card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.dp(12);
        col.addView(card.outer, lp);
    }

    /** 居中样式：图标 + 标题（动态壁纸 / Alive 壁纸）。 */
    private MiSettingsUi.Card centerCard(String title, android.graphics.drawable.Drawable icon,
                                         View.OnClickListener click) {
        MiSettingsUi.Card card = ui.card();
        View row = ui.bigRow(title, null, icon);
        card.body.addView(row, rowLp());
        row.setClickable(true);
        row.setOnClickListener(click);
        return card;
    }

    /** 左对齐样式：图标 + 标题 + 副标题（通知光效）。 */
    private MiSettingsUi.Card leftCard(String title, String subtitle,
                                       android.graphics.drawable.Drawable icon,
                                       View.OnClickListener click) {
        MiSettingsUi.Card card = ui.card();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(12));
        ImageView iv = new ImageView(this);
        if (icon != null) {
            iv.setImageDrawable(icon);
        }
        box.addView(iv, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(ui.titleColor());
        text.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView stv = new TextView(this);
            stv.setText(subtitle);
            stv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            stv.setTextColor(ui.summaryColor());
            text.addView(stv);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMarginStart(ui.dp(14));
        box.addView(text, tp);
        card.body.addView(box, rowLp());
        box.setClickable(true);
        box.setOnClickListener(click);
        return card;
    }

    private LinearLayout.LayoutParams rowLp() {"""
n2 = s.count(a2)
print('anchor2', n2)
if n2 != 1:
    raise SystemExit('匹配数不对 2')
s = s.replace(a2, b2)

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')