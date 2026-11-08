package com.qihang.aod;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 直接复用设备上「小米设置」({@code com.android.settings}) 的真实资源与主题，
 * 不打包任何 UI APK —— 布局名未混淆（1900+ layout / 3100+ style），
 * 卡片槽位是 Android 公共 id：{@code android.R.id.title / summary / widget_frame}。
 *
 * <p>类加载：{@code Settings.apk + miuix.jar} 组成 PathClassLoader，供
 * {@code com.google.android.material.card.MaterialCardView} 之类库控件加载。
 * 取不到系统资源时自动降级为自绘卡片，保证任何机型都能打开。
 */
final class MiSettingsUi {

    private static final String TAG = Root.TAG;
    static final String SETTINGS_PKG = "com.android.settings";
    private static final String SETTINGS_APK = "/system_ext/priv-app/Settings/Settings.apk";
    private static final String MIUIX_JAR = "/system_ext/framework/miuix.jar";

    final Context base;
    final Resources res;
    final Theme theme;
    final boolean system;
    /** 跟随系统深色模式。 */
    final boolean night;
    private final ClassLoader loader;
    private final LayoutInflater inflater;

    private MiSettingsUi(Context base, Resources res, Theme theme, ClassLoader loader, boolean system,
                        boolean night) {
        this.base = base;
        this.night = night;
        this.res = res;
        this.theme = theme;
        this.loader = loader;
        this.system = system;
        this.inflater = LayoutInflater.from(base).cloneInContext(new ThemedContext(base, res, theme));
        if (system) {
            inflater.setFactory2(new LayoutInflater.Factory2() {
                @Override
                public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                    return load(name, context, attrs);
                }

                @Override
                public View onCreateView(String name, Context context, AttributeSet attrs) {
                    return load(name, context, attrs);
                }
            });
        }
    }

    private View load(String name, Context context, AttributeSet attrs) {
        if (name == null || !name.contains(".") || name.startsWith("android.")) {
            return null;
        }
        try {
            Class<?> c = Class.forName(name, false, loader);
            if (!View.class.isAssignableFrom(c)) {
                return null;
            }
            return (View) c.getConstructor(Context.class, AttributeSet.class).newInstance(context, attrs);
        } catch (Throwable t) {
            Log.w(TAG, "控件加载失败 " + name + ": " + t);
            return null;
        }
    }

    static boolean isNight(Context ctx) {
        try {
            return (ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 卡片底色。 */
    int cardColor() {
        return night ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** 主文字色。 */
    int titleColor() {
        return night ? 0xFFF2F2F2 : 0xFF101010;
    }

    /** 次要文字色。 */
    int summaryColor() {
        return night ? 0xFF9A9A9E : 0xFF8C8C8C;
    }

    /** 分组标题色。 */
    int groupColor() {
        return night ? 0xFF8E8E93 : 0xFF7A7A7A;
    }

    static MiSettingsUi create(Context base) {
        try {
            Resources res = base.getPackageManager().getResourcesForApplication(SETTINGS_PKG);
            ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
            ClassLoader loader = new dalvik.system.PathClassLoader(
                    SETTINGS_APK + java.io.File.pathSeparator + MIUIX_JAR, parent);
            Theme theme = res.newTheme();
            theme.applyStyle(android.R.style.Theme_DeviceDefault_Light, true);
            int style = res.getIdentifier("Theme.Settings", "style", SETTINGS_PKG);
            if (style != 0) {
                theme.applyStyle(style, true);
            }
            MiSettingsUi ui = new MiSettingsUi(base, res, theme, loader, true, isNight(base));
            Log.i(TAG, "小米设置资源已加载 theme=Theme.Settings(" + Integer.toHexString(style) + ")");
            return ui;
        } catch (Throwable t) {
            Log.w(TAG, "取不到小米设置资源，降级自绘: " + t);
            return new MiSettingsUi(base, base.getResources(), base.getTheme(), base.getClassLoader(), false,
                    isNight(base));
        }
    }

    int id(String type, String name) {
        if (!system) {
            return 0;
        }
        return res.getIdentifier(name, type, SETTINGS_PKG);
    }

    String str(int id, String fallback) {
        try {
            return id == 0 ? fallback : res.getString(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                base.getResources().getDisplayMetrics()));
    }

    /** 页面大标题 */
    TextView title(String text) {
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tv.setTextColor(titleColor());
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
    }

    TextView subtitle(String text) {
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(summaryColor());
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    /** 分组标题（灰字小标题） */
    TextView groupTitle(String text) {
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(groupColor());
        tv.setPadding(dp(14), dp(18), 0, dp(6));
        return tv;
    }


    /** 一张圆角分组卡片：{@code outer} 加到页面上，{@code body} 用来放行。 */
    static final class Card {
        final ViewGroup outer;
        final ViewGroup body;

        Card(ViewGroup outer, ViewGroup body) {
            this.outer = outer;
            this.body = body;
        }
    }

    Card card() {
        if (system) {
            View v = inflate("card_preference_layout", null);
            if (v instanceof ViewGroup) {
                ViewGroup outer = (ViewGroup) v;
                styleCard(outer);
                ViewGroup body = outer;
                for (int i = 0; i < outer.getChildCount(); i++) {
                    if (outer.getChildAt(i) instanceof ViewGroup) {
                        body = (ViewGroup) outer.getChildAt(i);
                        break;
                    }
                }
                body.removeAllViews();
                // card_preference 根的 LinearLayout 没写 orientation → 默认横向，
                // 直接往里加多行会全部排到第一行右边（宽度 0）。这里改成纵向。
                if (body instanceof LinearLayout) {
                    ((LinearLayout) body).setOrientation(LinearLayout.VERTICAL);
                    ((LinearLayout) body).setGravity(Gravity.NO_GRAVITY);
                }
                return new Card(outer, body);
            }
        }
        LinearLayout fallback = new LinearLayout(base);
        fallback.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor());
        bg.setCornerRadius(dp(14));
        fallback.setBackground(bg);
        fallback.setPadding(dp(4), dp(4), dp(4), dp(4));
        return new Card(fallback, fallback);
    }

    /**
     * 官方 {@code card_preference_layout} 套的是 {@code ContextualCardStyle} —— 里面硬编码
     * {@code cardBackgroundColor=#ff1f1f23}（深色），不是设置页里那种白卡片，所以这里覆盖成白底圆角。
     */
    private void styleCard(ViewGroup card) {
        try {
            card.getClass().getMethod("setCardBackgroundColor", int.class).invoke(card, cardColor());
        } catch (Throwable t) {
            Log.w(TAG, "setCardBackgroundColor 不可用: " + t);
        }
        try {
            card.getClass().getMethod("setRadius", float.class).invoke(card, dp(14));
        } catch (Throwable ignored) {
        }
        try {
            card.getClass().getMethod("setElevation", float.class).invoke(card, 0f);
        } catch (Throwable ignored) {
        }
    }

    View findById(View root, String type, String name) {
        int id = id(type, name);
        return id == 0 ? null : root.findViewById(id);
    }

    /**
     * 一行设置项：预览图（放右侧 widget 槽）+ 标题 + 副标题。
     *
     * @param preview 可为 null，null 时不占位
     */
    View row(ViewGroup parent, String title, String summary, View preview, View.OnClickListener click) {
        View row;
        if (system) {
            row = inflate("card_preference", parent);
        } else {
            row = null;
        }
        if (row == null) {
            row = fallbackRow(title, summary, preview);
        } else {
            TextView t = row.findViewById(android.R.id.title);
            TextView s = row.findViewById(android.R.id.summary);
            if (t != null) {
                t.setText(title);
                t.setTextColor(titleColor());
            }
            if (s != null) {
                if (summary == null || summary.isEmpty()) {
                    s.setVisibility(View.GONE);
                } else {
                    s.setText(summary);
                    s.setTextColor(summaryColor());
                }
            }
            View widget = row.findViewById(android.R.id.widget_frame);
            if (preview != null && widget instanceof ViewGroup) {
                ViewGroup frame = (ViewGroup) widget;
                frame.removeAllViews();
                if (frame instanceof LinearLayout) {
                    ((LinearLayout) frame).setGravity(Gravity.CENTER_VERTICAL);
                }
                ViewGroup.LayoutParams ep = preview.getLayoutParams();
                int pw = ep != null && ep.width > 0 ? ep.width : dp(44);
                int ph = ep != null && ep.height > 0 ? ep.height : dp(58);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(pw, ph);
                plp.setMarginEnd(dp(10));
                frame.addView(preview, plp);
            }
        }
        if (click != null) {
            row.setOnClickListener(click);
            row.setClickable(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(14));
        parent.addView(row, lp);
        return row;
    }

    private View fallbackRow(String title, String summary, View preview) {
        LinearLayout row = new LinearLayout(base);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(cardColor());
        LinearLayout text = new LinearLayout(base);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(base);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(titleColor());
        text.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(base);
            s.setText(summary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setTextColor(summaryColor());
            text.addView(s);
        }
        if (preview != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(48), dp(48));
            plp.leftMargin = dp(12);
            row.addView(preview, plp);
        }
        return row;
    }

    /** 右侧预览缩略图 */
    ImageView preview(int widthDp, int heightDp) {
        ImageView iv = new ImageView(base);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new ViewGroup.LayoutParams(dp(widthDp), dp(heightDp)));
        if (!system) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF202020);
            bg.setCornerRadius(dp(6));
            iv.setBackground(bg);
        }
        return iv;
    }

    View inflate(String layoutName, ViewGroup parent) {
        int layout = id("layout", layoutName);
        if (layout == 0) {
            return null;
        }
        try {
            return inflater.inflate(layout, parent, false);
        } catch (Throwable t) {
            Log.w(TAG, "inflate " + layoutName + " 失败: " + t);
            return null;
        }
    }

    /** 让 LayoutInflater 用「小米设置」的 Resources + Theme */
    private static final class ThemedContext extends ContextWrapper {
        private final Resources res;
        private final Theme theme;

        ThemedContext(Context base, Resources res, Theme theme) {
            super(base);
            this.res = res;
            this.theme = theme;
        }

        @Override
        public Resources getResources() {
            return res;
        }

        @Override
        public Theme getTheme() {
            return theme;
        }
    }

    /** 页面背景色（小米设置为浅灰白） */
    /** 状态绿点 + 文字（首页顶部「● LSP 已激活  ● ROOT 已授权」）。 */
    View dot(String text) {
        LinearLayout box = new LinearLayout(base);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        View circle = new View(base);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF34C759);
        circle.setBackground(g);
        box.addView(circle, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(titleColor());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(6));
        box.addView(tv, tp);
        return box;
    }

    /** 圆角方底（放预览图/图标用）。 */
    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 全宽按钮卡内容：可选图标 + 居中标题（可选副标题）。 */
    View bigRow(String title, String subtitle, android.graphics.drawable.Drawable icon) {
        LinearLayout box = new LinearLayout(base);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        // 卡片高度固定、内容自顶向下排布：实测内容比卡片中心偏上 21px(7dp)，
        // 所以要加「上」内边距把它压到正中间（加下内边距无效）。
        box.setPadding(0, dp(13), 0, dp(6));
        ImageView iv = new ImageView(base);
        int iconSize = dp(22);
        if (icon != null) {
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
            ip.setMarginEnd(dp(8));
            box.addView(iv, ip);
        }
        LinearLayout text = new LinearLayout(base);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(base);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(titleColor());
        text.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView stv = new TextView(base);
            stv.setText(subtitle);
            stv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            stv.setTextColor(summaryColor());
            text.addView(stv);
        }
        box.addView(text, new LinearLayout.LayoutParams(-2, -2));
        // 右侧补一块与图标等宽的占位，这样"文字"本身才是按卡片居中的
        if (icon != null) {
            View spacer = new View(base);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(iconSize, iconSize);
            sp.setMarginStart(dp(8));
            box.addView(spacer, sp);
        }
        return box;
    }

    int pageBackground() {
        return night ? 0xFF000000 : 0xFFF2F3F5;
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }
}