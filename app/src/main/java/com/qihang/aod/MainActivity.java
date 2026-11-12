package com.qihang.aod;

import android.app.AlertDialog;
import android.content.Intent;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页 —— 布局与 OpenAlive 的 {@code HomeActivity} 保持一致（实机对齐）：
 *
 * <pre>
 *   标题「桌面、壁纸和个性化」
 *   ● LSP 已激活   ● ROOT 已授权
 *   布局：息屏 | 锁屏 | 桌面   三张效果图横排（点一张进编辑页对应场景）
 *   动态壁纸 / Alive 壁纸      全宽卡片 → 二级菜单
 *   通知光效                   全宽卡片（功能未实现，占位）
 * </pre>
 *
 * <p>刻意<b>不做</b> OpenAlive 里的「Vivo Alive 壁纸」「小米 Alive 壁纸」，也不放字体/主题 tile。
 */
public class MainActivity extends UiActivity {

    private Dot lspDot;
    private Dot rootDot;
    private final java.util.concurrent.atomic.AtomicLong probeSeq = new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        page("桌面、壁纸和个性化", null, createThreeDotsButton());
        // 整体往下挪一点（标题别贴着状态栏）
        col.setPadding(ui.dp(16), ui.dp(56), ui.dp(16), ui.dp(40));

        // ---- 状态点 ----
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(ui.dp(4), ui.dp(2), 0, ui.dp(14));
        lspDot = dot("LSP 检测中…");
        rootDot = dot("ROOT 检测中…");
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMarginEnd(ui.dp(20));
        dots.addView(lspDot.box, gap);
        dots.addView(rootDot.box, new LinearLayout.LayoutParams(-2, -2));
        col.addView(dots);

        // ---- 布局：三态效果图横排 ----
        group("布局");
        // 三态这一排和下面的卡片要拉开一点（之前只剩 3.7dp，太挤）
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(-1, -2);
        tilesLp.bottomMargin = ui.dp(14);
        col.addView(sceneTiles(), tilesLp);

        // ---- 壁纸：每项各一张卡片，彼此留间距 ----
        addSpacedCard(centerCard("动态壁纸", icon("ic_wallpaper"),
                v -> startActivity(new Intent(this, LibraryActivity.class))));
        // ---- 通知光效（未实现，占位）----
        addSpacedCard(leftCard("通知光效", "动静之间 传达通知", icon("ic_flash_notification", "ic_float_notification"),
                v -> startActivity(new Intent(this, NotificationActivity.class))));

        // ---- 恢复默认壁纸 ----
        addSpacedCard(leftCard("恢复默认壁纸", "还原为 HyperOS 原生静态壁纸（禁用模块前建议执行）",
                icon("ic_wallpaper", "ic_settings_backup_restore"),
                v -> confirmRestoreDefaultWallpaper()));
    }

    /** 独立卡片 + 卡片间距（首页几个选项之间要有空隙）。 */
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

    private LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

