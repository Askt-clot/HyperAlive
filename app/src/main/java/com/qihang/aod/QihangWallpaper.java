package com.qihang.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

/**
 * 启航壁纸服务 —— 支持 which=3 单槽位桌面+锁屏一体化三态无缝接力：
 *
 * <ul>
 *   <li>息屏（mode 0 = AOD）：向心收缩回缩，透镜光球或留光相框微光自转，圆外纯黑透出原生 AOD 时钟与指纹；</li>
 *   <li>锁屏（mode 1 = LOCK）：从 AOD 爆发扩散（tr0 950ms），全屏铺开壁纸与锁屏时钟；</li>
 *   <li>桌面（mode 2 = HOME）：
 *       <ul>
 *         <li>指纹从 AOD 直达：完整播放 tr2（1000ms 大圆爆发扩散）；</li>
 *         <li>锁屏解锁进桌面：锁屏已播完扩散则保持全屏稳态不重播，未播完则无缝接力续跑。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * 单 Surface、单 EGLContext 终生驻留，彻底根除跨槽位销毁重建黑闪！
 */
public class QihangWallpaper extends WallpaperService {

    private static final String TAG = Root.TAG;

    public static final String ACTION_MODE = "com.qihang.aod.CMD";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ANIMATE = "animate";
    public static final String EXTRA_STATE = "state";

    /** tr0（熄屏→锁屏）整段时长 = 150ms 起始延迟 + 最长通道 1000ms。 */
    private static final long LOCK_EXPAND_TOTAL_MS = 1150L;

    /** 跨状态共享标志 */
    private static volatile boolean sLockExpandShown;
    private static volatile long sLockExpandShownAt;
    private static volatile int sLockExpandTo = Motion.LOCK;
    private static volatile int sCurrentGlobalMode = Motion.LOCK;

    @Override
    public Engine onCreateEngine() {
        return new QihangEngine();
    }

    final class QihangEngine extends Engine {

        final boolean homeSlot = QihangWallpaper.this instanceof QihangHomeWallpaper;
        private LensRenderer renderer;
        private BroadcastReceiver receiver;
        private android.database.ContentObserver fsAodObserver;
        private Handler heartbeat;
        private Handler wakeWatchdog;
        private android.os.PowerManager powerManager;
        private android.os.PowerManager.WakeLock aodWakeLock;
        private volatile boolean lastInteractive = true;
        private volatile int mode = homeSlot ? Motion.HOME : Motion.LOCK;

