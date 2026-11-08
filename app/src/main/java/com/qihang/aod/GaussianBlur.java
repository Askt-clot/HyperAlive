package com.qihang.aod;

import android.opengl.GLES30;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenAlive 启航圆内模糊的**真·三级高斯**（不是 mipmap 近似）。
 *
 * <p>管线（照 native/renderer.rs blurred()）：降采样 → 高斯 H → 高斯 V → 升采样。
 * kernel 照 native/blur.rs kernel_with_limit()：σ 饱和在 limit(12)，降采样因子 = max(radius/limit,1)，
 * 权重在降采样尺度上算、截断到 1/256，再成对合并（每 2 个 tap 并成 1 次双向采样）。
 * 因此**屏幕等效 σ 恰好等于 radius**（σ_screen = σ_texel × factor）。
 *
 * <p>radius &lt; 2 直接返回输入纹理（直通，官方门限）。
 */
final class GaussianBlur {

    private static final String TAG = Root.TAG;

    /** 顶点（NDC 全屏 quad）。 */
    private static final float[] QUAD_POS = {
            -1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f,
    };
    private static final float[] QUAD_UV = {
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
    };

    private long kernelLogNanos;
    private android.content.Context appContext;
    private int down, gaussian, up;
    private int fboA, fboB, texA, texB;
    private int w, h;
    private int aPosDown, aUvDown, aPosGauss, aUvGauss, aPosUp, aUvUp;
    private FloatBuffer quadPos;
    private FloatBuffer quadUv;
    private final int[] texArr = new int[1];
    private final int[] fboArr = new int[1];

    /** 尺寸变了就重建（懒初始化，只在需要模糊时调用）。 */
    void ensure(android.content.Context ctx, int width, int height) {
        if (gaussian != 0 && w == width && h == height) {
            return;
        }
        init(ctx, width, height);
    }

    boolean ready() {
        return gaussian != 0;
    }

    /** 在当前 GL 上下文中初始化（必须在 GL 线程里调用）。 */
    void init(android.content.Context ctx, int width, int height) {
        release();
        appContext = ctx.getApplicationContext();
        w = Math.max(1, width);
        h = Math.max(1, height);
        down = buildProgram("shader/blur/down_sample_vertex.glsl", "shader/blur/down_sample_frag.glsl");
        gaussian = buildProgram("shader/blur/gaussian_vertex.glsl", "shader/blur/gaussian_frag.glsl");
        up = buildProgram("shader/blur/up_sample_vertex.glsl", "shader/blur/up_sample_frag.glsl");
        if (down == 0 || gaussian == 0 || up == 0) {
            Log.e(TAG, "模糊 shader 编译失败 down=" + down + " gaussian=" + gaussian + " up=" + up);
            return;
        }
        aPosDown = GLES30.glGetAttribLocation(down, "a_position");
        aUvDown = GLES30.glGetAttribLocation(down, "a_texCoord0");
        aPosGauss = GLES30.glGetAttribLocation(gaussian, "a_position");
        aUvGauss = GLES30.glGetAttribLocation(gaussian, "a_texCoord0");
        aPosUp = GLES30.glGetAttribLocation(up, "a_position");
        aUvUp = GLES30.glGetAttribLocation(up, "a_texCoord0");
        quadPos = floats(QUAD_POS);
        quadUv = floats(QUAD_UV);
        texA = newTexture(w, h);
        texB = newTexture(w, h);
        fboA = newFbo(texA);
        fboB = newFbo(texB);
        Log.i(TAG, "模糊管线就绪 " + w + "x" + h);
    }

    void release() {
        if (down != 0) {
            GLES30.glDeleteProgram(down);
            down = 0;
        }
        if (gaussian != 0) {
            GLES30.glDeleteProgram(gaussian);
            gaussian = 0;
        }
        if (up != 0) {
            GLES30.glDeleteProgram(up);
            up = 0;
        }
        if (texA != 0 || texB != 0) {
            GLES30.glDeleteTextures(2, new int[]{texA, texB}, 0);
            texA = 0;
            texB = 0;
        }
        if (fboA != 0 || fboB != 0) {
            GLES30.glDeleteFramebuffers(2, new int[]{fboA, fboB}, 0);
            fboA = 0;
            fboB = 0;
        }
    }

