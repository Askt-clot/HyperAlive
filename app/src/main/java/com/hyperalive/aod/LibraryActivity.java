package com.hyperalive.aod;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态壁纸库 —— 三列网格（圆角效果图 + 名字），点击进入编辑器「锁屏」场景并带上这张壁纸。
 *
 * <p>内容与顺序按用户指定：只显示名单里的壁纸，名单里有但设备上没有的忽略；
 * 没有效果图的一律不显示（例如天气壁纸）。效果图取壁纸自己声明的 {@code android:thumbnail}。
 */
public class LibraryActivity extends UiActivity {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** 用户指定的显示顺序（设备上缺的忽略）。 */
    static final String[] ORDER = {
            "星云绿", "朝阳金", "定胜青", "悦动黄", "热爱粉", "曙光银", "太空银", "先锋灰",
            "星纪摩卡", "星辰黑", "破晓灰", "晨曦紫", "白", "热爱", "无界", "未来", "星际", "星辰",
            "波子汽水", "气泡黑咖", "抹茶拿铁", "桃香乌龙", "蓝莓苏打", "园蓝", "梅洛", "金杏",
            "青提", "大漠", "极光", "远海", "浪流", "深潜", "涟漪",
            "3D壁纸 V4", "3D壁纸 V5", "3D壁纸 V6",
    };

    private static final Map<String, Integer> NAME_TO_VARIANT = new LinkedHashMap<>();
    private static final Object VARIANT_CACHE_LOCK = new Object();

    static final class VariantCacheSnapshot {
        final long updateTime;
        final Map<String, Integer> map;

