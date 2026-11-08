package com.qihang.aod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 启航渲染器：EGL14 + GLES30，只有一个 program —— 官方 {@code lens_photo}。
 *
 * <p>三态共用同一个 program：息屏时 scale=0.22 / distortion=9 / 弧光=1 / 圆心偏上，
 * 圆外由着色器直接输出纯黑；锁屏与桌面把 scale 张开到 expandedScale、
 * distortion 与弧光归零、zoom 回 1，于是同一套代码自然给出「全屏照片」。
 *
 * <p>圆内模糊用 mipmap 的 {@code GL_TEXTURE_MIN_LOD / MAX_LOD} 夹逼近似（官方为 3 级高斯，blur=22）。
 */
final class LensRenderer implements Runnable {

    private static final String TAG = Root.TAG;

    private static final float[] QUAD_POS = {
            -1f, -1f, 0f,
            1f, -1f, 0f,
            -1f, 1f, 0f,
            1f, 1f, 0f,
    };
    private static final float[] QUAD_UV = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    private final Context appContext;
    private final SurfaceHolder holder;
    /** 预览用：直接渲染到给定的 Surface（null 时走壁纸的 SurfaceHolder）。 */
    private final Surface previewSurface;
    /** true = 桌面槽位 Engine（QihangHomeWallpaper），false = 锁屏/AOD 槽位或预览。 */
    private final boolean homeSlot;
    private final Thread thread;
    private final Object lock = new Object();

    private volatile boolean running = true;
    private volatile boolean visible = true;
    private volatile int pendingMode = Motion.LOCK;
    private volatile boolean pendingAnimate = true;
    private volatile boolean pendingDirty = true;
    /** 渲染线程每帧刷新的过渡状态。 */
    private volatile boolean transitionRunning;
    private volatile boolean pendingForceLockBeforeHome;
    private volatile boolean pendingContinue;
    private volatile int pendingSeekFrom = -1;
    private volatile int pendingSeekTo = Motion.LOCK;
    private volatile long pendingSeekElapsedMs;
    private volatile boolean pendingPhotoReload;
    private volatile boolean pendingPhotoReloadForce;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private int program;
    private int aPosition = -1;
    private int aTexCoord = -1;
    private int uProjectionView;
    private int uDecoratorMatrix;
    private int uTextureMatrix;
    private int uPhoto;
    private int uHomePhoto;
    private int uHomeRatio;
    private int uDecorator;
    private int uTranslation;
    private int uLensScale;
    private int uAspectRatio;
    private int uAspectRatioReciprocal;
    private int uDistortion;
    private int uZoom;
    private int uDecoratorAlpha;
    private int uRippleRadius;
    private int uRippleBoundary;
    private int uDarkStrength;

    private FloatBuffer quadPos;
    private FloatBuffer quadUv;
    private int decoratorTexture;
    private int surfaceWidth;
    private int surfaceHeight;

    private volatile int aodStyle = AodStyleSettings.STYLE_SET_SAIL;
    private LiuguangPipeline liuguangPipeline;
    private final Runnable styleListener = new Runnable() {
        @Override
        public void run() {
            if (previewSurface == null) {
                int latest = AodStyleSettings.getStyle(appContext);
                if (latest != aodStyle) {
                    aodStyle = latest;
                    pendingDirty = true;
                    wake();
                }
            }
        }
    };

    private Motion motion;
    private boolean photoDirty = true;
    /** 预览专用：直接指定读哪张照片（PhotoStore.LOCK / HOME）；null = 走壁纸取图逻辑。 */
    private String previewPhoto;
    /** 预览专用：当桌面不跟随锁屏时，独立桌面照片文件名（PhotoStore.HOME）；null = 与锁屏相同。 */
    private String previewHomePhoto;
    /** 预览专用：>0 时使用 OpenAlive 的 WallpaperRenderer 实时渲染魅族动态壁纸三态。 */
    private volatile int previewVariant;
    private int activeVariant;
    private WallpaperRenderer dynamicRenderer;
    private StereoSensor stereoSensor;
    private android.os.HandlerThread sensorThread;
    /** 是否已经成功出过帧（预览用来决定要不要退回占位图）。 */
    private volatile boolean drewFrame;
    /** 上一帧是否真的做了圆内模糊（用于状态变化时打一行日志）。 */
    private boolean blurAppliedLast;
    /** 真·三级高斯模糊（OpenAlive 同款管线）；壁纸与预览共用。 */
    private GaussianBlur gaussianBlur;
    /**
     * 「息屏层是否还在画面上」—— 模糊只属于息屏层（官方 aod_pass 门控 ratio[0]&gt;0）。
     * ratio[0] 走完的时长：tr2（熄屏→桌面）300ms，其余方向 950/650ms。
     * 若拿 animating() 当门控，tr2 期间清晰层（桌面预览）会被整幅糊到动画结束 —— 用户报的正是这个。
     */
    private boolean aodLayerVisible() {
        if (pendingMode == Motion.AOD) {
            return true;
        }
        if (!motion.animating()) {
            return false;
        }
        long el = motion.transitionElapsedMs(System.nanoTime());
        if (el < 0) {
            return true;
        }
        return el < (motion.lastTransition() == 2 ? 300 : 950);
    }

    /** 目标刷新率与单帧纳秒间隔：壁纸引擎与预览均跟随屏幕刷新率（如 120Hz → 8.33ms）。 */
    private float targetRefreshHz = 120f;
    private long frameIntervalNanos = 8_333_333L;
    private long previewWaitMs = 8;
    private long lastFramePacingCheckMs;
    private boolean highFrameRateRequested;
    private int transitionFrames;
    private long transitionStartNanos;
    private final WallpaperSource wallpaperSource = new WallpaperSource();
    private int wallpaperTexture;
    private int homeWallpaperTexture;
    private int frameWallpaperTexture;

