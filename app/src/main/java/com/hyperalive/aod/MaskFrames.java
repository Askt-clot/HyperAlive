package com.hyperalive.aod;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 魅族「留光」「星月」等遮罩序列帧流式解码与 LRU 缓存器。
 *
 * 改进特性：
 *   1. 常驻锁定 start.rgbz (AOD 稳态) 与 end.rgbz (锁屏/桌面稳态)，稳态零解码开销；
 *   2. 过渡帧后台异步预取 + GL 线程未命中时同步兜底解码，保证高刷过渡每一帧遮罩都 100% 命中不黑屏；
 *   3. 记录当前已上传的帧索引，同一帧不重复执行 glTexSubImage2D，稳态 GPU 带宽开销为 0；
 *   4. 支持按视觉风格动态配置帧数、帧率与微秒范围（如留光 61 帧 120Hz、星月 32 帧 60Hz）；
 *   5. 全程基于对象身份 (IdentityHashMap + ==) 与引用计数管理 DirectByteBuffer，避免内容哈希合并与在途驱逐踩踏。
 */
final class MaskFrames implements AutoCloseable {

    interface FrameDecoder {
        boolean decode(int index, ByteBuffer out);
    }

    static final int COUNT = 61;
    static final int START = 61;
    static final int END = 62;
    static final int END_MICROS = 483667;
    static final int CACHE_LIMIT = 16;
    static final int MAX_ALLOCATED = CACHE_LIMIT + 4;

    private final AssetManager assets;
    private final String dir;
    private final int count;
    private final int start;
    private final int end;
    private final int fps;
    private final int endMicros;
    private final int frameBytes;
    private final FrameDecoder decoder;

    private final Object lock = new Object();
    private final LinkedHashMap<Integer, ByteBuffer> cache = new LinkedHashMap<>(CACHE_LIMIT, 0.75f, true);
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private final HashSet<Integer> inflight = new HashSet<>();
    private final IdentityHashMap<ByteBuffer, Integer> inUseCounts = new IdentityHashMap<>();
    private int allocatedCount = 0;
    private ByteBuffer pinnedStart;
    private ByteBuffer pinnedEnd;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PhotoMaskDec");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private int lastUploadedIndex = -1;
    private boolean textureAllocated = false;

    MaskFrames(Context context, String styleDir) {
        this(context, styleDir, 61, 120, 483667);
    }

    MaskFrames(Context context, String styleDir, int frameCount, int fps, int endMicros) {
        this.assets = context.getApplicationContext().getAssets();
        this.dir = "masks/" + styleDir;
        this.count = frameCount;
        this.start = frameCount;
        this.end = frameCount + 1;
        this.fps = fps;
        this.endMicros = endMicros;
        this.frameBytes = MaskDecoder.FRAME_BYTES;
        this.decoder = new FrameDecoder() {
            @Override
            public boolean decode(int index, ByteBuffer out) {
                return MaskDecoder.decode(assets, path(index), out);
            }
        };
        // 按需惰性分配 DirectByteBuffer（严格受限于 MAX_ALLOCATED），异步预热 start、end 及首尾过渡帧
        prefetch(start);
        prefetch(end);
        prefetch(0);
        prefetch(1);
        prefetch(count - 1);
    }

    /** 供离线 JVM 回归测试注入自定义解码器与帧大小，直接验证生产缓冲池/引用计数/驱逐与并发关闭逻辑。 */
    MaskFrames(int frameCount, int fps, int endMicros, int frameBytes, FrameDecoder decoder, boolean autoPrefetch) {
        this.assets = null;
        this.dir = "masks/test";
        this.count = frameCount;
        this.start = frameCount;
        this.end = frameCount + 1;
        this.fps = fps;
        this.endMicros = endMicros;
        this.frameBytes = Math.max(16, frameBytes);
        this.decoder = decoder;
        if (autoPrefetch) {
            prefetch(start);
            prefetch(end);
            prefetch(0);
            prefetch(1);
            prefetch(count - 1);
        }
    }

    static MaskFrames forStyle(Context context, int style) {
        if (style == AodStyleSettings.STYLE_MOON) {
            return new MaskFrames(context, "moon", 32, 60, 516667);
        }
        return new MaskFrames(context, "leave_light", 61, 120, 483667);
    }

    int startIndex() {
        return start;
    }

    int endIndex() {
        return end;
    }

    int getFrameIndex(long micros) {
        if (micros <= 0) return start;
        if (micros >= endMicros) return end;
        return Math.min(count - 1, Math.round(micros * (float) fps / 1_000_000f));
    }

    static int frameIndex(long micros) {
        if (micros <= 0) return START;
        if (micros >= END_MICROS) return END;
        return Math.min(COUNT - 1, Math.round(micros * 120f / 1_000_000f));
    }