/** 取系统设置里的图标（找不到就返回 null，只显示文字）。 */
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

    /** 息屏 / 锁屏 / 桌面 三张效果图横排，点一张进编辑页。 */
    private final java.util.List<LivePreviewView> previews = new java.util.ArrayList<>();

    @Override
    protected void onResume() {
        super.onResume();
        for (LivePreviewView v : previews) {
            v.startScene();
        }
        syncClockPreview();
        probeStatus();
    }

    @Override
    protected void onPause() {
        for (LivePreviewView v : previews) {
            v.stopScene();
        }
        super.onPause();
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
        // 动态效果图：里面是 TextureView 实时渲染（没有管线时自动退回静态效果图）
        LivePreviewView iv = new LivePreviewView(this, scene);
        iv.setBackground(ui.rounded(ui.cardColor(), 14));
        previews.add(iv);
        iv.setFallbackAsset(asset);
        item.addView(iv, new LinearLayout.LayoutParams(-1, ui.dp(232)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(ui.titleColor());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = ui.dp(10);
        item.addView(tv, tp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(marginStart);
        row.addView(item, lp);
        item.setClickable(true);
        item.setOnClickListener(v -> startActivity(EditorActivity.intent(this, scene)));
    }

    /** 绿点状态：只读探测当前用户的 LSPosed 状态与 root，去重异步过期结果。 */
    private void probeStatus() {
        final long seq = probeSeq.incrementAndGet();
        new Thread(() -> {
            try {
                PhotoStore.syncToDe(this);
            } catch (Throwable ignored) {}
            boolean root = Root.hasRoot();
            LspState.State lspState = LspState.queryLspState(this);

            if (seq != probeSeq.get() || isFinishing() || isDestroyed()) {
                return;
            }

            if (lspState == LspState.State.DISABLED) {
                LspState.checkFallbackIfInactiveAsync(this);
            }

            Log.i(TAG, "状态探测 root=" + root + " lspState=" + lspState);
            runOnUiThread(() -> {
                if (seq != probeSeq.get() || isFinishing() || isDestroyed()) {
                    return;
                }
                String lspText;
                boolean lspOk = false;
                if (lspState == LspState.State.ENABLED) {
                    lspText = "LSP 已激活";
                    lspOk = true;
                } else if (lspState == LspState.State.DISABLED) {
                    lspText = "LSP 已停用";
                    lspOk = false;
                } else {
                    lspText = "LSP 未激活";
                    lspOk = false;
                }
                setDot(lspDot, lspText, lspOk);
                setDot(rootDot, "ROOT " + (root ? "已授权" : "未授权"), root);
            });
        }, "qihang-status").start();
    }

    private void syncClockPreview() {
        ClockSettings.Config c = ClockSettings.get(this);
        for (LivePreviewView pv : previews) {
            pv.setAodStyle(AodStyleSettings.getStyle(this));
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
        TypedValue out = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
            btn.setBackgroundResource(out.resourceId);
        }
        btn.setClickable(true);
        btn.setContentDescription("更多");
        btn.setOnClickListener(v -> showMoreMenuDialog());
        return btn;
    }

    /** 更多菜单：重启作用域 / 恢复默认壁纸。 */
    private void showMoreMenuDialog() {
        final String[] items = new String[]{"重启作用域", "恢复默认壁纸"};
        boolean isNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int theme = isNight ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, theme);
        builder.setTitle("更多操作");
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                showRestartScopeDialog();
            } else if (which == 1) {
                confirmRestoreDefaultWallpaper();
            }
        });
        builder.show();
    }

    /** 二次确认弹窗：恢复 HyperOS 原生静态壁纸。 */
    private void confirmRestoreDefaultWallpaper() {
        boolean isNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int theme = isNight ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, theme);
        builder.setTitle("恢复默认壁纸");
        builder.setMessage("确定要将壁纸还原为 HyperOS 原生静态壁纸吗？\n\n如果在 LSPosed 中禁用本模块，请务必先恢复默认壁纸，以避免开机时产生壁纸异常。");
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("立即恢复", (dialog, which) -> {
            toast("正在恢复系统原生壁纸…");
            new Thread(() -> {
                boolean ok = Root.restoreDefaultWallpaper(this);
                runOnUiThread(() -> {
                    if (ok) {
                        toast("已成功恢复为系统原生壁纸！");
                    } else {
                        toast("恢复壁纸失败，请检查 ROOT 权限");
                    }
                });
            }, "qihang-restore-wall").start();
        });
        builder.show();
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

        boolean isNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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
        builder.show();
    }

    /** 执行重启所选作用域（支持多选）。 */
    private void doRestartScopes(boolean[] checked) {
        if (!Root.hasRoot()) {
            toast("未获取 ROOT 权限，无法重启作用域");
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
        final String summary = String.join("、", selectedNames);

        // 如果勾选了「系统框架」，软重启框架会自动拉起所有进程
        if (checked[1]) {
            toast("正在重启系统框架…");
            new Thread(() -> {
                Root.su("setprop ctl.restart zygote", 10000);
            }, "qihang-restart-scope").start();
            return;
        }

        toast("正在重启 " + summary + "…");
        new Thread(() -> {
            if (checked[2]) { // 熄屏与锁屏编辑
                Root.su("am force-stop com.miui.aod", 5000);
                Root.su("pkill -9 -f com.miui.aod", 5000);
            }
            if (checked[3]) { // 系统界面组件
                Root.su("pkill -9 -f miui.systemui.plugin", 5000);
            }
            if (checked[0] || checked[3]) { // 系统界面 或 系统界面组件（运行在 SystemUI 进程内）
                Root.su("pkill -9 -f com.android.systemui", 5000);
            }
            runOnUiThread(() -> toast("已重启 " + summary));
        }, "qihang-restart-scope").start();
    }

}
