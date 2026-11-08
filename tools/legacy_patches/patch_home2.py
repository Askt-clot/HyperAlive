# -*- coding: utf-8 -*-
"""首页：① 内容整体下移；② 删掉 Alive 壁纸卡片；③ 动态壁纸文字真居中（图标不挤偏文字）。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = 1
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:48].replace('\n', '\\n')))
        if c != n:
            raise SystemExit('匹配数不对: %r' % a[:80])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ---- MiSettingsUi.bigRow：图标右侧加等宽占位，让文字按卡片居中 ----
patch('MiSettingsUi.java', [
    ("""        ImageView iv = new ImageView(base);
        if (icon != null) {
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
            ip.setMarginEnd(dp(8));
            box.addView(iv, ip);
        }""",
     """        ImageView iv = new ImageView(base);
        int iconSize = dp(22);
        if (icon != null) {
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
            ip.setMarginEnd(dp(8));
            box.addView(iv, ip);
        }"""),
    ("""        box.addView(text, new LinearLayout.LayoutParams(-2, -2));
        return box;
    }""",
     """        box.addView(text, new LinearLayout.LayoutParams(-2, -2));
        // 右侧补一块与图标等宽的占位，这样"文字"本身才是按卡片居中的
        if (icon != null) {
            View spacer = new View(base);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(iconSize, iconSize);
            sp.setMarginStart(dp(8));
            box.addView(spacer, sp);
        }
        return box;
    }"""),
])

# ---- MainActivity：下移 + 删 Alive 壁纸 ----
patch('MainActivity.java', [
    ("""        page("桌面、壁纸和个性化", null);""",
     """        page("桌面、壁纸和个性化", null);
        // 整体往下挪一点（标题别贴着状态栏）
        col.setPadding(ui.dp(16), ui.dp(56), ui.dp(16), ui.dp(40));"""),
    ("""        addSpacedCard(centerCard("Alive 壁纸", icon("ic_wallpaper"),
                v -> startActivity(EditorActivity.intent(this, Motion.LOCK))));

""",
     """"""),
])
print('ok')