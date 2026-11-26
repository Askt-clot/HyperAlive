/*
 * Design or implementation reference: OpenAlive (app/java/org/aliveclean/NativeScene.java).
 * Upstream attribution and applicable license: THIRD_PARTY_NOTICES.md.
 */
package com.hyperalive.aod;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 纯 Java 遮罩帧解码器（对应 OpenAlive 的 NativeScene.decodeMask）。
 * 将遮罩目录中的 zlib RGB8 帧解压到对应尺寸的缓冲区。
 */
final class MaskDecoder {

    private static final String TAG = Root.TAG;
    static final int CHANNELS = 3;

    private MaskDecoder() {}

    static boolean decode(AssetManager assets, String path, ByteBuffer out, int frameBytes) {
        if (assets == null || out == null || out.capacity() < frameBytes) {
            return false;
        }
        Inflater inflater = new Inflater();
        try (InputStream in = assets.open(path);
             InputStream decoded = new InflaterInputStream(in, inflater, 8192)) {
            out.clear();
            out.limit(frameBytes);
            byte[] chunk = new byte[65536];
            while (out.hasRemaining()) {
                int count = decoded.read(chunk, 0, Math.min(chunk.length, out.remaining()));
                if (count == -1) {
                    return false;
                }
                out.put(chunk, 0, count);
            }
            out.position(0);
            return decoded.read() == -1 && inflater.finished();
        } catch (IOException t) {
            Log.e(TAG, "MaskDecoder 解压失败: " + path, t);
            return false;
        } finally {
            inflater.end();
        }
    }
}
