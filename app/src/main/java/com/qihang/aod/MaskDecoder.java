package com.qihang.aod;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.Inflater;

/**
 * 纯 Java 遮罩帧解码器（对应 OpenAlive 的 NativeScene.decodeMask）。
 * 将 assets/masks/<dir>/<file>.rgbz 通过 zlib Inflater 解压为 720×720×3 (RGB8) Direct ByteBuffer。
 */
final class MaskDecoder {

    private static final String TAG = Root.TAG;
    static final int WIDTH = 720;
    static final int HEIGHT = 720;
    static final int CHANNELS = 3;
    static final int FRAME_BYTES = WIDTH * HEIGHT * CHANNELS;

    private MaskDecoder() {}

    static boolean decode(AssetManager assets, String path, ByteBuffer out) {
        if (assets == null || out == null || out.capacity() < FRAME_BYTES) {
            return false;
        }
        byte[] compressed;
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            compressed = bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "MaskDecoder 读取失败: " + path, t);
            return false;
        }

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            out.clear();
            byte[] chunk = new byte[65536];
            int total = 0;
            while (!inflater.finished() && total < FRAME_BYTES) {
                int count = inflater.inflate(chunk, 0, Math.min(chunk.length, FRAME_BYTES - total));
                if (count <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    out.put(chunk, 0, count);
                    total += count;
                }
            }
            out.position(0);
            out.limit(FRAME_BYTES);
            return total == FRAME_BYTES;
        } catch (Throwable t) {
            Log.e(TAG, "MaskDecoder 解压失败: " + path, t);
            return false;
        } finally {
            inflater.end();
        }
    }
}
