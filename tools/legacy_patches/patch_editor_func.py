# -*- coding: utf-8 -*-
"""编辑器功能：锁屏壁纸→系统相册；桌面跟随锁屏 / 独立壁纸；应用→分别落地。"""
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


# ---- 字段 + 请求码 ----
rep("""    private int scene = Motion.LOCK;
    /** 选中的动态壁纸组件（来自壁纸库），非空时锁屏/桌面预览都显示它。 */
    private String wallpaper;""",
    """    private static final int REQ_LOCK = 2001;
    private static final int REQ_HOME = 2002;

    private int scene = Motion.LOCK;
    /** 选中的动态壁纸组件（来自壁纸库），非空时锁屏/桌面预览都显示它。 */
    private String wallpaper;
    /** 编辑器里选的照片（相册）：锁屏 / 桌面各一张。 */
    private android.graphics.Bitmap lockBmp;
    private android.graphics.Bitmap homeBmp;
    /** 桌面是否跟随锁屏（默认跟随）。 */
    private boolean followLock = true;
    private android.content.SharedPreferences prefs;""")

# ---- onCreate 读取已存的照片与开关 ----
rep("""            wallpaper = getIntent().getStringExtra(EXTRA_WP);
            buildEditor();""",
    """            wallpaper = getIntent().getStringExtra(EXTRA_WP);
            prefs = getSharedPreferences("qihang_editor", MODE_PRIVATE);
            followLock = prefs.getBoolean("home_follow_lock", true);
            lockBmp = PhotoStore.load(this, PhotoStore.LOCK);
            homeBmp = PhotoStore.load(this, PhotoStore.HOME);
            buildEditor();""")

# ---- 预览优先用编辑器里选的照片 ----
rep("""        if (wallpaper != null && s != Motion.AOD) {""",
    """        if (s != Motion.AOD) {
            android.graphics.Bitmap picked = (s == Motion.LOCK || followLock) ? lockBmp : homeBmp;
            if (picked != null) {
                append("预览=编辑器所选照片（" + (s == Motion.LOCK ? "锁屏"
                        : followLock ? "跟随锁屏" : "桌面独立") + "）");
                return new android.graphics.drawable.BitmapDrawable(getResources(), picked);
            }
        }
        if (wallpaper != null && s != Motion.AOD) {""")

# ---- 按钮行为 ----
rep("""        } else if (s == Motion.LOCK) {
            addRoundButton("壁纸", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                    0xFFFFFFFF, v -> run0("设为锁屏壁纸(which=2)", () -> Root.setWallpaper(this,
                            getPackageName(), getPackageName() + ".QihangWallpaper", 2)));
            addRoundButton("纹理", new String[]{"@self:ic_texture", "bg_screen_effect"},
                    0xFFFFFFFF, v -> notImplemented("纹理"));
        } else {
            addRoundButton("跟随锁屏", new String[]{"ic_lock", "flip_lock_screen_icon"},
                    0xFF7EB6FF, v -> notImplemented("跟随锁屏"));
            addRoundButton("壁纸", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                    0xFFFFFFFF, v -> run0("桌面壁纸(which=1)", this::setDesktopAlive));
            addRoundButton("特效", new String[]{"@self:ic_effect", "bg_screen_effect"},
                    0xFFFFFFFF, v -> notImplemented("特效"));
        }""",
    """        } else if (s == Motion.LOCK) {
            // 锁屏：从系统相册选一张照片（引擎画在锁屏 + 息屏圆圈里）
            addRoundButton("壁纸", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                    0xFFFFFFFF, v -> pick(PhotoStore.LOCK));
            addRoundButton("纹理", new String[]{"@self:ic_texture", "bg_screen_effect"},
                    0xFFFFFFFF, v -> notImplemented("纹理"));
        } else {
            // 桌面：跟随锁屏 / 单独选一张
            LinearLayout follow = addRoundButton("跟随锁屏",
                    new String[]{"ic_lock", "flip_lock_screen_icon"}, 0xFF7EB6FF, v -> {
                        followLock = !followLock;
                        prefs.edit().putBoolean("home_follow_lock", followLock).apply();
                        append("桌面跟随锁屏 = " + followLock);
                        selectScene(Motion.HOME);
                    });
            if (followLock) {
                GradientDrawable ring = ui.rounded(0x00000000, 40);
                ring.setStroke(ui.dp(2), 0xFF3B82F6);
                ((FrameLayout) follow.getChildAt(0)).setBackground(ring);
            }
            addRoundButton("壁纸", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                    0xFFFFFFFF, v -> pick(PhotoStore.HOME));
            addRoundButton("特效", new String[]{"bg_screen_effect", "ic_auto_awesome"},
                    0xFFFFFFFF, v -> notImplemented("特效"));
        }""")

