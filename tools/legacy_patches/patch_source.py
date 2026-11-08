# -*- coding: utf-8 -*-
"""WallpaperSource：优先用「编辑器里选的锁屏照片」(lock_photo.png)，用文件 mtime 当版本号。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\WallpaperSource.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    synchronized Bitmap get(Context ctx, boolean force) {
        int id = wallpaperId(ctx);
        if (!force && cached != null && id == cachedWallpaperId && id != -1) {
            return cached;
        }
        // 1) 自定义/静态壁纸：WallpaperManager 直接拿（动态壁纸这里拿不到）
        Bitmap bmp = readFromWallpaperManager(ctx);
        String from = "桌面自定义壁纸";""",
    """    synchronized Bitmap get(Context ctx, boolean force) {
        int id = wallpaperId(ctx);
        long picked = PhotoStore.stamp(ctx, PhotoStore.LOCK);
        if (!force && cached != null && id == cachedWallpaperId && picked == cachedPickedStamp
                && id != -1) {
            return cached;
        }
        // 0) 用户在编辑器里选过的锁屏照片（相册）——最高优先级
        Bitmap bmp = PhotoStore.load(ctx, PhotoStore.LOCK);
        String from = "锁屏照片（相册选择）";
        // 1) 没选过：自定义/静态壁纸，WallpaperManager 直接拿（动态壁纸这里拿不到）
        if (bmp == null) {
            bmp = readFromWallpaperManager(ctx);
            from = "桌面自定义壁纸";
        }""")

rep("""        cached = bmp;
        cachedWallpaperId = id;
        cachedStamp = stamp();""",
    """        cached = bmp;
        cachedWallpaperId = id;
        cachedPickedStamp = picked;
        cachedStamp = stamp();""")

# 字段
rep("""    private int cachedWallpaperId = -1;""",
    """    private int cachedWallpaperId = -1;
    /** lock_photo.png 的 mtime（换照片后要重新读）。 */
    private long cachedPickedStamp = -1;""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')