    static int frameIndexForStyle(int style, long micros) {
        if (style == AodStyleSettings.STYLE_MOON) {
            if (micros <= 0) return 32;
            if (micros >= 516667) return 33;
            return Math.min(31, Math.round(micros * 60f / 1_000_000f));
        }
        if (micros <= 0) return 61;
        if (micros >= 483667) return 62;
        return Math.min(60, Math.round(micros * 120f / 1_000_000f));
    }

    boolean upload(int index, int textureId) {
        if (textureId == 0 || closed) {
            return false;
        }
        // 预取后续帧（兼顾正向展开与反向回缩）
        if (index == start) {
            prefetch(0);
            prefetch(1);
            prefetch(2);
        } else if (index == end) {
            prefetch(count - 1);
            prefetch(count - 2);
        } else {
            for (int d = 1; d <= 3; d++) {
                if (index + d < count) prefetch(index + d);
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

        try {
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
        } finally {
            releaseInUse(buf);
        }
    }

    void resetTextureState() {
        lastUploadedIndex = -1;
        textureAllocated = false;
    }

    private void retainBufferLocked(ByteBuffer buf) {
        if (buf == null) return;
        Integer cur = inUseCounts.get(buf);
        inUseCounts.put(buf, cur == null ? 1 : (cur + 1));
    }

    private void unretainBufferLocked(ByteBuffer buf) {
        if (buf == null) return;
        Integer cur = inUseCounts.get(buf);
        if (cur == null) return;
        if (cur <= 1) {
            inUseCounts.remove(buf);
        } else {
            inUseCounts.put(buf, cur - 1);
        }
    }

    boolean isBufferInUseLocked(ByteBuffer buf) {
        Integer cur = inUseCounts.get(buf);
        return cur != null && cur > 0;
    }

    private boolean isInCacheIdentityLocked(ByteBuffer buf) {
        for (ByteBuffer v : cache.values()) {
            if (v == buf) {
                return true;
            }
        }
        return false;
    }

    private boolean isInPoolIdentityLocked(ByteBuffer buf) {
        for (ByteBuffer v : pool) {
            if (v == buf) {
                return true;
            }
        }
        return false;
    }

    private void returnToPoolOrDropLocked(ByteBuffer buf) {
        if (buf == null) return;
        if (closed) {
            if (!isBufferInUseLocked(buf)) {
                allocatedCount = inUseCounts.size();
            }
            return;
        }
        if (buf == pinnedStart || buf == pinnedEnd
                || isBufferInUseLocked(buf)
                || isInCacheIdentityLocked(buf)
                || isInPoolIdentityLocked(buf)) {
            return;
        }
        int resident = pool.size() + cache.size()
                + (pinnedStart != null ? 1 : 0)
                + (pinnedEnd != null ? 1 : 0)
                + inUseCounts.size();
        if (resident < MAX_ALLOCATED) {
            pool.addLast(buf);
        } else {
            allocatedCount = Math.max(inUseCounts.size(), allocatedCount - 1);
        }
    }

    ByteBuffer acquireBufferLocked() {
        if (closed) {
            return null;
        }
        ByteBuffer buf = pool.pollFirst();
        if (buf != null) {
            retainBufferLocked(buf);
            return buf;
        }
        if (allocatedCount < MAX_ALLOCATED) {
            allocatedCount++;
            buf = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder());
            retainBufferLocked(buf);
            return buf;
        }
        java.util.Iterator<Map.Entry<Integer, ByteBuffer>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ByteBuffer> entry = it.next();
            ByteBuffer candidate = entry.getValue();
            if (!isBufferInUseLocked(candidate)) {
                it.remove();
                retainBufferLocked(candidate);
                return candidate;
            }
        }
        return null;
    }

    void releaseInUse(ByteBuffer buf) {
        if (buf == null) return;
        synchronized (lock) {
            unretainBufferLocked(buf);
            returnToPoolOrDropLocked(buf);
        }
    }