        VariantCacheSnapshot(long updateTime, Map<String, Integer> map) {
            this.updateTime = updateTime;
            this.map = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(map));
        }
    }

    private static volatile VariantCacheSnapshot sVariantCache =
            new VariantCacheSnapshot(-1L, java.util.Collections.<String, Integer>emptyMap());

    static final class WallpaperEntry {
        final ResolveInfo resolveInfo;
        final String label;
        final String component;
        final Drawable thumbnail;
        final int variant;

        WallpaperEntry(ResolveInfo resolveInfo, String label, String component, Drawable thumbnail, int variant) {
            this.resolveInfo = resolveInfo;
            this.label = label;
            this.component = component;
            this.thumbnail = thumbnail;
            this.variant = variant;
        }
    }

    static {
        NAME_TO_VARIANT.put("星云绿", 1);
        NAME_TO_VARIANT.put("朝阳金", 3);
        NAME_TO_VARIANT.put("定胜青", 4);
        NAME_TO_VARIANT.put("悦动黄", 6);
        NAME_TO_VARIANT.put("热爱粉", 7);
        NAME_TO_VARIANT.put("曙光银", 8);
        NAME_TO_VARIANT.put("太空银", 9);
        NAME_TO_VARIANT.put("先锋灰", 10);
        NAME_TO_VARIANT.put("星纪摩卡", 11);
        NAME_TO_VARIANT.put("星辰黑", 12);
        NAME_TO_VARIANT.put("破晓灰", 13);
        NAME_TO_VARIANT.put("晨曦紫", 14);
        NAME_TO_VARIANT.put("白", 15);
        NAME_TO_VARIANT.put("园蓝", 16);
        NAME_TO_VARIANT.put("梅洛", 17);
        NAME_TO_VARIANT.put("金杏", 18);
        NAME_TO_VARIANT.put("青提", 19);
        NAME_TO_VARIANT.put("热爱", 101);
        NAME_TO_VARIANT.put("无界", 102);
        NAME_TO_VARIANT.put("未来", 103);
        NAME_TO_VARIANT.put("星际", 104);
        NAME_TO_VARIANT.put("星辰", 105);
        NAME_TO_VARIANT.put("波子汽水", 201);
        NAME_TO_VARIANT.put("气泡黑咖", 202);
        NAME_TO_VARIANT.put("抹茶拿铁", 203);
        NAME_TO_VARIANT.put("桃香乌龙", 204);
        NAME_TO_VARIANT.put("蓝莓苏打", 205);
        NAME_TO_VARIANT.put("大漠", 301);
        NAME_TO_VARIANT.put("极光", 302);
        NAME_TO_VARIANT.put("远海", 303);
        NAME_TO_VARIANT.put("浪流", 401);
        NAME_TO_VARIANT.put("深潜", 402);
        NAME_TO_VARIANT.put("涟漪", 403);
        NAME_TO_VARIANT.put("3D壁纸 V4", 501);
        NAME_TO_VARIANT.put("3D壁纸 V5", 502);
        NAME_TO_VARIANT.put("3D壁纸 V6", 503);
    }

    private static long queryAliveUpdateTime(PackageManager pm) {
        if (pm == null) return -1L;
        try {
            android.content.pm.PackageInfo pi = pm.getPackageInfo(ALIVE_PKG, 0);
            return pi != null ? pi.lastUpdateTime : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * 原子发布不可变 VariantCacheSnapshot：
     * 唯有扫描开始与结束时的包更新时间一致、且不早于已缓存版本时才允许发布，防止旧扫描回写覆盖新版本缓存。
     */
    static VariantCacheSnapshot tryPublishVariantCacheSnapshot(
            long scannedUpdateTime, long afterUpdateTime, Map<String, Integer> scannedMap) {
        synchronized (VARIANT_CACHE_LOCK) {
            if (scannedUpdateTime < 0L || afterUpdateTime < 0L) {
                sVariantCache = new VariantCacheSnapshot(-1L, java.util.Collections.<String, Integer>emptyMap());
                return sVariantCache;
            }
            if (scannedUpdateTime != afterUpdateTime || scannedUpdateTime < sVariantCache.updateTime) {
                return sVariantCache;
            }
            if (scannedMap != null) {
                sVariantCache = new VariantCacheSnapshot(scannedUpdateTime, scannedMap);
            }
            return sVariantCache;
        }
    }

    static VariantCacheSnapshot getVariantCacheForTest() {
        return sVariantCache;
    }

    static void resetVariantCacheForTest() {
        synchronized (VARIANT_CACHE_LOCK) {
            sVariantCache = new VariantCacheSnapshot(-1L, java.util.Collections.<String, Integer>emptyMap());
        }
    }

    /** 按组件名（pkg/cls）查找对应的 OpenAlive 动态渲染器 variant ID（找不到返回 0）。 */
    static int variantFor(Context ctx, String component) {
        if (component == null || component.isEmpty() || ctx == null) {
            return 0;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            long updateTimeBefore = queryAliveUpdateTime(pm);
            if (updateTimeBefore < 0L) {
                tryPublishVariantCacheSnapshot(-1L, -1L, null);
                return 0;
            }
            VariantCacheSnapshot snap = sVariantCache;
            if (snap.updateTime == updateTimeBefore && !snap.map.isEmpty()) {
                Integer cached = snap.map.get(component);
                return (cached != null && cached > 0) ? cached : 0;
            }
            Map<String, Integer> scanned = new java.util.HashMap<>();
            for (ResolveInfo ri : allAlive(ctx)) {
                ServiceInfo si = ri.serviceInfo;
                if (si == null) {
                    continue;
                }
                String comp = si.packageName + "/" + si.name;
                String label = String.valueOf(ri.loadLabel(pm)).trim();
                Integer v = NAME_TO_VARIANT.get(label);
                if (v != null && v > 0) {
                    scanned.put(comp, v);
                }
            }
            long updateTimeAfter = queryAliveUpdateTime(pm);
            VariantCacheSnapshot published = tryPublishVariantCacheSnapshot(
                    updateTimeBefore, updateTimeAfter, scanned);
            Integer found = (published.updateTime == updateTimeAfter && !published.map.isEmpty())
                    ? published.map.get(component)
                    : scanned.get(component);
            return (found != null && found > 0) ? found : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

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

        List<WallpaperEntry> items = orderedWallpaperEntries(this);
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
            for (WallpaperEntry entry : items) {
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

    private String entryNames(List<WallpaperEntry> items) {
        StringBuilder sb = new StringBuilder();
        for (WallpaperEntry entry : items) {
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
    private View gridCell(WallpaperEntry entry) {
        String label = entry.label;
        Drawable thumb = entry.thumbnail;

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

        if (entry.component != null && !entry.component.isEmpty()) {
            final String component = entry.component;
            cell.setClickable(true);
            cell.setOnClickListener(v -> startActivity(
                    EditorActivity.intent(this, Motion.LOCK, component)));
        }
        return cell;
    }

    /** 单次扫描构建已排序的动态壁纸条目（避免重复解析 label 与 thumbnail XML，并原子更新 variant 缓存快照）。 */
    static List<WallpaperEntry> orderedWallpaperEntries(Context ctx) {
        Map<String, WallpaperEntry> byName = new LinkedHashMap<>();
        Map<String, Integer> scannedVariants = new java.util.HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        long updateTimeBefore = queryAliveUpdateTime(pm);
        for (ResolveInfo ri : allAlive(ctx)) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null) {
                continue;
            }
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            String comp = si.packageName + "/" + si.name;
            Integer v = NAME_TO_VARIANT.get(label);
            int variant = v != null ? v : 0;
            if (variant > 0) {
                scannedVariants.put(comp, variant);
            }
            if (byName.containsKey(label)) {
                continue;
            }
            Drawable thumb = thumbnail(ctx, ri);
            if (thumb == null) {
                continue;   // 没有效果图的不显示（天气壁纸就是这种）
            }
            byName.put(label, new WallpaperEntry(ri, label, comp, thumb, variant));
        }
        long updateTimeAfter = queryAliveUpdateTime(pm);
        tryPublishVariantCacheSnapshot(updateTimeBefore, updateTimeAfter, scannedVariants);
        List<WallpaperEntry> out = new ArrayList<>();
        for (String name : ORDER) {
            WallpaperEntry entry = byName.get(name);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 按用户给定顺序、去掉没有效果图/不在名单里的壁纸（同名只留第一张）。 */
    static List<ResolveInfo> orderedWallpapers(Context ctx) {
        List<WallpaperEntry> entries = orderedWallpaperEntries(ctx);
        List<ResolveInfo> out = new ArrayList<>(entries.size());
        for (WallpaperEntry entry : entries) {
            out.add(entry.resolveInfo);
        }
        return out;
    }

    /** 设备上魅族 Alive 的壁纸服务（带 meta-data）。 */
    static List<ResolveInfo> allAlive(Context ctx) {
        List<ResolveInfo> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent probe = new Intent("android.service.wallpaper.WallpaperService");
            for (ResolveInfo ri : pm.queryIntentServices(probe, PackageManager.GET_META_DATA)) {
                ServiceInfo si = ri.serviceInfo;
                if (si != null && ALIVE_PKG.equals(si.packageName)) {
                    out.add(ri);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 按组件名（pkg/cls）取那张壁纸的效果图。 */
    static Drawable thumbnailFor(Context ctx, String component) {
        if (component == null) {
            return null;
        }
        for (ResolveInfo ri : allAlive(ctx)) {
            ServiceInfo si = ri.serviceInfo;
            if (si != null && component.equals(si.packageName + "/" + si.name)) {
                return thumbnail(ctx, ri);
            }
        }
        return null;
    }

    /** 壁纸自己声明的效果图（wallpaper XML 的 android:thumbnail）。 */
    static Drawable thumbnail(Context ctx, ResolveInfo ri) {
        ServiceInfo si = ri == null ? null : ri.serviceInfo;
        if (si == null || si.metaData == null) {
            return null;
        }
        int xmlId = si.metaData.getInt("android.service.wallpaper", 0);
        if (xmlId == 0) {
            return null;
        }
        try {
            Resources res = ctx.getPackageManager().getResourcesForApplication(si.packageName);
            int thumbId = 0;
            try (XmlResourceParser p = res.getXml(xmlId)) {
                int type;
                while ((type = p.next()) != XmlResourceParser.END_DOCUMENT) {
                    if (type == XmlResourceParser.START_TAG && "wallpaper".equals(p.getName())) {
                        thumbId = p.getAttributeResourceValue(ANDROID_NS, "thumbnail", 0);
                        break;
                    }
                }
            }
            if (thumbId != 0) {
                return res.getDrawable(thumbId, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "取效果图失败 " + si.name, t);
        }
        return null;
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