# ---- addRoundButton 返回 item（要给「跟随锁屏」画选中态）----
rep("""    private void addRoundButton(String label, String[] iconNames, int iconTint,
                                View.OnClickListener click) {""",
    """    private LinearLayout addRoundButton(String label, String[] iconNames, int iconTint,
                                        View.OnClickListener click) {""")
rep("""        item.setClickable(true);
        item.setOnClickListener(click);
        buttonRow.addView(item);
    }""",
    """        item.setClickable(true);
        item.setOnClickListener(click);
        buttonRow.addView(item);
        return item;
    }""")

# ---- 相册选择 + 导入 ----
rep("""    private void notImplemented(String name) {""",
    """    /** 打开系统相册选图（ACTION_PICK 优先，退化到 OPEN_DOCUMENT）。 */
    private void pick(String name) {
        int req = PhotoStore.LOCK.equals(name) ? REQ_LOCK : REQ_HOME;
        append("打开相册（" + (req == REQ_LOCK ? "锁屏" : "桌面") + "）");
        try {
            startActivityForResult(PhotoStore.pickerIntent(), req);
            return;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_PICK 不可用，退化 OPEN_DOCUMENT", t);
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, req);
        } catch (Throwable t) {
            toast("没有可用的相册");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            append("相册取消");
            return;
        }
        final String name = request == REQ_LOCK ? PhotoStore.LOCK : PhotoStore.HOME;
        final android.net.Uri uri = data.getData();
        run0("导入照片 " + name, () -> {
            android.graphics.Bitmap bmp = PhotoStore.importFrom(this, uri, name);
            if (request == REQ_LOCK) {
                lockBmp = bmp;
            } else {
                homeBmp = bmp;
                followLock = false;
                prefs.edit().putBoolean("home_follow_lock", false).apply();
            }
            runOnUiThread(() -> selectScene(request == REQ_LOCK ? Motion.LOCK : Motion.HOME));
            return name + " " + bmp.getWidth() + "x" + bmp.getHeight();
        });
    }

    private void notImplemented(String name) {""")

# ---- 应用：分别落地 ----
rep("""    /** 「应用」：把三态落到设备上（本模块已实现的两件事）。 */
    private void applyScene() {
        run0("应用三态", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("锁屏=").append(Root.setWallpaper(this, getPackageName(),
                    getPackageName() + ".QihangWallpaper", 2));
            sb.append(" · 桌面=").append(setDesktopAlive());
            sendReloadPhoto();
            return sb.toString();
        });
        toast("已应用");
    }""",
    """    /** 「应用」：锁屏槽位交回本模块（画出所选照片 + 息屏启航），桌面按选择落地。 */
    private void applyScene() {
        run0("应用", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("锁屏=").append(Root.setWallpaper(this, getPackageName(),
                    getPackageName() + ".QihangWallpaper", 2));
            sendReloadPhoto();
            android.graphics.Bitmap home = followLock ? lockBmp : homeBmp;
            if (home != null) {
                try {
                    WallpaperManager.getInstance(this).setBitmap(home, null, true,
                            WallpaperManager.FLAG_SYSTEM);
                    sb.append(" · 桌面=").append(followLock ? "跟随锁屏" : "独立照片").append(" 已设置");
                } catch (Throwable t) {
                    sb.append(" · 桌面设置失败：").append(t);
                }
            } else if (wallpaper != null) {
                sb.append(" · 桌面=").append(setDesktopFromLibrary());
            } else {
                sb.append(" · 桌面=未选照片，跳过");
            }
            return sb.toString();
        });
        toast("已应用");
    }

    /** 从壁纸库点进来的那张动态壁纸 → 应用到桌面槽位。 */
    private String setDesktopFromLibrary() {
        int slash = wallpaper.indexOf('/');
        if (slash <= 0) {
            return "无效组件 " + wallpaper;
        }
        return Root.setWallpaper(this, wallpaper.substring(0, slash),
                wallpaper.substring(slash + 1), 1);
    }""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')