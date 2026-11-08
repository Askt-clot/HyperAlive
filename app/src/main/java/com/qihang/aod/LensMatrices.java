package com.qihang.aod;

/**
 * 启航的官方矩阵链（复刻 Flyme 「inverse projection / view / model」写法）。
 *
 * <p>photo 矩阵：{@code scale(w,h) · origin(-w/2,-h/2) · unpivot(0,200) · rot(photoAngle) ·
 * pivot(0,-200) · translation(w·x, h·y) · view · projection}，全部取逆后左乘。
 * decorator 矩阵同样取逆，尺寸为 {@code w·scale·2}。
 *
 * <p>着色器侧再对结果做 {@code y = 1 - y} 翻转，所以这里保持官方原样，不要「顺手修正」。
 */
final class LensMatrices {

    private final float width;
    private final float height;
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] translation = new float[16];
    private final float[] origin = new float[16];
    private final float[] scale = new float[16];
    private final float[] rotation = new float[16];
    private final float[] pivot = new float[16];
    private final float[] unpivot = new float[16];
    private final float[] result = new float[16];
    private final float[] scratch = new float[16];

    LensMatrices(int width, int height) {
        this.width = width;
        this.height = height;
        android.opengl.Matrix.orthoM(projection, 0, -width / 2f, width / 2f, -height / 2f, height / 2f, 1, 3);
        invert(projection);
        android.opengl.Matrix.setLookAtM(view, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0);
        invert(view);
    }

    private void invert(float[] m) {
        if (!android.opengl.Matrix.invertM(scratch, 0, m, 0)) {
            throw new IllegalStateException("singular scene matrix");
        }
        System.arraycopy(scratch, 0, m, 0, 16);
    }

    private void append(float[] m) {
        android.opengl.Matrix.multiplyMM(scratch, 0, result, 0, m, 0);
        System.arraycopy(scratch, 0, result, 0, 16);
    }

    private static void translate(float[] m, float x, float y) {
        android.opengl.Matrix.setIdentityM(m, 0);
        android.opengl.Matrix.translateM(m, 0, x, y, 0);
    }

    private static void scale(float[] m, float x, float y) {
        android.opengl.Matrix.setIdentityM(m, 0);
        android.opengl.Matrix.scaleM(m, 0, x, y, 1);
    }

    /**
     * @param x            lens x（屏宽单位）
     * @param y            lens y（屏高单位）
     * @param angle        照片旋转角
     * @param lensScale    圆半径（屏宽单位）
     * @param decorAngle   弧光偏转角
     * @param photoOut     输出 16 float photo 矩阵
     * @param decorOut     输出 16 float decorator 矩阵
     */
    void write(float x, float y, float angle, float lensScale, float decorAngle,
               float[] photoOut, float[] decorOut) {
        translate(translation, width * x, height * y);
        invert(translation);
        translate(origin, -width / 2, -height / 2);
        invert(origin);
        scale(scale, width, height);
        invert(scale);
        translate(pivot, 0, -200);
        invert(pivot);
        translate(unpivot, 0, 200);
        invert(unpivot);
        android.opengl.Matrix.setRotateM(rotation, 0, angle, 0, 0, 1);
        invert(rotation);

        android.opengl.Matrix.setIdentityM(result, 0);
        append(scale);
        append(origin);
        append(unpivot);
        append(rotation);
        append(pivot);
        append(translation);
        append(view);
        append(projection);
        System.arraycopy(result, 0, photoOut, 0, 16);

        float size = width * (lensScale * 2f);
        translate(origin, -size / 2, -size / 2);
        invert(origin);
        scale(scale, size, size);
        invert(scale);
        android.opengl.Matrix.setRotateM(rotation, 0, -decorAngle, 0, 0, 1);
        invert(rotation);
        android.opengl.Matrix.setIdentityM(result, 0);
        append(scale);
        append(origin);
        append(rotation);
        append(translation);
        append(view);
        append(projection);
        System.arraycopy(result, 0, decorOut, 0, 16);
    }
}