    LensRenderer(Context context, SurfaceHolder holder) {
        this(context, holder, false);
    }

    LensRenderer(Context context, SurfaceHolder holder, boolean homeSlot) {
        this.appContext = context.getApplicationContext();
        this.holder = holder;
        this.previewSurface = null;
        this.homeSlot = homeSlot;
        this.aodStyle = AodStyleSettings.getStyle(this.appContext);
        if (homeSlot) {
            this.pendingMode = Motion.HOME;
            this.pendingAnimate = false;
        } else {
            try {
                android.os.PowerManager pm = (android.os.PowerManager)
                        this.appContext.getSystemService(Context.POWER_SERVICE);
                android.app.KeyguardManager km = (android.app.KeyguardManager)
                        this.appContext.getSystemService(Context.KEYGUARD_SERVICE);
                boolean interactive = pm == null || pm.isInteractive();
                boolean locked = km != null && km.isKeyguardLocked();
                if (interactive && !locked) {
                    this.pendingMode = Motion.HOME;
                    this.pendingAnimate = false;
                }
            } catch (Throwable ignored) {}
        }
        this.thread = new Thread(this, homeSlot ? "QihangGL-Home" : "QihangGL-Lock");
    }

    /**
     * 预览用构造：把同一套渲染管线画到任意 Surface（TextureView 的 SurfaceTexture）。
     * 与壁纸引擎共用全部 shader/参数，所以预览与真机效果一致。
     */
    LensRenderer(Context context, Surface surface) {
        this.appContext = context.getApplicationContext();
        this.holder = null;
        this.previewSurface = surface;
        this.homeSlot = false;
        this.aodStyle = AodStyleSettings.getStyle(this.appContext);
        this.thread = new Thread(this, "QihangGLPreview");
    }

    void start() {
        if (previewSurface == null) {
            AodStyleSettings.addListener(styleListener);
        }
        thread.start();
    }

    void setMode(int mode, boolean animate) {
        pendingMode = mode;
        pendingAnimate = animate;
        pendingContinue = false;
        pendingSeekFrom = -1;
        pendingForceLockBeforeHome = false;
        pendingDirty = true;
        wake();
    }

    /** 锁屏扩散已播完后解锁到独立桌面壁纸：先对齐到 LOCK 全屏稳态，再播 500ms tr4 交叉渐变到 HOME。 */
    void transitionLockToHome() {
        pendingMode = Motion.HOME;
        pendingAnimate = true;
        pendingContinue = false;
        pendingSeekFrom = -1;
        pendingForceLockBeforeHome = true;
        pendingDirty = true;
        wake();
    }

    /** 从当前进度接着跑到 mode（不重启动画）。 */
    void continueTo(int mode) {
        pendingMode = mode;
        pendingSeekFrom = -1;
        pendingForceLockBeforeHome = false;
        pendingContinue = true;
        pendingDirty = true;
        wake();
    }

    /**
     * 先把状态对齐到「{@code from}→{@code to} 已播 {@code elapsedMs}」，再续跑到 {@code mode}。
     * 桌面 Engine 在锁屏期间不渲染、没有进度，靠它接手锁屏侧已播的那一段。
     */
    void seekThenContinue(int from, int to, long elapsedMs, int mode) {
        pendingMode = mode;
        pendingSeekFrom = from;
        pendingSeekTo = to;
        pendingSeekElapsedMs = elapsedMs;
        pendingForceLockBeforeHome = false;
        pendingContinue = true;
        pendingDirty = true;
        wake();
    }

    private volatile boolean fodVisible = true;

    void setFodVisible(boolean v) {
        if (this.fodVisible != v) {
            this.fodVisible = v;
            wake();
        }
    }

    boolean isFodVisible() {
        return fodVisible;
    }

    void setVisible(boolean v) {
        visible = v;
        wake();
    }

    boolean hasDrawn() {
        return drewFrame;
    }

    /** 预览用：指定读哪张照片（锁屏/桌面各自一张，互不干扰）。 */
    void setPreviewPhoto(String name) {
        setPreviewPhotos(name, null);
    }

    /** 预览用：同时指定锁屏照片与独立桌面照片（homeName 与 lockName 不同时启用过渡混合）。 */
    void setPreviewPhotos(String lockName, String homeName) {
        String normHome = (homeName != null && !homeName.equals(lockName)) ? homeName : null;
        if (!java.util.Objects.equals(previewPhoto, lockName)
                || !java.util.Objects.equals(previewHomePhoto, normHome)) {
            previewPhoto = lockName;
            previewHomePhoto = normHome;
            photoDirty = true;
            wake();
        }
    }

    /** 预览用：设置魅族动态壁纸的 OpenAlive variant ID（0 = 使用启航自定义照片管线）。 */
    void setPreviewVariant(int variant) {
        if (previewVariant != variant) {
            previewVariant = variant;
            pendingDirty = true;
            wake();
        }
    }

    void setAodStyle(int style) {
        if (aodStyle != style) {
            aodStyle = style;
            blurAppliedLast = false;
            pendingDirty = true;
            wake();
        }
    }

    void requestPhotoReload() {
        pendingPhotoReload = true;
        wake();
    }

