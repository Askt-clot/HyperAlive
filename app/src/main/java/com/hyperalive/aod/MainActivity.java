package com.hyperalive.aod;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页 —— 对齐 HyperOS 设置视觉规范（参考 miuix_preference_settings 与 HyperCeiler）：
 *
 * <pre>
 *   大标题「桌面、壁纸和个性化」
 *   [ ● LSP 已激活 ]   [ ● ROOT 已授权 ]   HyperOS 胶囊状态芯片
 *   分组「布局」：息屏 | 锁屏 | 桌面       三张圆角效果图横排（点选进编辑页对应场景）
 *   动态壁纸                              HyperOS 偏好项卡片 + 右侧导航箭头 ›
 *   恢复应用前壁纸                        HyperOS 偏好项操作卡片（无二级箭头）
 * </pre>
 *
 * 保持现有页面结构、排列顺序、三态预览几何与全部底层逻辑不变。
 */
public class MainActivity extends UiActivity {

    private Dot lspDot;
    private Dot rootDot;
    private final java.util.concurrent.atomic.AtomicLong probeSeq = new java.util.concurrent.atomic.AtomicLong(0);

    static final class StatusProbeCoordinator {
        interface ProbeAction {
            ProbeResult execute();
        }

        interface ResultConsumer {
            void onProbeCompleted(long seq, ProbeResult result);
        }

        static final class ProbeResult {
            final boolean hasRoot;
            final LspState.State lspState;

            ProbeResult(boolean hasRoot, LspState.State lspState) {
                this.hasRoot = hasRoot;
                this.lspState = lspState;
            }
        }

        private final Object lock = new Object();
        private long latestSeq = 0L;
        private boolean running = false;
        private java.lang.ref.WeakReference<ResultConsumer> activeConsumerRef =
                new java.lang.ref.WeakReference<>(null);
        private ProbeAction activeAction;

        long requestProbe(ResultConsumer consumer, ProbeAction action, java.util.concurrent.Executor executor) {
            return requestProbe(consumer, null, action, executor);
        }

        long requestProbe(
                ResultConsumer consumer,
                java.util.concurrent.atomic.AtomicLong seqSink,
                ProbeAction action,
                java.util.concurrent.Executor executor) {
            final long seq;
            final boolean shouldStart;
            synchronized (lock) {
                seq = ++latestSeq;
                if (seqSink != null) {
                    seqSink.set(seq);
                }
                activeConsumerRef = new java.lang.ref.WeakReference<>(consumer);
                activeAction = action;
                if (!running) {
                    running = true;
                    shouldStart = true;
                } else {
                    shouldStart = false;
                }
            }
            if (shouldStart) {
                Runnable loopTask = new Runnable() {
                    @Override
                    public void run() {
                        runLoop();
                    }
                };
                if (executor != null) {
                    executor.execute(loopTask);
                } else {
                    Thread t = new Thread(loopTask, "qihang-status");
                    t.setDaemon(true);
                    t.start();
                }
            }
            return seq;
        }

        void cancelConsumer(ResultConsumer consumer) {
            cancelConsumer(consumer, null);
        }

        void cancelConsumer(ResultConsumer consumer, java.util.concurrent.atomic.AtomicLong seqSink) {
            synchronized (lock) {
                final long nextSeq = ++latestSeq;
                if (seqSink != null) {
                    seqSink.set(nextSeq);
                }
                ResultConsumer cur = activeConsumerRef.get();
                if (cur == null || cur == consumer) {
                    activeConsumerRef.clear();
                    activeAction = null;
                }
            }
        }

        private void runLoop() {
            while (true) {
                final long targetSeq;
                final ProbeAction action;
                synchronized (lock) {
                    ResultConsumer cur = activeConsumerRef.get();
                    if (cur == null || activeAction == null) {
                        running = false;
                        activeAction = null;
                        return;
                    }
                    targetSeq = latestSeq;
                    action = activeAction;
                }

                ProbeResult result = null;
                try {
                    result = action.execute();
                } catch (Throwable ignored) {
                }

                final ResultConsumer deliverTo;
                synchronized (lock) {
                    ResultConsumer cur = activeConsumerRef.get();
                    if (cur == null) {
                        running = false;
                        activeAction = null;
                        return;
                    }
                    if (latestSeq != targetSeq) {
                        // 在途期间又收到更新的 onResume 探测请求：丢弃过期结果，直接在同一线程继续跑最新一轮
                        continue;
                    }
                    deliverTo = cur;
                    running = false;
                    activeAction = null;
                }
                if (result != null && deliverTo != null) {
                    deliverTo.onProbeCompleted(targetSeq, result);
                }
                return;
            }
        }
    }

