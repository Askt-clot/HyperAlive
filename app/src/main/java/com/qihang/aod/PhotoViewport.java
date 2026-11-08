package com.qihang.aod;

/**
 * 归一化居中裁切视口（移植自 OpenAlive {@code org.aliveclean.PhotoViewport}）。
 * 默认保持 Center-Cover 铺满屏幕比例，支持 1x ~ 8x 双指定点缩放与拖拽平移。
 */
final class PhotoViewport {
    private float imageAspect = 1f;
    private float viewAspect = 1f;
    private float zoom = 1f;
    private float x = 0.5f;
    private float y = 0.5f;
    private boolean measured;
    private boolean freeMove;

    void setFreeMove(boolean free) {
        this.freeMove = free;
        if (freeMove && measured) {
            float minZ = minZoomLiuguang();
            if (zoom < minZ) {
                zoom = minZ;
            }
        }
        clamp();
    }

    boolean isFreeMove() {
        return freeMove;
    }

    void dimensions(int imageWidth, int imageHeight, int viewWidth, int viewHeight) {
        if (imageWidth < 1 || imageHeight < 1 || viewWidth < 1 || viewHeight < 1) {
            throw new IllegalArgumentException("Empty viewport");
        }
        imageAspect = (float) imageWidth / imageHeight;
        viewAspect = (float) viewWidth / viewHeight;
        measured = true;
        if (freeMove) {
            float minZ = minZoomLiuguang();
            if (zoom < minZ) {
                zoom = minZ;
            }
        }
        clamp();
    }

    float width() {
        return Math.min(1f, viewAspect / imageAspect) / zoom;
    }

    float height() {
        return Math.min(1f, imageAspect / viewAspect) / zoom;
    }

    float left() {
        return x - width() / 2f;
    }

    float top() {
        return y - height() / 2f;
    }

    float zoom() {
        return zoom;
    }

    float centerX() {
        return x;
    }

    float centerY() {
        return y;
    }

    /**
     * 计算留光相框模式下的最小缩放值 (zoom)：
     * 保证图片无论横图竖图，短边尺寸至少完整覆盖 320/720 取景框，绝不把黑边框入取景框内。
     */
    float minZoomLiuguang() {
        if (!measured) return 0.05f;
        float zWidth = Math.min(1f, viewAspect / imageAspect) * (320f / 720f);
        float zHeight = viewAspect * Math.min(1f, imageAspect / viewAspect) * (320f / 720f);
        return Math.max(zWidth, zHeight);
    }

    void restore(float cx, float cy, float scale) {
        float minZ = (freeMove && measured) ? minZoomLiuguang() : (freeMove ? 0.05f : 1f);
        zoom = Math.max(minZ, Math.min(8f, finite(scale, minZ)));
        x = finite(cx, 0.5f);
        y = finite(cy, 0.5f);
        clamp();
    }

    void reset() {
        restore(0.5f, 0.5f, 1f);
    }

    /**
     * 重置为官方默认取景范围：
     * 若为留光相框：对齐 OpenAlive 默认值，让相片短边 100% 刚好框入 320/720 正方形相框内，且居中显示；
     * 若为普通全屏壁纸：居中铺满屏幕 (Center-Cover)。
     */
    void resetDefault(boolean isLiuguang) {
        if (isLiuguang && measured) {
            float z = minZoomLiuguang();
            float h = Math.min(1f, imageAspect / viewAspect) / z;
            float cy = 0.5f + (11.5f / 720f) * h;
            restore(0.5f, cy, z);
        } else {
            reset();
        }
    }

    void drag(float dx, float dy) {
        x -= finite(dx, 0f) * width();
        y -= finite(dy, 0f) * height();
        clamp();
    }

    void scale(float factor, float focusX, float focusY) {
        if (!Float.isFinite(factor) || factor <= 0f || !Float.isFinite(focusX) || !Float.isFinite(focusY)) {
            return;
        }
        float oldW = width();
        float oldH = height();
        float minZ = freeMove ? minZoomLiuguang() : 1f;
        zoom = Math.max(minZ, Math.min(8f, zoom * factor));
        // 保持双指焦点下的图像像素位置不动
        x += (focusX - 0.5f) * (oldW - width());
        y += (focusY - 0.5f) * (oldH - height());
        clamp();
    }

    private void clamp() {
        if (!measured) {
            return;
        }
        if (freeMove) {
            // 留光相框模式：图片不能缩得比取景框小，且拖拽移动时取景框内严禁露出任何黑边
            float wBox = (320f / 720f) * width();
            float halfW = wBox * 0.5f;
            float minX = Math.min(halfW, 1f - halfW);
            float maxX = Math.max(halfW, 1f - halfW);
            x = Math.max(minX, Math.min(maxX, x));

            float hBox = (320f / 720f) * viewAspect * height();
            float halfH = hBox * 0.5f;
            float delta = (11.5f / 720f) * height();
            float minY = Math.min(halfH + delta, 1f - halfH + delta);
            float maxY = Math.max(halfH + delta, 1f - halfH + delta);
            y = Math.max(minY, Math.min(maxY, y));
            return;
        }
        float w = width() / 2f;
        float h = height() / 2f;
        x = Math.max(w, Math.min(1f - w, x));
        y = Math.max(h, Math.min(1f - h, y));
    }

    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
