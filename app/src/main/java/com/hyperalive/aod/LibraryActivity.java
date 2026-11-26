/*
 * Design or implementation reference: OpenAlive (app/java/org/aliveclean/WallpaperLibrary.java).
 * Upstream attribution and applicable license: THIRD_PARTY_NOTICES.md.
 */
package com.hyperalive.aod;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** 内置动态壁纸库。点选项目后在编辑器中预览三态。 */
public class LibraryActivity extends UiActivity {

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildLibrary();
        } catch (Throwable t) {
            Log.e(TAG, "壁纸库构建失败", t);
            TextView tv = new TextView(this);
            tv.setText("界面构建失败：\n" + t);
            tv.setPadding(40, 120, 40, 40);
            setContentView(tv);
        }
    }

    private void buildLibrary() {
        ui = MiSettingsUi.create(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.pageBackground());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(12), statusBarHeight() + ui.dp(10), ui.dp(16), ui.dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(ui.titleColor());
        back.setGravity(Gravity.CENTER);
        back.setBackground(ui.itemRipple(20));
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        backLp.setMarginEnd(ui.dp(6));
        bar.addView(back, backLp);

        TextView title = new TextView(this);
        title.setText("动态壁纸");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTextColor(ui.titleColor());
        bar.addView(title, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        final int baseGridBottom = ui.dp(20);
        grid.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), baseGridBottom);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom = getNavigationBarInsetBottom(v, insets);
            grid.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), baseGridBottom + navBottom);
            return insets;
        });
        scroll.addView(grid, new ViewGroup.LayoutParams(-1, -2));

        List<DynamicWallpapers.Entry> items = DynamicWallpapers.ENTRIES;
        append("动态壁纸 " + items.size() + " 项：" + entryNames(items));
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有找到壁纸");
            empty.setTextColor(ui.summaryColor());
            empty.setPadding(ui.dp(4), ui.dp(20), 0, 0);
            grid.addView(empty);
        } else {
            int col = 0;
            LinearLayout row = null;
            for (DynamicWallpapers.Entry entry : items) {
                if (col == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                row.addView(gridCell(entry), cellParams(col));
                col++;
                if (col == 3) {
                    col = 0;
                }
            }
        }
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        applyStatusBarAppearance(ui.night);
    }

    private String entryNames(List<DynamicWallpapers.Entry> items) {
        StringBuilder sb = new StringBuilder();
        for (DynamicWallpapers.Entry entry : items) {
            sb.append(entry.label).append(' ');
        }
        return sb.toString();
    }

    private LinearLayout.LayoutParams cellParams(int col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(col == 0 ? 0 : ui.dp(10));
        lp.bottomMargin = ui.dp(18);
        return lp;
    }

    /** 一格：圆角效果图 + 名字；点击进编辑器锁屏场景并带上这张壁纸。 */
    private View gridCell(DynamicWallpapers.Entry entry) {
        String label = entry.label;
        Drawable thumb = entry.thumbnail(this);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackground(ui.itemRipple(16));

        FrameLayout box = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ui.cardColor());
        bg.setCornerRadius(ui.dp(16));
        if (ui.night) {
            bg.setStroke(Math.max(1, ui.dp(0.5f)), 0x18FFFFFF);
        }
        box.setBackground(bg);
        box.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (thumb != null) {
            iv.setImageDrawable(thumb);
        }
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        cell.addView(box, new LinearLayout.LayoutParams(-1, ui.dp(158)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setTextColor(ui.titleColor());
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = ui.dp(8);
        cell.addView(name, np);

        cell.setClickable(true);
        cell.setOnClickListener(v -> startActivity(
                EditorActivity.intent(this, Motion.LOCK, entry.selection())));
        return cell;
    }

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

    /** 当前桌面（FLAG_SYSTEM）壁纸的组件名，形如 pkg/cls；拿不到返回 null。 */
    static String currentDesktopComponent(Context ctx) {
        try {
            android.app.WallpaperInfo info =
                    android.app.WallpaperManager.getInstance(ctx).getWallpaperInfo();
            if (info != null && info.getComponent() != null) {
                return info.getComponent().getPackageName() + "/" + info.getComponent().getClassName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}