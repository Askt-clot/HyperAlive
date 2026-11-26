package com.hyperalive.aod;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
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
 *   │        预览（圆角大卡）        │   息屏 / 锁屏 / 桌面 的实时效果图
 *   │   [ 息屏 | 锁屏 | 桌面 ] 胶囊  │
 *   └──────────────────────────────┘
 *        ⭕ 风格        ⭕ 壁纸  …         各场景自己的圆形按钮行
 * </pre>
 *
 * 按钮按场景区分：
 * 息屏=息屏样式/壁纸(或相框照片+调整取景)/时间样式；锁屏=时间样式/壁纸；桌面=跟随锁屏/壁纸。
 */
public class EditorActivity extends UiActivity {

    private static final String EXTRA_SCENE = "scene";
    private static final String EXTRA_WP = "wp";
    private static final java.util.concurrent.atomic.AtomicLong sCommittedApplySeq =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicBoolean APPLY_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    private static final String STATE_DRAFT_SESSION = "draft_session_id";
    private static final String STATE_SCENE = "state_scene";
    private static final String STATE_WP = "state_wp";
    private static final String STATE_FOLLOW_LOCK = "state_follow_lock";
    private static final String STATE_DESKTOP_SOURCE = "state_desktop_source";
    private static final String STATE_FROM_LIBRARY = "state_from_library";
    private static final String STATE_PENDING_STYLE = "state_pending_style";
    private static final String STATE_AOD_POSITIONS_EDITED = "state_aod_positions_edited";