        private void acquireAodWakeLock() {
            try {
                if (aodWakeLock == null && powerManager != null) {
                    aodWakeLock = powerManager.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK, "QihangAod:AodSpinWakeLock");
                    aodWakeLock.setReferenceCounted(false);
                }
                if (aodWakeLock != null && !aodWakeLock.isHeld()) {
                    aodWakeLock.acquire(15 * 60 * 1000L); // 15分钟安全超时保护
                    Log.i(TAG, "已获取 AOD 自转 WakeLock (PARTIAL_WAKE_LOCK)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "获取 AOD WakeLock 异常", t);
            }
        }

        private void releaseAodWakeLock() {
            try {
                if (aodWakeLock != null && aodWakeLock.isHeld()) {
                    aodWakeLock.release();
                    Log.i(TAG, "已释放 AOD 自转 WakeLock");
                }
            } catch (Throwable t) {
                Log.w(TAG, "释放 AOD WakeLock 异常", t);
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            if (!homeSlot && !isPreview()) {
                LspState.checkFallbackIfInactiveAsync(QihangWallpaper.this);
            }

            try {
                powerManager = (android.os.PowerManager)
                        QihangWallpaper.this.getSystemService(Context.POWER_SERVICE);
            } catch (Throwable t) {
                Log.w(TAG, "获取 PowerManager 失败", t);
            }
            boolean interactive = powerManager == null || powerManager.isInteractive();
            boolean keyguardLocked = false;
            try {
                android.app.KeyguardManager km = (android.app.KeyguardManager)
                        QihangWallpaper.this.getSystemService(Context.KEYGUARD_SERVICE);
                keyguardLocked = km != null && km.isKeyguardLocked();
            } catch (Throwable ignored) {}
            if (homeSlot || (interactive && !keyguardLocked)) {
                mode = Motion.HOME;
                sCurrentGlobalMode = Motion.HOME;
            } else if (!interactive) {
                mode = isFullScreenAod() ? Motion.LOCK : Motion.AOD;
                sCurrentGlobalMode = mode;
            } else {
                mode = Motion.LOCK;
                sCurrentGlobalMode = Motion.LOCK;
            }

            setOffsetNotificationsEnabled(false);
            renderer = new LensRenderer(QihangWallpaper.this, holder, homeSlot);
            renderer.setAodStyle(AodStyleSettings.getStyle(QihangWallpaper.this));
            renderer.setMode(mode, false);
            renderer.start();

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (AodStyleSettings.ACTION_STYLE_CHANGED.equals(action)) {
                        int style = intent.getIntExtra(AodStyleSettings.KEY_STYLE,
                                AodStyleSettings.getStyle(context));
                        Log.i(TAG, "收到息屏风格切换: " + style);
                        AodStyleSettings.updateCachedStyle(style);
                        if (renderer != null) {
                            renderer.setAodStyle(style);
                            renderer.requestPhotoReloadForce();
                        }
                        return;
                    }
                    if (intent.getBooleanExtra("reload_photo", false)) {
                        Log.i(TAG, "强制重新加载照片");
                        if (renderer != null) {
                            renderer.requestPhotoReloadForce();
                        }
                        return;
                    }
                    if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        Log.i(TAG, "设备解锁完毕 (USER_UNLOCKED)，重载壁纸照片与息屏风格");
                        if (renderer != null) {
                            renderer.setAodStyle(AodStyleSettings.getStyle(QihangWallpaper.this));
                            renderer.requestPhotoReloadForce();
                        }
                        return;
                    }
                    if (intent.hasExtra("fod_visible")) {
                        boolean fodVisible = intent.getBooleanExtra("fod_visible", true);
                        Log.i(TAG, "收到指纹图标显隐变化: fod_visible=" + fodVisible);
                        if (renderer != null) {
                            renderer.setFodVisible(fodVisible);
                        }
                        if (!homeSlot) {
                            if (!fodVisible && mode == Motion.AOD) {
                                releaseAodWakeLock();
                            } else if (fodVisible && mode == Motion.AOD) {
                                acquireAodWakeLock();
                            }
                        }
                        if (intent.getBooleanExtra("fod_changed", false)) {
                            return;
                        }
                    }
                    if (ACTION_MODE.equals(action)) {
                        int next = intent.getIntExtra(EXTRA_MODE, mode);
                        boolean animate = intent.getBooleanExtra(EXTRA_ANIMATE, true);
                        String state = intent.getStringExtra(EXTRA_STATE);
                        if (next == Motion.AOD && isFullScreenAod()) {
                            Log.i(TAG, "收到 AOD(mode=0) 但当前系统处于全屏AOD(和锁屏样式一致)，重定向为 LOCK(mode=1)");
                            next = Motion.LOCK;
                        }
                        Log.i(TAG, "收到三态指令: mode=" + next + " animate=" + animate + " state=" + state);
                        applyCommand(next, animate);
                    }
                }
            };

            IntentFilter filter = new IntentFilter(ACTION_MODE);
            filter.addAction(AodStyleSettings.ACTION_STYLE_CHANGED);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }

