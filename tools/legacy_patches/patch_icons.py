# -*- coding: utf-8 -*-
"""图标：换成验证过的名字，并在取不到时打日志（便于迭代）。"""
import io

base = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = base + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b, n in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:50]))
        if c != n:
            raise SystemExit('匹配数不对: %r' % a[:80])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('MainActivity.java', [
    ('icon("ic_homepage_wallpaper"),\n                v -> startActivity(new Intent(this, LibraryActivity.class))',
     'icon("ic_wallpaper"),\n                v -> startActivity(new Intent(this, LibraryActivity.class))', 1),
    ('icon("ic_homepage_wallpaper"),\n                v -> startActivity(EditorActivity.intent(this, Motion.LOCK))',
     'icon("ic_wallpaper"),\n                v -> startActivity(EditorActivity.intent(this, Motion.LOCK))', 1),
    ('icon("ic_homepage_notification"),', 'icon("ic_flash_notification", "ic_float_notification"),', 1),
    ("""    private android.graphics.drawable.Drawable icon(String name) {
        try {
            int id = ui.id("drawable", name);
            return id == 0 ? null : ui.res.getDrawable(id, ui.theme);
        } catch (Throwable t) {
            return null;
        }
    }""",
     """    /** 取系统设置里的图标（按候选顺序找，全找不到就返回 null 只显示文字）。 */
    private android.graphics.drawable.Drawable icon(String... names) {
        for (String name : names) {
            try {
                int id = ui.id("drawable", name);
                if (id != 0) {
                    return ui.res.getDrawable(id, ui.theme);
                }
            } catch (Throwable ignored) {
            }
        }
        Log.w(TAG, "图标未找到：" + java.util.Arrays.toString(names));
        return null;
    }""", 1),
])

patch('EditorActivity.java', [
    ("""    private Drawable icon(String[] names) {
        for (String n : names) {
            try {
                int id = ui.id("drawable", n);
                if (id != 0) {
                    return ui.res.getDrawable(id, ui.theme);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }""",
     """    private Drawable icon(String[] names) {
        for (String n : names) {
            try {
                int id = ui.id("drawable", n);
                if (id != 0) {
                    return ui.res.getDrawable(id, ui.theme);
                }
            } catch (Throwable ignored) {
            }
        }
        Log.w(TAG, "圆按钮图标未找到：" + java.util.Arrays.toString(names));
        return null;
    }""", 1),
])
print('ok')