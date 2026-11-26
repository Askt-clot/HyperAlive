package com.hyperalive.aod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.Callable;

/**
 * 所有页面的公共骨架：**沿用小米设置的原生资源**（{@link MiSettingsUi}）拼出
 * 「大标题 + 分组卡片」的页面，对齐 HyperOS / Miuix 视觉语言，并提供 root 异步任务、三态广播、预览图等辅助方法。
 *
 * <p>页面结构对齐 OpenAlive：首页（MainActivity）→ 二级菜单（动态壁纸库 / 场景编辑）。
 */
public abstract class UiActivity extends Activity {

    protected static final String TAG = Root.TAG;

    /** 桌面槽位用的魅族原生壁纸（默认 CosmicWallpaperV6）。 */

    protected MiSettingsUi ui;
    protected LinearLayout col;
    protected TextView status;

    private final StringBuilder log = new StringBuilder();

    /** 子类在 onCreate 里调用：建好可滚动页面并返回内容列。 */
    protected void page(String title, String subtitle) {
        page(title, subtitle, (java.util.function.Supplier<android.view.View>) null);
    }

    protected void page(String title, String subtitle, android.view.View rightAction) {
        page(title, subtitle, rightAction != null ? () -> rightAction : null);
    }

    protected void page(String title, String subtitle, java.util.function.Supplier<android.view.View> rightActionSupplier) {
        ui = MiSettingsUi.create(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.pageBackground());
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final int baseBottom = ui.dp(20);
        col.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), baseBottom);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = getNavigationBarInsetBottom(v, insets);
            col.setPadding(col.getPaddingLeft(), col.getPaddingTop(), col.getPaddingRight(), baseBottom + navBottom);
            return insets;
        });
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));
        android.view.View rightAction = rightActionSupplier != null ? rightActionSupplier.get() : null;
        if (rightAction == null) {
            col.addView(ui.title(title));
        } else {
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView titleTv = ui.title(title);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
            header.addView(titleTv, titleLp);
            header.addView(rightAction, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
            col.addView(header, new LinearLayout.LayoutParams(-1, -2));
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            col.addView(ui.subtitle(subtitle));
        }
        setContentView(scroll);
        applyStatusBarAppearance(ui.night);
    }

    /** 获取系统导航栏与手势小白条在底部的真实 Inset（API30+ 取导航栏与强制手势并集，API29 回退）。 */
    public static int getNavigationBarInsetBottom(View v, WindowInsets insets) {
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets ins = insets.getInsets(
                        WindowInsets.Type.navigationBars()
                        | WindowInsets.Type.mandatorySystemGestures());
                return Math.max(0, ins.bottom);
            } else {
                return Math.max(0, insets.getSystemWindowInsetBottom());
            }
        }
        if (v != null) {
            WindowInsets rootInsets = v.getRootWindowInsets();
            if (rootInsets != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets ins = rootInsets.getInsets(
                            WindowInsets.Type.navigationBars()
                            | WindowInsets.Type.mandatorySystemGestures());
                    return Math.max(0, ins.bottom);
                } else {
                    return Math.max(0, rootInsets.getSystemWindowInsetBottom());
                }
            }
        }
        return 0;
    }

    /** 设置普通页面系统栏图标外观：浅色背景启用深色状态栏与导航栏图标，深色背景还原浅色图标。 */
    protected void applyStatusBarAppearance(boolean night) {
        Window w = getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        boolean lightBars = !night;
        boolean handled = false;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = w.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int appearance = lightBars ? mask : 0;
                controller.setSystemBarsAppearance(appearance, mask);
                handled = true;
            }
        }
        if (!handled && decor != null) {
            int flags = decor.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                mask |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            if (lightBars) {
                flags |= mask;
            } else {
                flags &= ~mask;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /** 页面底部状态日志（排障用）。 */
    protected void addStatusLog() {
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(0xFF6E6E6E);
        status.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), 0);
        status.setTextIsSelectable(true);
        col.addView(status);
        if (log.length() > 0) {
            status.setText(log.toString());
        }
    }

    protected void group(String title) {
        col.addView(ui.groupTitle(title));
    }

    protected void addCard(MiSettingsUi.Card card) {
        col.addView(card.outer, new LinearLayout.LayoutParams(-1, -2));
    }

    protected void hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(ui.summaryColor());
        tv.setGravity(Gravity.START);
        tv.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0);
        col.addView(tv);
    }

    protected ImageView preview(String asset, int wDp, int hDp) {
        ImageView iv = ui.preview(wDp, hDp);
        try (InputStream in = getAssets().open(asset)) {
            iv.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Throwable t) {
            Log.w(TAG, "预览图缺失 " + asset, t);
        }
        return iv;
    }

    /** 首页顶部状态胶囊（HyperOS 胶囊芯片视觉）。 */
    protected static final class Dot {
        LinearLayout box;
        TextView text;
        android.view.View circle;
    }

    protected Dot dot(String label) {
        Dot d = new Dot();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(ui.rounded(ui.statusChipBg(), 14));
        box.setPadding(ui.dp(10), ui.dp(5), ui.dp(12), ui.dp(5));

        android.view.View circle = new android.view.View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF9A9A9E);
        circle.setBackground(g);
        box.addView(circle, new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(ui.titleColor());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(ui.dp(6));
        box.addView(tv, tp);

        d.box = box;
        d.text = tv;
        d.circle = circle;
        return d;
    }

    protected void setDot(Dot d, String label, boolean ok) {
        if (d == null) {
            return;
        }
        d.text.setText(label);
        android.graphics.drawable.Drawable bg = d.circle.getBackground();
        if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).setColor(ok ? 0xFF34C759 : 0xFFFF3B30);
        }
    }

    /**
     * 展示图 = 「最后一次在模块里设置的壁纸」（见 {@link LastSet}）：
     * 熄屏=用最后设置的锁屏照片按启航真实几何合成，锁屏=最后设的锁屏照片，桌面=最后设的桌面。
     * 不去读系统实时状态，所以桌面被其它应用改过也不影响展示。
     */
    protected void attachLivePreview(ImageView iv, int mode) {
        new Thread(() -> {
            final android.graphics.Bitmap bmp = LastSet.preview(this, mode);
            if (bmp == null) {
                return;
            }
            runOnUiThread(() -> iv.setImageBitmap(bmp));
        }, "qihang-preview-" + mode).start();
    }

    /** 在线程里跑特权操作，结果写进状态日志。 */
    protected void run0(String label, Callable<String> task) {
        append(label + " …");
        new Thread(() -> {
            String out;
            try {
                out = task.call();
            } catch (Throwable t) {
                out = "异常：" + t;
            }
            final String text = label + " => " + out;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    append(text);
                }
            });
        }, "qihang-task").start();
    }

    protected void append(String line) {
        Log.i(TAG, line);
        ModuleDiagLog.record(getApplicationContext(), line);
        log.append(line).append('\n');
        if (status != null) {
            runOnUiThread(() -> status.setText(log.toString()));
        }
    }

    protected void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** 手动切换三态（调试用）；正式态由 SystemUI 的 hook 下发。 */
    protected void sendMode(int mode, String label) {
        Intent i = new Intent(XposedInit.ACTION_MODE);
        i.setPackage(getPackageName());
        i.putExtra(XposedInit.EXTRA_MODE, mode);
        i.putExtra(XposedInit.EXTRA_ANIMATE, true);
        i.putExtra(XposedInit.EXTRA_STATE, "manual-" + label);
        sendBroadcast(i);
        append("手动广播 mode=" + mode + "（" + label + "）");
    }

    /** 让圆圈内容重新跟随桌面壁纸。 */
    protected void sendReloadPhoto() {
        Intent i = new Intent(XposedInit.ACTION_MODE);
        i.setPackage(getPackageName());
        i.putExtra("reload_photo", true);
        sendBroadcast(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /** 子类可重写：每次回到页面时刷新状态摘要。 */
    protected void refreshStatus() {
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }
}