            try {
                fsAodObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        boolean fsAod = isFullScreenAod();
                        Log.i(TAG, "full_screen_aod_on 动态变更: " + fsAod);
                        boolean interactive = powerManager == null || powerManager.isInteractive();
                        if (!interactive) {
                            if (fsAod && mode == Motion.AOD) {
                                applyCommand(Motion.LOCK, false);
                            } else if (!fsAod && mode == Motion.LOCK) {
                                applyCommand(Motion.AOD, true);
                            }
                        }
                    }
                };
                getContentResolver().registerContentObserver(
                        android.provider.Settings.Secure.getUriFor("full_screen_aod_on"),
                        false,
                        fsAodObserver
                );
            } catch (Throwable t) {
                Log.w(TAG, "注册 full_screen_aod_on ContentObserver 异常", t);
            }

            heartbeat = new Handler(Looper.getMainLooper());
            heartbeat.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (renderer != null) {
                        renderer.requestPhotoReload();
                    }
                    if (!homeSlot && !isPreview()) {
                        LspState.checkFallbackIfInactiveAsync(QihangWallpaper.this);
                    }
                    heartbeat.postDelayed(this, 30000);
                }
            }, 30000);

            // ★ 亮屏看门狗：MIUI Greezer 会缓存/丢弃熄屏期间的广播，亮屏时壁纸可能收不到 mode=1，
            //   表现为「息屏一段时间后亮屏没有光球扩散」。这里直接轮询电源交互态作为兜底，
            //   不依赖广播与可见性回调。
            try {
                powerManager = (android.os.PowerManager)
                        QihangWallpaper.this.getSystemService(Context.POWER_SERVICE);
            } catch (Throwable t) {
                Log.w(TAG, "获取 PowerManager 失败", t);
            }
            lastInteractive = powerManager == null || powerManager.isInteractive();
            wakeWatchdog = new Handler(Looper.getMainLooper());
            wakeWatchdog.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean interactive = powerManager != null && powerManager.isInteractive();
                        if (interactive && isAwakeForFallback() && (!lastInteractive
                                || (renderer != null && renderer.currentMotionMode() == Motion.AOD))) {
                            onScreenOnDetected();
                        }
                        lastInteractive = interactive;
                    } catch (Throwable t) {
                        Log.w(TAG, "亮屏看门狗异常", t);
                    }
                    wakeWatchdog.postDelayed(this, 500);
                }
            }, 500);

            Log.i(TAG, "QihangWallpaper 单槽位一体化引擎已创建");
        }

        private boolean isFullScreenAod() {
            try {
                return android.provider.Settings.Secure.getInt(
                        getContentResolver(),
                        "full_screen_aod_on",
                        0
                ) == 1;
            } catch (Throwable t) {
                return false;
            }
        }

        private void applyCommand(int next, boolean animate) {
            if (renderer == null) return;
            if (next == Motion.AOD && isFullScreenAod()) {
                Log.i(TAG, "applyCommand 拦截 AOD(0)，由于 full_screen_aod_on=1 重定向为 LOCK(1)");
                next = Motion.LOCK;
            }
            long now = android.os.SystemClock.uptimeMillis();
            int curMotion = renderer.currentMotionMode();
            boolean changed = (next != mode) || (curMotion != next);

            if (!changed) {
                if (next == Motion.LOCK && sLockExpandShownAt == 0) {
                    sLockExpandShownAt = now;
                }
                return;
            }

            int prevGlobalMode = sCurrentGlobalMode;
            if (next == Motion.AOD) {
                if (!homeSlot) {
                    acquireAodWakeLock();
                }
                // 进入息屏 AOD：若前态是桌面，确保以全屏为起点平滑回缩 (tr3)
                if (mode == Motion.HOME || prevGlobalMode == Motion.HOME) {
                    renderer.ensureHomeStateBeforeAod();
                }
                renderer.requestPhotoReload();

                sCurrentGlobalMode = Motion.AOD;
                sLockExpandShown = false;
                sLockExpandShownAt = 0;
                sLockExpandTo = Motion.LOCK;
                if (!homeSlot) {
                    // 锁屏槽在 AOD 期间是屏幕上的唯一显示窗口，确保可见性前置激活，消除从桌面直接息屏时系统不补发可见性回调导致的脏读
                    renderer.setVisible(true);
                }
            } else if (next == Motion.LOCK) {
                releaseAodWakeLock();
                if (homeSlot) {
                    // ★ 桌面独立槽位永远不需要切换为锁屏壁纸：
                    //   若当前在 AOD 缩圆态，保持 AOD 缩圆等待解锁；若已在 HOME 稳态，维持 HOME 稳态。
                    //   严禁桌面壁纸响应 mode=1，杜绝桌面壁纸被误设为锁屏壁纸或干扰扩散标记！
                    Log.i(TAG, "桌面独立槽位忽略 mode=1 锁屏指令，保持当前状态");
                    return;
                }
                boolean interactive = powerManager == null || powerManager.isInteractive();
                boolean keyguardLocked = false;
                try {
                    android.app.KeyguardManager km = (android.app.KeyguardManager)
                            QihangWallpaper.this.getSystemService(Context.KEYGUARD_SERVICE);
                    keyguardLocked = km != null && km.isKeyguardLocked();
                } catch (Throwable ignored) {}
                // 仅当设备确实已解锁处于桌面交互态（!keyguardLocked）或全局模式已进入 HOME 时，忽略残留的 mode=1 指令；
                // 若当前处于锁屏（keyguardLocked=true）或非交互态，锁屏引擎必须响应 mode=1 切换回锁屏壁纸与锁屏过渡！
                if ((interactive && !keyguardLocked && !isFullScreenAod())
                        || sCurrentGlobalMode == Motion.HOME) {
                    Log.i(TAG, "屏幕处于已解锁桌面态(HOME)，忽略残留 mode=1 锁屏指令");
                    return;
                }
                if (isFullScreenAod()) {
                    animate = false;
                }
                sCurrentGlobalMode = Motion.LOCK;
                if (!sLockExpandShown) {
                    sLockExpandShown = true;
                    sLockExpandShownAt = now;
                    sLockExpandTo = Motion.LOCK;
                }
            } else if (next == Motion.HOME) {
                releaseAodWakeLock();
                sCurrentGlobalMode = Motion.HOME;
                boolean isFromLock = (sLockExpandShown && sLockExpandTo == Motion.LOCK && sLockExpandShownAt > 0)
                        || (curMotion == Motion.LOCK);
                if (!isFromLock) {
                    // 指纹直接从 AOD 息屏直达桌面（未经过锁屏 mode=1 扩散）
                    sLockExpandShown = true;
                    sLockExpandShownAt = now;
                    sLockExpandTo = Motion.HOME;
                }
            }

            mode = next;

            if (next == Motion.HOME) {
                if (sLockExpandTo == Motion.HOME) {
                    // 指纹从 AOD 直达桌面 → 播放 tr2 爆发扩散动画
                    Log.i(TAG, "指纹从 AOD 直达桌面 → 播放 tr2 爆发扩散动画 (curMotion=" + curMotion + ")");
                    renderer.setMode(Motion.HOME, animate);
                    return;
                }
                // 从锁屏解锁进桌面
                long elapsed = sLockExpandShownAt > 0 ? Math.max(0, now - sLockExpandShownAt) : 0;
                boolean isAnimating = renderer.isTransitionRunning();
                boolean fromLock = sLockExpandShown && sLockExpandTo == Motion.LOCK
                        && sLockExpandShownAt > 0;
                if (TransitionPolicy.canResumeLock(homeSlot, curMotion, fromLock, elapsed, isAnimating)) {
                    if (homeSlot) {
                        Log.i(TAG, "桌面独立槽位接手锁屏扩散（已 " + elapsed + "ms）→ seek 后续跑");
                        renderer.seekThenContinue(Motion.AOD, Motion.LOCK, elapsed, Motion.HOME);
                    } else {
                        Log.i(TAG, "单槽一体化壁纸：锁屏扩散仍在播（已 " + elapsed + "ms）→ 保持原有扩散轨迹自然播完进入桌面");
                        renderer.continueTo(Motion.HOME);
                    }
                    return;
                }
                // 锁屏扩散已在锁屏播完
                if (LastSet.isHomeDistinct(QihangWallpaper.this) && animate) {
                    Log.i(TAG, "锁屏扩散已在锁屏播完（" + elapsed + "ms），独立桌面壁纸 → 播 500ms 交叉渐变(tr4)");
                    renderer.transitionLockToHome();
                } else {
                    Log.i(TAG, "锁屏扩散已在锁屏播完（" + elapsed + "ms）→ 直接进桌面（不重播）");
                    renderer.setMode(Motion.HOME, false);
                }
                return;
            }

            renderer.setMode(next, animate);
        }

        /** 屏幕点亮时的扩散兜底：只要渲染器仍停在 AOD，就补发扩散。 */
        private boolean isAwakeForFallback() {
            if (powerManager != null && !powerManager.isInteractive()) return false;
            try {
                android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                        getSystemService(Context.DISPLAY_SERVICE);
                android.view.Display display = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (display == null || display.getState() != android.view.Display.STATE_ON) return false;
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
                long sleepAt = Long.parseLong((String) get.invoke(null, "sys.qihang.sleep_at", "0"));
                long wakeAt = Long.parseLong((String) get.invoke(null, "sys.qihang.wake_at", "0"));
                return sleepAt <= 0 || sleepAt < wakeAt;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void onScreenOnDetected() {
            if (renderer == null || !isAwakeForFallback()) return;
            if (renderer.currentMotionMode() == Motion.AOD) {
                boolean keyguardLocked = false;
                try {
                    android.app.KeyguardManager km = (android.app.KeyguardManager)
                            QihangWallpaper.this.getSystemService(Context.KEYGUARD_SERVICE);
                    keyguardLocked = km != null && km.isKeyguardLocked();
                } catch (Throwable ignored) {}
                if (!keyguardLocked) {
                    Log.i(TAG, "亮屏看门狗兜底：设备已解锁且壁纸仍停留在 AOD，补发 HOME 扩散");
                    applyCommand(Motion.HOME, true);
                } else {
                    if (!homeSlot) {
                        Log.i(TAG, "亮屏看门狗兜底：设备处于锁屏且锁屏槽停留在 AOD，补发锁屏扩散");
                        applyCommand(Motion.LOCK, true);
                    }
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (renderer != null) {
                renderer.setVisible(visible);
                // 唤醒亮屏且仍停在 AOD 态时，补发锁屏/桌面扩散（不再要求引擎侧 mode==LOCK：
                // 熄屏期间广播可能被 Greezer 丢弃，mode 字段会停留在 AOD）。
                // 但必须确认屏幕确实处于交互态：熄屏瞬间系统会让壁纸可见性 true→false 抖动一次，
                // 若不校验，锁屏槽会在息屏过程中被误扩散到 LOCK 并一直停在 LOCK（AOD 无光球）。
                boolean interactive = powerManager == null || powerManager.isInteractive();
                if (visible && interactive && isAwakeForFallback() && renderer.currentMotionMode() == Motion.AOD) {
                    boolean keyguardLocked = false;
                    try {
                        android.app.KeyguardManager km = (android.app.KeyguardManager)
                                QihangWallpaper.this.getSystemService(Context.KEYGUARD_SERVICE);
                        keyguardLocked = km != null && km.isKeyguardLocked();
                    } catch (Throwable ignored) {}
                    if (!keyguardLocked) {
                        Log.i(TAG, "壁纸唤醒亮屏且设备已解锁，立即补发 HOME 扩散");
                        applyCommand(Motion.HOME, true);
                    } else {
                        if (!homeSlot) {
                            Log.i(TAG, "锁屏槽唤醒亮屏且停留在 AOD 态，补发锁屏扩散");
                            applyCommand(Motion.LOCK, true);
                        }
                    }
                } else if (visible && !interactive && renderer.currentMotionMode() == Motion.AOD) {
                    Log.i(TAG, "壁纸可见但屏幕非交互（熄屏抖动），忽略补发扩散");
                }
            }
            if (visible && !homeSlot && !isPreview()) {
                LspState.checkFallbackIfInactiveAsync(QihangWallpaper.this);
            }
            Log.i(TAG, "壁纸可见性变化: " + visible);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            Log.i(TAG, "Surface 尺寸变化: " + width + "x" + height);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            Log.i(TAG, "Surface created（GL 初始化就绪）");
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (renderer != null) {
                renderer.setVisible(false);
            }
            super.onSurfaceDestroyed(holder);
            Log.i(TAG, "Surface destroyed");
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (extras != null && extras.containsKey(EXTRA_MODE) && renderer != null) {
                int next = extras.getInt(EXTRA_MODE, mode);
                boolean animate = extras.getBoolean(EXTRA_ANIMATE, true);
                Log.i(TAG, "onCommand mode=" + next + " action=" + action);
                applyCommand(next, animate);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseAodWakeLock();
            if (receiver != null) {
                try {
                    unregisterReceiver(receiver);
                } catch (Throwable ignored) {}
                receiver = null;
            }
            if (fsAodObserver != null) {
                try {
                    getContentResolver().unregisterContentObserver(fsAodObserver);
                } catch (Throwable ignored) {}
                fsAodObserver = null;
            }
            if (heartbeat != null) {
                heartbeat.removeCallbacksAndMessages(null);
            }
            if (wakeWatchdog != null) {
                wakeWatchdog.removeCallbacksAndMessages(null);
                wakeWatchdog = null;
            }
            if (renderer != null) {
                renderer.stop();
                renderer = null;
            }
            Log.i(TAG, "QihangWallpaper 引擎已销毁");
        }
    }
}
