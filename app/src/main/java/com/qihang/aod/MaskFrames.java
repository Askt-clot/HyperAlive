package com.qihang.aod;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 魅族「留光」等遮罩序列帧流式解码与 LRU 缓存器（61 帧过渡 000..060.rgbz + start.rgbz(61) + end.rgbz(62)）。
 *
 * 改进特性：
 *   1. 常驻锁定 start.rgbz (61, AOD 稳态) 与 end.rgbz (62, 锁屏/桌面稳态)，稳态零解码开销；
 *   2. 过渡帧 (0..60) 后台异步预取 + GL 线程未命中时同步兜底解码，保证 120Hz 过渡每一帧遮罩都 100% 命中不黑屏；
 *   3. 记录当前已上传的帧索引，同一帧不重复执行 glTexSubImage2D，稳态 GPU 带宽开销为 0。
 */
final class MaskFrames implements AutoCloseable {

    static final int COUNT = 61;
    static final int START = 61;
    static final int END = 62;
    static final int END_MICROS = 483667;
    private static final int CACHE_LIMIT = 16;

    private final AssetManager assets;
    private final String dir;
    private final Object lock = new Object();
    private final LinkedHashMap<Integer, ByteBuffer> cache = new LinkedHashMap<>(CACHE_LIMIT, 0.75f, true);
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private final HashSet<Integer> inflight = new HashSet<>();
    private ByteBuffer pinnedStart;
    private ByteBuffer pinnedEnd;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LiuguangMaskDec");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private int lastUploadedIndex = -1;
    private boolean textureAllocated = false;

    MaskFrames(Context context, String styleDir) {
        this.assets = context.getApplicationContext().getAssets();
        this.dir = "masks/" + styleDir;
        for (int i = 0; i < CACHE_LIMIT + 2; i++) {
            pool.add(ByteBuffer.allocateDirect(MaskDecoder.FRAME_BYTES).order(ByteOrder.nativeOrder()));
        }
        // 异步预热 start(61)、end(62) 及首尾过渡帧
        prefetch(START);
        prefetch(END);
        prefetch(0);
        prefetch(1);
        prefetch(COUNT - 1);
    }

    static int frameIndex(long micros) {
        if (micros <= 0) return START;
        if (micros >= END_MICROS) return END;
        return Math.min(COUNT - 1, Math.round(micros * 120f / 1_000_000f));
    }

    boolean upload(int index, int textureId) {
        if (textureId == 0 || closed) {
            return false;
        }
        // 预取后续帧（兼顾正向展开与反向回缩）
        if (index == START) {
            prefetch(0);
            prefetch(1);
            prefetch(2);
        } else if (index == END) {
            prefetch(COUNT - 1);
            prefetch(COUNT - 2);
        } else {
            for (int d = 1; d <= 3; d++) {
                if (index + d < COUNT) prefetch(index + d);
                if (index - d >= 0) prefetch(index - d);
            }
        }

        if (textureAllocated && index == lastUploadedIndex) {
            return true;
        }

        ByteBuffer buf = getOrDecodeSync(index);
        if (buf == null) {
            return false;
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        synchronized (buf) {
            buf.position(0);
            if (!textureAllocated) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB,
                        MaskDecoder.WIDTH, MaskDecoder.HEIGHT, 0,
                        GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buf);
                textureAllocated = true;
            } else {
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0,
                        MaskDecoder.WIDTH, MaskDecoder.HEIGHT,
                        GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buf);
            }
        }
        lastUploadedIndex = index;
        return true;
    }

    void resetTextureState() {
        lastUploadedIndex = -1;
        textureAllocated = false;
    }

    private ByteBuffer getOrDecodeSync(int index) {
        synchronized (lock) {
            if (index == START && pinnedStart != null) {
                return pinnedStart;
            }
            if (index == END && pinnedEnd != null) {
                return pinnedEnd;
            }
            ByteBuffer hit = cache.get(index);
            if (hit != null) {
                return hit;
            }
        }
        // 未命中缓存时同步解码当前帧，保证 120Hz 过渡绝不闪黑或丢帧
        ByteBuffer syncBuf = ByteBuffer.allocateDirect(MaskDecoder.FRAME_BYTES).order(ByteOrder.nativeOrder());
        if (!MaskDecoder.decode(assets, path(index), syncBuf)) {
            return null;
        }
        synchronized (lock) {
            if (index == START) {
                pinnedStart = syncBuf;
                return pinnedStart;
            }
            if (index == END) {
                pinnedEnd = syncBuf;
                return pinnedEnd;
            }
            ByteBuffer existing = cache.get(index);
            if (existing != null) {
                return existing;
            }
            if (cache.size() >= CACHE_LIMIT) {
                IteratorEvict();
            }
            cache.put(index, syncBuf);
            return syncBuf;
        }
    }

    private void IteratorEvict() {
        Map.Entry<Integer, ByteBuffer> eldest = cache.entrySet().iterator().next();
        cache.remove(eldest.getKey());
        pool.add(eldest.getValue());
    }

    private void prefetch(int index) {
        if (index < 0 || index > END || closed) {
            return;
        }
        synchronized (lock) {
            if (index == START && pinnedStart != null) return;
            if (index == END && pinnedEnd != null) return;
            if (cache.containsKey(index) || !inflight.add(index)) {
                return;
            }
        }
        worker.execute(() -> decodeAsync(index));
    }

    private void decodeAsync(int index) {
        ByteBuffer buffer;
        synchronized (lock) {
            if (closed) {
                inflight.remove(index);
                return;
            }
            if ((index == START && pinnedStart != null)
                    || (index == END && pinnedEnd != null)
                    || cache.containsKey(index)) {
                inflight.remove(index);
                return;
            }
            buffer = pool.poll();
            if (buffer == null && cache.size() >= CACHE_LIMIT) {
                Map.Entry<Integer, ByteBuffer> eldest = cache.entrySet().iterator().next();
                cache.remove(eldest.getKey());
                buffer = eldest.getValue();
            }
        }
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(MaskDecoder.FRAME_BYTES).order(ByteOrder.nativeOrder());
        }
        boolean ok = false;
        synchronized (buffer) {
            ok = MaskDecoder.decode(assets, path(index), buffer);
        }
        synchronized (lock) {
            inflight.remove(index);
            if (!ok || closed) {
                pool.add(buffer);
                return;
            }
            if (index == START) {
                pinnedStart = buffer;
                return;
            }
            if (index == END) {
                pinnedEnd = buffer;
                return;
            }
            if (cache.containsKey(index)) {
                pool.add(buffer);
                return;
            }
            while (cache.size() >= CACHE_LIMIT) {
                IteratorEvict();
            }
            cache.put(index, buffer);
        }
    }

    private String path(int index) {
        if (index == START) return dir + "/start.rgbz";
        if (index == END) return dir + "/end.rgbz";
        return String.format(Locale.US, "%s/%03d.rgbz", dir, index);
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            cache.clear();
            pool.clear();
            inflight.clear();
        }
        worker.shutdownNow();
    }
}
