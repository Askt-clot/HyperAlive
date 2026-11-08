# -*- coding: utf-8 -*-
"""圆圈内容：桌面是自定义/静态壁纸时跟随它；动态壁纸（魅族 Alive 等）不跟随。"""
import io
import os

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(name, pairs):
    p = os.path.join(BASE, name)
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = s.count(a)
        print('%s: %d <- %r' % (name, n, a[:60].replace('\n', '\\n')))
        if n != 1:
            raise SystemExit('匹配数不对：%s %r' % (name, a[:90]))
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('WallpaperSource.java', [
    # 文档与顺序：自定义壁纸优先用 WallpaperManager（无需 root），动态壁纸不跟随
    (""" * <p>取图优先序：
 * <ol>
 *   <li>root 管道直接读系统壁纸文件 {@code /data/system/users/0/wallpaper}
 *       （澎湃会把它同步成当前壁纸的静态图；WebP/JPEG/PNG 都能解）；</li>
 *   <li>{@link WallpaperManager#getDrawable()} / {@code getBitmap()}（普通图片壁纸一定拿得到）；</li>
 *   <li>内置兜底图 {@code assets/photo/default.jpg}。</li>
 * </ol>
 * 用 {@code stat -c "%s:%Y"} 做变更检测，壁纸一换下一次刷新就跟着换。""",
     """ * <p><b>规则</b>：桌面壁纸是<b>自定义（静态图片）壁纸</b>时，圆圈内容跟随它；
 * <b>魅族 Alive 这类动态壁纸不参与跟随</b>（拿不到它的帧，也刻意不跟）。
 *
 * <p>取图优先序：
 * <ol>
 *   <li>{@link WallpaperManager}（自定义/图片壁纸直接给到位图，**不需要 root**）；
 *       动态壁纸这里会拿不到 → 不跟随，保持上一次的自定义壁纸或内置图；</li>
 *   <li>SystemUI/system_server 快照 → 系统壁纸文件 {@code /data/system/users/0/wallpaper}（兜底）；</li>
 *   <li>内置兜底图 {@code assets/photo/default.jpg}。</li>
 * </ol>
 * 自定义壁纸用 {@code getWallpaperId(FLAG_SYSTEM)} 做变更检测（换壁纸立刻跟随）。"""),
    ("""    /** 取当前壁纸位图；{@code force=false} 且文件未变时复用缓存。 */
    synchronized Bitmap get(Context ctx, boolean force) {
        String stamp = stamp();
        if (!force && cached != null && stamp != null && stamp.equals(cachedStamp)) {
            return cached;
        }
        Bitmap bmp = readFromSnapshot(ctx);
        String from = "SystemUI 快照";
        if (bmp == null) {
            bmp = readFromSystemFile();
            from = "系统壁纸文件";
        }
        if (bmp == null) {
            bmp = readFromWallpaperManager(ctx);
            from = "WallpaperManager";
        }
        if (bmp == null) {""",
     """    private int cachedWallpaperId = -1;

    /** 桌面（FLAG_SYSTEM）壁纸 id：自定义壁纸换了它就变。 */
    private int wallpaperId(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Method m = WallpaperManager.class.getMethod("getWallpaperId", int.class);
            Object v = m.invoke(wm, WallpaperManager.FLAG_SYSTEM);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 取当前壁纸位图；{@code force=false} 且壁纸没换时复用缓存。 */
    synchronized Bitmap get(Context ctx, boolean force) {
        int id = wallpaperId(ctx);
        if (!force && cached != null && id == cachedWallpaperId && id != -1) {
            return cached;
        }
        // 1) 自定义/静态壁纸：WallpaperManager 直接拿（动态壁纸这里拿不到）
        Bitmap bmp = readFromWallpaperManager(ctx);
        String from = "桌面自定义壁纸";
        if (bmp == null) {
            Log.i(TAG, "WallpaperManager 拿不到（多半桌面是动态壁纸，如魅族 Alive）→ 圆圈内容不跟随");
            bmp = readFromSnapshot(ctx);
            from = "SystemUI 快照";
        }
        if (bmp == null) {
            bmp = readFromSystemFile();
            from = "系统壁纸文件";
        }
        if (bmp == null) {"""),
    ("""        cached = bmp;
        cachedStamp = stamp;""",
     """        cached = bmp;
        cachedWallpaperId = id;
        cachedStamp = stamp();"""),
    # 直接试带 which 的公开/隐藏 API
    ("""    private Bitmap readFromWallpaperManager(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Drawable d = wm.getDrawable();""",
     """    private Bitmap readFromWallpaperManager(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Drawable d = null;
            try {
                Method m = WallpaperManager.class.getMethod("getDrawable", int.class);
                d = (Drawable) m.invoke(wm, WallpaperManager.FLAG_SYSTEM);
            } catch (Throwable ignored) {
            }
            if (d == null) {
                d = wm.getDrawable();
            }"""),
])

# import Method
patch('WallpaperSource.java', [
    ("""import java.io.InputStream;""", """import java.io.InputStream;
import java.lang.reflect.Method;"""),
])

# UI 文案
patch('MainActivity.java', [
    ("""        ui.row(wall.body, "圆圈内容：跟随桌面壁纸", "不单独设置内容；点一下立即同步", null, v -> {""",
     """        ui.row(wall.body, "圆圈内容：跟随桌面自定义壁纸", "动态壁纸（魅族 Alive 等）不参与；点一下立即同步", null, v -> {"""),
])
print('ok')