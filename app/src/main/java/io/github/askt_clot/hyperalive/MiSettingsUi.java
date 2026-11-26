package io.github.askt_clot.hyperalive;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 直接复用设备上「小米设置」({@code com.android.settings}) 的真实资源与主题，
 * 对齐 HyperOS / Miuix 设置规范：主次字重色阶、浅深色大圆角分组卡片、触控水波纹反馈、
 * 导航箭头（仅进入二级页的偏好项）与规范控件风格（开关、滑条、胶囊按钮）。
 *
 * <p>取不到系统资源时自动降级为高保真自绘卡片，保证任何机型都能完美呈现 HyperOS 视觉。
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

    // =====================================================================
    // HyperOS 色阶与语义色彩
    // =====================================================================

    /** 卡片底色（HyperOS：浅色纯白，深色沉浸深灰）。 */
    int cardColor() {
        return night ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** 卡片边框描边色（深色模式下强化层级轮廓，浅色模式下通透融合）。 */
    int cardStrokeColor() {
        return night ? 0x1AFFFFFF : 0x08000000;
    }

    /** 主文字色（HyperOS 高对比文本）。 */
    int titleColor() {
        return night ? 0xFFF5F5F7 : 0xFF111111;
    }

    /** 次要文字色（HyperOS 辅助信息灰阶）。 */
    int summaryColor() {
        return night ? 0xFF8E8E93 : 0xFF86868B;
    }

    /** 分组标题色（HyperOS 分组小标题灰阶）。 */
    int groupColor() {
        return night ? 0xFF8E8E93 : 0xFF6E6E73;
    }

    /** 页面背景色（HyperOS：深色纯黑 AMOLED，浅色灰白底）。 */
    int pageBackground() {
        return night ? 0xFF000000 : 0xFFF2F3F5;
    }

    /** 导航箭头图标色。 */
    int chevronColor() {
        return night ? 0xFF636366 : 0xFFB0B0B4;
    }

    /** 分隔线色彩。 */
    int dividerColor() {
        return night ? 0x18FFFFFF : 0x0C000000;
    }

    /** 偏好项左侧图标背景色。 */
    int iconBgColor() {
        return night ? 0xFF2A2A2D : 0xFFEBEBF0;
    }

    /** 水波纹反馈色。 */
    int rippleColor() {
        return night ? 0x24FFFFFF : 0x14000000;
    }

    /** 状态胶囊底色。 */
    int statusChipBg() {
        return night ? 0xFF242426 : 0xFFE5E5EA;
    }

    /** HyperOS 官方主色蓝。 */
    int accentBlue() {
        return 0xFF0D84FF;
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

    // =====================================================================
    // 标题与文字排版（字号、字重与边距）
    // =====================================================================

    /** 页面大标题（HyperOS 标准 26sp 中黑）。 */
    TextView title(String text) {
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(titleColor());
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    TextView subtitle(String text) {
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(summaryColor());
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    /** 分组标题（HyperOS 灰字小标题，13sp 中黑）。 */
    TextView groupTitle(String text) {
        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(groupColor());
        tv.setPadding(dp(16), dp(18), dp(16), dp(8));
        return tv;
    }

    // =====================================================================
    // 圆角分组卡片
    // =====================================================================

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
                if (body instanceof LinearLayout) {
                    ((LinearLayout) body).setOrientation(LinearLayout.VERTICAL);
                    ((LinearLayout) body).setGravity(Gravity.NO_GRAVITY);
                }
                outer.setClipToOutline(true);
                return new Card(outer, body);
            }
        }
        LinearLayout fallback = new LinearLayout(base);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setBackground(cardBackground());
        fallback.setClipToOutline(true);
        fallback.setPadding(0, 0, 0, 0);
        return new Card(fallback, fallback);
    }

    private void styleCard(ViewGroup card) {
        try {
            card.getClass().getMethod("setCardBackgroundColor", int.class).invoke(card, cardColor());
        } catch (Throwable t) {
            Log.w(TAG, "setCardBackgroundColor 不可用: " + t);
        }
        try {
            card.getClass().getMethod("setRadius", float.class).invoke(card, dp(18));
        } catch (Throwable ignored) {
        }
        try {
            card.getClass().getMethod("setElevation", float.class).invoke(card, 0f);
        } catch (Throwable ignored) {
        }
        card.setClipToOutline(true);
    }

    GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor());
        bg.setCornerRadius(dp(18));
        if (night) {
            bg.setStroke(Math.max(1, dp(0.5f)), cardStrokeColor());
        }
        return bg;
    }

    View findById(View root, String type, String name) {
        int id = id(type, name);
        return id == 0 ? null : root.findViewById(id);
    }

    // =====================================================================
    // 偏好项行与右侧导航箭头（对齐 miuix_preference_settings_main_layout）
    // =====================================================================

    /**
     * 一行偏好设置项：支持左侧圆角图标槽位、主/副标题、右侧小箭头与水波纹按压反馈。
     *
     * @param hasChevron 仅进入二级页面的现有可点项传 true；动作弹窗/不可点项传 false。
     */
    View preferenceRow(String title, String summary, Drawable icon, boolean hasChevron, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(base);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setMinimumHeight(dp(56));

        if (icon != null) {
            FrameLayout iconBox = new FrameLayout(base);
            iconBox.setBackground(rounded(iconBgColor(), 10));
            ImageView iv = new ImageView(base);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int isize = dp(22);
            iconBox.addView(iv, new FrameLayout.LayoutParams(isize, isize, Gravity.CENTER));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMarginEnd(dp(14));
            row.addView(iconBox, ip);
        }

        LinearLayout text = new LinearLayout(base);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(base);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(titleColor());
        text.addView(t);

        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(base);
            s.setText(summary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            s.setTextColor(summaryColor());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(2);
            text.addView(s, sp);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (hasChevron) {
            ImageView chevron = new ImageView(base);
            chevron.setImageDrawable(new ChevronDrawable(chevronColor(), dp(7.5f), dp(13f), dp(1.8f)));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(16), dp(16));
            cp.setMarginStart(dp(8));
            row.addView(chevron, cp);
        }

        if (click != null) {
            row.setBackground(itemRipple(18));
            row.setClickable(true);
            row.setOnClickListener(click);
        }
        return row;
    }

    /** 独立成卡的偏好项（首页入口卡片）。 */
    Card preferenceCard(String title, String summary, Drawable icon, boolean hasChevron, View.OnClickListener click) {
        Card c = card();
        View r = preferenceRow(title, summary, icon, hasChevron, click);
        c.body.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** 偏好项内嵌细分隔线。 */
    View divider(float marginStartDp) {
        View line = new View(base);
        line.setBackgroundColor(dividerColor());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.setMarginStart(dp(marginStartDp));
        line.setLayoutParams(lp);
        return line;
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
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
            row.setBackground(itemRipple(18));
            row.setOnClickListener(click);
            row.setClickable(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(row, lp);
        return row;
    }

    private View fallbackRow(String title, String summary, View preview) {
        LinearLayout row = new LinearLayout(base);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setMinimumHeight(dp(56));
        row.setBackgroundColor(cardColor());
        LinearLayout text = new LinearLayout(base);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(base);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(titleColor());
        text.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(base);
            s.setText(summary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            s.setTextColor(summaryColor());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(2);
            text.addView(s, sp);
        }
        if (preview != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(48), dp(48));
            plp.setMarginStart(dp(12));
            row.addView(preview, plp);
        }
        return row;
    }

    /** 右侧预览缩略图 */
    ImageView preview(int widthDp, int heightDp) {
        ImageView iv = new ImageView(base);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new ViewGroup.LayoutParams(dp(widthDp), dp(heightDp)));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF2A2A2D : 0xFFE0E0E0);
        bg.setCornerRadius(dp(8));
        iv.setBackground(bg);
        iv.setClipToOutline(true);
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

    /** 状态绿点 + 文字（首页顶部「● LSP 已激活  ● ROOT 已授权」）。 */
    View dot(String text) {
        LinearLayout box = new LinearLayout(base);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(rounded(statusChipBg(), 14));
        box.setPadding(dp(10), dp(5), dp(12), dp(5));

        View circle = new View(base);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF34C759);
        circle.setBackground(g);
        box.addView(circle, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView tv = new TextView(base);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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

    /** 带边框的圆角方底。 */
    GradientDrawable roundedBorder(int fillColor, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            g.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        }
        return g;
    }

    /** 针对特定圆角的轻量水波纹效果（受限于圆角边缘）。 */
    Drawable itemRipple(float cornerRadiusDp) {
        GradientDrawable mask = rounded(0xFFFFFFFF, cornerRadiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), null, mask);
    }

    /** HyperOS 胶囊按钮水波纹。 */
    Drawable pillButtonDrawable(int normalColor, int rippleColor, float radiusDp) {
        GradientDrawable content = rounded(normalColor, radiusDp);
        GradientDrawable mask = rounded(0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /** 全宽按钮卡内容：可选图标 + 居中标题（可选副标题）。 */
    View bigRow(String title, String subtitle, Drawable icon) {
        LinearLayout box = new LinearLayout(base);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setMinimumHeight(dp(56));

        int iconSize = dp(22);
        if (icon != null) {
            ImageView iv = new ImageView(base);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
            ip.setMarginEnd(dp(10));
            box.addView(iv, ip);
        }
        LinearLayout text = new LinearLayout(base);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(base);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(titleColor());
        text.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView stv = new TextView(base);
            stv.setText(subtitle);
            stv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            stv.setTextColor(summaryColor());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(2);
            text.addView(stv, sp);
        }
        box.addView(text, new LinearLayout.LayoutParams(-2, -2));
        if (icon != null) {
            View spacer = new View(base);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(iconSize, iconSize);
            sp.setMarginStart(dp(10));
            box.addView(spacer, sp);
        }
        return box;
    }

    // =====================================================================
    // HyperOS 原生控件样式增强（开关、滑条）
    // =====================================================================

    /** 将 Switch 样式统一度量为 HyperOS 绿色开关。 */
    void styleSwitch(Switch sw) {
        if (sw == null) return;
        try {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            int[] trackColors = new int[]{0xFF34C759, night ? 0xFF39393D : 0xFFE5E5EA};
            sw.setTrackTintList(new ColorStateList(states, trackColors));
            sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        } catch (Throwable ignored) {
        }
    }

    /** 将 SeekBar 样式统一度量为 HyperOS 进度滑条。 */
    void styleSeekBar(SeekBar sb) {
        if (sb == null) return;
        try {
            sb.setProgressTintList(ColorStateList.valueOf(accentBlue()));
            sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf(night ? 0x2AFFFFFF : 0x1A000000));
        } catch (Throwable ignored) {
        }
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    // =====================================================================
    // 矢量导航箭头（对齐 HyperOS / Miuix 偏好项右侧箭头）
    // =====================================================================

    static final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int widthPx;
        private final int heightPx;

        ChevronDrawable(int color, int widthPx, int heightPx, float strokeWidthPx) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            paint.setStrokeWidth(strokeWidthPx);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            path.reset();
            float left = bounds.exactCenterX() - widthPx / 2f;
            float top = bounds.exactCenterY() - heightPx / 2f;
            float right = left + widthPx;
            float bottom = top + heightPx;
            float midY = (top + bottom) / 2f;
            path.moveTo(left, top);
            path.lineTo(right, midY);
            path.lineTo(left, bottom);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return widthPx;
        }

        @Override
        public int getIntrinsicHeight() {
            return heightPx;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
