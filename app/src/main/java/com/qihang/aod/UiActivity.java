package com.qihang.aod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.Callable;

/**
 * 所有页面的公共骨架：**沿用小米设置的原生资源**（{@link MiSettingsUi}）拼出
 * 「大标题 + 分组卡片」的页面，并提供 root 异步任务、三态广播、预览图等辅助方法。
 *
 * <p>页面结构对齐 OpenAlive：首页（HomeActivity）→ 二级菜单（动态壁纸库 / 编辑 / 通知光效）。
 */
public abstract class UiActivity extends Activity {

    protected static final String TAG = Root.TAG;

    /** 桌面槽位用的魅族原生壁纸（默认 CosmicWallpaperV6）。 */
    protected static final String ALIVE_PKG = "com.flyme.alivewallpaper";

    protected MiSettingsUi ui;
    protected LinearLayout col;
    protected TextView status;

    private final StringBuilder log = new StringBuilder();

    /** 子类在 onCreate 里调用：建好可滚动页面并返回内容列。 */
    protected void page(String title, String subtitle) {
        page(title, subtitle, null);
    }

    protected void page(String title, String subtitle, android.view.View rightAction) {
        ui = MiSettingsUi.create(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.pageBackground());
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), ui.dp(40));
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(0xFF9A9A9A);
        tv.setGravity(Gravity.START);
        tv.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), 0);
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

    /** 首页顶部那种「● 文字」状态点。 */
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
        android.view.View circle = new android.view.View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFF9A9A9E);
        circle.setBackground(g);
        box.addView(circle, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(ui.titleColor());
        LinearLayout.LayoutParams tp =
                new LinearLayout.LayoutParams(-2, -2);
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
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bg).setColor(ok ? 0xFF34C759 : 0xFFFF3B30);
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
            runOnUiThread(() -> append(text));
        }, "qihang-task").start();
    }

    protected void append(String line) {
        Log.i(TAG, line);
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