    ByteBuffer getOrDecodeSync(int index) {
        ByteBuffer syncBuf;
        synchronized (lock) {
            if (closed) {
                return null;
            }
            if (index == start && pinnedStart != null) {
                retainBufferLocked(pinnedStart);
                return pinnedStart;
            }
            if (index == end && pinnedEnd != null) {
                retainBufferLocked(pinnedEnd);
                return pinnedEnd;
            }
            ByteBuffer hit = cache.get(index);
            if (hit != null) {
                retainBufferLocked(hit);
                return hit;
            }
            syncBuf = acquireBufferLocked();
            if (syncBuf == null) {
                return null;
            }
        }
        // 未命中缓存时同步解码当前帧，保证高刷过渡绝不闪黑或丢帧
        boolean ok;
        synchronized (syncBuf) {
            ok = decoder != null && decoder.decode(index, syncBuf);
        }
        if (!ok) {
            releaseInUse(syncBuf);
            return null;
        }
        synchronized (lock) {
            if (closed) {
                unretainBufferLocked(syncBuf);
                returnToPoolOrDropLocked(syncBuf);
                return null;
            }
            if (index == start) {
                if (pinnedStart == null) {
                    pinnedStart = syncBuf;
                } else if (pinnedStart != syncBuf) {
                    unretainBufferLocked(syncBuf);
                    returnToPoolOrDropLocked(syncBuf);
                    retainBufferLocked(pinnedStart);
                }
                return pinnedStart;
            }
            if (index == end) {
                if (pinnedEnd == null) {
                    pinnedEnd = syncBuf;
                } else if (pinnedEnd != syncBuf) {
                    unretainBufferLocked(syncBuf);
                    returnToPoolOrDropLocked(syncBuf);
                    retainBufferLocked(pinnedEnd);
                }
                return pinnedEnd;
            }
            ByteBuffer existing = cache.get(index);
            if (existing != null) {
                if (existing != syncBuf) {
                    unretainBufferLocked(syncBuf);
                    returnToPoolOrDropLocked(syncBuf);
                    retainBufferLocked(existing);
                }
                return existing;
            }
            while (cache.size() >= CACHE_LIMIT) {
                if (!iteratorEvictLocked()) {
                    break;
                }
            }
            if (cache.size() < CACHE_LIMIT) {
                cache.put(index, syncBuf);
            }
            return syncBuf;
        }
    }

    private boolean iteratorEvictLocked() {
        java.util.Iterator<Map.Entry<Integer, ByteBuffer>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ByteBuffer> eldest = it.next();
            ByteBuffer candidate = eldest.getValue();
            if (!isBufferInUseLocked(candidate)) {
                it.remove();
                returnToPoolOrDropLocked(candidate);
                return true;
            }
        }
        return false;
    }

    void prefetch(int index) {
        if (index < 0 || index > end || closed) {
            return;
        }
        synchronized (lock) {
            if (index == start && pinnedStart != null) return;
            if (index == end && pinnedEnd != null) return;
            if (cache.containsKey(index) || !inflight.add(index)) {
                return;
            }
        }
        try {
            worker.execute(() -> decodeAsync(index));
        } catch (Throwable ignored) {
            synchronized (lock) {
                inflight.remove(index);
            }
        }
    }

    void decodeAsync(int index) {
        ByteBuffer buffer;
        synchronized (lock) {
            if (closed) {
                inflight.remove(index);
                return;
            }
            if ((index == start && pinnedStart != null)
                    || (index == end && pinnedEnd != null)
                    || cache.containsKey(index)) {
                inflight.remove(index);
                return;
            }
            buffer = acquireBufferLocked();
            if (buffer == null) {
                inflight.remove(index);
                return;
            }
        }
        boolean ok = false;
        synchronized (buffer) {
            ok = decoder != null && decoder.decode(index, buffer);
        }
        synchronized (lock) {
            inflight.remove(index);
            unretainBufferLocked(buffer);
            if (!ok || closed) {
                returnToPoolOrDropLocked(buffer);
                return;
            }
            if (index == start) {
                if (pinnedStart == null) {
                    pinnedStart = buffer;
                } else {
                    returnToPoolOrDropLocked(buffer);
                }
                return;
            }
            if (index == end) {
                if (pinnedEnd == null) {
                    pinnedEnd = buffer;
                } else {
                    returnToPoolOrDropLocked(buffer);
                }
                return;
            }
            if (cache.containsKey(index)) {
                returnToPoolOrDropLocked(buffer);
                return;
            }
            while (cache.size() >= CACHE_LIMIT) {
                if (!iteratorEvictLocked()) {
                    break;
                }
            }
            if (cache.size() < CACHE_LIMIT) {
                cache.put(index, buffer);
            } else {
                returnToPoolOrDropLocked(buffer);
            }
        }
    }

    int allocatedCountForTest() {
        synchronized (lock) {
            return allocatedCount;
        }
    }

    int inUseRefCountForTest(ByteBuffer buf) {
        synchronized (lock) {
            Integer c = inUseCounts.get(buf);
            return c == null ? 0 : c;
        }
    }

    int inUseDistinctCountForTest() {
        synchronized (lock) {
            return inUseCounts.size();
        }
    }

    int poolSizeForTest() {
        synchronized (lock) {
            return pool.size();
        }
    }

    int cacheSizeForTest() {
        synchronized (lock) {
            return cache.size();
        }
    }

    boolean isCachedIdentityForTest(int index, ByteBuffer expected) {
        synchronized (lock) {
            return cache.get(index) == expected;
        }
    }

    private String path(int index) {
        if (index == start) return dir + "/start.rgbz";
        if (index == end) return dir + "/end.rgbz";
        return String.format(Locale.US, "%s/%03d.rgbz", dir, index);
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            cache.clear();
            pool.clear();
            inflight.clear();
            pinnedStart = null;
            pinnedEnd = null;
            allocatedCount = inUseCounts.size();
        }
        worker.shutdownNow();
    }
}
