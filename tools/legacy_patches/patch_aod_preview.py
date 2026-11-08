# -*- coding: utf-8 -*-
"""LivePreview：熄屏预览本地合成（按引擎真实 AOD 几何：圆心 y=16.5%、半径 0.22W、压暗 0.27、圆内模糊）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LivePreview.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""        if (mode == Motion.AOD) {
            return null;                    // 息屏由我们自绘，兜底交给 assets
        }""",
    """        if (mode == Motion.AOD) {
            return aod(ctx);                // 熄屏由我们自绘：本地按真实几何合成
        }""")

rep("""    /** 系统壁纸槽位的静态图（动态壁纸拿不到，返回 null）。 */""",
    """    /**
     * 熄屏「启航」预览：按引擎的真实几何本地合成 ——
     * 黑底 + 圆心在 16.5% 高度处、半径 0.22×屏宽 的圆，圆内是当前锁屏照片（模糊 + 压暗 0.27）。
     */
    static Bitmap aod(Context ctx) {
        Bitmap photo = PhotoStore.load(ctx, PhotoStore.LOCK);
        if (photo == null) {
            photo = systemWallpaper(ctx, Motion.LOCK);
        }
        if (photo == null) {
            return null;
        }
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = 600;
            int h = (int) (w * (double) dm.heightPixels / dm.widthPixels);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(android.graphics.Color.BLACK);
            float cx = w * 0.5f;
            float cy = h * 0.165f;
            float r = w * 0.22f;
            Bitmap square = centerSquare(photo);
            // 便宜的模糊：缩到 1/10 再放大回来
            int smallW = Math.max(4, (int) (r / 5));
            Bitmap blur = Bitmap.createScaledBitmap(square, smallW, smallW, true);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                            | android.graphics.Paint.FILTER_BITMAP_FLAG);
            p.setAlpha((int) (255 * 0.27f));
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(blur, null,
                    new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), p);
            c.restore();
            Log.i(Root.TAG, "熄屏预览=本地合成 " + w + "x" + h + " 圆心(" + cx + "," + cy + ") r=" + r);
            return out;
        } catch (Throwable t) {
            Log.w(Root.TAG, "合成熄屏预览失败", t);
            return null;
        }
    }

    /** 取图片中心正方形（圆内不做拉伸）。 */
    static Bitmap centerSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        return Bitmap.createBitmap(src, (src.getWidth() - side) / 2,
                (src.getHeight() - side) / 2, side, side);
    }

    /** 系统壁纸槽位的静态图（动态壁纸拿不到，返回 null）。 */""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')