package com.qihang.aod;

import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.InputStream;

/**
 * 编辑器页 —— 对齐 OpenAlive 的编辑器（魅族壁纸设置样式）：
 *
 * <pre>
 *   左上「取消」                          右上「应用」
 *   ┌──────────────────────────────┐
 *   │        预览（圆角大卡）        │   息屏 / 锁屏 / 桌面 的效果图
 *   │   [ 息屏 | 锁屏 | 桌面 ] 胶囊  │   （动态壁纸时展示动态效果 —— 待实现）
 *   └──────────────────────────────┘
 *        ⭕ 风格        ⭕ 壁纸  ⭕ 纹理  …   各场景自己的圆形按钮行
 * </pre>
 *
 * 按钮按场景区分（与 OpenAlive 的系统按钮条一致）：
 * 息屏=风格；锁屏=壁纸/纹理；桌面=跟随锁屏/壁纸/特效。
 * 其中「壁纸」接本模块已实现的动作，其余未实现的只占位。
 */
public class EditorActivity extends UiActivity {

    private static final String EXTRA_SCENE = "scene";
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
    }

    private static final int REQ_LOCK = 2001;
    private static final int REQ_HOME = 2002;
    private static final int REQ_CROP_LOCK = 2003;
    private static final int REQ_CROP_HOME = 2004;
    private static final int REQ_FRAME = 2005;
    private static final int REQ_CROP_FRAME = 2006;

    private int scene = Motion.LOCK;
    /** 选中的动态壁纸组件（来自壁纸库），非空时锁屏/桌面预览都显示它。 */
    private String wallpaper;
    /** 编辑器里选的照片（相册）：锁屏 / 桌面各一张。 */
    private android.graphics.Bitmap lockBmp;
    private android.graphics.Bitmap homeBmp;
    /** 桌面是否跟随锁屏（默认跟随）。 */
    private boolean followLock = true;
    /** 桌面来源：0=壁纸库选的动态壁纸 1=跟随锁屏 2=独立照片。 */
    private int desktopSource = 1;
    /** 本次会话是否**真的**从壁纸库选了动态壁纸（只有这时才允许应用动态壁纸）。 */
    private boolean fromLibrary;
    private android.content.SharedPreferences prefs;
    private LivePreviewView previewImage;
    private TextView[] tabs;
    private LinearLayout buttonRow;
    /** 暂存的时间样式草稿（熄屏与锁屏独立，用户在对话框调整，点右上角「应用」才真正生效落盘）。 */
    private ClockSettings.Config mPendingAodConfig = null;
    private ClockSettings.Config mPendingLockConfig = null;
    /** 暂存的息屏视觉样式草稿（0=启航, 1=留光，点右上角「应用」才真正生效落盘）。 */
    private int mPendingAodVisualStyle = -1;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            scene = getIntent().getIntExtra(EXTRA_SCENE, Motion.LOCK);
            wallpaper = getIntent().getStringExtra(EXTRA_WP);
            mPendingAodConfig = ClockSettings.getAodDirect(this);
            mPendingLockConfig = ClockSettings.getLockDirect(this);
            mPendingAodVisualStyle = AodStyleSettings.getStyle(this);
            prefs = getSharedPreferences("qihang_editor", MODE_PRIVATE);
            followLock = prefs.getBoolean("home_follow_lock", true);
            lockBmp = PhotoStore.load(this, PhotoStore.LOCK);
            homeBmp = PhotoStore.load(this, PhotoStore.HOME);
            // 初值 = 最后一次在模块里设置的状态（没从壁纸库带组件进来时）
            fromLibrary = wallpaper != null;      // 从壁纸库点进来 = 本次真的选了动态壁纸
            desktopSource = fromLibrary ? LastSet.KIND_DYNAMIC : LastSet.kind(this);
            if (desktopSource == LastSet.KIND_DYNAMIC && wallpaper == null) {
                String comp = LastSet.component(this);
                if (!comp.isEmpty()) {
                    wallpaper = comp;
                }
            }
            followLock = desktopSource == LastSet.KIND_FOLLOW
                    || desktopSource == LastSet.KIND_DYNAMIC;
            if (mPendingAodVisualStyle == AodStyleSettings.STYLE_LEAVE_LIGHT && !PhotoStore.has(this, PhotoStore.FRAME)) {
                new Thread(() -> {
                    PhotoStore.syncFrameToLockWithDefaultCrop(EditorActivity.this);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            previewImage.reloadPhoto();
                        }
                    });
                }, "SyncFramePhotoOnInit").start();
            }
            buildEditor();
        } catch (Throwable t) {
            Log.e(TAG, "编辑器构建失败", t);
            TextView tv = new TextView(this);
            tv.setText("界面构建失败：\n" + t);
            tv.setPadding(40, 120, 40, 40);
            setContentView(tv);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int s = intent.getIntExtra(EXTRA_SCENE, scene);
        String wp = intent.getStringExtra(EXTRA_WP);
        if (wp != null) {
            wallpaper = wp;
            fromLibrary = true;
            desktopSource = LastSet.KIND_DYNAMIC;   // 刚从壁纸库选了动态壁纸 → 息屏/锁屏/桌面统用它
            followLock = true;
        }
        if (tabs != null && (s != scene || wp != null)) {
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

    @Override
    public void onBackPressed() {
        mPendingAodConfig = null;
        mPendingLockConfig = null;
        mPendingAodVisualStyle = -1;
        super.onBackPressed();
    }

    private void buildEditor() {
        ui = MiSettingsUi.create(this);
        Window w = getWindow();
        if (w != null) {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // ---- 全屏预览（底层，铺满整个屏幕）----
        previewImage = new LivePreviewView(this, scene);
        root.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));

        // ---- 顶栏：取消 / 应用（浮在上方）----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(20), statusBarHeight() + ui.dp(14), ui.dp(20), ui.dp(10));
        TextView cancel = pillButton("取消", 0xCC3A3A3C, 0xFFFFFFFF);
        TextView apply = pillButton("应用", 0xFF2F6BFF, 0xFFFFFFFF);
        cancel.setOnClickListener(v -> {
            append("取消");
            mPendingAodConfig = null;
            mPendingLockConfig = null;
            mPendingAodVisualStyle = -1;
            finish();
        });
        apply.setOnClickListener(v -> {
            append("应用：" + sceneName(scene));
            applyScene();
        });
        bar.addView(cancel, new LinearLayout.LayoutParams(ui.dp(74), ui.dp(36)));
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(apply, new LinearLayout.LayoutParams(ui.dp(74), ui.dp(36)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- 场景切换胶囊：息屏 | 锁屏 | 桌面（浮在底部按钮行上方）----
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setBackground(ui.rounded(0xCC2A2A2C, 24));
        tabs = new TextView[3];
        String[] names = {"息屏", "锁屏", "桌面"};
        for (int i = 0; i < 3; i++) {
            final int s = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setClickable(true);
            t.setOnClickListener(v -> selectScene(s));
            tabs[i] = t;
            pill.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(ui.dp(232), ui.dp(48),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pillLp.bottomMargin = ui.dp(142);
        root.addView(pill, pillLp);

        // ---- 圆形按钮行（浮在底部）----
        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, ui.dp(6), 0, ui.dp(6));
        FrameLayout.LayoutParams btnRowLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        btnRowLp.bottomMargin = ui.dp(38);
        root.addView(buttonRow, btnRowLp);

        setContentView(root);
        selectScene(scene);
        append("进入场景=" + scene);
    }

    // =====================================================================
    // 场景
    // =====================================================================

    private static String sceneName(int s) {
        return s == Motion.AOD ? "息屏" : s == Motion.HOME ? "桌面" : "锁屏";
    }

    private void selectScene(int s) {
        scene = s;
        for (int i = 0; i < 3; i++) {
            boolean on = i == s;
            tabs[i].setTextColor(on ? 0xFFFFFFFF : 0xFF9A9A9E);
            tabs[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tabs[i].setBackground(ui.rounded(on ? 0xFF4A4A4C : 0x00000000, 24));
        }
        previewImage.setDynamicComponent(wallpaper);
        previewImage.setHomeKind(desktopSource);
        previewImage.setAodStyle(mPendingAodVisualStyle != -1
                ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this));
        previewImage.setScene(s);
        if (s == Motion.AOD && mPendingAodConfig != null) {
            previewImage.applyAodClockConfig(mPendingAodConfig);
        } else if (s == Motion.LOCK && mPendingLockConfig != null) {
            previewImage.applyAodClockConfig(mPendingLockConfig);
        }
        buildButtons(s);
        // 注意：预览图切三态纯属应用内效果展示，严禁调用 sendMode 给真机壁纸发全局广播！
    }

    /** 每个场景自己的圆形按钮行（与 OpenAlive 的系统按钮条一致）。 */
    private void buildButtons(int s) {
        buttonRow.removeAllViews();
        if (s == Motion.AOD) {
            int aodStyle = mPendingAodVisualStyle != -1
                    ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
            addRoundButton("息屏样式", new String[]{"ic_aod", "ambient_display_with_wallpaper"},
                    0xFF7EB6FF, v -> showAodStyleDialog());
            if (aodStyle == AodStyleSettings.STYLE_LEAVE_LIGHT) {
                // 留光样式：相框照片 与 调整取景 独立绑定留光相框（PhotoStore.FRAME），绝不污染锁屏与桌面壁纸
                addRoundButton("相框照片", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                        0xFFFFFFFF, v -> pick(PhotoStore.FRAME));
                addRoundButton("调整取景", new String[]{"@self:ic_crop", "ic_crop"},
                        0xFF7EB6FF, v -> cropExistingPhoto(PhotoStore.FRAME));
            } else {
                addRoundButton("壁纸", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                        0xFFFFFFFF, v -> pick(PhotoStore.LOCK));
            }
            addRoundButton("时间样式", new String[]{"ic_clock", "ic_settings_date_time", "ic_time"},
                    0xFF7EB6FF, v -> showClockStyleDialog(Motion.AOD));
        } else if (s == Motion.LOCK) {
            addRoundButton("时间样式", new String[]{"ic_clock", "ic_settings_date_time", "ic_time"},
                    0xFF7EB6FF, v -> showClockStyleDialog(Motion.LOCK));
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
                        if (followLock) {
                            desktopSource = wallpaper != null ? LastSet.KIND_DYNAMIC : LastSet.KIND_FOLLOW;
                        } else if (homeBmp != null) {
                            desktopSource = LastSet.KIND_PHOTO;
                        }
                        prefs.edit().putBoolean("home_follow_lock", followLock).apply();
                        append("桌面跟随锁屏 = " + followLock + "（来源=" + desktopSource + "）");
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
        }
    }

    /** 打开系统相册选图（优先直达相册包，避免弹出"选择应用"面板）。 */
    private void pick(String name) {
        int req = PhotoStore.FRAME.equals(name) ? REQ_FRAME
                : PhotoStore.LOCK.equals(name) ? REQ_LOCK : REQ_HOME;
        append("打开相册（" + (req == REQ_FRAME ? "相框" : req == REQ_LOCK ? "锁屏" : "桌面") + "）");
        try {
            Intent pick = PhotoStore.pickerIntent();
            String gallery = galleryPackage(pick);
            if (gallery == null) {
                gallery = "com.miui.gallery";   // 本机已知系统相册（HyperOS）
            }
            try {
                Intent targeted = new Intent(pick);
                targeted.setPackage(gallery);
                append("相册应用=" + gallery);
                startActivityForResult(targeted, req);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "直达 " + gallery + " 失败，退回选择器", t);
            }
            startActivityForResult(pick, req);
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

    /** 找一个"相册"应用来处理选图 Intent（HyperOS 是 com.miui.gallery）。 */
    private String galleryPackage(Intent pick) {
        String[] prefer = {"com.miui.gallery", "com.google.android.apps.photos",
                "com.android.gallery3d", "org.codeaurora.gallery", "com.coloros.gallery3d"};
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(pick, 0);
            for (String want : prefer) {
                for (android.content.pm.ResolveInfo ri : list) {
                    String pkg = ri.activityInfo == null ? null : ri.activityInfo.packageName;
                    if (want.equals(pkg)) {
                        return pkg;
                    }
                }
            }
            for (android.content.pm.ResolveInfo ri : list) {
                String pkg = ri.activityInfo == null ? null : ri.activityInfo.packageName;
                if (pkg != null && pkg.toLowerCase(java.util.Locale.ROOT).contains("gallery")) {
                    return pkg;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "找相册失败", t);
        }
        return null;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CROP_FRAME || request == REQ_CROP_LOCK || request == REQ_CROP_HOME) {
            if (result != RESULT_OK) {
                append("裁切已取消（保持不动）");
                return;
            }
            final boolean isFrame = (request == REQ_CROP_FRAME);
            final boolean isLock = (request == REQ_CROP_LOCK);
            final String name = isFrame ? PhotoStore.FRAME : isLock ? PhotoStore.LOCK : PhotoStore.HOME;
            android.graphics.Bitmap bmp = PhotoStore.load(this, name);
            if (isFrame) {
                // 留光相框相片刷新（不影响锁屏与桌面壁纸）
                previewImage.reloadPhoto();
                selectScene(Motion.AOD);
                previewImage.startScene();
            } else if (isLock) {
                lockBmp = bmp;
                // 选了自定义锁屏照片 → 切换到本模块一镜到底自绘模式
                wallpaper = null;
                fromLibrary = false;
                if (desktopSource == LastSet.KIND_DYNAMIC) {
                    desktopSource = LastSet.KIND_FOLLOW;
                    followLock = true;
                    prefs.edit().putBoolean("home_follow_lock", true).apply();
                }
                int curAodStyle = mPendingAodVisualStyle != -1
                        ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
                if (curAodStyle == AodStyleSettings.STYLE_LEAVE_LIGHT) {
                    new Thread(() -> {
                        PhotoStore.syncFrameToLockWithDefaultCrop(EditorActivity.this);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                previewImage.reloadPhoto();
                                sendReloadPhoto();
                            }
                        });
                    }, "SyncFramePhoto").start();
                }
                previewImage.setDynamicComponent(wallpaper);
                previewImage.setHomeKind(desktopSource);
                previewImage.reloadPhoto();
                selectScene(scene == Motion.AOD ? Motion.AOD : Motion.LOCK);
                previewImage.startScene();
            } else {
                homeBmp = bmp;
                wallpaper = null;
                fromLibrary = false;
                followLock = false;
                desktopSource = LastSet.KIND_PHOTO;
                prefs.edit().putBoolean("home_follow_lock", false).apply();
                previewImage.setDynamicComponent(wallpaper);
                previewImage.setHomeKind(desktopSource);
                previewImage.reloadPhoto();
                selectScene(Motion.HOME);
                previewImage.startScene();
            }
            if (bmp != null) {
                append("裁切完成：" + name + " " + bmp.getWidth() + "x" + bmp.getHeight());
            }
            sendReloadPhoto();
            return;
        }
        if (request != REQ_FRAME && request != REQ_LOCK && request != REQ_HOME) {
            return;
        }
        android.net.Uri pickedUri = data != null ? data.getData() : null;
        if (pickedUri == null && data != null && data.getClipData() != null
                && data.getClipData().getItemCount() > 0) {
            pickedUri = data.getClipData().getItemAt(0).getUri();
        }
        if (result != RESULT_OK || pickedUri == null) {
            append("相册取消（没选照片）");
            return;
        }
        final String name = (request == REQ_FRAME) ? PhotoStore.FRAME
                : (request == REQ_LOCK) ? PhotoStore.LOCK : PhotoStore.HOME;
        final int cropReq = (request == REQ_FRAME) ? REQ_CROP_FRAME
                : (request == REQ_LOCK) ? REQ_CROP_LOCK : REQ_CROP_HOME;
        final android.net.Uri uri = pickedUri;
        final int aodStyle = mPendingAodVisualStyle != -1
                ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
        // 关键修复：仅针对留光相框选相片时启用留光自由移动与相框取景；锁屏选壁纸时严禁开启留光模式！
        final boolean isLiuguang = (request == REQ_FRAME);
        run0("读取照片并进入裁切 " + name, () -> {
            java.io.File staged = PhotoStore.stageFromUri(this, uri, name);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    staged.delete();
                    return;
                }
                startActivityForResult(
                        PhotoCropActivity.intent(this, staged.getName(), name, isLiuguang),
                        cropReq);
            });
            return staged.getName();
        });
    }

    /** 直接对当前已有照片进行裁切与调整取景（无需重新打开相册选图）。 */
    private void cropExistingPhoto(String name) {
        final boolean isFrame = PhotoStore.FRAME.equals(name);
        final boolean isLock = PhotoStore.LOCK.equals(name);
        final int cropReq = isFrame ? REQ_CROP_FRAME : isLock ? REQ_CROP_LOCK : REQ_CROP_HOME;
        final int aodStyle = mPendingAodVisualStyle != -1
                ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
        final boolean isLiuguang = isFrame;
        run0("读取照片并进入裁切 " + name, () -> {
            java.io.File staged = PhotoStore.stageExistingOrFallback(this, name);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (staged != null) staged.delete();
                    return;
                }
                if (staged == null) {
                    toast("未能准备好相片");
                    return;
                }
                startActivityForResult(
                        PhotoCropActivity.intent(this, staged.getName(), name, isLiuguang),
                        cropReq);
            });
            return staged != null ? staged.getName() : null;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewImage != null) {
            previewImage.setAodStyle(mPendingAodVisualStyle != -1
                    ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this));
            previewImage.startScene();
        }
    }

    @Override
    protected void onPause() {
        if (previewImage != null) {
            previewImage.stopScene();
        }
        super.onPause();
    }

    private void notImplemented(String name) {
        toast(name + "：暂未实现");
        append(name + " 面板暂未实现（按约定留空）");
    }

    // =====================================================================
    // 锁屏 / 熄屏时间样式与个性化面板（二级页面熄屏预览图下触发）
    // =====================================================================

    private void showClockStyleDialog() {
        showClockStyleDialog(scene);
    }

    private void showClockStyleDialog(final int targetScene) {
        if (mPendingAodConfig == null) {
            mPendingAodConfig = ClockSettings.getAodDirect(this);
        }
        if (mPendingLockConfig == null) {
            mPendingLockConfig = ClockSettings.getLockDirect(this);
        }
        final ClockSettings.Config clockConfig = (targetScene == Motion.AOD) ? mPendingAodConfig : mPendingLockConfig;
        FlymeClockView.ensureTypeface(this);

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x75000000);
        root.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ui.rounded(0xFF1E1E20, 24));
        sheet.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(24));
        sheet.setClickable(true);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(sheet, sheetLp);

        // 顶部小横条把手
        View handle = new View(this);
        handle.setBackground(ui.rounded(0xFF555558, 3));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = ui.dp(10);
        sheet.addView(handle, handleLp);

        // 标题栏：左侧标题与副标题，右侧「恢复默认」与「完成」
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(targetScene == Motion.AOD ? "熄屏时间样式" : "锁屏时间样式");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTypeface(null, Typeface.BOLD);
        titleCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(targetScene == Motion.AOD ? "魅族 Flyme 熄屏个性化 · 独立配置" : "魅族 Flyme 锁屏个性化 · 独立配置");
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvSub.setTextColor(0xFF8E8E93);
        titleCol.addView(tvSub);

        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvReset = new TextView(this);
        tvReset.setText("恢复默认");
        tvReset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvReset.setTextColor(0xFF60A5FA);
        tvReset.setPadding(ui.dp(6), ui.dp(4), ui.dp(10), ui.dp(4));
        tvReset.setClickable(true);
        titleRow.addView(tvReset);

        TextView btnDone = pillButton("完成", 0xFF2F6BFF, 0xFFFFFFFF);
        btnDone.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(btnDone, new LinearLayout.LayoutParams(ui.dp(62), ui.dp(32)));

        sheet.addView(titleRow);

        // 可滚动内容区
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, ui.dp(12), 0, 0);

        // ---- 1. 五种官方时钟样式卡片选择器 ----
        TextView lblStyles = new TextView(this);
        lblStyles.setText("样式选择");
        lblStyles.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblStyles.setTextColor(0xFFE5E5EA);
        lblStyles.setTypeface(null, Typeface.BOLD);
        content.addView(lblStyles);

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setPadding(0, ui.dp(8), 0, ui.dp(12));

        final FrameLayout[] styleCards = new FrameLayout[ClockSettings.STYLE_COUNT];
        final String[] styleNames = {"灵动双行", "灵动单行", "纵向拉伸", "极简现代", "典雅衬线"};
        final String[] fontDescs = {"SFPRO 半窄", "SFPRO 单行", "SixCaps 拉伸", "FlymeVF", "DMSerif 衬线"};

        for (int i = 0; i < ClockSettings.STYLE_COUNT; i++) {
            final int sIdx = i;
            FrameLayout card = new FrameLayout(this);
            card.setClickable(true);

            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8));

            TextView sample = new TextView(this);
            sample.setGravity(Gravity.CENTER);
            sample.setTextColor(0xFFFFFFFF);
            sample.setIncludeFontPadding(false);

            Typeface tf = FlymeClockView.getTypeface(sIdx, clockConfig.weight);
            sample.setTypeface(tf);

            if (sIdx == ClockSettings.STYLE_SINGLE_LINE) {
                sample.setText("09:30");
                sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            } else if (sIdx == ClockSettings.STYLE_VERTICAL_STRETCH) {
                sample.setText("09\n30");
                sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
            } else {
                sample.setText("09\n30");
                sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            }
            inner.addView(sample, new LinearLayout.LayoutParams(-2, ui.dp(44)));

            TextView name = new TextView(this);
            name.setText(styleNames[sIdx]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            name.setTextColor(0xFFFFFFFF);
            name.setGravity(Gravity.CENTER);
            inner.addView(name);

            TextView fontTag = new TextView(this);
            fontTag.setText(fontDescs[sIdx]);
            fontTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            fontTag.setTextColor(0xFF8E8E93);
            fontTag.setGravity(Gravity.CENTER);
            inner.addView(fontTag);

            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ui.dp(78), ui.dp(94));
            cardLp.setMarginEnd(ui.dp(8));
            styleRow.addView(card, cardLp);
            styleCards[sIdx] = card;

            card.setOnClickListener(v -> {
                clockConfig.style = sIdx;
                for (int j = 0; j < ClockSettings.STYLE_COUNT; j++) {
                    boolean sel = (j == sIdx);
                    GradientDrawable bg = ui.rounded(sel ? 0xFF1E293B : 0xFF2A2A2C, 16);
                    if (sel) bg.setStroke(ui.dp(2), 0xFF2F6BFF);
                    styleCards[j].setBackground(bg);
                }
                previewImage.applyAodClockConfig(clockConfig);
            });
        }
        hScroll.addView(styleRow);
        content.addView(hScroll);

        // ---- 2. 色彩选择 ----
        TextView lblColor = new TextView(this);
        lblColor.setText("色彩风格");
        lblColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblColor.setTextColor(0xFFE5E5EA);
        lblColor.setTypeface(null, Typeface.BOLD);
        content.addView(lblColor);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setPadding(0, ui.dp(6), 0, ui.dp(12));

        final java.util.List<FrameLayout> pickers = new java.util.ArrayList<>();
        for (int i = 0; i < ClockSettings.PRESET_COLORS.length; i++) {
            final int color = ClockSettings.PRESET_COLORS[i];
            FrameLayout item = new FrameLayout(this);
            item.setClickable(true);

            View circle = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            circle.setBackground(g);
            int circleSize = ui.dp(30);
            FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(circleSize, circleSize, Gravity.CENTER);
            item.addView(circle, cLp);

            pickers.add(item);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, ui.dp(40), 1f);
            colorRow.addView(item, itemLp);

            item.setOnClickListener(v -> {
                clockConfig.color = color;
                for (int j = 0; j < pickers.size(); j++) {
                    boolean sel = (ClockSettings.PRESET_COLORS[j] == color);
                    if (sel) {
                        GradientDrawable ring = new GradientDrawable();
                        ring.setShape(GradientDrawable.OVAL);
                        ring.setColor(0x00000000);
                        ring.setStroke(ui.dp(2), 0xFFFFFFFF);
                        pickers.get(j).setBackground(ring);
                    } else {
                        pickers.get(j).setBackground(null);
                    }
                }
                previewImage.applyAodClockConfig(clockConfig);
            });
        }
        content.addView(colorRow);

        // ---- 3. 大小缩放 ----
        LinearLayout scaleHeader = new LinearLayout(this);
        scaleHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblScale = new TextView(this);
        lblScale.setText("时钟大小缩放");
        lblScale.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblScale.setTextColor(0xFFE5E5EA);
        lblScale.setTypeface(null, Typeface.BOLD);
        scaleHeader.addView(lblScale, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvScaleVal = new TextView(this);
        tvScaleVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvScaleVal.setTextColor(0xFF8E8E93);
        tvScaleVal.setText(String.format(java.util.Locale.US, "%.2fx", clockConfig.scale));
        scaleHeader.addView(tvScaleVal, new LinearLayout.LayoutParams(-2, -2));
        content.addView(scaleHeader);

        final SeekBar sbScale = new SeekBar(this);
        sbScale.setMax(70);
        int curScaleProg = Math.round((clockConfig.scale - 0.70f) * 100f);
        sbScale.setProgress(Math.max(0, Math.min(70, curScaleProg)));
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float scale = 0.70f + (progress / 100f);
                clockConfig.scale = scale;
                tvScaleVal.setText(String.format(java.util.Locale.US, "%.2fx", scale));
                previewImage.applyAodClockConfig(clockConfig);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams sbLp1 = new LinearLayout.LayoutParams(-1, -2);
        sbLp1.topMargin = ui.dp(4);
        sbLp1.bottomMargin = ui.dp(10);
        content.addView(sbScale, sbLp1);

        // ---- 4. 字体粗细 ----
        LinearLayout weightHeader = new LinearLayout(this);
        weightHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblWeight = new TextView(this);
        lblWeight.setText("字体粗细 (字重)");
        lblWeight.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblWeight.setTextColor(0xFFE5E5EA);
        lblWeight.setTypeface(null, Typeface.BOLD);
        weightHeader.addView(lblWeight, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvWeightVal = new TextView(this);
        tvWeightVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvWeightVal.setTextColor(0xFF8E8E93);
        tvWeightVal.setText(clockConfig.weight + (clockConfig.weight <= 300 ? " (轻盈)" : clockConfig.weight >= 700 ? " (加粗)" : " (标准)"));
        weightHeader.addView(tvWeightVal, new LinearLayout.LayoutParams(-2, -2));
        content.addView(weightHeader);

        final SeekBar sbWeight = new SeekBar(this);
        sbWeight.setMax(650);
        sbWeight.setProgress(Math.max(0, Math.min(650, clockConfig.weight - 250)));
        sbWeight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int weight = 250 + progress;
                clockConfig.weight = weight;
                String desc = weight <= 300 ? " (轻盈)" : weight >= 700 ? " (加粗)" : " (标准)";
                tvWeightVal.setText(weight + desc);
                previewImage.applyAodClockConfig(clockConfig);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams sbLp2 = new LinearLayout.LayoutParams(-1, -2);
        sbLp2.topMargin = ui.dp(4);
        sbLp2.bottomMargin = ui.dp(10);
        content.addView(sbWeight, sbLp2);

        // ---- 5. 垂直位置微调 ----
        LinearLayout offsetHeader = new LinearLayout(this);
        offsetHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblOffset = new TextView(this);
        lblOffset.setText("垂直微调 (对齐圆心)");
        lblOffset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblOffset.setTextColor(0xFFE5E5EA);
        lblOffset.setTypeface(null, Typeface.BOLD);
        offsetHeader.addView(lblOffset, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvOffsetVal = new TextView(this);
        tvOffsetVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvOffsetVal.setTextColor(0xFF8E8E93);
        tvOffsetVal.setText(clockConfig.offsetY == 0 ? "0dp (圆心)" : (clockConfig.offsetY > 0 ? "+" : "") + clockConfig.offsetY + "dp");
        offsetHeader.addView(tvOffsetVal, new LinearLayout.LayoutParams(-2, -2));
        content.addView(offsetHeader);

        final SeekBar sbOffset = new SeekBar(this);
        sbOffset.setMax(80);
        sbOffset.setProgress(Math.max(0, Math.min(80, clockConfig.offsetY + 40)));
        sbOffset.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int offset = progress - 40;
                clockConfig.offsetY = offset;
                tvOffsetVal.setText(offset == 0 ? "0dp (圆心)" : (offset > 0 ? "+" : "") + offset + "dp");
                previewImage.applyAodClockConfig(clockConfig);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams sbLp3 = new LinearLayout.LayoutParams(-1, -2);
        sbLp3.topMargin = ui.dp(4);
        sbLp3.bottomMargin = ui.dp(10);
        content.addView(sbOffset, sbLp3);

        // ---- 6. 农历开关 ----
        LinearLayout lunarRow = new LinearLayout(this);
        lunarRow.setOrientation(LinearLayout.HORIZONTAL);
        lunarRow.setGravity(Gravity.CENTER_VERTICAL);
        lunarRow.setPadding(0, ui.dp(4), 0, ui.dp(10));

        LinearLayout lunarTextCol = new LinearLayout(this);
        lunarTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView lblLunar = new TextView(this);
        lblLunar.setText("显示农历信息");
        lblLunar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblLunar.setTextColor(0xFFE5E5EA);
        lblLunar.setTypeface(null, Typeface.BOLD);
        lunarTextCol.addView(lblLunar);

        TextView subLunar = new TextView(this);
        subLunar.setText("在日期右侧显示阴历月份与传统节气");
        subLunar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subLunar.setTextColor(0xFF8E8E93);
        lunarTextCol.addView(subLunar);
        lunarRow.addView(lunarTextCol, new LinearLayout.LayoutParams(0, -2, 1f));

        final Switch swLunar = new Switch(this);
        swLunar.setChecked(clockConfig.showLunar);
        swLunar.setOnCheckedChangeListener((buttonView, isChecked) -> {
            clockConfig.showLunar = isChecked;
            previewImage.applyAodClockConfig(clockConfig);
        });
        lunarRow.addView(swLunar, new LinearLayout.LayoutParams(-2, -2));
        content.addView(lunarRow);

        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, ui.dp(360)));

        // 恢复默认按钮逻辑
        tvReset.setOnClickListener(v -> {
            ClockSettings.Config def = new ClockSettings.Config();
            clockConfig.style = def.style;
            clockConfig.color = def.color;
            clockConfig.scale = def.scale;
            clockConfig.weight = def.weight;
            clockConfig.offsetY = def.offsetY;
            clockConfig.showLunar = def.showLunar;
            previewImage.applyAodClockConfig(clockConfig);

            // 刷新卡片与颜色状态
            for (int j = 0; j < ClockSettings.STYLE_COUNT; j++) {
                boolean sel = (j == clockConfig.style);
                GradientDrawable bg = ui.rounded(sel ? 0xFF1E293B : 0xFF2A2A2C, 16);
                if (sel) bg.setStroke(ui.dp(2), 0xFF2F6BFF);
                styleCards[j].setBackground(bg);
            }
            for (int j = 0; j < pickers.size(); j++) {
                boolean sel = (ClockSettings.PRESET_COLORS[j] == clockConfig.color);
                if (sel) {
                    GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setColor(0x00000000);
                    ring.setStroke(ui.dp(2), 0xFFFFFFFF);
                    pickers.get(j).setBackground(ring);
                } else {
                    pickers.get(j).setBackground(null);
                }
            }
            sbScale.setProgress(30);
            tvScaleVal.setText("1.00x");
            sbWeight.setProgress(250);
            tvWeightVal.setText("500 (标准)");
            sbOffset.setProgress(40);
            tvOffsetVal.setText("0dp (圆心)");
            swLunar.setChecked(false);
            toast("已恢复为 Flyme 官方默认时钟样式");
        });

        // 初始化卡片边框与色彩圆环
        for (int j = 0; j < ClockSettings.STYLE_COUNT; j++) {
            boolean sel = (j == clockConfig.style);
            GradientDrawable bg = ui.rounded(sel ? 0xFF1E293B : 0xFF2A2A2C, 16);
            if (sel) bg.setStroke(ui.dp(2), 0xFF2F6BFF);
            styleCards[j].setBackground(bg);
        }
        for (int j = 0; j < pickers.size(); j++) {
            boolean sel = (ClockSettings.PRESET_COLORS[j] == clockConfig.color);
            if (sel) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(0x00000000);
                ring.setStroke(ui.dp(2), 0xFFFFFFFF);
                pickers.get(j).setBackground(ring);
            } else {
                pickers.get(j).setBackground(null);
            }
        }

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
    }

    private LinearLayout addRoundButton(String label, String[] iconNames, int iconTint,
                                        View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(88), -2);
        item.setLayoutParams(lp);

        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(ui.rounded(0xFF232326, 40));
        ImageView iv = new ImageView(this);
        Drawable d = icon(iconNames);
        if (d != null) {
            iv.setImageDrawable(d);
            if (iconTint != 0 && iconTint != 0xFFFFFFFF) {
                iv.setColorFilter(iconTint);
            }
            circle.addView(iv, new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        } else {
            TextView glyph = new TextView(this);
            glyph.setText("◍");
            glyph.setTextColor(0xFFFFFFFF);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            circle.addView(glyph, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        item.addView(circle, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(0xFFEDEDED);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = ui.dp(8);
        item.addView(tv, tp);

        item.setClickable(true);
        item.setOnClickListener(click);
        buttonRow.addView(item);
        return item;
    }

    /** 取系统设置里的图标（找不到返回 null）。 */
    private Drawable icon(String[] names) {
        for (String n : names) {
            try {
                if (n.startsWith("@self:")) {
                    // 本模块自带的矢量图
                    String res = n.substring(6);
                    int id = "ic_crop".equals(res) ? R.drawable.ic_crop
                            : "ic_texture".equals(res) ? R.drawable.ic_texture
                            : R.drawable.ic_effect;
                    return getResources().getDrawable(id, null);
                }
                int id = ui.id("drawable", n);
                if (id != 0) {
                    return ui.res.getDrawable(id, ui.theme);
                }
            } catch (Throwable ignored) {
            }
        }
        Log.w(TAG, "圆按钮图标未找到：" + java.util.Arrays.toString(names));
        return null;
    }

    private TextView pillButton(String text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(ui.rounded(bg, 18));
        tv.setClickable(true);
        return tv;
    }

    private LinearLayout createActionCard(String title, String desc, String[] icons, int iconTint,
                                          View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ui.rounded(0xFF2E2E32, 14));
        card.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        card.setClickable(true);
        card.setOnClickListener(click);

        ImageView iv = new ImageView(this);
        Drawable d = icon(icons);
        if (d != null) {
            iv.setImageDrawable(d);
            if (iconTint != 0 && iconTint != 0xFFFFFFFF) {
                iv.setColorFilter(iconTint);
            }
        }
        card.addView(iv, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tLp.leftMargin = ui.dp(10);
        texts.setLayoutParams(tLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTypeface(null, Typeface.BOLD);
        texts.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvDesc.setTextColor(0xFF8E8E93);
        texts.addView(tvDesc);

        card.addView(texts);
        return card;
    }

    private void showAodStyleDialog() {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x75000000);
        root.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ui.rounded(0xFF1E1E20, 24));
        sheet.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(24));
        sheet.setClickable(true);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(sheet, sheetLp);

        View dragBar = new View(this);
        dragBar.setBackground(ui.rounded(0x40FFFFFF, 2));
        LinearLayout.LayoutParams dragLp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        dragLp.gravity = Gravity.CENTER_HORIZONTAL;
        dragLp.bottomMargin = ui.dp(12);
        sheet.addView(dragBar, dragLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("选择息屏视觉样式");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = ui.dp(16);
        sheet.addView(tvTitle, titleLp);

        LinearLayout cardRow = new LinearLayout(this);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        cardRow.setGravity(Gravity.CENTER);

        int curStyle = mPendingAodVisualStyle != -1
                ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
        int[] styles = {AodStyleSettings.STYLE_SET_SAIL, AodStyleSettings.STYLE_LEAVE_LIGHT};
        String[] titles = {"启航", "留光"};
        String[] subs = {"圆形透镜 · 自转扩散", "拍立得相框 · 倾斜绽放"};
        String[] assets = {"preview/aod_qihang.png", "preview/aod_liuguang.png"};

        final FrameLayout[] cardViews = new FrameLayout[styles.length];

        // 留光相片设置专区（参考 OpenAlive）
        final LinearLayout panelLiuguang = new LinearLayout(this);
        panelLiuguang.setOrientation(LinearLayout.VERTICAL);
        panelLiuguang.setBackground(ui.rounded(0xFF242427, 18));
        panelLiuguang.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.topMargin = ui.dp(14);
        panelLiuguang.setLayoutParams(panelLp);

        // 标题行：左侧标题与副标题，右侧「樱花示例」快捷载入
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvPanelTitle = new TextView(this);
        tvPanelTitle.setText("留光相片设置");
        tvPanelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvPanelTitle.setTextColor(0xFFFFFFFF);
        tvPanelTitle.setTypeface(null, Typeface.BOLD);
        textCol.addView(tvPanelTitle);

        TextView tvPanelSub = new TextView(this);
        tvPanelSub.setText("拍立得相框与全屏过渡照片");
        tvPanelSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvPanelSub.setTextColor(0xFF8E8E93);
        textCol.addView(tvPanelSub);
        headerRow.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvExample = new TextView(this);
        tvExample.setText("示例相片");
        tvExample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvExample.setTextColor(0xFF60A5FA);
        tvExample.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4));
        tvExample.setClickable(true);
        tvExample.setOnClickListener(v -> {
            boolean ok = PhotoStore.loadDefaultExample(EditorActivity.this, PhotoStore.FRAME);
            if (PhotoStore.load(EditorActivity.this, PhotoStore.LOCK) == null) {
                PhotoStore.loadDefaultExample(EditorActivity.this, PhotoStore.LOCK);
            }
            if (ok) {
                previewImage.reloadPhoto();
                selectScene(Motion.AOD);
                previewImage.startScene();
                toast("已载入示例相片");
                append("已载入示例相片到相框");
            } else {
                toast("未能载入示例相片");
            }
        });
        headerRow.addView(tvExample);
        panelLiuguang.addView(headerRow);

        // 按钮行：相框照片（自选） + 调整取景（裁剪）
        LinearLayout btnGrid = new LinearLayout(this);
        btnGrid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnGridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnGridLp.topMargin = ui.dp(10);
        btnGrid.setLayoutParams(btnGridLp);

        LinearLayout btnChoose = createActionCard(
                "相框照片", "从系统相册选取",
                new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"}, 0xFFFFFFFF,
                v -> {
                    dialog.dismiss();
                    pick(PhotoStore.FRAME);
                });
        LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bcLp.rightMargin = ui.dp(8);
        btnGrid.addView(btnChoose, bcLp);

        LinearLayout btnCrop = createActionCard(
                "调整取景", "缩放拖拽裁切",
                new String[]{"@self:ic_crop", "ic_crop"}, 0xFF7EB6FF,
                v -> {
                    dialog.dismiss();
                    cropExistingPhoto(PhotoStore.FRAME);
                });
        btnGrid.addView(btnCrop, new LinearLayout.LayoutParams(0, -2, 1f));

        panelLiuguang.addView(btnGrid);
        panelLiuguang.setVisibility(curStyle == AodStyleSettings.STYLE_LEAVE_LIGHT ? View.VISIBLE : View.GONE);

        for (int i = 0; i < styles.length; i++) {
            final int styleId = styles[i];
            FrameLayout card = new FrameLayout(this);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) cardLp.leftMargin = ui.dp(12);
            card.setLayoutParams(cardLp);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12));

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try (InputStream in = getAssets().open(assets[i])) {
                thumb.setImageBitmap(BitmapFactory.decodeStream(in));
            } catch (Throwable t) {
                Log.w(TAG, "加载缩略图失败: " + assets[i], t);
            }
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72));
            thumbLp.bottomMargin = ui.dp(8);
            content.addView(thumb, thumbLp);

            TextView title = new TextView(this);
            title.setText(titles[i]);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTextColor(0xFFFFFFFF);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cardTitleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardTitleLp.gravity = Gravity.CENTER_HORIZONTAL;
            content.addView(title, cardTitleLp);

            TextView sub = new TextView(this);
            sub.setText(subs[i]);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            sub.setTextColor(0x90FFFFFF);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subTextLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subTextLp.gravity = Gravity.CENTER_HORIZONTAL;
            subTextLp.topMargin = ui.dp(2);
            content.addView(sub, subTextLp);

            card.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            boolean selected = (curStyle == styleId);
            GradientDrawable bg = ui.rounded(selected ? 0x253B82F6 : 0x15FFFFFF, 16);
            if (selected) {
                bg.setStroke(ui.dp(2), 0xFF3B82F6);
            }
            card.setBackground(bg);
            card.setClickable(true);
            cardViews[i] = card;

            final String selectedName = titles[i];
            card.setOnClickListener(v -> {
                if (desktopSource == LastSet.KIND_DYNAMIC || wallpaper != null) {
                    wallpaper = null;
                    fromLibrary = false;
                    followLock = prefs.getBoolean("home_follow_lock", true);
                    desktopSource = (!followLock && homeBmp != null)
                            ? LastSet.KIND_PHOTO : LastSet.KIND_FOLLOW;
                    previewImage.setDynamicComponent(null);
                    previewImage.setHomeKind(desktopSource);
                    previewImage.reloadPhoto();
                }
                mPendingAodVisualStyle = styleId;
                previewImage.setAodStyle(styleId);
                if (styleId == AodStyleSettings.STYLE_LEAVE_LIGHT && !PhotoStore.has(EditorActivity.this, PhotoStore.FRAME)) {
                    new Thread(() -> {
                        PhotoStore.syncFrameToLockWithDefaultCrop(EditorActivity.this);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                previewImage.reloadPhoto();
                                sendReloadPhoto();
                            }
                        });
                    }, "SyncFramePhotoOnSelect").start();
                }
                selectScene(Motion.AOD);

                for (int j = 0; j < styles.length; j++) {
                    boolean sel = (styles[j] == styleId);
                    GradientDrawable b = ui.rounded(sel ? 0x253B82F6 : 0x15FFFFFF, 16);
                    if (sel) b.setStroke(ui.dp(2), 0xFF3B82F6);
                    cardViews[j].setBackground(b);
                }
                panelLiuguang.setVisibility(styleId == AodStyleSettings.STYLE_LEAVE_LIGHT ? View.VISIBLE : View.GONE);
                append("息屏视觉样式暂存为：" + selectedName + "（点击右上角「应用」后生效）");
            });

            cardRow.addView(card);
        }

        sheet.addView(cardRow);
        sheet.addView(panelLiuguang);

        TextView btnDone = pillButton("完成", 0xFF2F6BFF, 0xFFFFFFFF);
        btnDone.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40));
        doneLp.topMargin = ui.dp(16);
        sheet.addView(btnDone, doneLp);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
    }

    // =====================================================================
    // 动作
    // =====================================================================

    /** 「应用」：动态壁纸模式用 which=3 同时设桌面+锁屏+息屏；自绘照片模式原子设双槽一镜到底。 */
    private void applyScene() {
        if (mPendingAodVisualStyle != -1) {
            AodStyleSettings.setStyle(this, mPendingAodVisualStyle);
        }
        if (mPendingAodConfig != null || mPendingLockConfig != null) {
            ClockSettings.save(this, mPendingAodConfig, mPendingLockConfig);
            append("时钟样式已保存生效（熄屏+锁屏独立配置）");
        }
        LastSet.save(this, desktopSource, wallpaper);
        run0("应用", () -> {
            StringBuilder sb = new StringBuilder();
            append("…应用开始");
            if (desktopSource == LastSet.KIND_DYNAMIC && wallpaper != null && !wallpaper.isEmpty()) {
                if (wallpaper.startsWith(ALIVE_PKG + "/")) {
                    String db = Root.appProcess(this, "com.qihang.aod.root.LspDb", "-get", getPackageName());
                    if (!db.contains(ALIVE_PKG)) {
                        append("⓪ 同步 LSPosed 作用域加入 " + ALIVE_PKG);
                        Root.lsposedSync(this,
                                new String[]{"system", "com.android.systemui", "miui.systemui.plugin", "com.miui.aod", ALIVE_PKG});
                    }
                }
                append("① 动态壁纸统管桌面+锁屏+息屏 (which=3)：" + wallpaper);
                sb.append("动态壁纸(三态)=").append(setWallpaperFromLibrary(3));
                LastSet.save(this, desktopSource, wallpaper);
                sendMode(Motion.HOME, "应用动态壁纸");
                return sb.toString();
            }
            // ★ 自绘照片模式（跟随锁屏 或 独立桌面照片）：
            //   在单次 root app_process 调用中原子设置桌面(which=1 -> QihangHomeWallpaper)
            //   与锁屏/息屏(which=2 -> QihangWallpaper)两个槽位，确保从动态壁纸切回自定义壁纸时
            //   息屏、锁屏、桌面全部切换为自定义壁纸样式，绝不残留动态壁纸！
            wallpaper = null;
            fromLibrary = false;
            if (desktopSource == LastSet.KIND_DYNAMIC) {
                desktopSource = followLock ? LastSet.KIND_FOLLOW : LastSet.KIND_PHOTO;
            }
            LastSet.save(this, desktopSource, null);
            append("① 原子设置双槽自定义壁纸 (which=1 桌面 + which=2 锁屏/息屏)");
            String dualRes = Root.setDualWallpaper(this,
                    getPackageName(),
                    getPackageName() + ".QihangHomeWallpaper",
                    getPackageName() + ".QihangWallpaper");
            sb.append("双槽自定义壁纸=").append(dualRes);
            sb.append(" · 模式=").append(desktopSource == LastSet.KIND_FOLLOW ? "跟随锁屏" : "独立桌面(渐变过渡)");
            sendReloadPhoto();
            return sb.toString();
        });
        toast("已应用");
    }

/** 从壁纸库点进来的那张动态壁纸 → 应用到指定槽位（which=3 同时设桌面+锁屏+息屏）。 */
    private String setWallpaperFromLibrary(int which) {
        int slash = wallpaper.indexOf('/');
        if (slash <= 0) {
            return "无效组件 " + wallpaper;
        }
        return Root.setWallpaper(this, wallpaper.substring(0, slash),
                wallpaper.substring(slash + 1), which);
    }
}
