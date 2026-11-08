# -*- coding: utf-8 -*-
"""LensRenderer 接入真·三级高斯模糊（替换 mipmap 近似）：
熄屏稳态与过渡期间按 blur 通道模糊整张照片，作为 lens shader 的 u_photo。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:44].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:90])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('LensRenderer.java', [
    # 字段
    ("""    /** 预览的帧间隔：按屏幕刷新率算（120Hz → 8ms）；壁纸引擎固定 16ms 省电。 */""",
     """    /** 真·三级高斯模糊（OpenAlive 同款管线）；壁纸与预览共用。 */
    private GaussianBlur gaussianBlur;
    /** 预览的帧间隔：按屏幕刷新率算（120Hz → 8ms）；壁纸引擎固定 16ms 省电。 */"""),
    # 绘制：先做模糊，再画 lens
    ("""        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);

        float aspect = (float) surfaceHeight / surfaceWidth;
        float lod = lodFor(motion.blur());
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wallpaperTexture);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_LOD, lod);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LOD, lod);""",
     """        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        // ★ 圆内模糊 = 官方同款真·三级高斯（down → H → V → up），由 blur 通道驱动。
        // 只在"点亮息屏层"时做（熄屏稳态 + 过渡期间）；锁屏/桌面稳态不模糊（官方亦然）。
        // 之前用 mipmap LOD 近似，糊度与官方差一截，这里换成精确实现。
        float blurRadius = motion.blur();
        int photoTex = wallpaperTexture;
        if (blurRadius >= 2f && (pendingMode == Motion.AOD || motion.animating())) {
            if (gaussianBlur == null) {
                gaussianBlur = new GaussianBlur();
            }
            gaussianBlur.ensure(appContext, surfaceWidth, surfaceHeight);
            photoTex = gaussianBlur.blur(wallpaperTexture, blurRadius);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        }

        GLES30.glUseProgram(program);

        float aspect = (float) surfaceHeight / surfaceWidth;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTex);
        // 真高斯已负责柔化，纹理 LOD 固定 0（否则会二次模糊）
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_LOD, 0f);
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LOD, 0f);"""),
])

# GaussianBlur：加 ensure（按尺寸懒初始化）
patch('GaussianBlur.java', [
    ("""    boolean ready() {""",
     """    /** 尺寸变了就重建（懒初始化，只在需要模糊时调用）。 */
    void ensure(android.content.Context ctx, int width, int height) {
        if (gaussian != 0 && w == width && h == height) {
            return;
        }
        init(ctx, width, height);
    }

    boolean ready() {"""),
])
print('ok')