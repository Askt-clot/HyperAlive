package com.hyperalive.aod;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Paint an OpenAlive effect into a reusable top-origin photo texture. */
final class PhotoTexturePipeline implements AutoCloseable {
    private static final String ROOT = "shader/photo/lock/";
    private static final String[] SHADERS = {
            "", "straightGlass/straight_glass", "curveGlass/curve_glass", "groundGlass/ground_glass"
    };
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f, 1f,   1f, -1f, 0f, 1f, 1f,
            -1f,  1f, 0f, 0f, 0f,   1f,  1f, 0f, 1f, 0f
    };

    private final AssetManager assets;
    private final AssetGl[] programs = new AssetGl[SHADERS.length];
    private final int[] vaos = new int[SHADERS.length];
    private final boolean[] unavailable = new boolean[SHADERS.length];
    private final int[] buffer = new int[1];
    private final Target lock = new Target();
    private final Target home = new Target();
    private int gray;
    private int mask;
    private int grayWidth;
    private int grayHeight;

    PhotoTexturePipeline(AssetManager assets) {
        this.assets = assets;
    }

    void invalidate() {
        lock.input = 0;
        home.input = 0;
    }

    int render(int input, PhotoTextureMotion.Track track, int width, int height, boolean desktop) {
        if (input == 0 || track.strength <= 0f || unavailable[track.style]) return input;
        Target target = desktop ? home : lock;
        if (target.matches(input, track, width, height)) return target.texture;
        try {
            AssetGl shader = program(track.style);
            target.ensure(width, height);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer);
            GLES30.glViewport(0, 0, width, height);
            GLES30.glDisable(GLES30.GL_BLEND);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);

            shader.use();
            bind(shader, "u_lockTex", 0, input);
            GLES30.glUniform4f(shader.at("u_display_uv_rect"), 0f, 1f, 1f, 0f);
            shader.f("u_maskThreshold", 1f - track.reveal);
            if (track.style == PhotoTextureSettings.FROSTED) {
                bind(shader, "u_grayTex", 1, gray);
                bind(shader, "u_maskTex", 2, mask);
                shader.f("u_minGray", 1f - track.strength);
                GLES30.glUniform2f(shader.at("u_maskOffset"), track.maskX, track.maskY);
                GLES30.glUniform2f(shader.at("u_dudv"), 1f / grayWidth, 1f / grayHeight);
            } else {
                boolean wave = track.style == PhotoTextureSettings.WAVE;
                float widthAtRest = wave ? .072f : .076f;
                float widthAwake = wave ? .05f : .04f;
                float lineWidth = widthAtRest + (widthAwake - widthAtRest) * track.strength;
                // Keep the current viewport crop while the effect opens and closes.
                shader.f("u_tiling", 1f);
                shader.f("u_lineWidth", lineWidth);
                shader.f("u_lineOffset", lineWidth);
                shader.f("u_lineFrequency", 40f);
                shader.f("u_lineAmplitude", .045f);
                GLES30.glUniform2f(shader.at("u_offsetStrength"), .03f * track.strength,
                        wave ? .007f * track.strength : 0f);
            }
            GLES30.glBindVertexArray(vaos[track.style]);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            target.record(input, track);
            return target.texture;
        } catch (IOException | RuntimeException error) {
            unavailable[track.style] = true;
            Log.e(Root.TAG, "照片纹理渲染失败 style=" + track.style, error);
            return input;
        } finally {
            GLES30.glBindVertexArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }
    }

    private AssetGl program(int style) throws IOException {
        if (programs[style] != null) return programs[style];
        if (style == PhotoTextureSettings.FROSTED) {
            if (gray == 0) gray = loadMap("gray", false);
            if (mask == 0) mask = loadMap("mask", true);
        }
        String base = ROOT + SHADERS[style];
        AssetGl shader = new AssetGl(assets, base + "_vertex.glsl", base + "_frag.glsl");
        programs[style] = shader;
        if (buffer[0] == 0) {
            ByteBuffer data = ByteBuffer.allocateDirect(QUAD.length * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
            data.asFloatBuffer().put(QUAD);
            GLES30.glGenBuffers(1, buffer, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer[0]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.capacity(), data, GLES30.GL_STATIC_DRAW);
        }
        GLES30.glGenVertexArrays(1, vaos, style);
        GLES30.glBindVertexArray(vaos[style]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer[0]);
        attribute(shader, "a_position", 3, 0);
        attribute(shader, "a_texCoord0", 2, 3 * Float.BYTES);
        GLES30.glBindVertexArray(0);
        Log.i(Root.TAG, "照片纹理就绪 style=" + style);
        return shader;
    }

    private static void attribute(AssetGl shader, String name, int size, int offset) throws IOException {
        int location = GLES30.glGetAttribLocation(shader.id, name);
        if (location < 0) throw new IOException("Missing photo texture attribute: " + name);
        GLES30.glEnableVertexAttribArray(location);
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 5 * Float.BYTES, offset);
    }

    private int loadMap(String name, boolean repeat) throws IOException {
        String path = ROOT + "groundGlass/" + name + ".webp";
        Bitmap bitmap;
        try (InputStream stream = assets.open(path)) {
            bitmap = BitmapFactory.decodeStream(stream);
        }
        if (bitmap == null) throw new IOException("Cannot decode " + path);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        ByteBuffer pixels;
        try {
            pixels = ByteBuffer.allocateDirect(width * height);
            int[] colors = new int[width];
            byte[] row = new byte[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(colors, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) row[x] = (byte) (colors[x] >>> 16);
                pixels.put(row);
            }
            pixels.flip();
        } finally {
            bitmap.recycle();
        }
        int texture = newTexture(repeat);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, pixels);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, new int[]{texture}, 0);
            throw new IOException("Texture upload failed for " + path + " (GL error " + error + ")");
        }
        if (!repeat) {
            grayWidth = width;
            grayHeight = height;
        }
        return texture;
    }

    private static int newTexture(boolean repeat) {
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        int wrap = repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE;
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);
        return id[0];
    }

    private static void bind(AssetGl shader, String sampler, int unit, int texture) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(shader.at(sampler), unit);
    }

    @Override
    public void close() {
        for (AssetGl program : programs) {
            if (program != null) program.close();
        }
        GLES30.glDeleteVertexArrays(vaos.length, vaos, 0);
        GLES30.glDeleteBuffers(1, buffer, 0);
        GLES30.glDeleteTextures(2, new int[]{gray, mask}, 0);
        lock.release();
        home.release();
    }

    private static final class Target {
        int framebuffer;
        int texture;
        int width;
        int height;
        int input;
        int style;
        float strength;
        float reveal;
        float maskX;
        float maskY;

        boolean matches(int input, PhotoTextureMotion.Track track, int width, int height) {
            return this.input == input && this.width == width && this.height == height
                    && style == track.style && strength == track.strength && reveal == track.reveal
                    && maskX == track.maskX && maskY == track.maskY;
        }

        void record(int input, PhotoTextureMotion.Track track) {
            this.input = input;
            style = track.style;
            strength = track.strength;
            reveal = track.reveal;
            maskX = track.maskX;
            maskY = track.maskY;
        }

        void ensure(int width, int height) throws IOException {
            if (framebuffer != 0 && this.width == width && this.height == height) return;
            release();
            int[] id = new int[1];
            texture = newTexture(false);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glGenFramebuffers(1, id, 0);
            framebuffer = id[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, texture, 0);
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                    != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                release();
                throw new IOException("Incomplete photo texture framebuffer");
            }
            this.width = width;
            this.height = height;
        }

        void release() {
            GLES30.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            GLES30.glDeleteTextures(1, new int[]{texture}, 0);
            framebuffer = texture = input = 0;
        }
    }
}
