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

    static final int CACHE_LIMIT = 16;
    static final int MAX_ALLOCATED = CACHE_LIMIT + 4;
    private static final int MASK_BYTE_BUDGET = MAX_ALLOCATED * 720 * 720 * MaskDecoder.CHANNELS;
    private static final int END_SIZE = 720;

    private final String dir;
    private final int count;
    private final int start;
    private final int end;
    private final int fps;
    private final int endMicros;
    private final int size;
    private final int frameBytes;
    private final int maxAllocated;
    private final int cacheLimit;
    private final int prefetchRadius;
    private final boolean directedPrefetch;
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
    private int uploadedSize;
    private int playbackDirection = 1;

    MaskFrames(Context context, PhotoMaskStyle style) {
        AssetManager assets = context.getApplicationContext().getAssets();
        this.dir = "masks/" + style.folder;
        this.count = style.frameCount;
        this.start = count;
        this.end = count + 1;
        this.fps = style.fps;
        this.endMicros = style.endMicros;
        this.size = style.size;
        this.frameBytes = size * size * MaskDecoder.CHANNELS;
        // 1080p 帧更大，按字节限制缓存，预留起止帧和同步/异步解码缓冲。
        this.maxAllocated = MASK_BYTE_BUDGET / frameBytes;
        this.cacheLimit = maxAllocated - (MAX_ALLOCATED - CACHE_LIMIT);
        this.prefetchRadius = size > 720 ? 1 : 3;
        this.directedPrefetch = style == PhotoMaskStyle.LEAVE_LIGHT;
        this.decoder = (index, out) -> {
            int side = sizeOf(index);
            return MaskDecoder.decode(assets, path(index), out, side * side * MaskDecoder.CHANNELS);
        };
        prefetch(start);
        prefetch(end);
        prefetch(0);
        prefetch(1);
        prefetch(getFrameIndex(endMicros - 1L));
    }

    /** 供离线 JVM 回归测试注入自定义解码器与帧大小，直接验证生产缓冲池/引用计数/驱逐与并发关闭逻辑。 */
    MaskFrames(int frameCount, int fps, int endMicros, int frameBytes, FrameDecoder decoder, boolean autoPrefetch) {
        this.dir = "masks/test";
        this.count = frameCount;
        this.start = frameCount;
        this.end = frameCount + 1;
        this.fps = fps;
        this.endMicros = endMicros;
        this.size = 720;
        this.frameBytes = Math.max(16, frameBytes);
        this.maxAllocated = MAX_ALLOCATED;
        this.cacheLimit = CACHE_LIMIT;
        this.prefetchRadius = 3;
        this.directedPrefetch = false;
        this.decoder = decoder;
        if (autoPrefetch) {
            prefetch(start);
            prefetch(end);
            prefetch(0);
            prefetch(1);
            prefetch(count - 1);
        }
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

    private int sizeOf(int index) {
        return index == end ? END_SIZE : size;
    }

    private void prefetchInPlaybackOrder(int index) {
        int finalFrame = getFrameIndex(endMicros - 1L);
        int position = index == start ? -1 : index == end ? finalFrame + 1 : index;
        int previous = lastUploadedIndex == start ? -1
                : lastUploadedIndex == end ? finalFrame + 1 : lastUploadedIndex;
        if (index == start) playbackDirection = 1;
        else if (index == end) playbackDirection = -1;
        else if (position != previous) playbackDirection = Integer.compare(position, previous);
        for (int step = 1; step <= 6; step++) {
            int frame = position + playbackDirection * step;
            if (frame < 0 || frame > finalFrame) break;
            prefetch(frame);
        }
    }

    boolean upload(int index, int textureId) {
        if (textureId == 0 || closed) {
            return false;
        }
        // 预取后续帧（兼顾正向展开与反向回缩）
        if (directedPrefetch) {
            prefetchInPlaybackOrder(index);
        } else if (index == start) {
            prefetch(0);
            prefetch(1);
            prefetch(2);
        } else if (index == end) {
            int lastTransitionFrame = getFrameIndex(endMicros - 1L);
            prefetch(lastTransitionFrame);
            prefetch(lastTransitionFrame - 1);
        } else {
            for (int d = 1; d <= prefetchRadius; d++) {
                if (index + d < count) prefetch(index + d);
                if (index - d >= 0) prefetch(index - d);
            }
        }

        if (uploadedSize != 0 && index == lastUploadedIndex) {
            return true;
        }

        ByteBuffer buf = getOrDecodeSync(index);
        if (buf == null) {
            return false;
        }

        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            int side = sizeOf(index);
            synchronized (buf) {
                buf.position(0);
                if (uploadedSize != side) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB,
                            side, side, 0,
                            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buf);
                    uploadedSize = side;
                } else {
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0,
                            side, side,
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
        uploadedSize = 0;
        playbackDirection = 1;
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
        if (resident < maxAllocated) {
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
        if (allocatedCount < maxAllocated) {
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
            while (cache.size() >= cacheLimit) {
                if (!iteratorEvictLocked()) {
                    break;
                }
            }
            if (cache.size() < cacheLimit) {
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
            while (cache.size() >= cacheLimit) {
                if (!iteratorEvictLocked()) {
                    break;
                }
            }
            if (cache.size() < cacheLimit) {
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