    private int scene = Motion.LOCK;
    /** 选中的动态壁纸组件（来自壁纸库），非空时锁屏/桌面预览都显示它。 */
    private String wallpaper;
    /** 当前编辑器会话专属草稿 ID（旋转重建保持，取消/退出丢弃，仅在顶层「应用」时提交）。 */
    private String draftSessionId;
    private boolean mHasUserEdited = false;
    private final PhotoDraftStore.StartupRecoveryListener mStartupRecoveryListener =
            new PhotoDraftStore.StartupRecoveryListener() {
                @Override
                public void onStartupRecoveryFinished(final PhotoDraftStore.RecoveryResult result) {
                    if (result == null || (!result.hasChanges() && result.ok)) {
                        return;
                    }
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || APPLY_IN_FLIGHT.get()) {
                            return;
                        }
                        if (result.hasChanges() && !mHasUserEdited) {
                            AodStyleSettings.resetCacheForTest();
                            mPendingAodConfig = ClockSettings.getAodDirect(EditorActivity.this);
                            mPendingLockConfig = ClockSettings.getLockDirect(EditorActivity.this);
                            mPendingAodVisualStyle = AodStyleSettings.getStyle(EditorActivity.this);
                            mPendingAodPositions = AodStyleSettings.getPositions(EditorActivity.this);
                            mPendingPhotoTextures = PhotoTextureSettings.get(EditorActivity.this);
                            if (!fromLibrary) {
                                desktopSource = LastSet.kind(EditorActivity.this);
                                if (desktopSource == LastSet.KIND_DYNAMIC) {
                                    String comp = LastSet.component(EditorActivity.this);
                                    wallpaper = (comp != null && !comp.isEmpty()) ? comp : null;
                                } else {
                                    wallpaper = null;
                                }
                                followLock = desktopSource == LastSet.KIND_FOLLOW
                                        || desktopSource == LastSet.KIND_DYNAMIC;
                            }
                            if (previewImage != null) {
                                updateAodStylePreview();
                                previewImage.setPhotoTextureStyles(mPendingPhotoTextures);
                                previewImage.setDynamicComponent(wallpaper);
                                previewImage.setHomeKind(desktopSource);
                                previewImage.reloadPhoto();
                            }
                            append("后台事务恢复完成：" + result.detail);
                        }
                        if (!result.ok) {
                            append("✖ 检测到未解决的事务恢复异常：" + result.detail);
                            toast("检测到未解决的事务恢复异常，已保留快照证据");
                        }
                    });
                }
            };
    /** 桌面是否跟随锁屏（默认跟随）。 */
    private boolean followLock = true;
    /** 桌面来源：0=壁纸库选的动态壁纸 1=跟随锁屏 2=独立照片。 */
    private int desktopSource = 1;
    private int mPendingPhotoTextures;
    /** 本次会话是否**真的**从壁纸库选了动态壁纸（只有这时才允许应用动态壁纸）。 */
    private boolean fromLibrary;
    private android.content.SharedPreferences prefs;
    private LivePreviewView previewImage;
    private TextView[] tabs;
    private LinearLayout buttonRow;
    private TextView applyBtn;
    private android.app.AlertDialog applyProgressDialog;
    private TextView applyProgressText;
    /** 暂存的时间样式草稿（熄屏与锁屏独立，用户在对话框调整，点右上角「应用」才真正生效落盘）。 */
    private ClockSettings.Config mPendingAodConfig = null;
    private ClockSettings.Config mPendingLockConfig = null;
    /** 息屏样式及各样式的位置在应用前保留为编辑草稿。 */
    private int mPendingAodVisualStyle = -1;
    private int[] mPendingAodPositions;
    private boolean mAodPositionsEdited;
    private boolean mAodDragActive;
    private boolean mAodDragging;
    private float mAodDragStartX;
    private float mAodDragStartY;
    private int mAodDragStartPosition;
    /** 系统导航栏 / 手势小白条底部 Inset（px）。 */
    private int mNavInsetBottom = 0;

    private boolean hasEffectiveHomePhoto() {
        return PhotoDraftStore.hasEffective(this, draftSessionId, PhotoStore.HOME);
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String restoredDraft = savedInstanceState != null
                    ? savedInstanceState.getString(STATE_DRAFT_SESSION) : null;
            draftSessionId = PhotoDraftStore.isValidSessionId(restoredDraft)
                    ? restoredDraft : PhotoDraftStore.newSessionId();
            PhotoDraftStore.acquireDraftLease(draftSessionId, this);
            PhotoDraftStore.addStartupRecoveryListener(mStartupRecoveryListener);
            PhotoDraftStore.triggerStartupRecoveryAsync(this);

            if (savedInstanceState == null) {
                final Context appCtx = getApplicationContext();
                final String keepSid = draftSessionId;
                Thread cleanupThread = new Thread(() -> {
                    PhotoDraftStore.awaitStartupRecoveryIfNonMainThread(appCtx);
                    PhotoDraftStore.cleanupStaleSessions(appCtx, keepSid);
                }, "QihangDraftCleanup");
                cleanupThread.setDaemon(true);
                cleanupThread.start();
            }

            prefs = getSharedPreferences("qihang_editor", MODE_PRIVATE);
            mPendingAodConfig = ClockSettings.getAodDirect(this);
            mPendingLockConfig = ClockSettings.getLockDirect(this);
            mPendingPhotoTextures = PhotoTextureSettings.get(this);
            mPendingAodPositions = AodStyleSettings.getPositions(this);

            if (savedInstanceState != null) {
                mPendingPhotoTextures = savedInstanceState.getInt(
                        PhotoTextureSettings.KEY, mPendingPhotoTextures);
                scene = savedInstanceState.getInt(STATE_SCENE, Motion.LOCK);
                wallpaper = savedInstanceState.getString(STATE_WP);
                followLock = savedInstanceState.getBoolean(STATE_FOLLOW_LOCK, true);
                desktopSource = savedInstanceState.getInt(STATE_DESKTOP_SOURCE, LastSet.KIND_FOLLOW);
                fromLibrary = savedInstanceState.getBoolean(STATE_FROM_LIBRARY, false);
                mPendingAodVisualStyle = savedInstanceState.getInt(
                        STATE_PENDING_STYLE, AodStyleSettings.getStyle(this));
                String positions = savedInstanceState.getString(AodStyleSettings.KEY_POSITIONS);
                if (positions != null) {
                    mPendingAodPositions = AodStyleSettings.decodePositions(positions);
                    mAodPositionsEdited = savedInstanceState.getBoolean(STATE_AOD_POSITIONS_EDITED);
                    mHasUserEdited |= mAodPositionsEdited;
                }
                ClockSettings.Config restoredAod = readClockBundle(savedInstanceState, "aod");
                if (restoredAod != null) mPendingAodConfig = restoredAod;
                ClockSettings.Config restoredLock = readClockBundle(savedInstanceState, "lock");
                if (restoredLock != null) mPendingLockConfig = restoredLock;
            } else {
                scene = getIntent().getIntExtra(EXTRA_SCENE, Motion.LOCK);
                wallpaper = getIntent().getStringExtra(EXTRA_WP);
                mPendingAodVisualStyle = AodStyleSettings.getStyle(this);
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
            }
            buildEditor();
            if (mPendingAodVisualStyle == AodStyleSettings.STYLE_LEAVE_LIGHT) {
                prepareDefaultFramePreview();
            }
        } catch (Throwable t) {
            Log.e(TAG, "编辑器构建失败", t);
            TextView tv = new TextView(this);
            tv.setText("界面构建失败：\n" + t);
            tv.setPadding(40, 120, 40, 40);
            setContentView(tv);
        }
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        if (PhotoDraftStore.isValidSessionId(draftSessionId)) {
            outState.putString(STATE_DRAFT_SESSION, draftSessionId);
        }
        outState.putInt(STATE_SCENE, scene);
        outState.putString(STATE_WP, wallpaper);
        outState.putBoolean(STATE_FOLLOW_LOCK, followLock);
        outState.putInt(STATE_DESKTOP_SOURCE, desktopSource);
        outState.putBoolean(STATE_FROM_LIBRARY, fromLibrary);
        outState.putInt(STATE_PENDING_STYLE, mPendingAodVisualStyle);
        if (mPendingAodPositions != null) {
            outState.putString(AodStyleSettings.KEY_POSITIONS,
                    AodStyleSettings.encodePositions(mPendingAodPositions));
            outState.putBoolean(STATE_AOD_POSITIONS_EDITED, mAodPositionsEdited);
        }
        outState.putInt(PhotoTextureSettings.KEY, mPendingPhotoTextures);
        writeClockBundle(outState, "aod", mPendingAodConfig);
        writeClockBundle(outState, "lock", mPendingLockConfig);
    }

    private static void writeClockBundle(android.os.Bundle b, String pfx, ClockSettings.Config cfg) {
        if (b == null || cfg == null) return;
        b.putBoolean("clk_" + pfx + "_on", cfg.enabled);
        b.putInt("clk_" + pfx + "_st", cfg.style);
        b.putInt("clk_" + pfx + "_cl", cfg.color);
        b.putFloat("clk_" + pfx + "_sc", cfg.scale);
        b.putInt("clk_" + pfx + "_wt", cfg.weight);
        b.putInt("clk_" + pfx + "_oy", cfg.offsetY);
        b.putBoolean("clk_" + pfx + "_ln", cfg.showLunar);
    }

    private static ClockSettings.Config readClockBundle(android.os.Bundle b, String pfx) {
        if (b == null || !b.containsKey("clk_" + pfx + "_st")) return null;
        ClockSettings.Config c = new ClockSettings.Config();
        c.enabled = b.getBoolean("clk_" + pfx + "_on", false);
        c.style = b.getInt("clk_" + pfx + "_st", ClockSettings.STYLE_DOUBLE_STACK);
        c.color = b.getInt("clk_" + pfx + "_cl", 0xFFFFFFFF);
        c.scale = b.getFloat("clk_" + pfx + "_sc", 1.0f);
        c.weight = b.getInt("clk_" + pfx + "_wt", 500);
        c.offsetY = b.getInt("clk_" + pfx + "_oy", 0);
        c.showLunar = b.getBoolean("clk_" + pfx + "_ln", false);
        return c;
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
        previewImage.setDraftSessionId(draftSessionId);
        previewImage.setPhotoTextureStyles(mPendingPhotoTextures);
        previewImage.setOnTouchListener(this::onAodPositionTouch);
        root.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));

        // ---- 顶栏：取消 / 应用（浮在上方）----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(20), statusBarHeight() + ui.dp(14), ui.dp(20), ui.dp(10));
        TextView cancel = pillButton("取消", 0xCC3A3A3C, 0xFFFFFFFF);
        TextView apply = pillButton("应用", ui.accentBlue(), 0xFFFFFFFF);
        applyBtn = apply;
        cancel.setOnClickListener(v -> {
            append("取消");
            mPendingAodConfig = null;
            mPendingLockConfig = null;
            mPendingAodVisualStyle = -1;
            PhotoDraftStore.releaseDraftLeaseAndDiscardIfIdle(this, draftSessionId, this);
            finish();
        });
        apply.setOnClickListener(v -> showApplyOptions());
        bar.addView(cancel, new LinearLayout.LayoutParams(ui.dp(74), ui.dp(36)));
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(apply, new LinearLayout.LayoutParams(ui.dp(74), ui.dp(36)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- 场景切换胶囊：息屏 | 锁屏 | 桌面（浮在底部按钮行上方）----
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setBackground(ui.roundedBorder(0xCC242426, 0x15FFFFFF, 0.5f, 24));
        pill.setClipToOutline(true);
        tabs = new TextView[3];
        String[] names = {"息屏", "锁屏", "桌面"};
        for (int i = 0; i < 3; i++) {
            final int s = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            t.setClickable(true);
            t.setOnClickListener(v -> selectScene(s));
            tabs[i] = t;
            pill.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(ui.dp(232), ui.dp(48),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        final int basePillBottom = ui.dp(122);
        pillLp.bottomMargin = basePillBottom;
        root.addView(pill, pillLp);

        // ---- 圆形按钮行（浮在底部）----
        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, ui.dp(6), 0, ui.dp(6));
        final int baseBtnBottom = ui.dp(18);
        FrameLayout.LayoutParams btnRowLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        btnRowLp.bottomMargin = baseBtnBottom;
        root.addView(buttonRow, btnRowLp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = UiActivity.getNavigationBarInsetBottom(v, insets);
            mNavInsetBottom = navBottom;
            pillLp.bottomMargin = basePillBottom + navBottom;
            pill.setLayoutParams(pillLp);
            btnRowLp.bottomMargin = baseBtnBottom + navBottom;
            buttonRow.setLayoutParams(btnRowLp);
            return insets;
        });

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
        if (s != Motion.AOD || desktopSource == LastSet.KIND_DYNAMIC) endAodPositionDrag();
        scene = s;
        for (int i = 0; i < 3; i++) {
            boolean on = i == s;
            tabs[i].setTextColor(on ? 0xFFFFFFFF : 0xFF8E8E93);
            tabs[i].setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
            if (on) {
                tabs[i].setBackground(ui.pillButtonDrawable(0xFF3A3A3E, ui.rippleColor(), 22));
            } else {
                tabs[i].setBackground(ui.itemRipple(22));
            }
        }
        previewImage.setDynamicComponent(wallpaper);
        previewImage.setHomeKind(desktopSource);
        updateAodStylePreview();
        previewImage.setScene(s);
        if (s == Motion.AOD && mPendingAodConfig != null) {
            previewImage.applyAodClockConfig(mPendingAodConfig);
        } else if (s == Motion.LOCK && mPendingLockConfig != null) {
            previewImage.applyAodClockConfig(mPendingLockConfig);
        }
        buildButtons(s);
        // 注意：预览图切三态纯属应用内效果展示，严禁调用 sendMode 给真机壁纸发全局广播！
    }

    private void updateAodStylePreview() {
        int style = AodStyleSettings.normalizeStyle(mPendingAodVisualStyle);
        previewImage.setAodStyle(style, mPendingAodPositions[style]);
    }

    private void updateAodPosition(int position) {
        int style = AodStyleSettings.normalizeStyle(mPendingAodVisualStyle);
        if (mPendingAodPositions[style] == position) return;
        mPendingAodPositions[style] = position;
        mAodPositionsEdited = mHasUserEdited = true;
        updateAodStylePreview();
    }

    private int currentAodPosition() {
        return mPendingAodPositions[AodStyleSettings.normalizeStyle(mPendingAodVisualStyle)];
    }

    private void endAodPositionDrag() {
        mAodDragActive = false;
        mAodDragging = false;
        previewImage.setAodPositionGuidesVisible(false);
    }

    private boolean onAodPositionTouch(View view, MotionEvent event) {
        if (scene != Motion.AOD || desktopSource == LastSet.KIND_DYNAMIC) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mAodDragActive = true;
                mAodDragging = false;
                mAodDragStartX = event.getX();
                mAodDragStartY = event.getY();
                mAodDragStartPosition = currentAodPosition();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mAodDragActive || view.getWidth() <= 0 || view.getHeight() <= 0) break;
                float dx = event.getX() - mAodDragStartX;
                float dy = event.getY() - mAodDragStartY;
                int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                if (!mAodDragging && Math.hypot(dx, dy) < slop) break;
                mAodDragging = true;
                previewImage.setAodPositionGuidesVisible(true);
                updateAodPosition(AodStyleSettings.packPosition(
                        AodStyleSettings.positionX(mAodDragStartPosition) + dx / view.getWidth(),
                        AodStyleSettings.positionY(mAodDragStartPosition) - dy / view.getHeight()));
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endAodPositionDrag();
                break;
        }
        return true;
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
            if (desktopSource != LastSet.KIND_DYNAMIC) {
                addRoundButton("纹理", new String[]{"ic_wallpaper_texture_btn_icon", "@self:ic_wallpaper"},
                        0xFF7EB6FF, v -> showPhotoTextureDialog(Motion.LOCK));
            }
        } else {
            // 桌面：跟随锁屏 / 单独选一张
                    LinearLayout follow = addRoundButton("跟随锁屏",
                    new String[]{"ic_lock", "flip_lock_screen_icon"}, 0xFF7EB6FF, v -> {
                        mHasUserEdited = true;
                        followLock = !followLock;
                        if (followLock) {
                            desktopSource = wallpaper != null ? LastSet.KIND_DYNAMIC : LastSet.KIND_FOLLOW;
                        } else if (hasEffectiveHomePhoto()) {
                            desktopSource = LastSet.KIND_PHOTO;
                        }
                        append("桌面跟随锁屏(草稿) = " + followLock + "（来源=" + desktopSource + "）");
                        previewImage.setDynamicComponent(wallpaper);
                        previewImage.setHomeKind(desktopSource);
                        previewImage.reloadPhoto();
                        selectScene(Motion.HOME);
                    });
            if (followLock) {
                GradientDrawable ring = ui.rounded(0x00000000, 30);
                ring.setStroke(ui.dp(2), ui.accentBlue());
                ((FrameLayout) follow.getChildAt(0)).setBackground(ring);
            }
            addRoundButton("壁纸", new String[]{"ic_homepage_wallpaper", "ic_settings_wallpaper_filled"},
                    0xFFFFFFFF, v -> pick(PhotoStore.HOME));
            if (desktopSource != LastSet.KIND_DYNAMIC) {
                addRoundButton("纹理", new String[]{"ic_wallpaper_texture_btn_icon", "@self:ic_wallpaper"},
                        0xFF7EB6FF, v -> showPhotoTextureDialog(Motion.HOME));
            }
        }
    }

    private void showPhotoTextureDialog(int targetScene) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x36000000);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setBackground(ui.rounded(0xFF1E1E22, 24));
        int padding = ui.dp(14);
        sheet.setPadding(padding, padding, padding, padding + mNavInsetBottom);
        scrim.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = UiActivity.getNavigationBarInsetBottom(v, insets);
            sheet.setPadding(padding, padding, padding, padding + bottom);
            return insets;
        });
        scrim.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        boolean desktop = targetScene == Motion.HOME;
        TextView title = new TextView(this);
        title.setText(desktop ? "桌面纹理" : "锁屏纹理");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setGravity(Gravity.CENTER);
        sheet.addView(title, new LinearLayout.LayoutParams(-1, ui.dp(36)));

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = ui.dp(10);
        sheet.addView(row, rowParams);
        String[] labels = {"无", "长虹", "波浪", "雾花"};
        String[] thumbnails = {"source", "straight_line", "curve", "frosted_glass"};
        LinearLayout[] cards = new LinearLayout[labels.length];
        int selected = desktop ? PhotoTextureSettings.home(mPendingPhotoTextures)
                : PhotoTextureSettings.lock(mPendingPhotoTextures);
        for (int i = 0; i < labels.length; i++) {
            final int style = i;
            LinearLayout card = new LinearLayout(this);
            cards[i] = card;
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(6));
            card.setForeground(ui.itemRipple(14));
            card.setFocusable(true);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) cell.setMarginStart(ui.dp(6));
            row.addView(card, cell);

            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try (InputStream in = getAssets().open("preview/textures/" + thumbnails[i] + ".png")) {
                preview.setImageBitmap(BitmapFactory.decodeStream(in));
            } catch (java.io.IOException error) {
                Log.w(TAG, "读取纹理缩略图失败", error);
            }
            card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(70)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setGravity(Gravity.CENTER);
            card.addView(label, new LinearLayout.LayoutParams(-1, ui.dp(26)));
            markPhotoTextureCard(card, selected == i);
            card.setOnClickListener(v -> {
                int lock = desktop ? PhotoTextureSettings.lock(mPendingPhotoTextures) : style;
                int home = desktop ? style : PhotoTextureSettings.home(mPendingPhotoTextures);
                mPendingPhotoTextures = PhotoTextureSettings.selection(lock, home);
                mHasUserEdited = true;
                previewImage.setPhotoTextureStyles(mPendingPhotoTextures);
                for (int j = 0; j < cards.length; j++) {
                    markPhotoTextureCard(cards[j], j == style);
                }
                append((desktop ? "桌面纹理" : "锁屏纹理") + "草稿：" + labels[style]);
            });
        }

        BottomSheetController controller = new BottomSheetController(dialog, scrim, sheet, null);
        scrim.setOnClickListener(v -> controller.dismissWithAnimation());
        TextView done = pillButton("完成", ui.accentBlue(), 0xFFFFFFFF);
        done.setOnClickListener(v -> controller.dismissWithAnimation());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, ui.dp(40));
        doneParams.topMargin = ui.dp(14);
        sheet.addView(done, doneParams);
        controller.setupAndShow();
    }

    private void markPhotoTextureCard(View card, boolean selected) {
        card.setSelected(selected);
        GradientDrawable background = ui.rounded(selected ? 0x250D84FF : 0xFF28282C, 14);
        background.setStroke(ui.dp(selected ? 2 : 1), selected ? ui.accentBlue() : 0x14FFFFFF);
        card.setBackground(background);
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
            String retDraft = data != null ? data.getStringExtra(PhotoCropActivity.EXTRA_DRAFT_SESSION) : null;
            if (!PhotoDraftStore.isValidSessionId(retDraft) || !retDraft.equals(draftSessionId)) {
                append("忽略失效的裁切草稿结果");
                return;
            }
            if (isFrame) {
                // 留光相框草稿刷新（仅更新预览，不改正式文件与广播）
                mHasUserEdited = true;
                previewImage.reloadPhoto();
                selectScene(Motion.AOD);
                previewImage.startScene();
            } else if (isLock) {
                // 选了自定义锁屏照片 → 切换到本模块一镜到底自绘草稿模式（暂不写入偏好与正式文件）
                mHasUserEdited = true;
                wallpaper = null;
                fromLibrary = false;
                if (desktopSource == LastSet.KIND_DYNAMIC) {
                    desktopSource = LastSet.KIND_FOLLOW;
                    followLock = true;
                }
                int curAodStyle = mPendingAodVisualStyle != -1
                        ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
                if (curAodStyle == AodStyleSettings.STYLE_LEAVE_LIGHT) {
                    final String targetSession = draftSessionId;
                    new Thread(() -> {
                        PhotoDraftStore.syncFrameToLockDraftWithDefaultCrop(
                                EditorActivity.this, targetSession);
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()
                                    && targetSession.equals(draftSessionId)) {
                                previewImage.reloadPhoto();
                            }
                        });
                    }, "SyncFramePhotoDraft").start();
                }
                previewImage.setDynamicComponent(wallpaper);
                previewImage.setHomeKind(desktopSource);
                previewImage.reloadPhoto();
                selectScene(scene == Motion.AOD ? Motion.AOD : Motion.LOCK);
                previewImage.startScene();
            } else {
                mHasUserEdited = true;
                wallpaper = null;
                fromLibrary = false;
                followLock = false;
                desktopSource = LastSet.KIND_PHOTO;
                previewImage.setDynamicComponent(wallpaper);
                previewImage.setHomeKind(desktopSource);
                previewImage.reloadPhoto();
                selectScene(Motion.HOME);
                previewImage.startScene();
            }
            android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            try {
                java.io.File df = PhotoDraftStore.slotFile(this, draftSessionId, name);
                if (df.isFile()) {
                    android.graphics.BitmapFactory.decodeFile(df.getAbsolutePath(), bo);
                    if (bo.outWidth > 0 && bo.outHeight > 0) {
                        append("裁切完成(草稿)：" + name + " " + bo.outWidth + "x" + bo.outHeight);
                    }
                }
            } catch (Throwable ignored) {
            }
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
        final boolean isLiuguang = (request == REQ_FRAME);
        final String targetSession = draftSessionId;
        run0("读取照片并进入裁切 " + name, () -> {
            java.io.File staged = PhotoDraftStore.stageFromUri(this, targetSession, uri, name);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !targetSession.equals(draftSessionId)) {
                    staged.delete();
                    return;
                }
                startActivityForResult(
                        PhotoCropActivity.intent(this, staged.getName(), name, isLiuguang, targetSession, true),
                        cropReq);
            });
            return staged.getName();
        });
    }

    /** 直接对当前已有照片进行裁切与调整取景（仅写入草稿，不修改已应用壁纸）。 */
    private void cropExistingPhoto(String name) {
        final boolean isFrame = PhotoStore.FRAME.equals(name);
        final boolean isLock = PhotoStore.LOCK.equals(name);
        final int cropReq = isFrame ? REQ_CROP_FRAME : isLock ? REQ_CROP_LOCK : REQ_CROP_HOME;
        final boolean isLiuguang = isFrame;
        final String targetSession = draftSessionId;
        run0("读取照片并进入裁切 " + name, () -> {
            boolean frameChanged = isFrame && PhotoDraftStore.ensureFrameDraft(this, targetSession);
            java.io.File staged = PhotoDraftStore.stageExistingOrFallback(this, targetSession, name);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !targetSession.equals(draftSessionId)) {
                    if (staged != null) staged.delete();
                    return;
                }
                if (staged == null) {
                    toast("未能准备好相片");
                    return;
                }
                if (frameChanged) previewImage.reloadPhoto();
                startActivityForResult(
                        PhotoCropActivity.intent(this, staged.getName(), name, isLiuguang, targetSession, false),
                        cropReq);
            });
            return staged != null ? staged.getName() : null;
        });
    }

    private void prepareDefaultFramePreview() {
        final String targetSession = draftSessionId;
        new Thread(() -> {
            if (PhotoDraftStore.ensureFrameDraft(this, targetSession)) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && previewImage != null
                            && targetSession.equals(draftSessionId)) {
                        previewImage.reloadPhoto();
                    }
                });
            }
        }, "PrepareFrameDraftPreview").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewImage != null) {
            updateAodStylePreview();
            previewImage.startScene();
        }
    }

    @Override
    protected void onPause() {
        if (previewImage != null) {
            endAodPositionDrag();
            previewImage.stopScene();
        }
        super.onPause();
    }

    // =====================================================================
    // 锁屏 / 熄屏时间样式与个性化面板（二级页面熄屏预览图下触发）
    // =====================================================================

    /** 检查系统全局动画开关（开发者选项 Animator 缩放与无障碍消除动画）。 */
    private boolean isAnimationEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    return false;
                }
            }
            float scale = Settings.Global.getFloat(
                    getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f);
            return scale > 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 底部面板动画、手势返回与安全区控制器：
     * 1. 统一管理 Dialog Window 初始化与 setWindowAnimations(0)；
     * 2. 入场动效：首帧前利用 OnPreDrawListener 将面板下移自身高度，杜绝首帧闪现，随后 280ms 减速滑入，遮罩 alpha 0 -> 0x75；
     * 3. 退出动效：正常返回（返回键、Android 13+ 返回手势、点「完成」、点遮罩）时，从当前位置向下滑出自身高度，约 220–250ms 加速离场，遮罩同步淡出至 0，动画结束真正 dismiss；
     * 4. 快速返回衔接：若入场进行中触发返回，平滑衔接当前 translationY 向下滑出，重复触发不穿透到底层编辑器，也不重启动画；
     * 5. 立即关闭：系统动画关闭、窗口未就绪或选择相框照片/裁剪跳转时，立即 dismiss，保持原跳转与系统入场动效，防止 Activity 销毁后执行迟到回调；
     * 6. 状态保存：保证 ClockSettings.save 仅执行且必执行一次；
     * 7. 导航栏适配：底板延伸至系统手势小白条背后，内容区为底部操作按钮保留 12–16dp 安全区间隔。
     */
    private final class BottomSheetController {
        private final Dialog dialog;
        private final View rootMask;
        private final View sheet;
        private final Runnable onDismissSave;

        private boolean mIsExiting = false;
        private boolean mIsDismissed = false;
        private boolean mSaveExecuted = false;

        private ValueAnimator mMaskAnim = null;
        private int mMeasuredSheetHeight = 0;

        BottomSheetController(Dialog dialog, View rootMask, View sheet, Runnable onDismissSave) {
            this.dialog = dialog;
            this.rootMask = rootMask;
            this.sheet = sheet;
            this.onDismissSave = onDismissSave;
        }

        public void setupAndShow() {
            dialog.setContentView(rootMask);

            Window w = dialog.getWindow();
            if (w != null) {
                w.setWindowAnimations(0);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                w.setStatusBarColor(0x00000000);
                w.setNavigationBarColor(0x00000000);
                if (Build.VERSION.SDK_INT >= 29) {
                    w.setNavigationBarContrastEnforced(false);
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    w.setDecorFitsSystemWindows(false);
                }
                View decor = w.getDecorView();
                if (decor != null) {
                    int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                    decor.setSystemUiVisibility(flags);
                }
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setGravity(Gravity.BOTTOM);
            }
            dialog.setOnDismissListener(d -> ensureSaved());

            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismissWithAnimation();
                    return true;
                }
                return false;
            });

            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    android.window.OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
                    if (dispatcher != null) {
                        dispatcher.registerOnBackInvokedCallback(
                                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                this::dismissWithAnimation);
                    }
                } catch (Throwable ignored) {
                }
            }

            sheet.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {}

                @Override
                public void onViewDetachedFromWindow(View v) {
                    cancelAnimators();
                    ensureSaved();
                }
            });

            if (!isAnimationEnabled()) {
                rootMask.setBackgroundColor(0x75000000);
                sheet.setTranslationY(0f);
                dialog.show();
                if (w != null) {
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                return;
            }

            rootMask.setBackgroundColor(0x00000000);

            sheet.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (!sheet.getViewTreeObserver().isAlive()) {
                        return true;
                    }
                    sheet.getViewTreeObserver().removeOnPreDrawListener(this);

                    if (!dialog.isShowing() || mIsExiting || mIsDismissed) {
                        return true;
                    }

                    int h = sheet.getHeight();
                    if (h <= 0) h = sheet.getMeasuredHeight();
                    if (h <= 0) h = ui != null ? ui.dp(420) : (int) (getResources().getDisplayMetrics().density * 420f);
                    mMeasuredSheetHeight = h;

                    sheet.setTranslationY((float) h);

                    sheet.animate()
                            .translationY(0f)
                            .setDuration(280)
                            .setInterpolator(new DecelerateInterpolator(1.8f))
                            .start();

                    ValueAnimator maskAnim = ValueAnimator.ofInt(0, 0x75);
                    mMaskAnim = maskAnim;
                    maskAnim.setDuration(280);
                    maskAnim.setInterpolator(new DecelerateInterpolator(1.8f));
                    maskAnim.addUpdateListener(anim -> {
                        int alpha = (Integer) anim.getAnimatedValue();
                        rootMask.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
                    });
                    maskAnim.start();

                    return true;
                }
            });

            dialog.show();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
        }

        public void dismissWithAnimation() {
            if (mIsDismissed || mIsExiting) {
                return;
            }
            if (!isAnimationEnabled() || !dialog.isShowing() || isFinishing() || isDestroyed() || !sheet.isAttachedToWindow()) {
                dismissImmediately();
                return;
            }

            mIsExiting = true;
            cancelAnimators();

            int targetH = sheet.getHeight();
            if (targetH <= 0) targetH = sheet.getMeasuredHeight();
            if (targetH <= 0) targetH = mMeasuredSheetHeight;
            if (targetH <= 0) targetH = ui != null ? ui.dp(420) : (int) (getResources().getDisplayMetrics().density * 420f);
            mMeasuredSheetHeight = targetH;

            float currentTransY = sheet.getTranslationY();
            float remaining = Math.max(0f, targetH - currentTransY);
            float progress = targetH > 0 ? Math.max(0f, Math.min(1f, remaining / targetH)) : 1f;

            int startAlpha = 0x75;
            Drawable bg = rootMask.getBackground();
            if (bg instanceof ColorDrawable) {
                startAlpha = Color.alpha(((ColorDrawable) bg).getColor());
            }

            long duration = Math.max(120, Math.round(240 * progress));

            sheet.animate()
                    .translationY((float) targetH)
                    .setDuration(duration)
                    .setInterpolator(new AccelerateInterpolator(1.6f))
                    .withEndAction(() -> {
                        mIsExiting = false;
                        dismissImmediately();
                    })
                    .start();

            ValueAnimator maskAnim = ValueAnimator.ofInt(startAlpha, 0);
            mMaskAnim = maskAnim;
            maskAnim.setDuration(duration);
            maskAnim.setInterpolator(new AccelerateInterpolator(1.6f));
            maskAnim.addUpdateListener(anim -> {
                int alpha = (Integer) anim.getAnimatedValue();
                rootMask.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
            });
            maskAnim.start();
        }

        public boolean dismissImmediately() {
            if (mIsDismissed) {
                return false;
            }
            mIsDismissed = true;
            mIsExiting = false;
            cancelAnimators();
            ensureSaved();
            try {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            } catch (Throwable ignored) {
            }
            return true;
        }

        private void cancelAnimators() {
            try {
                sheet.animate().cancel();
            } catch (Throwable ignored) {
            }
            if (mMaskAnim != null) {
                try {
                    mMaskAnim.cancel();
                } catch (Throwable ignored) {
                }
                mMaskAnim = null;
            }
        }

        private void ensureSaved() {
            if (!mSaveExecuted) {
                mSaveExecuted = true;
                if (onDismissSave != null) {
                    onDismissSave.run();
                }
            }
        }
    }

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

        final BottomSheetController[] controllerHolder = new BottomSheetController[1];
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar) {
            @Override
            public void onBackPressed() {
                if (controllerHolder[0] != null) {
                    controllerHolder[0].dismissWithAnimation();
                } else {
                    super.onBackPressed();
                }
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x75000000);
        root.setOnClickListener(v -> {
            if (controllerHolder[0] != null) {
                controllerHolder[0].dismissWithAnimation();
            } else {
                dialog.dismiss();
            }
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ui.rounded(0xFF1E1E22, 24));
        final int baseSheetBottom = ui.dp(14);
        int initialNav = mNavInsetBottom > 0 ? mNavInsetBottom : UiActivity.getNavigationBarInsetBottom(getWindow().getDecorView(), null);
        sheet.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), baseSheetBottom + initialNav);
        sheet.setClickable(true);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = UiActivity.getNavigationBarInsetBottom(v, insets);
            sheet.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), baseSheetBottom + navBottom);
            return insets;
        });

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(sheet, sheetLp);

        // 顶部小横条把手
        View handle = new View(this);
        handle.setBackground(ui.rounded(0x35FFFFFF, 2));
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
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        tvReset.setTextColor(ui.accentBlue());
        tvReset.setPadding(ui.dp(6), ui.dp(4), ui.dp(10), ui.dp(4));
        tvReset.setClickable(true);
        titleRow.addView(tvReset);

        TextView btnDone = pillButton("完成", ui.accentBlue(), 0xFFFFFFFF);
        btnDone.setOnClickListener(v -> {
            if (controllerHolder[0] != null) {
                controllerHolder[0].dismissWithAnimation();
            } else {
                dialog.dismiss();
            }
        });
        titleRow.addView(btnDone, new LinearLayout.LayoutParams(ui.dp(62), ui.dp(32)));

        sheet.addView(titleRow);

        // 可滚动内容区
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, ui.dp(12), 0, 0);

        // ---- 0. 自由开关：开启自定义时间样式 / 关闭显示原生时钟 ----
        LinearLayout enableRow = new LinearLayout(this);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        enableRow.setBackground(ui.rounded(0xFF28282C, 16));
        enableRow.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout.LayoutParams enableLp = new LinearLayout.LayoutParams(-1, -2);
        enableLp.bottomMargin = ui.dp(14);

        LinearLayout enableTextCol = new LinearLayout(this);
        enableTextCol.setOrientation(LinearLayout.VERTICAL);

        TextView lblEnable = new TextView(this);
        lblEnable.setText("启用魅族时间样式");
        lblEnable.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        lblEnable.setTextColor(0xFFFFFFFF);
        lblEnable.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        enableTextCol.addView(lblEnable);

        TextView subEnable = new TextView(this);
        subEnable.setText("开启应用魅族个性化时钟，关闭则显示系统原生时钟");
        subEnable.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subEnable.setTextColor(0xFF8E8E93);
        enableTextCol.addView(subEnable);

        enableRow.addView(enableTextCol, new LinearLayout.LayoutParams(0, -2, 1f));

        final Switch swEnable = new Switch(this);
        swEnable.setChecked(clockConfig.enabled);
        ui.styleSwitch(swEnable);
        enableRow.addView(swEnable, new LinearLayout.LayoutParams(-2, -2));
        content.addView(enableRow, enableLp);

        final LinearLayout optionsGroup = new LinearLayout(this);
        optionsGroup.setOrientation(LinearLayout.VERTICAL);
        optionsGroup.setAlpha(clockConfig.enabled ? 1.0f : 0.4f);

        swEnable.setOnCheckedChangeListener((bv, isChecked) -> {
            clockConfig.enabled = isChecked;
            optionsGroup.setAlpha(isChecked ? 1.0f : 0.4f);
            previewImage.applyAodClockConfig(clockConfig);
        });

        // ---- 1. 五种官方时钟样式卡片选择器 ----
        TextView lblStyles = new TextView(this);
        lblStyles.setText("样式选择");
        lblStyles.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblStyles.setTextColor(0xFFE5E5EA);
        lblStyles.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        optionsGroup.addView(lblStyles);

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
            card.setClipToOutline(true);
            card.setForeground(ui.itemRipple(16));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ui.dp(78), ui.dp(94));
            cardLp.setMarginEnd(ui.dp(8));
            styleRow.addView(card, cardLp);
            styleCards[sIdx] = card;

            card.setOnClickListener(v -> {
                clockConfig.style = sIdx;
                for (int j = 0; j < ClockSettings.STYLE_COUNT; j++) {
                    boolean sel = (j == sIdx);
                    GradientDrawable bg = ui.rounded(sel ? 0x250D84FF : 0xFF2A2A2E, 16);
                    if (sel) {
                        bg.setStroke(ui.dp(2), ui.accentBlue());
                    } else {
                        bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x14FFFFFF);
                    }
                    styleCards[j].setBackground(bg);
                }
                previewImage.applyAodClockConfig(clockConfig);
            });
        }
        hScroll.addView(styleRow);
        optionsGroup.addView(hScroll);

        // ---- 2. 色彩选择 ----
        TextView lblColor = new TextView(this);
        lblColor.setText("色彩风格");
        lblColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblColor.setTextColor(0xFFE5E5EA);
        lblColor.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        optionsGroup.addView(lblColor);

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
        optionsGroup.addView(colorRow);

        // ---- 3. 大小缩放 ----
        LinearLayout scaleHeader = new LinearLayout(this);
        scaleHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblScale = new TextView(this);
        lblScale.setText("时钟大小缩放");
        lblScale.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblScale.setTextColor(0xFFE5E5EA);
        lblScale.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        scaleHeader.addView(lblScale, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvScaleVal = new TextView(this);
        tvScaleVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvScaleVal.setTextColor(0xFF8E8E93);
        tvScaleVal.setText(String.format(java.util.Locale.US, "%.2fx", clockConfig.scale));
        scaleHeader.addView(tvScaleVal, new LinearLayout.LayoutParams(-2, -2));
        optionsGroup.addView(scaleHeader);

        final SeekBar sbScale = new SeekBar(this);
        sbScale.setMax(70);
        int curScaleProg = Math.round((clockConfig.scale - 0.70f) * 100f);
        sbScale.setProgress(Math.max(0, Math.min(70, curScaleProg)));
        ui.styleSeekBar(sbScale);
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
        optionsGroup.addView(sbScale, sbLp1);

        // ---- 4. 字体粗细 ----
        LinearLayout weightHeader = new LinearLayout(this);
        weightHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblWeight = new TextView(this);
        lblWeight.setText("字体粗细 (字重)");
        lblWeight.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblWeight.setTextColor(0xFFE5E5EA);
        lblWeight.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        weightHeader.addView(lblWeight, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvWeightVal = new TextView(this);
        tvWeightVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvWeightVal.setTextColor(0xFF8E8E93);
        tvWeightVal.setText(clockConfig.weight + (clockConfig.weight <= 300 ? " (轻盈)" : clockConfig.weight >= 700 ? " (加粗)" : " (标准)"));
        weightHeader.addView(tvWeightVal, new LinearLayout.LayoutParams(-2, -2));
        optionsGroup.addView(weightHeader);

        final SeekBar sbWeight = new SeekBar(this);
        sbWeight.setMax(650);
        sbWeight.setProgress(Math.max(0, Math.min(650, clockConfig.weight - 250)));
        ui.styleSeekBar(sbWeight);
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
        optionsGroup.addView(sbWeight, sbLp2);

        // ---- 5. 垂直位置微调 ----
        LinearLayout offsetHeader = new LinearLayout(this);
        offsetHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblOffset = new TextView(this);
        lblOffset.setText("垂直微调 (对齐圆心)");
        lblOffset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lblOffset.setTextColor(0xFFE5E5EA);
        lblOffset.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        offsetHeader.addView(lblOffset, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvOffsetVal = new TextView(this);
        tvOffsetVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvOffsetVal.setTextColor(0xFF8E8E93);
        tvOffsetVal.setText(clockConfig.offsetY == 0 ? "0dp (圆心)" : (clockConfig.offsetY > 0 ? "+" : "") + clockConfig.offsetY + "dp");
        offsetHeader.addView(tvOffsetVal, new LinearLayout.LayoutParams(-2, -2));
        optionsGroup.addView(offsetHeader);

        final SeekBar sbOffset = new SeekBar(this);
        sbOffset.setMax(80);
        sbOffset.setProgress(Math.max(0, Math.min(80, clockConfig.offsetY + 40)));
        ui.styleSeekBar(sbOffset);
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
        optionsGroup.addView(sbOffset, sbLp3);

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
        lblLunar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lunarTextCol.addView(lblLunar);

        TextView subLunar = new TextView(this);
        subLunar.setText("在日期右侧显示农历月日");
        subLunar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subLunar.setTextColor(0xFF8E8E93);
        lunarTextCol.addView(subLunar);
        lunarRow.addView(lunarTextCol, new LinearLayout.LayoutParams(0, -2, 1f));

        final Switch swLunar = new Switch(this);
        swLunar.setChecked(clockConfig.showLunar);
        ui.styleSwitch(swLunar);
        swLunar.setOnCheckedChangeListener((buttonView, isChecked) -> {
            clockConfig.showLunar = isChecked;
            previewImage.applyAodClockConfig(clockConfig);
        });
        lunarRow.addView(swLunar, new LinearLayout.LayoutParams(-2, -2));
        optionsGroup.addView(lunarRow);

        content.addView(optionsGroup);

        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, ui.dp(360)));

        // 恢复默认按钮逻辑
        tvReset.setOnClickListener(v -> {
            ClockSettings.Config def = new ClockSettings.Config();
            clockConfig.enabled = def.enabled;
            swEnable.setChecked(def.enabled);
            optionsGroup.setAlpha(def.enabled ? 1.0f : 0.4f);
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
                GradientDrawable bg = ui.rounded(sel ? 0x250D84FF : 0xFF28282C, 16);
                if (sel) {
                    bg.setStroke(ui.dp(2), ui.accentBlue());
                } else {
                    bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x14FFFFFF);
                }
                styleCards[j].setBackground(bg);
            }
            for (int j = 0; j < pickers.size(); j++) {
                boolean sel = (ClockSettings.PRESET_COLORS[j] == clockConfig.color);
                if (sel) {
                    GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setColor(0x00000000);
                    ring.setStroke(ui.dp(2.5f), 0xFFFFFFFF);
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
            GradientDrawable bg = ui.rounded(sel ? 0x250D84FF : 0xFF28282C, 16);
            if (sel) {
                bg.setStroke(ui.dp(2), ui.accentBlue());
            } else {
                bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x14FFFFFF);
            }
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

        BottomSheetController controller = new BottomSheetController(dialog, root, sheet, null);
        controllerHolder[0] = controller;
        controller.setupAndShow();
    }

    private LinearLayout addRoundButton(String label, String[] iconNames, int iconTint,
                                        View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(88), -2);
        item.setLayoutParams(lp);

        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(ui.roundedBorder(0xFF242428, 0x18FFFFFF, 0.5f, 30));
        circle.setClipToOutline(true);
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
        circle.setForeground(ui.itemRipple(30));
        item.addView(circle, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
                    if ("ic_crop".equals(res)) {
                        return getResources().getDrawable(R.drawable.ic_crop, null);
                    }
                    continue;
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
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(ui.pillButtonDrawable(bg, ui.rippleColor(), 18));
        tv.setClickable(true);
        return tv;
    }

    private LinearLayout createActionCard(String title, String desc, String[] icons, int iconTint,
                                          View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ui.roundedBorder(0xFF2E2E34, 0x15FFFFFF, 0.5f, 14));
        card.setClipToOutline(true);
        card.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        card.setForeground(ui.itemRipple(14));
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
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        final BottomSheetController[] controllerHolder = new BottomSheetController[1];
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar) {
            @Override
            public void onBackPressed() {
                if (controllerHolder[0] != null) {
                    controllerHolder[0].dismissWithAnimation();
                } else {
                    super.onBackPressed();
                }
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x75000000);
        root.setOnClickListener(v -> {
            if (controllerHolder[0] != null) {
                controllerHolder[0].dismissWithAnimation();
            } else {
                dialog.dismiss();
            }
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ui.rounded(0xFF1E1E22, 24));
        final int baseSheetBottom = ui.dp(14);
        int initialNav = mNavInsetBottom > 0 ? mNavInsetBottom : UiActivity.getNavigationBarInsetBottom(getWindow().getDecorView(), null);
        sheet.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), baseSheetBottom + initialNav);
        sheet.setClickable(true);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = UiActivity.getNavigationBarInsetBottom(v, insets);
            sheet.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), baseSheetBottom + navBottom);
            return insets;
        });

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(sheet, sheetLp);

        View dragBar = new View(this);
        dragBar.setBackground(ui.rounded(0x35FFFFFF, 2));
        LinearLayout.LayoutParams dragLp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        dragLp.gravity = Gravity.CENTER_HORIZONTAL;
        dragLp.bottomMargin = ui.dp(12);
        sheet.addView(dragBar, dragLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("选择息屏视觉样式");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = ui.dp(16);
        sheet.addView(tvTitle, titleLp);

        LinearLayout cardRow = new LinearLayout(this);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        cardRow.setGravity(Gravity.CENTER);
        LinearLayout secondCardRow = new LinearLayout(this);
        secondCardRow.setOrientation(LinearLayout.HORIZONTAL);
        secondCardRow.setGravity(Gravity.CENTER);

        int curStyle = mPendingAodVisualStyle != -1
                ? mPendingAodVisualStyle : AodStyleSettings.getStyle(this);
        final int columns = 3;
        int[] styles = {
                AodStyleSettings.STYLE_SET_SAIL, AodStyleSettings.STYLE_LEAVE_LIGHT, AodStyleSettings.STYLE_MOON,
                AodStyleSettings.STYLE_DOOR, AodStyleSettings.STYLE_MOUNTAIN, AodStyleSettings.STYLE_GALAXY
        };
        String[] titles = {"启航", "留光", "星月", "轻启", "山脉", "银河"};
        String[] subs = {"圆形透镜 · 自转扩散", "拍立得相框 · 倾斜绽放", "弯月星芒 · 朦胧绽放",
                "门扉渐开", "群峰舒展", "星河铺展"};
        String[] assets = {"preview/aod_qihang.png", "preview/aod_liuguang.png", "preview/aod_moon.png",
                "preview/aod_door.png", "preview/aod_mountain.png", "preview/aod_galaxy.png"};

        final FrameLayout[] cardViews = new FrameLayout[styles.length];

        // 留光相片设置专区（参考 OpenAlive）
        final LinearLayout panelLiuguang = new LinearLayout(this);
        panelLiuguang.setOrientation(LinearLayout.VERTICAL);
        panelLiuguang.setBackground(ui.rounded(0xFF26262A, 18));
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
        tvPanelTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        tvExample.setTextColor(ui.accentBlue());
        tvExample.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4));
        tvExample.setClickable(true);
        tvExample.setOnClickListener(v -> {
            mHasUserEdited = true;
            final String targetSession = draftSessionId;
            boolean ok = PhotoDraftStore.loadDefaultExampleToDraft(
                    EditorActivity.this, targetSession, PhotoStore.FRAME);
            if (!PhotoDraftStore.hasEffective(EditorActivity.this, targetSession, PhotoStore.LOCK)) {
                PhotoDraftStore.loadDefaultExampleToDraft(
                        EditorActivity.this, targetSession, PhotoStore.LOCK);
            }
            if (ok) {
                previewImage.reloadPhoto();
                selectScene(Motion.AOD);
                previewImage.startScene();
                toast("已载入示例相片（草稿）");
                append("已载入示例相片到相框草稿");
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
                    if (controllerHolder[0] != null && controllerHolder[0].dismissImmediately()) {
                        if (!isFinishing() && !isDestroyed()) {
                            pick(PhotoStore.FRAME);
                        }
                    }
                });
        LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bcLp.rightMargin = ui.dp(8);
        btnGrid.addView(btnChoose, bcLp);

        LinearLayout btnCrop = createActionCard(
                "调整取景", "缩放拖拽裁切",
                new String[]{"@self:ic_crop", "ic_crop"}, 0xFF7EB6FF,
                v -> {
                    if (controllerHolder[0] != null && controllerHolder[0].dismissImmediately()) {
                        if (!isFinishing() && !isDestroyed()) {
                            cropExistingPhoto(PhotoStore.FRAME);
                        }
                    }
                });
        btnGrid.addView(btnCrop, new LinearLayout.LayoutParams(0, -2, 1f));

        panelLiuguang.addView(btnGrid);
        panelLiuguang.setVisibility(curStyle == AodStyleSettings.STYLE_LEAVE_LIGHT ? View.VISIBLE : View.GONE);

        for (int i = 0; i < styles.length; i++) {
            final int styleId = styles[i];
            FrameLayout card = new FrameLayout(this);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i % columns != 0) cardLp.setMarginStart(ui.dp(8));
            card.setLayoutParams(cardLp);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12));

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try (InputStream in = getAssets().open(assets[i])) {
                thumb.setImageBitmap(BitmapFactory.decodeStream(in));
            } catch (Throwable t) {
                Log.w(TAG, "加载缩略图失败: " + assets[i], t);
            }
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64));
            thumbLp.bottomMargin = ui.dp(8);
            content.addView(thumb, thumbLp);

            TextView title = new TextView(this);
            title.setText(titles[i]);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTextColor(0xFFFFFFFF);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setGravity(Gravity.CENTER);
            title.setSingleLine(true);
            LinearLayout.LayoutParams cardTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardTitleLp.gravity = Gravity.CENTER_HORIZONTAL;
            content.addView(title, cardTitleLp);

            TextView sub = new TextView(this);
            sub.setText(subs[i]);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
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
            GradientDrawable bg = ui.rounded(selected ? 0x250D84FF : 0xFF28282C, 16);
            if (selected) {
                bg.setStroke(ui.dp(2), ui.accentBlue());
            } else {
                bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x14FFFFFF);
            }
            card.setBackground(bg);
            card.setClipToOutline(true);
            card.setForeground(ui.itemRipple(16));
            card.setClickable(true);
            cardViews[i] = card;

            final String selectedName = titles[i];
            card.setOnClickListener(v -> {
                mHasUserEdited = true;
                if (desktopSource == LastSet.KIND_DYNAMIC || wallpaper != null) {
                    wallpaper = null;
                    fromLibrary = false;
                    desktopSource = (!followLock && hasEffectiveHomePhoto())
                            ? LastSet.KIND_PHOTO : LastSet.KIND_FOLLOW;
                    previewImage.setDynamicComponent(null);
                    previewImage.setHomeKind(desktopSource);
                    previewImage.reloadPhoto();
                }
                mPendingAodVisualStyle = styleId;
                if (styleId == AodStyleSettings.STYLE_LEAVE_LIGHT) {
                    prepareDefaultFramePreview();
                }
                selectScene(Motion.AOD);

                for (int j = 0; j < styles.length; j++) {
                    boolean sel = (styles[j] == styleId);
                    GradientDrawable b = ui.rounded(sel ? 0x250D84FF : 0xFF28282C, 16);
                    if (sel) {
                        b.setStroke(ui.dp(2), ui.accentBlue());
                    } else {
                        b.setStroke(Math.max(1, ui.dp(0.5f)), 0x14FFFFFF);
                    }
                    cardViews[j].setBackground(b);
                }
                panelLiuguang.setVisibility(styleId == AodStyleSettings.STYLE_LEAVE_LIGHT ? View.VISIBLE : View.GONE);
                append("息屏视觉样式更改为：" + selectedName);
            });

            (i < columns ? cardRow : secondCardRow).addView(card);
        }

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.addView(cardRow);
        LinearLayout.LayoutParams secondRowParams = new LinearLayout.LayoutParams(-1, -2);
        secondRowParams.topMargin = ui.dp(8);
        choices.addView(secondCardRow, secondRowParams);
        choices.addView(panelLiuguang);

        ScrollView choicesScroll = new ScrollView(this);
        choicesScroll.addView(choices);
        sheet.addView(choicesScroll, new LinearLayout.LayoutParams(-1, -2, 1f));

        LinearLayout positionRow = new LinearLayout(this);
        positionRow.setGravity(Gravity.CENTER_VERTICAL);
        positionRow.setPadding(0, ui.dp(10), 0, 0);
        TextView positionHint = new TextView(this);
        positionHint.setText("也可单指拖动预览");
        positionHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        positionHint.setTextColor(0xFF8E8E93);
        positionRow.addView(positionHint, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView adjustPosition = pillButton("调整位置", 0xFF3A3A3C, 0xFFFFFFFF);
        adjustPosition.setOnClickListener(v -> {
            if (desktopSource == LastSet.KIND_DYNAMIC) {
                toast("请先选择一种息屏样式");
            } else if (controllerHolder[0] != null && controllerHolder[0].dismissImmediately()) {
                showAodPositionDialog();
            }
        });
        positionRow.addView(adjustPosition, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(36)));
        sheet.addView(positionRow);

        TextView btnDone = pillButton("完成", ui.accentBlue(), 0xFFFFFFFF);
        btnDone.setOnClickListener(v -> {
            if (controllerHolder[0] != null) {
                controllerHolder[0].dismissWithAnimation();
            } else {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40));
        doneLp.topMargin = ui.dp(16);
        sheet.addView(btnDone, doneLp);

        BottomSheetController controller = new BottomSheetController(dialog, root, sheet, null);
        controllerHolder[0] = controller;
        controller.setupAndShow();
    }

    private void showAodPositionDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout scrim = new FrameLayout(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setMotionEventSplittingEnabled(false);
        sheet.setBackground(ui.rounded(0xFF1E1E22, 24));
        int padding = ui.dp(18);
        sheet.setPadding(padding, ui.dp(14), padding, ui.dp(14) + mNavInsetBottom);
        scrim.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = UiActivity.getNavigationBarInsetBottom(v, insets);
            sheet.setPadding(padding, ui.dp(14), padding, ui.dp(14) + navBottom);
            return insets;
        });
        scrim.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("息屏位置");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reset = pillButton("恢复位置", 0xFF3A3A3C, 0xFFFFFFFF);
        titleRow.addView(reset, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(36)));
        sheet.addView(titleRow);

        SeekBar horizontal = addAodPositionSlider(sheet, "水平位置", false);
        SeekBar vertical = addAodPositionSlider(sheet, "垂直位置", true);
        reset.setOnClickListener(v -> {
            updateAodPosition(0);
            horizontal.setProgress(0);
            vertical.setProgress(0);
        });

        BottomSheetController controller = new BottomSheetController(
                dialog, scrim, sheet, this::endAodPositionDrag);
        scrim.setOnClickListener(v -> controller.dismissWithAnimation());
        TextView done = pillButton("完成", ui.accentBlue(), 0xFFFFFFFF);
        done.setOnClickListener(v -> controller.dismissWithAnimation());
        sheet.addView(done, new LinearLayout.LayoutParams(-1, ui.dp(40)));
        controller.setupAndShow();
    }

    private SeekBar addAodPositionSlider(LinearLayout sheet, String label, boolean vertical) {
        LinearLayout header = new LinearLayout(this);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTextColor(0xFFE5E5EA);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView offsetLabel = new TextView(this);
        offsetLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        offsetLabel.setTextColor(0xFF8E8E93);
        header.addView(offsetLabel);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.topMargin = ui.dp(8);
        sheet.addView(header, headerParams);

        float units = AodStyleSettings.POSITION_UNITS;
        float centerLimit = AodStyleSettings.CENTER_LIMIT;
        float defaultOffset = vertical ? Motion.AOD_Y : 0f;
        float density = getResources().getDisplayMetrics().density;
        float axisDp = (vertical ? previewImage.getHeight() : previewImage.getWidth()) / density;
        int position = currentAodPosition();
        float offset = vertical ? -AodStyleSettings.positionY(position) : AodStyleSettings.positionX(position);

        SeekBar slider = new SeekBar(this);
        slider.setMin(Math.round((defaultOffset - centerLimit) * units));
        slider.setMax(Math.round((defaultOffset + centerLimit) * units));
        slider.setProgress(Math.round(offset * units));
        slider.setKeyProgressIncrement(Math.max(1, Math.round(units / Math.max(1f, axisDp))));
        slider.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        slider.setContentDescription(label);
        ui.styleSeekBar(slider);
        offsetLabel.setText(formatAodPositionOffset(offset, axisDp));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float newOffset = progress / units;
                offsetLabel.setText(formatAodPositionOffset(newOffset, axisDp));
                if (!fromUser) return;
                int current = currentAodPosition();
                float x = vertical ? AodStyleSettings.positionX(current) : newOffset;
                float y = vertical ? -newOffset : AodStyleSettings.positionY(current);
                updateAodPosition(AodStyleSettings.packPosition(x, y));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                previewImage.setAodPositionGuidesVisible(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                previewImage.setAodPositionGuidesVisible(false);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(44));
        params.bottomMargin = ui.dp(4);
        sheet.addView(slider, params);
        return slider;
    }

    private static String formatAodPositionOffset(float offset, float axisDp) {
        int dp = Math.round(offset * axisDp);
        return (dp > 0 ? "+" : "") + dp + "dp";
    }

    // =====================================================================
    // 动作
    // =====================================================================

    static boolean tryBeginApplyTransaction() {
        return APPLY_IN_FLIGHT.compareAndSet(false, true);
    }

    static void endApplyTransaction() {
        APPLY_IN_FLIGHT.set(false);
    }

    static String resolvePreparingApplyProgressMessage(boolean backupOriginal) {
        return backupOriginal ? "正在准备备份…" : resolveApplyingStageProgressMessage();
    }

    static String resolveInitialApplyProgressMessage(boolean hasValidBackup) {
        return hasValidBackup
                ? "正在校验已有备份…"
                : "正在备份原壁纸，耗时较长请稍候…";
    }

    static String resolveApplyingStageProgressMessage() {
        return "正在应用壁纸与配置…";
    }

    /**
     * 事务锁保护下的应用启动编排：
     * 若展示进度 UI 或启动后台线程任一步抛出异常，确定性执行 UI 清理并释放 {@code APPLY_IN_FLIGHT}。
     */
    static boolean executeApplyTransactionWithCleanup(
            Runnable onDuplicate,
            Runnable showUi,
            Runnable launchWorker,
            Runnable cleanupOnLaunchFailure) {
        if (!tryBeginApplyTransaction()) {
            if (onDuplicate != null) {
                onDuplicate.run();
            }
            return false;
        }
        boolean workerLaunched = false;
        try {
            if (showUi != null) {
                showUi.run();
            }
            if (launchWorker != null) {
                launchWorker.run();
            }
            workerLaunched = true;
            return true;
        } finally {
            if (!workerLaunched) {
                try {
                    if (cleanupOnLaunchFailure != null) {
                        cleanupOnLaunchFailure.run();
                    }
                } finally {
                    endApplyTransaction();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        PhotoDraftStore.removeStartupRecoveryListener(mStartupRecoveryListener);
        dismissApplyProgressDialogOnly();
        if (isFinishing() && !isChangingConfigurations()) {
            PhotoDraftStore.releaseDraftLeaseAndDiscardIfIdle(this, draftSessionId, this);
        } else {
            PhotoDraftStore.releaseDraftLease(draftSessionId, this, isChangingConfigurations());
        }
        super.onDestroy();
    }

    private void showApplyProgressUi(String initialMsg) {
        if (applyBtn != null) {
            applyBtn.setEnabled(false);
            applyBtn.setAlpha(0.55f);
            applyBtn.setText("处理中");
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        dismissApplyProgressDialogOnly();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(20));

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26));
        barLp.rightMargin = ui.dp(14);
        box.addView(bar, barLp);

        TextView tv = new TextView(this);
        tv.setText(initialMsg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(ui.night ? 0xFFF2F2F7 : 0xFF1C1C1E);
        box.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        applyProgressText = tv;

        int theme = ui.night
                ? android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK
                : android.app.AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        applyProgressDialog = new android.app.AlertDialog.Builder(this, theme)
                .setTitle("正在应用")
                .setView(box)
                .setCancelable(false)
                .create();
        applyProgressDialog.setCanceledOnTouchOutside(false);
        applyProgressDialog.show();
    }

    private void updateApplyProgressMessage(String msg) {
        if (applyProgressText != null && msg != null && !isFinishing() && !isDestroyed()) {
            applyProgressText.setText(msg);
        }
    }

    private void dismissApplyProgressDialogOnly() {
        android.app.AlertDialog dlg = applyProgressDialog;
        applyProgressDialog = null;
        applyProgressText = null;
        if (dlg != null) {
            try {
                if (dlg.isShowing()) {
                    dlg.dismiss();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void finishApplyProgressUi() {
        dismissApplyProgressDialogOnly();
        if (applyBtn != null && !isDestroyed()) {
            applyBtn.setEnabled(true);
            applyBtn.setAlpha(1f);
            applyBtn.setText("应用");
        }
    }

    static final class ApplyTxRequest {
        final String draftSessionId;
        final int pendingStyle;
        final ClockSettings.Config pendingAod;
        final ClockSettings.Config pendingLock;
        final int pendingSource;
        final String pendingWall;
        final boolean pendingFollow;
        final boolean backupOriginal;
        final int pendingPhotoTextures;
        final String pendingAodPositions;

        ApplyTxRequest(
                String draftSessionId,
                int pendingStyle,
                ClockSettings.Config pendingAod,
                ClockSettings.Config pendingLock,
                int pendingSource,
                String pendingWall,
                boolean pendingFollow,
                boolean backupOriginal,
                int pendingPhotoTextures,
                String pendingAodPositions) {
            this.draftSessionId = draftSessionId;
            this.pendingStyle = pendingStyle;
            this.pendingAod = pendingAod;
            this.pendingLock = pendingLock;
            this.pendingSource = pendingSource;
            this.pendingWall = pendingWall;
            this.pendingFollow = pendingFollow;
            this.backupOriginal = backupOriginal;
            this.pendingPhotoTextures = pendingPhotoTextures;
            this.pendingAodPositions = pendingAodPositions;
        }
    }

    interface ApplyTxEnv {
        boolean hasValidBackup(Context ctx);
        PreModuleWallpaperBackup.BackupPrepResult ensureBackupBeforeApply(Context ctx);
        PreModuleWallpaperBackup.RollbackResult rollbackCreatedBackupIfUnapplied(
                Context ctx, PreModuleWallpaperBackup.PreApplyBaseline baseline);
        String setDynamicWallpaper(Context ctx, String pkg, String cls);
        String setDualWallpaper(Context ctx, String pkg, String homeCls, String lockCls);
        PreModuleWallpaperBackup.SlotVerifyResult verifyAppliedSlots(
                Context ctx, boolean isDual, String pkg, String homeCls, String dualOrNull, String lockCls);
        PhotoDraftStore.CommitTargetWriter commitWriter();
        void onStageMessage(String msg);
        void onLog(String msg);
    }

    static final class ApplyTxResult {
        final boolean ok;
        final boolean abortedBeforeBackupOrUnresolved;
        final boolean dynamicMode;
        final int finalSource;
        final boolean finalFollowFlag;
        final String summary;
        final String failTitle;
        final String failDetail;
        final String fullFailureMessage;
        final boolean rollbackSucceeded;
        final boolean snapshotPreserved;
        final PreModuleWallpaperBackup.BackupPrepResult prep;

        ApplyTxResult(
                boolean ok,
                boolean abortedBeforeBackupOrUnresolved,
                boolean dynamicMode,
                int finalSource,
                boolean finalFollowFlag,
                String summary,
                String failTitle,
                String failDetail,
                String fullFailureMessage,
                boolean rollbackSucceeded,
                boolean snapshotPreserved,
                PreModuleWallpaperBackup.BackupPrepResult prep) {
            this.ok = ok;
            this.abortedBeforeBackupOrUnresolved = abortedBeforeBackupOrUnresolved;
            this.dynamicMode = dynamicMode;
            this.finalSource = finalSource;
            this.finalFollowFlag = finalFollowFlag;
            this.summary = summary != null ? summary : "";
            this.failTitle = failTitle != null ? failTitle : "";
            this.failDetail = failDetail != null ? failDetail : "";
            this.fullFailureMessage = fullFailureMessage != null ? fullFailureMessage : "";
            this.rollbackSucceeded = rollbackSucceeded;
            this.snapshotPreserved = snapshotPreserved;
            this.prep = prep;
        }
    }

    private static final class TxReturnedFailureException extends RuntimeException {
        final String title;
        final String detail;

        TxReturnedFailureException(String title, String detail) {
            super(detail);
            this.title = title;
            this.detail = detail;
        }
    }

    static ApplyTxResult executeApplyTransactionCore(
            Context ctx,
            ApplyTxRequest req,
            ApplyTxEnv env) {
        PreModuleWallpaperBackup.lockTransaction();
        try {
            PhotoDraftStore.RecoveryResult recoveryCheck = PhotoDraftStore.recoverIncompleteCommits(ctx);
            if (!recoveryCheck.ok || PhotoDraftStore.hasUnresolvedRecovery(ctx)) {
                String msg = "存在未解决的异常事务恢复记录，已阻止本次应用以保护证据与现场：" + recoveryCheck.detail;
                if (env != null) env.onLog("✖ " + msg);
                return new ApplyTxResult(
                        false, true, false, req.pendingSource, req.pendingFollow,
                        "应用中止：" + msg,
                        "存在未解决的异常恢复记录", recoveryCheck.detail, msg,
                        false, true, null);
            }
            if (PhotoDraftStore.hasCorruptRevision(ctx, req.draftSessionId)) {
                String corruptDetail = PhotoDraftStore.getCorruptRevisionDetail(ctx, req.draftSessionId);
                String msg = "当前编辑草稿存在未修复的修订回滚异常，已阻止应用以防止半残图片覆盖壁纸：" + corruptDetail;
                if (env != null) env.onLog("✖ " + msg);
                return new ApplyTxResult(
                        false, true, false, req.pendingSource, req.pendingFollow,
                        "应用中止：" + msg,
                        "草稿状态异常", corruptDetail, msg,
                        false, false, null);
            }

            PreModuleWallpaperBackup.BackupPrepResult prep = null;
            if (req.backupOriginal) {
                String stageMsg = resolveInitialApplyProgressMessage(env.hasValidBackup(ctx));
                env.onLog("…应用开始：" + stageMsg);
                env.onStageMessage(stageMsg);
                prep = env.ensureBackupBeforeApply(ctx);
                if (!prep.ok) {
                    env.onLog("✖ 原壁纸备份失败：" + prep.message);
                    return new ApplyTxResult(
                            false, true, false, req.pendingSource, req.pendingFollow,
                            "应用中止：" + prep.message,
                            "无法备份当前壁纸", prep.message, prep.message,
                            false, false, prep);
                }
                env.onLog("⓪ " + prep.message);
            }
            env.onStageMessage(resolveApplyingStageProgressMessage());

            // 1. 在首次提交模块配置或照片之前创建完整持久事务日志（PENDING 状态覆盖至壁纸绑定与核验完成）
            PhotoDraftStore.CommitSnapshot snapshot;
            try {
                snapshot = PhotoDraftStore.beginTransactionSnapshot(ctx);
            } catch (Throwable snapErr) {
                String msg = "创建事务前置快照失败，已中止应用：" + snapErr.getMessage();
                env.onLog("✖ " + msg);
                return new ApplyTxResult(
                        false, true, false, req.pendingSource, req.pendingFollow,
                        msg, "无法创建事务快照", snapErr.getMessage(), msg,
                        false, false, prep);
            }

            final boolean isDynamic = req.pendingSource == LastSet.KIND_DYNAMIC
                    && req.pendingWall != null && !req.pendingWall.isEmpty();
            int finalSource = req.pendingSource;
            boolean finalFollowFlag = req.pendingFollow;
            StringBuilder sb = new StringBuilder();

            try {
                // 2. 在 PENDING 事务边界内持久化样式与时钟设置
                if (req.pendingAodPositions != null
                        && !AodStyleSettings.savePositionsStrict(ctx, req.pendingAodPositions)) {
                    throw new java.io.IOException("持久化息屏位置失败");
                }
                if (req.pendingStyle != -1) {
                    if (!AodStyleSettings.setStyleStrict(ctx, req.pendingStyle)) {
                        throw new java.io.IOException("持久化息屏视觉样式失败");
                    }
                }
                if (req.pendingAod != null || req.pendingLock != null) {
                    if (!ClockSettings.saveStrict(ctx, req.pendingAod, req.pendingLock)) {
                        throw new java.io.IOException("持久化时钟样式失败");
                    }
                    env.onLog("时钟样式已保存生效（熄屏+锁屏独立配置）");
                }

                if (isDynamic) {
                    DynamicWallpapers.Entry entry = DynamicWallpapers.find(req.pendingWall);
                    if (entry == null) {
                        throw new TxReturnedFailureException("应用动态壁纸失败", "请从动态壁纸库选择一款内置壁纸");
                    }
                    env.onLog("① 应用内置动态壁纸到桌面、锁屏和息屏：" + entry.label);
                    if (!LastSet.saveStrict(ctx, req.pendingSource, entry.selection())) {
                        throw new java.io.IOException("持久化壁纸来源偏好失败");
                    }
                    String targetPkg = ctx.getPackageName();
                    String targetCls = targetPkg + ".QihangDynamicWallpaper";
                    String res = env.setDynamicWallpaper(ctx, targetPkg, targetCls);
                    sb.append("动态壁纸(三态)=").append(res);
                    boolean textOk = Root.isSetWallSuccess(res);
                    PreModuleWallpaperBackup.SlotVerifyResult slotVer = textOk
                            ? env.verifyAppliedSlots(ctx, false, targetPkg, targetCls, null, null)
                            : new PreModuleWallpaperBackup.SlotVerifyResult(false, "底层命令返回未通过严格校验");
                    if (!(textOk && slotVer.ok)) {
                        String failDetail = textOk
                                ? ("槽位实际核验未通过：" + slotVer.detail + "\n返回：" + res)
                                : res;
                        throw new TxReturnedFailureException("应用动态壁纸失败", failDetail);
                    }
                    PhotoDraftStore.completeTransactionSnapshot(ctx, snapshot);
                    return new ApplyTxResult(
                            true, false, true, req.pendingSource, req.pendingFollow,
                            sb.toString(), "", "", "", true, false, prep);
                } else {
                    if (req.pendingPhotoTextures >= 0
                            && !PhotoTextureSettings.saveStrict(ctx, req.pendingPhotoTextures)) {
                        throw new java.io.IOException("持久化锁屏和桌面纹理失败");
                    }
                    if (finalSource == LastSet.KIND_DYNAMIC) {
                        finalSource = req.pendingFollow ? LastSet.KIND_FOLLOW : LastSet.KIND_PHOTO;
                    }
                    finalFollowFlag = (finalSource == LastSet.KIND_FOLLOW);
                    if (!LastSet.saveStrict(ctx, finalSource, null)) {
                        throw new java.io.IOException("持久化壁纸来源偏好失败");
                    }

                    PhotoDraftStore.commitDraftPhotosIntoTransaction(
                            ctx, snapshot, req.draftSessionId, finalFollowFlag, env.commitWriter());

                    env.onLog("① 原子设置双槽自定义壁纸 (which=1 桌面 + which=2 锁屏/息屏)");
                    String pkg = ctx.getPackageName();
                    String homeCls = pkg + ".QihangHomeWallpaper";
                    String lockCls = pkg + ".QihangWallpaper";
                    String dualRes = env.setDualWallpaper(ctx, pkg, homeCls, lockCls);
                    sb.append("双槽自定义壁纸=").append(dualRes);
                    sb.append(" · 模式=").append(finalSource == LastSet.KIND_FOLLOW ? "跟随锁屏" : "独立桌面(渐变过渡)");
                    boolean textOk = Root.isSetWallSuccess(dualRes);
                    PreModuleWallpaperBackup.SlotVerifyResult slotVer = textOk
                            ? env.verifyAppliedSlots(ctx, true, pkg, homeCls, pkg, lockCls)
                            : new PreModuleWallpaperBackup.SlotVerifyResult(false, "底层命令返回未通过严格校验");
                    if (!(textOk && slotVer.ok)) {
                        String failDetail = textOk
                                ? ("双槽实际核验未通过：" + slotVer.detail + "\n返回：" + dualRes)
                                : dualRes;
                        throw new TxReturnedFailureException("应用自定义壁纸失败", failDetail);
                    }
                    // 仅当照片、偏好、WMS 双槽绑定及真实槽位核验全部成功后才标记 COMMITTED 并清理快照
                    PhotoDraftStore.completeTransactionSnapshot(ctx, snapshot);
                    return new ApplyTxResult(
                            true, false, false, finalSource, finalFollowFlag,
                            sb.toString(), "", "", "", true, false, prep);
                }
            } catch (Throwable t) {
                String failTitle = (t instanceof TxReturnedFailureException)
                        ? ((TxReturnedFailureException) t).title
                        : (isDynamic ? "应用动态壁纸异常" : "应用自定义壁纸异常");
                String failDetail = (t instanceof TxReturnedFailureException)
                        ? ((TxReturnedFailureException) t).detail
                        : ("事务过程抛出异常：" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));

                PhotoDraftStore.RollbackResult rb =
                        PhotoDraftStore.rollbackTransactionSnapshot(ctx, snapshot, true);
                String backupNote = "";
                boolean createdBackup = prep != null && prep.createdNew;
                if (rb.ok && createdBackup) {
                    PreModuleWallpaperBackup.RollbackResult bRb =
                            env.rollbackCreatedBackupIfUnapplied(ctx, prep.baseline);
                    backupNote = bRb.deleted
                            ? "，并已确认零改动后清理本次新建备份"
                            : ("，且已保留新建备份快照（" + bRb.reason + "）");
                } else if (!rb.ok && createdBackup) {
                    backupNote = "，且已保留新建备份快照与未完成事务证据";
                }
                final String fullMsg;
                if (rb.ok) {
                    fullMsg = "设置系统壁纸未成功，已完整回滚模块配置与照片提交" + backupNote
                            + "。\n\n详情：" + failDetail;
                    env.onLog("✖ 应用失败，已完整回滚模块配置与照片提交" + backupNote);
                } else {
                    fullMsg = "设置系统壁纸未成功，且事务回滚存在异常（已保留快照证据以防丢失："
                            + rb.detail + "）" + backupNote + "。\n\n原错误：" + failDetail;
                    env.onLog("✖ 应用失败且回滚未完全成功（已保留快照证据）：" + rb.detail + " | 原错误：" + failDetail);
                }
                return new ApplyTxResult(
                        false, false, isDynamic, finalSource, finalFollowFlag,
                        "应用失败：" + failDetail,
                        failTitle, failDetail, fullMsg,
                        rb.ok, rb.snapshotPreserved, prep);
            }
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    private void showApplyOptions() {
        if (APPLY_IN_FLIGHT.get()) {
            toast("正在应用中，请勿重复点击");
            return;
        }
        int theme = ui.night
                ? android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK
                : android.app.AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        new android.app.AlertDialog.Builder(this, theme)
                .setTitle("应用壁纸")
                .setMessage("备份可用于恢复应用前的壁纸。")
                .setNeutralButton("取消", null)
                .setNegativeButton("直接应用", (dialog, which) -> applyScene(false))
                .setPositiveButton("备份并应用", (dialog, which) -> applyScene(true))
                .show();
    }

    private void applyScene(boolean backupOriginal) {
        final int pendingStyle = mPendingAodVisualStyle;
        final ClockSettings.Config pendingAod = mPendingAodConfig;
        final ClockSettings.Config pendingLock = mPendingLockConfig;
        final int pendingSource = desktopSource;
        final String pendingWall = wallpaper;
        final boolean pendingFollow = followLock;
        final int pendingPhotoTextures = mPendingPhotoTextures;
        final String pendingAodPositions = mAodPositionsEdited
                ? AodStyleSettings.encodePositions(mPendingAodPositions) : null;
        final String applyDraftId = draftSessionId;
        final Object workerLeaseToken = new Object();

        executeApplyTransactionWithCleanup(
                () -> toast("正在应用中，请勿重复点击"),
                () -> {
                    append("应用：" + sceneName(scene));
                    showApplyProgressUi(resolvePreparingApplyProgressMessage(backupOriginal));
                },
                () -> {
                    PhotoDraftStore.acquireDraftLease(applyDraftId, workerLeaseToken);
                    boolean workerStarted = false;
                    try {
                        run0("应用", () -> {
                            boolean committedCleanly = false;
                            try {
                                ApplyTxRequest req = new ApplyTxRequest(
                                        applyDraftId,
                                        pendingStyle,
                                        pendingAod,
                                        pendingLock,
                                        pendingSource,
                                        pendingWall,
                                        pendingFollow,
                                        backupOriginal,
                                        pendingPhotoTextures,
                                        pendingAodPositions);
                                ApplyTxEnv env = new ApplyTxEnv() {
                                    @Override
                                    public boolean hasValidBackup(Context ctx) {
                                        return PreModuleWallpaperBackup.hasValidBackup(ctx);
                                    }

                                    @Override
                                    public PreModuleWallpaperBackup.BackupPrepResult ensureBackupBeforeApply(Context ctx) {
                                        return PreModuleWallpaperBackup.ensureBackupBeforeApply(ctx);
                                    }

                                    @Override
                                    public PreModuleWallpaperBackup.RollbackResult rollbackCreatedBackupIfUnapplied(
                                            Context ctx, PreModuleWallpaperBackup.PreApplyBaseline baseline) {
                                        return PreModuleWallpaperBackup.rollbackCreatedBackupIfUnapplied(ctx, baseline);
                                    }

                                    @Override
                                    public String setDynamicWallpaper(Context ctx, String pkg, String cls) {
                                        return Root.setWallpaperComponentRaw(ctx, pkg, cls, 3);
                                    }

                                    @Override
                                    public String setDualWallpaper(Context ctx, String pkg, String homeCls, String lockCls) {
                                        return Root.setDualWallpaper(ctx, pkg, homeCls, lockCls);
                                    }

                                    @Override
                                    public PreModuleWallpaperBackup.SlotVerifyResult verifyAppliedSlots(
                                            Context ctx, boolean isDual, String pkg, String homeCls,
                                            String dualOrNull, String lockCls) {
                                        return PreModuleWallpaperBackup.verifyAppliedSlots(
                                                ctx, isDual, pkg, homeCls, dualOrNull, lockCls);
                                    }

                                    @Override
                                    public PhotoDraftStore.CommitTargetWriter commitWriter() {
                                        return null;
                                    }

                                    @Override
                                    public void onStageMessage(String msg) {
                                        runOnUiThread(() -> updateApplyProgressMessage(msg));
                                    }

                                    @Override
                                    public void onLog(String msg) {
                                        append(msg);
                                    }
                                };

                                ApplyTxResult res = executeApplyTransactionCore(EditorActivity.this, req, env);
                                if (res.ok) {
                                    committedCleanly = true;
                                    if (res.dynamicMode) {
                                        final long commitSeq = sCommittedApplySeq.incrementAndGet();
                                        sendReloadPhoto();
                                        sendMode(Motion.HOME, "应用动态壁纸");
                                        schedulePostApplyAlignment(
                                                getApplicationContext(), commitSeq, true, "应用动态壁纸(延时对齐)");
                                        runOnUiThread(() -> {
                                            if (!isFinishing() && !isDestroyed()) {
                                                toast("已应用");
                                            }
                                        });
                                    } else {
                                        final String nextDraftId = PhotoDraftStore.newSessionId();
                                        PhotoDraftStore.acquireDraftLease(nextDraftId, EditorActivity.this);
                                        PhotoDraftStore.releaseDraftLease(applyDraftId, EditorActivity.this, false);
                                        wallpaper = null;
                                        fromLibrary = false;
                                        desktopSource = res.finalSource;
                                        followLock = res.finalFollowFlag;
                                        draftSessionId = nextDraftId;
                                        mHasUserEdited = false;
                                        final long commitSeq = sCommittedApplySeq.incrementAndGet();
                                        sendReloadPhoto();
                                        sendMode(Motion.HOME, "应用自定义壁纸");
                                        schedulePostApplyAlignment(
                                                getApplicationContext(), commitSeq, true, "应用自定义壁纸(延时对齐)");
                                        runOnUiThread(() -> {
                                            if (!isFinishing() && !isDestroyed()) {
                                                if (previewImage != null) {
                                                    previewImage.setDraftSessionId(nextDraftId);
                                                    previewImage.reloadPhoto();
                                                }
                                                toast("已应用");
                                            }
                                        });
                                    }
                                    return res.summary;
                                }

                                showApplyFailureDialogOnUi(res);
                                return res.summary;
                            } finally {
                                if (committedCleanly) {
                                    PhotoDraftStore.releaseDraftLeaseAndDiscardIfIdle(
                                            getApplicationContext(), applyDraftId, workerLeaseToken);
                                } else {
                                    PhotoDraftStore.releaseDraftLease(applyDraftId, workerLeaseToken, false);
                                }
                                endApplyTransaction();
                                runOnUiThread(this::finishApplyProgressUi);
                            }
                        });
                        workerStarted = true;
                    } finally {
                        if (!workerStarted) {
                            PhotoDraftStore.releaseDraftLease(applyDraftId, workerLeaseToken, false);
                        }
                    }
                },
                this::finishApplyProgressUi);
    }

    /**
     * 使用 ApplicationContext 发送 250ms 延时对齐广播并绑定应用代数：
     * 即使编辑页在应用成功后立即被用户关闭，新绑定的壁纸引擎仍能收到对齐通知；
     * 若期间有更新一次的应用提交，旧回调通过 commitSeq 自动失效。
     */
    private static void schedulePostApplyAlignment(
            final Context appCtx,
            final long commitSeq,
            final boolean reloadPhoto,
            final String label) {
        if (appCtx == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (commitSeq != sCommittedApplySeq.get()) {
                return;
            }
            String pkg = appCtx.getPackageName();
            if (reloadPhoto) {
                Intent reload = new Intent(XposedInit.ACTION_MODE);
                reload.setPackage(pkg);
                reload.putExtra("reload_photo", true);
                appCtx.sendBroadcast(reload);
            }
            Intent modeIntent = new Intent(XposedInit.ACTION_MODE);
            modeIntent.setPackage(pkg);
            modeIntent.putExtra(XposedInit.EXTRA_MODE, Motion.HOME);
            modeIntent.putExtra(XposedInit.EXTRA_ANIMATE, true);
            modeIntent.putExtra(XposedInit.EXTRA_STATE, "manual-" + label);
            appCtx.sendBroadcast(modeIntent);
        }, 250);
    }

    private void showApplyFailureDialogOnUi(final ApplyTxResult res) {
        if (res == null) return;
        runOnUiThread(() -> {
            finishApplyProgressUi();
            if (!isFinishing() && !isDestroyed()) {
                toast(res.abortedBeforeBackupOrUnresolved
                        ? ("应用已中止：" + res.failDetail)
                        : ("应用失败：" + res.failDetail));
                int theme = ui.night
                        ? android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK
                        : android.app.AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(EditorActivity.this, theme)
                        .setTitle(res.failTitle)
                        .setMessage(res.fullFailureMessage);
                if (res.prep != null && !res.prep.ok) {
                    builder.setNegativeButton("取消", null)
                            .setPositiveButton("直接应用", (dialog, which) -> applyScene(false));
                } else {
                    builder.setPositiveButton("我知道了", null);
                }
                builder.show();
            }
        });
    }
}