    /**
     * 把 input 纹理按 radius 做三级高斯模糊，返回结果纹理；
     * radius &lt; 2（官方直通门限）时原样返回 input。
     */
    int blur(int input, float radius) {
        if (!ready() || !Float.isFinite(radius) || radius < 2f) {
            return input;
        }
        float[] k = kernel(radius, 12f);
        float factor = k[0];
        int tapCount = (int) k[1];
        float[] packed = k;                       // [factor, n, w0, o0, w1, o1, ...]
        float s = 1f / factor;
        if (System.nanoTime() - kernelLogNanos > 3_000_000_000L) {
            kernelLogNanos = System.nanoTime();
            Log.i(TAG, String.format(java.util.Locale.ROOT,
                    "高斯核 radius=%.1f factor=%.6f u_scale=%.6f taps=%d w0=%.8f w1=%.8f wLast=%.8f",
                    radius, factor, s, tapCount, packed[2], packed[3],
                    packed[2 + tapCount - 1]));
        }
        float[] matrix = {s, 0f, 0f, 0f, s, 0f, (1f - s) / 2f, (1f - s) / 2f, 1f};

        // ① 降采样：把全屏内容画到 FBO 中央 s 比例
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(down);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(down, "u_scale"), s);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(down, "u_photo"), 0);
        drawQuad(aPosDown, aUvDown, GLES30.glGetUniformLocation(down, "u_projectionViewMatrix"));

        // ②③ 高斯 H(4→5) / V(5→4)：u_step 用**屏幕尺寸**的倒数（屏幕等效 σ = radius 的前提）
        gaussianPass(texA, fboB, 1, s, matrix, packed, tapCount);
        gaussianPass(texB, fboA, 0, s, matrix, packed, tapCount);

        // ④ 升采样：把 A 的中央 s 区域放大回全屏 → B
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(up);
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(up, "u_texture_matrix"),
                1, false, matrix, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texA);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(up, "u_photo"), 0);
        drawQuad(aPosUp, aUvUp, GLES30.glGetUniformLocation(up, "u_projectionViewMatrix"));
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return texB;
    }

    /** 一趟分离式高斯（horizontal=1 走 u_step.x，0 走 u_step.y）。 */
    private void gaussianPass(int srcTex, int dstFbo, int horizontal, float s,
                              float[] matrix, float[] packed, int tapCount) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(gaussian);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gaussian, "u_scale"), s);
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(gaussian, "u_texture_matrix"),
                1, false, matrix, 0);
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(gaussian, "u_weights"),
                tapCount, packed, 2);
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(gaussian, "u_offsets"),
                tapCount, packed, 2 + tapCount);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gaussian, "u_radius"), tapCount);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gaussian, "u_horizontal"), horizontal);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(gaussian, "u_step"), 1f / w, 1f / h);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gaussian, "u_texture_min"), (1f - s) / 2f);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gaussian, "u_texture_max"), (1f + s) / 2f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gaussian, "u_blur_texture"), 0);
        drawQuad(aPosGauss, aUvGauss,
                GLES30.glGetUniformLocation(gaussian, "u_projectionViewMatrix"));
    }

    private void drawQuad(int aPos, int aUv, int uPvm) {
        if (aPos >= 0) {
            GLES30.glEnableVertexAttribArray(aPos);
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 0, quadPos);
        }
        if (aUv >= 0) {
            GLES30.glEnableVertexAttribArray(aUv);
            GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 0, quadUv);
        }
        if (uPvm >= 0) {
            float[] id = {1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
            GLES30.glUniformMatrix4fv(uPvm, 1, false, id, 0);
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ===== kernel（照 native/blur.rs 逐式移植）=====

    /**
     * @return [factor, tapCount, weights..., offsets...]
     */
    static float[] kernel(float radius, float limit) {
        if (!Float.isFinite(radius) || radius < 2f) {
            return new float[]{1f, 1f, 1f, 0f};
        }
        float lim = Math.min(Math.max(limit, 2f), 12f);
        double sigma = Math.min(radius, lim);
        int extent = (int) Math.floor(Math.sqrt(
                -2.0 * sigma * sigma * Math.log(0.00390625 * Math.sqrt(sigma * sigma * 2 * Math.PI))));
        extent += extent % 2;
        double[] w = new double[extent + 1];
        double tot = 0;
        for (int i = 0; i <= extent; i++) {
            w[i] = Math.exp(-(i * i) / (2.0 * sigma * sigma))
                    / Math.sqrt(sigma * sigma * 2 * Math.PI);
        }
        tot += w[0];
        for (int i = 1; i <= extent; i++) {
            tot += 2 * w[i];
        }
        for (int i = 0; i <= extent; i++) {
            w[i] /= tot;
        }
        int taps = 1 + extent / 2;
        float[] out = new float[2 + taps * 2];
        out[0] = Math.max(radius / lim, 1f);
        out[1] = taps;
        out[2] = (float) w[0];
        out[3] = 0f;
        int n = 1;
        for (int i = 1; i < extent; i += 2) {
            double a = w[i];
            double b = w[i + 1];
            double sum = a + b;
            out[2 + n] = (float) sum;
            out[2 + taps + n] = (float) ((i * a + (i + 1) * b) / sum);
            n++;
        }
        return out;
    }

    // ===== GL 小工具 =====

    private int newTexture(int width, int height) {
        GLES30.glGenTextures(1, texArr, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texArr[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return texArr[0];
    }

    private int newFbo(int texture) {
        GLES30.glGenFramebuffers(1, fboArr, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboArr[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO 不完整 0x" + Integer.toHexString(status));
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return fboArr[0];
    }

    private int buildProgram(String vertexPath, String fragmentPath) {
        int vs = compile(GLES30.GL_VERTEX_SHADER, readAsset(vertexPath), vertexPath);
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, readAsset(fragmentPath), fragmentPath);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs);
        GLES30.glAttachShader(p, fs);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "链接失败 " + GLES30.glGetProgramInfoLog(p));
            GLES30.glDeleteProgram(p);
            return 0;
        }
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return p;
    }

    private int compile(int type, String src, String tag) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "编译失败 " + tag + "：" + GLES30.glGetShaderInfoLog(s));
            GLES30.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private String readAsset(String path) {
        try (InputStream in = appContext.getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.e(TAG, "读 shader 失败 " + path, t);
            return "";
        }
    }

    private static FloatBuffer floats(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }
}