    static String formatLspStatusLabel(LspState.State state) {
        if (state == LspState.State.ENABLED) {
            return "LSP 已激活";
        }
        if (state == LspState.State.DISABLED) {
            return "LSP 已停用";
        }
        return "LSP 状态未知";
    }

    static boolean isLspStatusOk(LspState.State state) {
        return state == LspState.State.ENABLED;
    }

    private static final StatusProbeCoordinator sStatusProbeCoordinator = new StatusProbeCoordinator();
    private final StatusProbeCoordinator.ResultConsumer mProbeConsumer = new StatusProbeCoordinator.ResultConsumer() {
        @Override
        public void onProbeCompleted(final long seq, final StatusProbeCoordinator.ProbeResult result) {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Log.i(TAG, "状态探测 root=" + result.hasRoot + " lspState=" + result.lspState);
            runOnUiThread(() -> {
                if (seq != probeSeq.get() || isFinishing() || isDestroyed()) {
                    return;
                }
                String lspText = formatLspStatusLabel(result.lspState);
                boolean lspOk = isLspStatusOk(result.lspState);
                setDot(lspDot, lspText, lspOk);
                setDot(rootDot, "ROOT " + (result.hasRoot ? "已授权" : "未授权"), result.hasRoot);
            });
        }
    };