    void requestPhotoReloadForce() {
        pendingPhotoReloadForce = true;
        wake();
    }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    void stop() {
        AodStyleSettings.removeListener(styleListener);
        running = false;
        wake();
        try {
            thread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================

    @Override
    public void run() {
        try {
            if (!initEgl()) {
                Log.e(TAG, "EGL 初始化失败");
                releaseEgl();
                return;
            }
            initFramePacing();
            if (!initGl()) {
                Log.e(TAG, "GL 初始化失败");
                releaseGl();
                releaseEgl();
                return;
            }
            while (running) {
                long frameStartNanos = System.nanoTime();
                if (previewSurface == null) {
                    int latestStyle = AodStyleSettings.getStyle(appContext);
                    if (latestStyle != aodStyle) {
                        aodStyle = latestStyle;
                        pendingDirty = true;
                    }
                }
                if (pendingPhotoReloadForce) {
                    pendingPhotoReloadForce = false;
                    pendingPhotoReload = false;
                    photoDirty = true;
                } else if (pendingPhotoReload) {
                    pendingPhotoReload = false;
                    if (previewPhoto != null || wallpaperSource.hasChanged(appContext)) {
                        photoDirty = true;
                    }
                }
                Surface surface = previewSurface != null ? previewSurface : holder.getSurface();
                boolean ok = surface != null && surface.isValid();
                // 1) 只要有新指令 (pendingDirty) 或过渡动画正在进行 (motion.animating())，
                //    对于当前可见槽位或锁屏/AOD 槽位 (!homeSlot)，必须立刻渲染并出帧；
                // 2) 若是桌面槽位 (homeSlot) 且当前不可见 (!visible) 且目标是 AOD，
                //    无需在后台不可见时逐帧渲染高斯模糊抢占锁屏槽 GPU，直接走 else 分支对齐状态即可。
                boolean dynActive = previewVariant > 0 && (!homeSlot || visible);
                // AOD 连续自转帧率仅属于锁屏槽（!homeSlot 或预览），且当屏下指纹图标亮起时维持自转：
                // 1) 桌面槽在 AOD 期间完全被锁屏图层遮挡，收缩动画结束后不应在后台持续自转消耗 GPU；
                // 2) 息屏期间指纹图标一旦消失，立即停止自转并省电休眠，指纹图标重新亮起时恢复 120 FPS 渲染。
                boolean isAodContinuous = !homeSlot
                        && aodStyle == AodStyleSettings.STYLE_SET_SAIL
                        && fodVisible
                        && motion != null
                        && motion.needsContinuousFrames();
                boolean needDraw = visible
                        || pendingDirty
                        || (motion != null && motion.animating())
                        || isAodContinuous
                        || dynActive
                        || (dynamicRenderer != null && dynamicRenderer.motion().active());
                if (ok && needDraw) {
                    try {
                        drawFrame(frameStartNanos);
                        drewFrame = true;
                        updateSurfaceFrameRateHint(surface);
                        countTransitionFrames(frameStartNanos);
                        countAodFrames(frameStartNanos);
                        if (previewSurface != null) {
                            logPreview(System.nanoTime());
                        }
                        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                            Log.w(TAG, "eglSwapBuffers 失败 0x" + Integer.toHexString(EGL14.eglGetError()));
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "drawFrame 异常 (跳过该帧保持渲染线程存活)", t);
                    }
                } else if (motion != null && previewVariant <= 0) {
                    applyPendingMode();
                    long now = System.nanoTime();
                    motion.advance(now);
                    transitionRunning = motion.animating();
                    updateSurfaceFrameRateHint(surface);
                }
                waitForNextFrame(frameStartNanos);
            }
        } catch (Throwable t) {
            Log.e(TAG, "渲染线程异常", t);
        } finally {
            releaseGl();
            releaseEgl();
        }
    }

    private long previewLogNanos;
    private int previewFrames;

    /** 预览：每秒打一行"角度 + 帧率"，用来核对自转速度与流畅度。 */
    private void logPreview(long nowNanos) {
        previewFrames++;
        if (previewLogNanos == 0) {
            previewLogNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - previewLogNanos;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        float sec = elapsed / 1_000_000_000f;
        Log.i(TAG, "预览 angle=" + (motion == null ? 0 : motion.photoAngle())
                + " fps=" + Math.round(previewFrames / sec));
        previewLogNanos = nowNanos;
        previewFrames = 0;
    }

    private boolean isHighRateActive() {
        if (previewVariant > 0 && (!homeSlot || visible)) {
            return true;
        }
        if (motion != null && motion.animating()) {
            return true;
        }
        return aodStyle == AodStyleSettings.STYLE_SET_SAIL
                && !homeSlot
                && fodVisible
                && motion != null && motion.needsContinuousFrames();
    }

    /**
     * 精确帧步进（Frame Pacing）：
     * 已开启 {@code eglSwapInterval(1)} 对齐硬件 VSync（120Hz 下每 8.33ms 一次）。
     * 在过渡动画（扩散/收缩）期间，直接交由 {@code eglSwapBuffers} 硬件 VSync 节流，
     * 避免 {@code Object.wait} 的内核定时器唤醒抖动（1~2ms）跨过 VSync 边沿造成掉帧；
     * 若驱动未阻塞（单帧耗时远小于帧周期），仅等待扣除 1.5ms 调度余量后的时间。
     */
    private void waitForNextFrame(long frameStartNanos) {
        if (pendingDirty) {
            return;
        }
        boolean inTransition = (motion != null && motion.animating())
                || (dynamicRenderer != null && dynamicRenderer.motion().active());
        if (inTransition) {
            refreshFramePacingIfNeeded();
            long spentNanos = System.nanoTime() - frameStartNanos;
            // 若 eglSwapBuffers 已按 VSync 阻塞（耗时 > 2.5ms），立即进入下一帧，完全贴合 120Hz VSync
            if (spentNanos >= 2_500_000L) {
                return;
            }
            long remainNanos = frameIntervalNanos - spentNanos - 1_500_000L;
            if (remainNanos <= 200_000L) {
                return;
            }
            long waitMs = remainNanos / 1_000_000L;
            int waitNs = (int) (remainNanos % 1_000_000L);
            synchronized (lock) {
                if (running && !pendingDirty) {
                    try {
                        lock.wait(waitMs, waitNs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return;
        }
        if (isHighRateActive()) {
            refreshFramePacingIfNeeded();
            long spentNanos = System.nanoTime() - frameStartNanos;
            long remainNanos = frameIntervalNanos - spentNanos - 1_000_000L;
            if (remainNanos <= 200_000L) {
                return;
            }
            long waitMs = remainNanos / 1_000_000L;
            int waitNs = (int) (remainNanos % 1_000_000L);
            synchronized (lock) {
                if (running && !pendingDirty) {
                    try {
                        lock.wait(waitMs, waitNs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return;
        }
        if (!homeSlot && currentMotionMode() == Motion.AOD && !fodVisible) {
            synchronized (lock) {
                if (running && !pendingDirty && !fodVisible) {
                    try {
                        lock.wait(5000L); // 指纹消失后 AOD 停止自转，进入长周期休眠待机省电
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return;
        }
        long idleMs = !visible ? 300L : 150L;
        synchronized (lock) {
            if (running && !pendingDirty) {
                try {
                    lock.wait(idleMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 当前实际运动引擎模式。 */
    int currentMotionMode() {
        return motion != null ? motion.mode : pendingMode;
    }

    Motion motion() {
        synchronized (lock) {
            return motion;
        }
    }

    /** 锁屏槽在进入 AOD 收缩前，确保内部状态对齐到桌面全屏稳态（以便完整播放 tr3 回缩）。 */
    void ensureHomeStateBeforeAod() {
        synchronized (lock) {
            if (motion != null) {
                motion.applyState(Motion.HOME, System.nanoTime());
            }
            pendingMode = Motion.HOME;
            pendingDirty = false;
        }
    }

    /**
     * 取屏幕目标刷新率（跟随屏幕刷新率，支持 120Hz / LTPO 峰值刷新率）：
     * 注意 LTPO 屏幕在息屏 DOZE 时 getRefreshRate() 可能暂报 30Hz/60Hz，
     * 因此结合 getSupportedModes() 最高刷新率与系统设置 user_refresh_rate / peak_refresh_rate，
     * 确保从息屏唤醒到锁屏扩散的第 1 帧就按屏幕高刷新率（120Hz）满帧运行。
     */
    private void initFramePacing() {
        updateFramePacing(true);
    }

    private void refreshFramePacingIfNeeded() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFramePacingCheckMs >= 1000L) {
            updateFramePacing(false);
        }
    }

    private void updateFramePacing(boolean logIt) {
        lastFramePacingCheckMs = android.os.SystemClock.uptimeMillis();
        float hz = 120f;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            appContext.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display d = dm == null ? null
                    : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) {
                float curHz = d.getRefreshRate();
                float maxSupportedHz = curHz;
                android.view.Display.Mode[] modes = d.getSupportedModes();
                if (modes != null) {
                    for (android.view.Display.Mode m : modes) {
                        if (m != null && m.getRefreshRate() > maxSupportedHz) {
                            maxSupportedHz = m.getRefreshRate();
                        }
                    }
                }
                // 读取用户在系统设置中选择的刷新率上限（如 60 / 90 / 120），未设置则取屏幕最高支持刷新率
                float userCapHz = maxSupportedHz;
                try {
                    android.content.ContentResolver cr = appContext.getContentResolver();
                    int ur = android.provider.Settings.System.getInt(cr, "user_refresh_rate", -1);
                    if (ur <= 0) {
                        ur = android.provider.Settings.Secure.getInt(cr, "user_refresh_rate", -1);
                    }
                    if (ur <= 0) {
                        ur = Math.round(android.provider.Settings.System.getFloat(cr, "peak_refresh_rate", -1f));
                    }
                    if (ur >= 60) {
                        userCapHz = Math.min(maxSupportedHz, (float) ur);
                    }
                } catch (Throwable ignored) {
                }
                // 当屏幕处于亮屏高刷或即将进行过渡动画时，避免被 DOZE 瞬态的 30/60Hz 卡住
                hz = Math.max(curHz, userCapHz);
                if (hz < 60f) {
                    hz = 120f;
                }
            }
        } catch (Throwable t) {
            if (logIt) {
                Log.w(TAG, "取刷新率失败，按 120Hz", t);
            }
        }
        targetRefreshHz = hz;
        frameIntervalNanos = Math.max(4_000_000L, Math.round(1_000_000_000.0 / hz));
        previewWaitMs = Math.max(4L, Math.round(1000f / hz));
        if (logIt) {
            Log.i(TAG, (previewSurface != null ? "[预览]" : (homeSlot ? "[桌面槽]" : "[锁屏槽]"))
                    + " 帧率跟随屏幕=" + hz + "Hz（单帧预算="
                    + String.format(java.util.Locale.US, "%.2f", frameIntervalNanos / 1_000_000f) + "ms）");
        }
    }

    /**
     * 在扩散/收缩过渡动画期间，通过 Android 11+ Surface.setFrameRate 主动通知 SurfaceFlinger
     * 将壁纸图层拉升至屏幕峰值刷新率（如 120Hz），防止 LTPO 从 DOZE 唤醒初期停留在 60Hz。
     */
    private void updateSurfaceFrameRateHint(Surface surface) {
        if (surface == null || !surface.isValid() || android.os.Build.VERSION.SDK_INT < 30) {
            return;
        }
        boolean wantHigh = (motion != null && motion.animating())
                || isHighRateActive()
                || (dynamicRenderer != null && dynamicRenderer.motion().active())
                || (previewVariant > 0 && (!homeSlot || visible));
        if (wantHigh == highFrameRateRequested) {
            return;
        }
        highFrameRateRequested = wantHigh;
        try {
            surface.setFrameRate(wantHigh ? targetRefreshHz : 0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (Throwable ignored) {
        }
    }

    /** 统计一次过渡实际出了多少帧与实际平均帧率（验证 120Hz 流畅度用）。 */
    private void countTransitionFrames(long nowNanos) {
        if (motion == null) {
            return;
        }
        if (motion.animating()) {
            if (transitionFrames == 0) {
                transitionStartNanos = nowNanos;
            }
            transitionFrames++;
        } else if (transitionFrames > 0) {
            long durMs = Math.max(1L, (nowNanos - transitionStartNanos) / 1_000_000L);
            float avgFps = transitionFrames * 1000f / durMs;
            Log.i(TAG, (previewSurface != null ? "[预览]" : (homeSlot ? "[桌面槽]" : "[锁屏槽]"))
                    + " 过渡帧数=" + transitionFrames + " 时长=" + durMs + "ms 平均帧率="
                    + String.format(java.util.Locale.US, "%.1f", avgFps)
                    + "fps（tr=" + motion.lastTransition() + " 目标=" + targetRefreshHz + "Hz）");
            transitionFrames = 0;
        }
    }

    private long aodFpsStartNanos;
    private int aodFpsFrames;

    private void countAodFrames(long nowNanos) {
        if (!isHighRateActive()) {
            aodFpsStartNanos = 0;
            aodFpsFrames = 0;
            return;
        }
        if (aodFpsStartNanos == 0) {
            aodFpsStartNanos = nowNanos;
            aodFpsFrames = 0;
        }
        aodFpsFrames++;
        long elapsed = nowNanos - aodFpsStartNanos;
        if (elapsed >= 2_000_000_000L) {
            float fps = aodFpsFrames * 1_000_000_000f / elapsed;
            Log.i(TAG, (previewSurface != null ? "[预览]" : (homeSlot ? "[桌面槽]" : "[锁屏槽]"))
                    + " AOD自转帧率=" + String.format(java.util.Locale.US, "%.1f", fps)
                    + "fps（目标=" + targetRefreshHz + "Hz）");
            aodFpsStartNanos = nowNanos;
            aodFpsFrames = 0;
        }
    }

    private void applyPendingMode() {
        if (motion == null) {
            return; // surface 尺寸还没拿到，第一帧时再建
        }
        // 只有「新请求」才重新起动画；否则每帧重设会把 animStart 一直推后，动画永不推进
        if (!pendingDirty) {
            return;
        }
        pendingDirty = false;
        long now = System.nanoTime();
        if (pendingForceLockBeforeHome) {
            pendingForceLockBeforeHome = false;
            motion.setMode(Motion.LOCK, false, now);
            motion.setMode(Motion.HOME, true, now);
            return;
        }
        if (pendingSeekFrom >= 0) {
            // 桌面 Engine 接手锁屏侧已播的扩散：先对齐进度，再续跑到目标态
            motion.seekTransition(pendingSeekFrom, pendingSeekTo, pendingSeekElapsedMs, now);
            pendingSeekFrom = -1;
        }
        if (pendingContinue) {
            pendingContinue = false;
            motion.continueTo(pendingMode, now);
        } else {
            boolean anim = pendingAnimate;
            motion.setMode(pendingMode, anim, now);
        }
    }

    // ---- EGL / GL ----

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            return false;
        }
        try {
            eglConfig = CosmicEgl.choose(eglDisplay, EGL14.EGL_WINDOW_BIT);
        } catch (Throwable ignored) {
            int[] attribs = {
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_NONE,
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                return false;
            }
            eglConfig = configs[0];
        }
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        // ★ 等待 SurfaceHolder / Surface 真正变为 valid 再创建 WindowSurface，
        //   避免 QihangWallpaper.onCreate() 刚启动线程时 Surface 尚未创建抛 IllegalArgumentException 崩溃进程！
        int[] surfAttribs = {EGL14.EGL_NONE};
        long deadline = System.currentTimeMillis() + 5000L;
        while (running && System.currentTimeMillis() < deadline) {
            Surface s = previewSurface != null ? previewSurface : (holder != null ? holder.getSurface() : null);
            if (s != null && s.isValid()) {
                try {
                    Object window = previewSurface != null ? previewSurface : holder;
                    eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, window, surfAttribs, 0);
                    if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                        break;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "等待有效 Surface 创建 eglWindowSurface: " + t.getMessage());
                }
            }
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            return false;
        }
        EGL14.eglSwapInterval(eglDisplay, 1);
        return true;
    }

    private boolean initGl() {
        quadPos = floats(QUAD_POS);
        quadUv = floats(QUAD_UV);

        String vertex = loadShader("shader/lens/lens_photo_vertex.glsl");
        String fragment = loadShader("shader/lens/lens_photo_frag.glsl");
        program = buildProgram(vertex, fragment);
        if (program == 0) {
            return false;
        }
        aPosition = GLES30.glGetAttribLocation(program, "a_position");
        aTexCoord = GLES30.glGetAttribLocation(program, "a_texCoord0");
        uProjectionView = GLES30.glGetUniformLocation(program, "u_projectionViewMatrix");
        uDecoratorMatrix = GLES30.glGetUniformLocation(program, "u_decorator_matrix");
        uTextureMatrix = GLES30.glGetUniformLocation(program, "u_texture_matrix");
        uPhoto = GLES30.glGetUniformLocation(program, "u_photo");
        uHomePhoto = GLES30.glGetUniformLocation(program, "u_home_photo");
        uHomeRatio = GLES30.glGetUniformLocation(program, "u_home_ratio");
        uDecorator = GLES30.glGetUniformLocation(program, "u_lens_decorator");
        uTranslation = GLES30.glGetUniformLocation(program, "u_translation");
        uLensScale = GLES30.glGetUniformLocation(program, "u_lens_scale");
        uAspectRatio = GLES30.glGetUniformLocation(program, "u_aspect_ratio");
        uAspectRatioReciprocal = GLES30.glGetUniformLocation(program, "u_aspect_ratio_reciprocal");
        uDistortion = GLES30.glGetUniformLocation(program, "u_distortion");
        uZoom = GLES30.glGetUniformLocation(program, "u_zoom");
        uDecoratorAlpha = GLES30.glGetUniformLocation(program, "u_decorator_alpha");
        uRippleRadius = GLES30.glGetUniformLocation(program, "u_ripple_radius");
        uRippleBoundary = GLES30.glGetUniformLocation(program, "u_ripple_boundary");
        uDarkStrength = GLES30.glGetUniformLocation(program, "u_dark_strength");

        decoratorTexture = loadTexture("shader/lens/lens_decorator.png", false);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        Log.i(TAG, "GL ready program=" + program + " decor=" + decoratorTexture);
        return true;
    }

    private void drawFrame(long nowNanos) {
        int[] size = new int[2];
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, size, 0);
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, size, 1);
        if (size[0] <= 0 || size[1] <= 0) {
            return;
        }
        boolean sizeChanged = size[0] != surfaceWidth || size[1] != surfaceHeight;
        if (sizeChanged) {
            surfaceWidth = size[0];
            surfaceHeight = size[1];
            Motion oldMotion = motion;
            motion = new Motion(surfaceWidth, surfaceHeight);
            if (oldMotion != null) {
                motion.copyFrom(oldMotion);
            } else {
                motion.setMode(pendingMode, false, nowNanos);
            }
            photoDirty = true;
        }
        int wantVariant = previewVariant;
        if (wantVariant > 0) {
            drawDynamicFrame(wantVariant, sizeChanged, nowNanos);
            return;
        } else if (dynamicRenderer != null) {
            closeDynamicRenderer();
        }
        if (motion == null) {
            return;
        }
        applyPendingMode();
        if (photoDirty) {
            photoDirty = false;
            loadWallpaperTexture(surfaceWidth, surfaceHeight);
        }

        motion.advance(nowNanos);
        transitionRunning = motion.animating();

        if (aodStyle == AodStyleSettings.STYLE_LEAVE_LIGHT) {
            if (liuguangPipeline == null) {
                liuguangPipeline = new LiuguangPipeline(appContext);
            }
            int homeTex = homeWallpaperTexture != 0 ? homeWallpaperTexture : wallpaperTexture;
            int frameTex = frameWallpaperTexture != 0 ? frameWallpaperTexture : wallpaperTexture;
            liuguangPipeline.render(wallpaperTexture, homeTex, frameTex, motion.homeRatio(), motion, surfaceWidth, surfaceHeight);
            long nowMs = nowNanos / 1_000_000L;
            if (nowMs - lastBeatMs > 10000) {
                lastBeatMs = nowMs;
                Log.i(TAG, String.format(java.util.Locale.US,
                        "[留光] draw mode=%d scale=%.3f rot=%.1f micros=%d alpha=%.2f homeRatio=%.2f tr=%d anim=%s",
                        motion.mode, motion.frameScale(), motion.frameRotation(), motion.frameMicros(),
                        motion.framePhotoAlpha(), motion.homeRatio(), motion.lastTransition(), motion.animating()));
            }
            return;
        }

        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        // ★ 圆内模糊 = 官方同款真·三级高斯（down → H → V → up），由 blur 通道驱动。
        // 只在"点亮息屏层"时做（熄屏稳态 + 过渡期间）；锁屏/桌面稳态不模糊（官方亦然）。
        // 之前用 mipmap LOD 近似，糊度与官方差一截，这里换成精确实现。
        float blurRadius = motion.blur();
        int photoTex = wallpaperTexture;
        boolean blurNow = blurRadius >= 2f && aodLayerVisible();
        if (blurNow != blurAppliedLast) {
            blurAppliedLast = blurNow;
            Log.i(TAG, "圆内模糊 " + (blurNow ? "on" : "off") + " blur=" + blurRadius
                    + " tr=" + motion.lastTransition() + " 过渡已进行="
                    + motion.transitionElapsedMs(System.nanoTime()) + "ms mode=" + pendingMode);
        }
        if (blurNow) {
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
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LOD, 0f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, decoratorTexture);
        int homeTex = homeWallpaperTexture != 0 ? homeWallpaperTexture : wallpaperTexture;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, homeTex);

        GLES30.glUniform1i(uPhoto, 0);
        GLES30.glUniform1i(uDecorator, 1);
        GLES30.glUniform1i(uHomePhoto, 2);
        GLES30.glUniform1f(uHomeRatio, homeWallpaperTexture != 0 ? motion.homeRatio() : 0f);
        GLES30.glUniformMatrix4fv(uProjectionView, 1, false, identity(), 0);
        GLES30.glUniformMatrix4fv(uTextureMatrix, 1, false, motion.photoMatrix, 0);
        GLES30.glUniformMatrix4fv(uDecoratorMatrix, 1, false, motion.decorMatrix, 0);
        GLES30.glUniform3f(uTranslation, motion.translateX(), motion.translateY(), 0f);
        GLES30.glUniform1f(uLensScale, motion.scale());
        GLES30.glUniform1f(uAspectRatio, aspect);
        GLES30.glUniform1f(uAspectRatioReciprocal, 1f / aspect);
        GLES30.glUniform1f(uDistortion, motion.scale() > 0.0001f ? motion.distortion() / motion.scale() : 0f);
        GLES30.glUniform1f(uZoom, zoomFor(motion.scale()));
        GLES30.glUniform1f(uDecoratorAlpha, motion.decorAlpha());
        GLES30.glUniform1f(uRippleRadius, motion.ripple());
        GLES30.glUniform1f(uRippleBoundary, 0.12f);
        GLES30.glUniform1f(uDarkStrength, 0f);

        if (!matrixLogged) {
            matrixLogged = true;
            Log.i(TAG, "uniforms scale=" + uLensScale + " zoom=" + uZoom + " aspect=" + uAspectRatio
                    + " recip=" + uAspectRatioReciprocal + " dist=" + uDistortion
                    + " trans=" + uTranslation + " texMat=" + uTextureMatrix);
        }

        GLES30.glBindVertexArray(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glEnableVertexAttribArray(aPosition);
        GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 0, quadPos);
        GLES30.glEnableVertexAttribArray(aTexCoord);
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, quadUv);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(aPosition);
        GLES30.glDisableVertexAttribArray(aTexCoord);

        long nowMs = nowNanos / 1_000_000L;
        if (nowMs - lastBeatMs > 10000) {
            lastBeatMs = nowMs;
            Log.i(TAG, String.format(java.util.Locale.US,
                    "draw mode=%d scale=%.3f blur=%.1f dist=%.2f zoom=%.3f tx=%.3f ty=%.3f angle=%.1f "
                            + "lod=%.2f(实用0) ratios=[%.2f,%.2f,%.2f] tr=%d anim=%s",
                    motion.mode, motion.scale(), motion.blur(), motion.distortion(), zoomFor(motion.scale()),
                    motion.translateX(), motion.translateY(), motion.photoAngle(), lodFor(motion.blur()),
                    motion.ratio[0], motion.ratio[1], motion.ratio[2], motion.lastTransition(),
                    motion.animating()));
        }
    }

    private long lastBeatMs;
    private boolean matrixLogged;

    /**
     * 官方 u_zoom = 1/(min(scale,0.5)*4) —— 不能改！
     *
     * <p>着色器里 {@code distortionUV = uv + lens_distortion(uv-center, α)}，α=0（锁屏/桌面）时
     * 等于 {@code 2*uv - center}，坐标被翻倍；官方的 zoom 在 scale≥0.5 时正好是 0.5，
     * 恰好把这次翻倍抵掉 → 锁屏/桌面才是 1:1 的全屏照片。夹到 ≥1 会让画面缩到中央 50% 并拉丝。
     */
    private static float zoomFor(float scale) {
        return 1f / (Math.min(scale, 0.5f) * 4f);
    }

    /** 官方 blur 半径 → mipmap LOD 近似（blur=22 → ≈1.83；blur<2 视为直通）。 */
    private static float lodFor(float blur) {
        if (blur < 2f) {
            return 0f;
        }
        return Math.min(3f, blur / 12f);
    }

    private static float[] identityCache;

    private static float[] identity() {
        if (identityCache == null) {
            identityCache = new float[16];
            android.opengl.Matrix.setIdentityM(identityCache, 0);
        }
        return identityCache;
    }

    // ---- 资源加载 ----

    private static FloatBuffer floats(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    private String loadShader(String path) {
        String src = readAssetText(path);
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#include")) {
                int s = t.indexOf('<');
                int e = t.indexOf('>');
                if (s > 0 && e > s) {
                    out.append(loadShader(t.substring(s + 1, e))).append('\n');
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private String readAssetText(String path) {
        try (InputStream in = appContext.getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.e(TAG, "读取 asset 失败 " + path, t);
            return "";
        }
    }

    private int buildProgram(String vertex, String fragment) {
        int vs = compile(GLES30.GL_VERTEX_SHADER, vertex, "vertex");
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment, "fragment");
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
            Log.e(TAG, "link 失败: " + GLES30.glGetProgramInfoLog(p));
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
            Log.e(TAG, tag + " 编译失败: " + GLES30.glGetShaderInfoLog(s));
            return 0;
        }
        return s;
    }

    private int loadTexture(String assetPath, boolean mipmap) {
        try (InputStream in = appContext.getAssets().open(assetPath)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) {
                return 0;
            }
            int tex = uploadBitmap(bmp, mipmap);
            bmp.recycle();
            return tex;
        } catch (Throwable t) {
            Log.e(TAG, "纹理加载失败 " + assetPath, t);
            return 0;
        }
    }

    private int uploadBitmap(Bitmap bmp, boolean mipmap) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                mipmap ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        if (mipmap) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        }
        return tex[0];
    }

    /**
     * 圆圈内容 = 当前桌面壁纸（{@link WallpaperSource}），按屏幕比例 cover 裁剪，
     * 保证官方矩阵链按原语义工作。取到的位图由 WallpaperSource 持有，这里不回收。
     */
    private int loadWallpaperTexture(int width, int height) {
        Bitmap src = previewPhoto != null ? PhotoStore.load(appContext, previewPhoto)
                : wallpaperSource.get(appContext, false);
        if (src == null || src.isRecycled()) {
            Log.w(TAG, "没有可用的壁纸图");
            return 0;
        }
        if (wallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{wallpaperTexture}, 0);
            wallpaperTexture = 0;
        }
        if (homeWallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{homeWallpaperTexture}, 0);
            homeWallpaperTexture = 0;
        }
        if (frameWallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{frameWallpaperTexture}, 0);
            frameWallpaperTexture = 0;
        }
        Bitmap cover = coverCrop(src, (float) height / width, 2048);
        int tex = uploadBitmap(cover, true);
        Log.i(TAG, "圆圈纹理 " + cover.getWidth() + "x" + cover.getHeight()
                + " 来源=" + wallpaperSource.origin());
        if (cover != src) {
            cover.recycle();
        }
        wallpaperTexture = tex;

        // 加载独立桌面照片纹理（不跟随锁屏时用于圆内/全屏交叉渐变）
        if (homeWallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{homeWallpaperTexture}, 0);
            homeWallpaperTexture = 0;
        }
        if (frameWallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{frameWallpaperTexture}, 0);
            frameWallpaperTexture = 0;
        }
        Bitmap homeSrc = previewPhoto != null
                ? (previewHomePhoto != null ? PhotoStore.load(appContext, previewHomePhoto) : null)
                : wallpaperSource.getHome(appContext, false);
        if (homeSrc != null && !homeSrc.isRecycled()) {
            Bitmap homeCover = coverCrop(homeSrc, (float) height / width, 2048);
            homeWallpaperTexture = uploadBitmap(homeCover, false);
            Log.i(TAG, "独立桌面纹理 " + homeCover.getWidth() + "x" + homeCover.getHeight());
            if (homeCover != homeSrc) {
                homeCover.recycle();
            }
        }

        // 加载留光相框专属照片纹理（PhotoStore.FRAME），若无则退回锁屏壁纸纹理
        Bitmap frameSrc = PhotoStore.load(appContext, PhotoStore.FRAME);
        if (frameSrc != null && !frameSrc.isRecycled()) {
            Bitmap frameCover = coverCrop(frameSrc, (float) height / width, 2048);
            frameWallpaperTexture = uploadBitmap(frameCover, false);
            Log.i(TAG, "留光相框专属纹理 " + frameCover.getWidth() + "x" + frameCover.getHeight());
            if (frameCover != frameSrc) {
                frameCover.recycle();
            }
        }
        return tex;
    }

    /** 居中裁剪成目标宽高比（hw = 高/宽），再缩放到 maxHeight 以内。 */
    private static Bitmap coverCrop(Bitmap src, float targetHw, int maxHeight) {
        int w = src.getWidth();
        int h = src.getHeight();
        float hw = (float) h / w;
        int cw = w;
        int ch = h;
        if (hw > targetHw) {
            ch = Math.max(1, Math.round(w * targetHw));
        } else {
            cw = Math.max(1, Math.round(h / targetHw));
        }
        int x = (w - cw) / 2;
        int y = (h - ch) / 2;
        Bitmap cropped;
        if (cw == w && ch == h) {
            cropped = src;
        } else {
            cropped = Bitmap.createBitmap(src, x, y, cw, ch);
        }
        if (cropped.getHeight() <= maxHeight) {
            return cropped;
        }
        int nh = maxHeight;
        int nw = Math.max(1, Math.round(cropped.getWidth() * (maxHeight / (float) cropped.getHeight())));
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, nw, nh, true);
        if (scaled != cropped && cropped != src) {
            cropped.recycle();
        }
        return scaled;
    }

