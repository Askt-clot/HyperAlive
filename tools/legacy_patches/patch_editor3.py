# -*- coding: utf-8 -*-
"""编辑器：接收选中的壁纸（锁屏/桌面预览都显示它）+ 纹理/特效 用我们自己的矢量图标。"""
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


# 1) intent 重载 + 字段
rep("""    private static final String EXTRA_SCENE = "scene";

    static Intent intent(Context ctx, int scene) {
        Intent i = new Intent(ctx, EditorActivity.class);
        i.putExtra(EXTRA_SCENE, scene);
        return i;
    }""",
    """    private static final String EXTRA_SCENE = "scene";
    private static final String EXTRA_WP = "wp";

    static Intent intent(Context ctx, int scene) {
        return intent(ctx, scene, null);
    }

    /** wp = 选中的壁纸组件（pkg/cls），锁屏与桌面预览都显示它。 */
    static Intent intent(Context ctx, int scene, String wp) {
        Intent i = new Intent(ctx, EditorActivity.class);
        i.putExtra(EXTRA_SCENE, scene);
        if (wp != null) {
            i.putExtra(EXTRA_WP, wp);
        }
        return i;
    }""")

rep("""    private int scene = Motion.LOCK;""",
    """    private int scene = Motion.LOCK;
    /** 选中的动态壁纸组件（来自壁纸库），非空时锁屏/桌面预览都显示它。 */
    private String wallpaper;""")

rep("""            scene = getIntent().getIntExtra(EXTRA_SCENE, Motion.LOCK);
            buildEditor();""",
    """            scene = getIntent().getIntExtra(EXTRA_SCENE, Motion.LOCK);
            wallpaper = getIntent().getStringExtra(EXTRA_WP);
            buildEditor();""")

rep("""        int s = intent.getIntExtra(EXTRA_SCENE, scene);
        if (s != scene && tabs != null) {
            selectScene(s);
        }""",
    """        int s = intent.getIntExtra(EXTRA_SCENE, scene);
        String wp = intent.getStringExtra(EXTRA_WP);
        if (wp != null) {
            wallpaper = wp;
        }
        if (tabs != null && (s != scene || wp != null)) {
            selectScene(s);
        }""")

# 2) 预览：选中壁纸优先（息屏仍然是我们自己的启航）
rep("""        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);""",
    """        if (wallpaper != null && s != Motion.AOD) {
            Drawable picked = LibraryActivity.thumbnailFor(this, wallpaper);
            if (picked != null) {
                append("预览=选中壁纸 " + wallpaper);
                return picked;
            }
            append("预览：选中壁纸没有效果图，回退默认");
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);""")

# 3) 纹理 / 特效 用我们自己的矢量图
rep("""            addRoundButton("纹理", new String[]{"bg_screen_effect", "ic_photo_filter"},
                    0xFFFFFFFF, v -> notImplemented("纹理"));""",
    """            addRoundButton("纹理", new String[]{"@self:ic_texture", "bg_screen_effect"},
                    0xFFFFFFFF, v -> notImplemented("纹理"));""")

rep("""            addRoundButton("特效", new String[]{"bg_screen_effect", "ic_auto_awesome"},
                    0xFFFFFFFF, v -> notImplemented("特效"));""",
    """            addRoundButton("特效", new String[]{"@self:ic_effect", "bg_screen_effect"},
                    0xFFFFFFFF, v -> notImplemented("特效"));""")

# 4) icon() 支持 @self: 前缀（走我们模块自己的 R.drawable）
rep("""    private Drawable icon(String[] names) {
        for (String n : names) {
            try {
                int id = ui.id("drawable", n);
                if (id != 0) {
                    return ui.res.getDrawable(id, ui.theme);
                }
            } catch (Throwable ignored) {
            }
        }""",
    """    private Drawable icon(String[] names) {
        for (String n : names) {
            try {
                if (n.startsWith("@self:")) {
                    // 本模块自带的矢量图
                    String res = n.substring(6);
                    int id = "ic_texture".equals(res) ? R.drawable.ic_texture : R.drawable.ic_effect;
                    return getResources().getDrawable(id, null);
                }
                int id = ui.id("drawable", n);
                if (id != 0) {
                    return ui.res.getDrawable(id, ui.theme);
                }
            } catch (Throwable ignored) {
            }
        }""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')