    private final PhotoDraftStore.StartupRecoveryListener mRecoveryListener =
            new PhotoDraftStore.StartupRecoveryListener() {
                @Override
                public void onStartupRecoveryFinished(final PhotoDraftStore.RecoveryResult result) {
                    if (result == null || (!result.hasChanges() && result.ok)) {
                        return;
                    }
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (result.hasChanges()) {
                            AodStyleSettings.resetCacheForTest();
                            syncClockPreview();
                            for (LivePreviewView v : previews) {
                                v.reloadPhoto();
                            }
                        }
                        if (!result.ok) {
                            toast("检测到未解决的壁纸事务恢复异常，已保留快照证据");
                        }
                    });
                }
            };

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhotoDraftStore.triggerStartupRecoveryAsync(this);
        PhotoDraftStore.addStartupRecoveryListener(mRecoveryListener);
        try {
            buildUi();
        } catch (Throwable t) {
            Log.e(TAG, "构建界面失败", t);
            TextView tv = new TextView(this);
            tv.setText("界面构建失败：\n" + t);
            tv.setPadding(40, 120, 40, 40);
            setContentView(tv);
        }
    }

    private void buildUi() {
        page("桌面、壁纸和个性化", null, this::createThreeDotsButton);
        col.setPadding(ui.dp(16), ui.dp(52), ui.dp(16), ui.dp(40));

        // ---- 状态胶囊芯片 ----
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(0, ui.dp(4), 0, ui.dp(14));
        lspDot = dot("LSP 检测中…");
        rootDot = dot("ROOT 检测中…");
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMarginEnd(ui.dp(10));
        dots.addView(lspDot.box, gap);
        dots.addView(rootDot.box, new LinearLayout.LayoutParams(-2, -2));
        col.addView(dots);

        // ---- 布局：三态效果图横排 ----
        group("布局");
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(-1, -2);
        tilesLp.bottomMargin = ui.dp(16);
        col.addView(sceneTiles(), tilesLp);

        // ---- 壁纸入口项：严格保持原顺序与功能，仅对二级页面显示导航箭头 › ----
        addSpacedCard(ui.preferenceCard("动态壁纸", null, icon("ic_wallpaper"), true,
                v -> startActivity(new Intent(this, LibraryActivity.class))));

        addSpacedCard(ui.preferenceCard("恢复应用前壁纸", null,
                icon("ic_wallpaper", "ic_settings_backup_restore"), false,
                v -> confirmRestorePreModuleWallpaper()));
    }

    /** 独立卡片 + 底部留白（对齐 HyperOS 分组卡片节奏）。 */
    private void addSpacedCard(MiSettingsUi.Card card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.dp(12);
        col.addView(card.outer, lp);
    }

    /** 取系统设置里的图标（按候选顺序找，全找不到就返回 null 只显示文字）。 */
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
    }

    // =====================================================================
    // 息屏 / 锁屏 / 桌面 三张效果图横排
    // =====================================================================

    private final java.util.List<LivePreviewView> previews = new java.util.ArrayList<>();

    @Override
    protected void onResume() {
        super.onResume();
        syncClockPreview();
        for (LivePreviewView v : previews) {
            v.startScene();
        }
        probeStatus();
    }

    @Override
    protected void onPause() {
        sStatusProbeCoordinator.cancelConsumer(mProbeConsumer, probeSeq);
        for (LivePreviewView v : previews) {
            v.stopScene();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        PhotoDraftStore.removeStartupRecoveryListener(mRecoveryListener);
        sStatusProbeCoordinator.cancelConsumer(mProbeConsumer, probeSeq);
        super.onDestroy();
    }

    private LinearLayout sceneTiles() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        tile(row, "熄屏", "preview/aod_qihang.png", Motion.AOD, 0);
        tile(row, "锁屏", "photo/default.jpg", Motion.LOCK, ui.dp(10));
        tile(row, "桌面", "preview/wp_alive_light.png", Motion.HOME, ui.dp(10));
        return row;
    }

    private void tile(LinearLayout row, String label, String asset, int scene, int marginStart) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setBackground(ui.itemRipple(16));

        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.cardColor());
        bg.setCornerRadius(ui.dp(16));
        if (ui.night) {
            bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x18FFFFFF);
        }
        box.setBackground(bg);
        box.setClipToOutline(true);

        LivePreviewView iv = new LivePreviewView(this, scene);
        previews.add(iv);
        iv.setFallbackAsset(asset);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        item.addView(box, new LinearLayout.LayoutParams(-1, ui.dp(232)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(ui.titleColor());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = ui.dp(8);
        item.addView(tv, tp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(marginStart);
        row.addView(item, lp);

        item.setClickable(true);
        item.setOnClickListener(v -> startActivity(EditorActivity.intent(this, scene)));
    }

    /** 绿点状态：单飞合并后台探测当前用户的 LSPosed 状态与 root，去重异步过期结果并避免并发 fork su。 */
    private void probeStatus() {
        final android.content.Context appCtx = getApplicationContext();
        sStatusProbeCoordinator.requestProbe(mProbeConsumer, probeSeq, new StatusProbeCoordinator.ProbeAction() {
            @Override
            public StatusProbeCoordinator.ProbeResult execute() {
                try {
                    PhotoDraftStore.awaitStartupRecoveryIfNonMainThread(appCtx);
                    PreModuleWallpaperBackup.lockTransaction();
                    try {
                        if (!PhotoDraftStore.hasUnresolvedRecovery(appCtx)) {
                            PhotoStore.syncToDe(appCtx);
                        }
                    } finally {
                        PreModuleWallpaperBackup.unlockTransaction();
                    }
                } catch (Throwable ignored) {}
                boolean root = Root.hasRoot();
                LspState.State lspState = LspState.queryLspState(appCtx, root);
                return new StatusProbeCoordinator.ProbeResult(root, lspState);
            }
        }, null);
    }

    private void syncClockPreview() {
        int style = AodStyleSettings.getStyle(this);
        for (LivePreviewView pv : previews) {
            pv.setCommittedAodStyle(style);
            pv.refreshAodClock();
        }
    }

    /** 标题栏右上角三点菜单按钮。 */
    private View createThreeDotsButton() {
        View btn = new View(this) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                paint.setColor(ui.titleColor());
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float r = ui.dp(2f);
                float gap = ui.dp(6f);
                canvas.drawCircle(cx, cy - gap, r, paint);
                canvas.drawCircle(cx, cy, r, paint);
                canvas.drawCircle(cx, cy + gap, r, paint);
            }
        };
        btn.setBackground(ui.itemRipple(20));
        btn.setClickable(true);
        btn.setContentDescription("更多");
        btn.setOnClickListener(v -> showMoreMenuDialog());
        return btn;
    }

    /** 对话框统一 HyperOS 圆角与色调。 */
    private void applyHyperOsDialogStyle(AlertDialog dialog, Integer positiveColor) {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ui.night ? 0xFF242428 : 0xFFFFFFFF);
            bg.setCornerRadius(ui.dp(22));
            if (ui.night) {
                bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x22FFFFFF);
            }
            w.setBackgroundDrawable(bg);
        }
        dialog.setOnShowListener(d -> {
            Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            Button neu = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            if (pos != null) {
                pos.setTypeface(med);
                if (positiveColor != null) {
                    pos.setTextColor(positiveColor);
                } else {
                    pos.setTextColor(ui.accentBlue());
                }
            }
            if (neg != null) {
                neg.setTypeface(med);
                neg.setTextColor(ui.summaryColor());
            }
            if (neu != null) {
                neu.setTypeface(med);
                neu.setTextColor(ui.accentBlue());
            }
        });
    }

    /** 更多菜单：重启作用域 / 恢复应用前壁纸 / 日志。 */
    private void showMoreMenuDialog() {
        final String[] items = new String[]{"重启作用域", "恢复应用前壁纸", "日志"};
        boolean isNight = ui.night;
        int theme = isNight ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, theme);
        builder.setTitle("更多操作");
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                showRestartScopeDialog();
            } else if (which == 1) {
                confirmRestorePreModuleWallpaper();
            } else if (which == 2) {
                showDiagLogDialog();
            }
        });
        AlertDialog dialog = builder.create();
        applyHyperOsDialogStyle(dialog, null);
        dialog.show();
    }

    static String resolveDiagSubtitleText(ModuleDiagLog.DiagSnapshot snap) {
        if (snap == null || !snap.rootAvailable) {
            return "仅显示应用操作日志（未获取 Root 权限）";
        }
        if (!snap.hookCollectionOk) {
            return "仅显示应用操作日志（Root 可用但 Hook 日志读取失败）";
        }
        return snap.hasHookLogs
                ? "已接入应用操作日志 + 系统 Hook 实时日志"
                : "已接入应用操作日志 + 系统 Hook 日志（暂无匹配记录）";
    }

    private void showDiagLogDialog() {
        final boolean isNight = ui.night;
        final int theme = isNight ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(4));

        final TextView subtitleView = new TextView(this);
        subtitleView.setText("包含应用内关键操作记录与（若已授权 Root）模块 Hook 日志");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setTextColor(ui.summaryColor());
        subtitleView.setPadding(0, 0, 0, ui.dp(8));
        container.addView(subtitleView);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        final TextView logView = new TextView(this);
        logView.setText("正在后台读取诊断日志…");
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(ui.titleColor());
        logView.setTextIsSelectable(true);
        logView.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(isNight ? 0xFF1A1A1E : 0xFFF3F4F6);
        boxBg.setCornerRadius(ui.dp(12));
        scrollView.setBackground(boxBg);
        scrollView.addView(logView, new FrameLayout.LayoutParams(-1, -2));

        int maxHeight = Math.max(ui.dp(220), getResources().getDisplayMetrics().heightPixels * 45 / 100);
        container.addView(scrollView, new LinearLayout.LayoutParams(-1, maxHeight));

        final String[] latestText = new String[]{""};
        final java.util.concurrent.atomic.AtomicInteger loadSeq = new java.util.concurrent.atomic.AtomicInteger(0);

        final AlertDialog dialog = new AlertDialog.Builder(this, theme)
                .setTitle("模块诊断日志")
                .setView(container)
                .setNeutralButton("刷新", null)
                .setNegativeButton("复制", null)
                .setPositiveButton("关闭", null)
                .create();
        applyHyperOsDialogStyle(dialog, null);
        dialog.show();

        final Runnable triggerRefresh = () -> {
            final int seq = loadSeq.incrementAndGet();
            logView.setText("正在后台读取诊断日志…");
            new Thread(() -> {
                ModuleDiagLog.DiagSnapshot snap = ModuleDiagLog.collectDiagnostics(MainActivity.this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing() || seq != loadSeq.get()) {
                        return;
                    }
                    latestText[0] = snap.formattedText;
                    logView.setText(snap.formattedText.isEmpty() ? "（暂无日志可显示）" : snap.formattedText);
                    subtitleView.setText(resolveDiagSubtitleText(snap));
                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                });
            }, "diag-log-load").start();
        };

        Button btnRefresh = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> triggerRefresh.run());
        }
        Button btnCopy = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                String text = latestText[0];
                if (text == null || text.trim().isEmpty()) {
                    toast("日志尚未就绪或为空");
                    return;
                }
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("HyperAlive 诊断日志", text));
                    toast("已复制诊断日志");
                } else {
                    toast("无法访问剪贴板");
                }
            });
        }
        triggerRefresh.run();
    }

    private volatile boolean mRestoreOpInProgress = false;

    /** 二次确认弹窗：在全局事务锁保护下异步探查快照，避免备份进行中误报 NONE 并确保零写入。 */
    private void confirmRestorePreModuleWallpaper() {
        if (mRestoreOpInProgress) {
            toast("正在处理恢复请求，请稍候…");
            return;
        }
        mRestoreOpInProgress = true;
        final boolean isNight = ui.night;
        final int theme = isNight ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        toast("正在读取应用前壁纸快照…");
        new Thread(() -> {
            PreModuleWallpaperBackup.BackupInspection inspection;
            try {
                inspection = PreModuleWallpaperBackup.inspectBackupLocked(MainActivity.this);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    mRestoreOpInProgress = false;
                    if (!isFinishing() && !isDestroyed()) {
                        toast("读取壁纸快照失败：" + t.getMessage());
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                mRestoreOpInProgress = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showRestorePreModuleDialog(theme, inspection);
            });
        }, "qihang-inspect-backup").start();
    }

    private void showRestorePreModuleDialog(
            int theme, PreModuleWallpaperBackup.BackupInspection inspection) {
        PreModuleWallpaperBackup.BackupState state =
                inspection != null ? inspection.state : PreModuleWallpaperBackup.BackupState.NONE;
        // 无快照或损坏快照均零写入，直接告知用户
        if (state != PreModuleWallpaperBackup.BackupState.VALID) {
            String msg = (state == PreModuleWallpaperBackup.BackupState.CORRUPTED)
                    ? "检测到应用前备份快照文件损坏或不完整。\n\n为防误操作，本次不会执行恢复，也不会改动当前设备壁纸。"
                    : "未检测到模块应用前的有效壁纸备份快照。\n\n当前设备未保存首次应用前的原生壁纸快照。为保证当前壁纸安全，不会对设备做任何写入。若需更换系统壁纸，请前往 HyperOS 系统设置挑选。";
            AlertDialog.Builder builder = new AlertDialog.Builder(this, theme);
            builder.setTitle("恢复应用前壁纸");
            builder.setMessage(msg);
            builder.setPositiveButton("我知道了", null);
            AlertDialog dialog = builder.create();
            applyHyperOsDialogStyle(dialog, null);
            dialog.show();
            return;
        }

        PreModuleWallpaperBackup.BackupMetadata meta = inspection.metadata;
        String timeStr = meta != null ? meta.getFormattedTime() : "未知时间";
        String sysDesc = meta != null ? meta.getSystemDescription() : "静态壁纸";
        String lockDesc = meta != null ? meta.getLockDescription() : "跟随桌面（合并槽）";

        AlertDialog.Builder builder = new AlertDialog.Builder(this, theme);
        builder.setTitle("恢复应用前壁纸");
        builder.setMessage("确定要还原为 HyperAlive 首次应用前的壁纸与系统参数吗？\n\n"
                + "• 快照时间：" + timeStr + "\n"
                + "• 桌面槽位：" + sysDesc + "\n"
                + "• 锁屏槽位：" + lockDesc + "\n"
                + "• 息屏参数：还原或清除模块修改的息屏设置");
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("立即恢复", (dialog, which) -> {
            if (mRestoreOpInProgress) {
                toast("正在恢复壁纸，请勿重复操作…");
                return;
            }
            mRestoreOpInProgress = true;
            toast("正在校验并恢复应用前壁纸…");
            new Thread(() -> {
                PreModuleWallpaperBackup.RestoreResult res;
                try {
                    res = PreModuleWallpaperBackup.restoreBackupSync(this);
                } catch (Throwable t) {
                    res = PreModuleWallpaperBackup.RestoreResult.failure("恢复异常：" + t.getMessage());
                }
                final PreModuleWallpaperBackup.RestoreResult finalRes = res;
                runOnUiThread(() -> {
                    mRestoreOpInProgress = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (finalRes.success) {
                        ModuleDiagLog.record(MainActivity.this, "恢复应用前壁纸成功");
                        toast("已成功还原应用前壁纸！");
                        AlertDialog d = new AlertDialog.Builder(this, theme)
                                .setTitle("恢复完成")
                                .setMessage(finalRes.message)
                                .setPositiveButton("确定", null)
                                .create();
                        applyHyperOsDialogStyle(d, null);
                        d.show();
                    } else if (finalRes.partial) {
                        ModuleDiagLog.record(MainActivity.this, "⚠ 恢复应用前壁纸部分完成：" + finalRes.message);
                        toast("部分项目已恢复");
                        AlertDialog d = new AlertDialog.Builder(this, theme)
                                .setTitle("部分恢复")
                                .setMessage(finalRes.message)
                                .setPositiveButton("确定", null)
                                .create();
                        applyHyperOsDialogStyle(d, 0xFFFF9500);
                        d.show();
                    } else {
                        ModuleDiagLog.record(MainActivity.this, "✖ 恢复应用前壁纸失败：" + finalRes.message);
                        toast("恢复未成功：" + finalRes.message);
                        AlertDialog d = new AlertDialog.Builder(this, theme)
                                .setTitle("恢复未完成")
                                .setMessage(finalRes.message)
                                .setPositiveButton("确定", null)
                                .create();
                        applyHyperOsDialogStyle(d, 0xFFFF3B30);
                        d.show();
                    }
                });
            }, "qihang-restore-wall").start();
        });
        AlertDialog dialog = builder.create();
        applyHyperOsDialogStyle(dialog, 0xFFFF3B30);
        dialog.show();
    }

    /** 弹出重启作用域对话框（模块在 LSPosed 中申请的各作用域，支持多选）。 */
    private void showRestartScopeDialog() {
        final String[] displayItems = new String[]{
                "系统界面",
                "系统框架",
                "熄屏与锁屏编辑",
                "系统界面组件"
        };
        final boolean[] checked = new boolean[]{true, false, false, false};

        boolean isNight = ui.night;
        int theme = isNight ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, theme);
        builder.setTitle("重启作用域");
        builder.setMultiChoiceItems(displayItems, checked, (dialog, which, isChecked) -> {
            checked[which] = isChecked;
        });
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("确定", (dialog, which) -> {
            doRestartScopes(checked);
        });
        AlertDialog dialog = builder.create();
        applyHyperOsDialogStyle(dialog, ui.accentBlue());
        dialog.show();
    }

    private volatile boolean mRestartOpInProgress = false;

    /** 执行重启所选作用域（支持多选，后台探测 Root 避免阻塞主线程）。 */
    private void doRestartScopes(boolean[] checked) {
        if (mRestartOpInProgress) {
            toast("正在重启作用域，请稍候…");
            return;
        }
        final String[] names = new String[]{"系统界面", "系统框架", "熄屏与锁屏编辑", "系统界面组件"};
        java.util.List<String> selectedNames = new java.util.ArrayList<>();
        for (int i = 0; i < checked.length; i++) {
            if (checked[i]) {
                selectedNames.add(names[i]);
            }
        }
        if (selectedNames.isEmpty()) {
            toast("请至少选择一个作用域");
            return;
        }
        final boolean[] snapshot = checked.clone();
        final String summary = String.join("、", selectedNames);
        mRestartOpInProgress = true;
        toast(snapshot[1] ? "正在重启系统框架…" : ("正在重启 " + summary + "…"));

        new Thread(() -> {
            try {
                if (!Root.hasRoot()) {
                    ModuleDiagLog.record(MainActivity.this, "重启作用域失败：未获取 ROOT 权限 (" + summary + ")");
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            toast("未获取 ROOT 权限，无法重启作用域");
                        }
                    });
                    return;
                }
                ModuleDiagLog.record(MainActivity.this, "重启作用域：" + summary);
                if (snapshot[1]) {
                    Root.su("setprop ctl.restart zygote", 10000);
                    return;
                }
                if (snapshot[2]) {
                    Root.su("am force-stop com.miui.aod", 5000);
                    Root.su("pkill -9 -f com.miui.aod", 5000);
                }
                if (snapshot[3]) {
                    Root.su("pkill -9 -f miui.systemui.plugin", 5000);
                }
                if (snapshot[0] || snapshot[3]) {
                    Root.su("pkill -9 -f com.android.systemui", 5000);
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        toast("已重启 " + summary);
                    }
                });
            } finally {
                mRestartOpInProgress = false;
            }
        }, "qihang-restart-scope").start();
    }
}