    /** 使用 OpenAlive 的 WallpaperRenderer 实时渲染魅族 23 款动态壁纸三态与切换过渡。 */
    private void drawDynamicFrame(int wantVariant, boolean sizeChanged, long nowNanos) {
        try {
            if (dynamicRenderer == null || activeVariant != wantVariant || sizeChanged) {
                closeDynamicRenderer();
                activeVariant = wantVariant;
                boolean dark = (appContext.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                dynamicRenderer = WallpaperRenderer.create(appContext.getAssets(),
                        activeVariant, dark, surfaceWidth, surfaceHeight, pendingMode, false, this::wake);
                dynamicRenderer.motion().follow(0f, Motion.AOD_Y);
                dynamicRenderer.continuous(true);
                dynamicRenderer.visible(visible);
                if (dynamicRenderer instanceof StereoRenderer) {
                    sensorThread = new android.os.HandlerThread("QihangStereoGyro");
                    sensorThread.start();
                    stereoSensor = new StereoSensor(appContext,
                            new android.os.Handler(sensorThread.getLooper()),
                            (x, y, z, time) -> {
                                WallpaperRenderer r = dynamicRenderer;
                                if (r instanceof StereoRenderer) {
                                    ((StereoMotion) r.motion()).gyroscope(x, y, z, time);
                                    if (r.motion().active()) {
                                        wake();
                                    }
                                }
                            });
                    stereoSensor.setActive(visible);
                }
                pendingDirty = false;
                Log.i(TAG, "动态壁纸实时预览就绪 variant=" + activeVariant
                        + " " + surfaceWidth + "x" + surfaceHeight + " mode=" + pendingMode);
            }
            if (pendingDirty) {
                pendingDirty = false;
                dynamicRenderer.motion().change(pendingMode, pendingAnimate);
                dynamicRenderer.interaction();
            }
            dynamicRenderer.visible(visible);
            if (stereoSensor != null) {
                stereoSensor.setActive(visible);
            }
            dynamicRenderer.motion().advance(nowNanos, 1f);
            dynamicRenderer.render();
            transitionRunning = dynamicRenderer.motion().active();
        } catch (Throwable t) {
            Log.e(TAG, "动态壁纸实时预览异常 variant=" + wantVariant, t);
            closeDynamicRenderer();
            previewVariant = 0;
        }
    }

    private void closeDynamicRenderer() {
        if (stereoSensor != null) {
            try {
                stereoSensor.close();
            } catch (Throwable ignored) {
            }
            stereoSensor = null;
        }
        if (sensorThread != null) {
            try {
                sensorThread.quitSafely();
            } catch (Throwable ignored) {
            }
            sensorThread = null;
        }
        if (dynamicRenderer != null) {
            try {
                dynamicRenderer.close();
            } catch (Throwable ignored) {
            }
            dynamicRenderer = null;
        }
        activeVariant = 0;
    }

    // ---- 释放 ----

    private void releaseGl() {
        closeDynamicRenderer();
        if (liuguangPipeline != null) {
            liuguangPipeline.release();
            liuguangPipeline = null;
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program);
            program = 0;
        }
        if (wallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{wallpaperTexture}, 0);
            wallpaperTexture = 0;
        }
        if (homeWallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{homeWallpaperTexture}, 0);
            homeWallpaperTexture = 0;
        }
        if (frameWallpaperTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{frameWallpaperTexture}, 0);
            frameWallpaperTexture = 0;
        }
        if (decoratorTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{decoratorTexture}, 0);
            decoratorTexture = 0;
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }
}