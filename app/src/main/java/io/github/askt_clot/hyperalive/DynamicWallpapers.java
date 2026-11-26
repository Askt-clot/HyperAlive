/*
 * Design or implementation reference: OpenAlive (app/assets/cosmic/catalog.json).
 * Upstream attribution and applicable license: THIRD_PARTY_NOTICES.md.
 */
package io.github.askt_clot.hyperalive;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Built-in scenes in the order displayed by OpenAlive. Legacy class names identify selections saved before the port. */
final class DynamicWallpapers {
    private static final String KEY_PREFIX = "openalive:";
    private static final String OLD_KEY_PREFIX = "builtin:";
    private static final String LEGACY_PACKAGE = "com.flyme.alivewallpaper";
    private static final String LEGACY_PREFIX = LEGACY_PACKAGE + "/";

    static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry(1, "星云绿", "com.meizu.flyme.sdkstage.wallpaper.cosmic.CosmicWallpaper"),
            new Entry(3, "朝阳金", "com.meizu.flyme.sdkstage.wallpaper.cosmic.CosmicWallpaperV3"),
            new Entry(4, "定胜青", "com.meizu.flyme.sdkstage.wallpaper.cosmic.CosmicWallpaperV4"),
            new Entry(6, "悦动黄", "com.flyme.alive.CosmicWallpaperV6"),
            new Entry(7, "热爱粉", "com.flyme.alive.CosmicWallpaperV7"),
            new Entry(8, "曙光银", "com.flyme.alive.CosmicWallpaperV8"),
            new Entry(9, "太空银", "com.flyme.alive.CosmicWallpaperV9"),
            new Entry(10, "先锋灰", "com.flyme.alive.CosmicWallpaperV10"),
            new Entry(11, "星纪摩卡", "com.flyme.alive.CosmicWallpaperV11"),
            new Entry(12, "星辰黑", "com.flyme.alive.CosmicWallpaperV12"),
            new Entry(13, "破晓灰", "com.flyme.alive.CosmicWallpaperV13"),
            new Entry(14, "晨曦紫", "com.flyme.alive.CosmicWallpaperV14"),
            new Entry(15, "白", "com.flyme.alive.CosmicWallpaperV15"),
            new Entry(101, "热爱", "com.flyme.alive.mixer.CosmicPhoenixWallpaperV1"),
            new Entry(102, "无界", "com.flyme.alive.mixer.CosmicPhoenixWallpaperV2"),
            new Entry(103, "未来", "com.flyme.alive.mixer.CosmicPhoenixWallpaperV3"),
            new Entry(104, "星际", "com.flyme.alive.mixer.CosmicPhoenixWallpaperV4"),
            new Entry(105, "星辰", "com.flyme.alive.mixer.CosmicPhoenixWallpaperV5"),
            new Entry(201, "波子汽水", "com.meizu.flyme.sdkstage.wallpaper.bubble.BubbleWallpaper"),
            new Entry(202, "气泡黑咖", "com.meizu.flyme.sdkstage.wallpaper.bubble.BubbleWallpaperV2"),
            new Entry(203, "抹茶拿铁", "com.meizu.flyme.sdkstage.wallpaper.bubble.BubbleWallpaperV3"),
            new Entry(204, "桃香乌龙", "com.meizu.flyme.sdkstage.wallpaper.bubble.BubbleWallpaperV4"),
            new Entry(205, "蓝莓苏打", "com.meizu.flyme.sdkstage.wallpaper.bubble.BubbleWallpaperV5"),
            new Entry(16, "园蓝"),
            new Entry(17, "梅洛"),
            new Entry(18, "金杏"),
            new Entry(19, "青提"),
            new Entry(301, "大漠"),
            new Entry(302, "极光"),
            new Entry(303, "远海"),
            new Entry(401, "浪流"),
            new Entry(402, "深潜"),
            new Entry(403, "涟漪"),
            new Entry(501, "3D壁纸 V4"),
            new Entry(502, "3D壁纸 V5"),
            new Entry(503, "3D壁纸 V6")
    ));

    private DynamicWallpapers() {}

    static final class Entry {
        final int variant;
        final String label;
        private final String legacyClass;

        Entry(int variant, String label) {
            this(variant, label, null);
        }

        Entry(int variant, String label, String legacyClass) {
            this.variant = variant;
            this.label = label;
            this.legacyClass = legacyClass;
        }

        String selection() {
            return KEY_PREFIX + variant;
        }

        Drawable thumbnail(Context context) {
            try {
                Bitmap bitmap = loadThumbnail(context.getAssets(), variant);
                return new BitmapDrawable(context.getResources(), bitmap);
            } catch (IOException error) {
                Log.e(Root.TAG, "读取内置动态壁纸缩略图失败 variant=" + variant, error);
                return null;
            }
        }
    }

    static Entry find(String selection) {
        if (selection == null || selection.isEmpty()) {
            return null;
        }
        // Those three IDs belonged to different Alive 1.3.2 clips. Keep the
        // same theme on upgrade by selecting its OpenAlive procedural scene.
        switch (selection) {
            case "builtin:401":
            case LEGACY_PREFIX + "com.flyme.alive.VideoWallpaperV1":
                selection = KEY_PREFIX + "9";
                break;
            case "builtin:402":
            case LEGACY_PREFIX + "com.flyme.alive.VideoWallpaperV2":
                selection = KEY_PREFIX + "1";
                break;
            case "builtin:403":
            case LEGACY_PREFIX + "com.flyme.alive.VideoWallpaperV3":
                selection = KEY_PREFIX + "12";
                break;
            default:
                if (selection.startsWith(OLD_KEY_PREFIX)) {
                    selection = KEY_PREFIX + selection.substring(OLD_KEY_PREFIX.length());
                }
        }
        if (selection.startsWith(KEY_PREFIX)) {
            for (Entry entry : ENTRIES) {
                if (entry.selection().equals(selection)) return entry;
            }
            return null;
        }
        if (selection.startsWith(LEGACY_PREFIX)) {
            String className = selection.substring(LEGACY_PREFIX.length());
            if (className.startsWith(".")) className = LEGACY_PACKAGE + className;
            for (Entry entry : ENTRIES) {
                if (className.equals(entry.legacyClass)) return entry;
            }
        }
        return null;
    }

    static Entry appliedEntry(Context context) {
        Entry entry = LastSet.kind(context) == LastSet.KIND_DYNAMIC
                ? find(LastSet.component(context)) : null;
        return entry == null ? ENTRIES.get(0) : entry;
    }

    static Drawable thumbnailFor(Context context, String selection) {
        Entry entry = find(selection);
        return entry == null ? null : entry.thumbnail(context);
    }

    static String thumbnailAsset(int variant) {
        return "dynamic/thumbnails/" + variant + ".webp";
    }

    static Bitmap loadThumbnail(AssetManager assets, int variant) throws IOException {
        String path = thumbnailAsset(variant);
        try (InputStream input = assets.open(path)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IOException("Cannot decode " + path);
            return bitmap;
        }
    }
}
