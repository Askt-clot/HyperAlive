package com.qihang.aod;

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
    private static final Map<String, Integer> COMPONENT_TO_VARIANT = new java.util.concurrent.ConcurrentHashMap<>();

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

    /** 按组件名（pkg/cls）查找对应的 OpenAlive 动态渲染器 variant ID（找不到返回 0）。 */
    static int variantFor(Context ctx, String component) {
        if (component == null || component.isEmpty()) {
            return 0;
        }
        Integer cached = COMPONENT_TO_VARIANT.get(component);
        if (cached != null) {
            return cached;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            for (ResolveInfo ri : allAlive(ctx)) {
                ServiceInfo si = ri.serviceInfo;
                if (si == null) {
                    continue;
                }
                String comp = si.packageName + "/" + si.name;
                String label = String.valueOf(ri.loadLabel(pm)).trim();
                Integer v = NAME_TO_VARIANT.get(label);
                if (v != null) {
                    COMPONENT_TO_VARIANT.put(comp, v);
                }
            }
        } catch (Throwable ignored) {
        }
        Integer found = COMPONENT_TO_VARIANT.get(component);
        return found != null ? found : 0;
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
        root.setBackgroundColor(ui.night ? 0xFF000000 : ui.pageBackground());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(16), statusBarHeight() + ui.dp(14), ui.dp(16), ui.dp(10));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setTextColor(ui.titleColor());
        back.setPadding(0, 0, ui.dp(6), ui.dp(2));
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = new TextView(this);
        title.setText("动态壁纸");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(ui.titleColor());
        bar.addView(title, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(40));
        scroll.addView(grid, new ViewGroup.LayoutParams(-1, -2));

        List<ResolveInfo> items = orderedWallpapers(this);
        append("动态壁纸 " + items.size() + " 项：" + names(items));
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有找到壁纸");
            empty.setTextColor(ui.summaryColor());
            empty.setPadding(ui.dp(4), ui.dp(20), 0, 0);
            grid.addView(empty);
        } else {
            int col = 0;
            LinearLayout row = null;
            for (ResolveInfo ri : items) {
                if (col == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                row.addView(gridCell(ri, thumbnail(this, ri)), cellParams(col));
                col++;
                if (col == 3) {
                    col = 0;
                }
            }
        }
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private String names(List<ResolveInfo> items) {
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo ri : items) {
            sb.append(ri.loadLabel(getPackageManager())).append(' ');
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
    private View gridCell(ResolveInfo ri, Drawable thumb) {
        final ServiceInfo si = ri.serviceInfo;
        String label = String.valueOf(ri.loadLabel(getPackageManager()));

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(ui.rounded(0xFF1C1C1E, 14));
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
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(ui.titleColor());
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = ui.dp(8);
        cell.addView(name, np);

        if (si != null) {
            final String component = si.packageName + "/" + si.name;
            cell.setClickable(true);
            cell.setOnClickListener(v -> startActivity(
                    EditorActivity.intent(this, Motion.LOCK, component)));
        }
        return cell;
    }

    /** 按用户给定顺序、去掉没有效果图/不在名单里的壁纸（同名只留第一张）。 */
    static List<ResolveInfo> orderedWallpapers(Context ctx) {
        Map<String, ResolveInfo> byName = new LinkedHashMap<>();
        for (ResolveInfo ri : allAlive(ctx)) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null) {
                continue;
            }
            String label = String.valueOf(ri.loadLabel(ctx.getPackageManager()));
            if (thumbnail(ctx, ri) == null) {
                continue;   // 没有效果图的不显示（天气壁纸就是这种）
            }
            if (!byName.containsKey(label)) {
                byName.put(label, ri);
            }
        }
        List<ResolveInfo> out = new ArrayList<>();
        for (String name : ORDER) {
            ResolveInfo ri = byName.get(name);
            if (ri != null) {
                out.add(ri);
            }
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