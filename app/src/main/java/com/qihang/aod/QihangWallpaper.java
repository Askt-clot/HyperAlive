package com.qihang.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

/**
 * 本模块自己的壁纸：占用**锁屏槽位**（{@code which=2}）——澎湃 OS 息屏时显示的就是锁屏槽位那张壁纸。
 *
 * <ul>
 *   <li>息屏（mode 0）：启航圆透镜，圆外纯黑；</li>
 *   <li>锁屏（mode 1）：同一张照片铺满全屏；</li>
 *   <li>桌面（mode 2）：本模块不占桌面槽位（桌面留给魅族原生 Alive），这里仅作为广播占位。</li>
 * </ul>
 * 三态由 SystemUI 侧 {@link XposedInit} 广播 {@value XposedInit#ACTION_MODE} 驱动。
 */
public class QihangWallpaper extends WallpaperService {

    private static final String TAG = Root.TAG;
    /** tr0（熄屏→锁屏）整段时长 = 150ms 起始延迟 + 最长通道 1000ms（decoratorAngle）。 */
    private static final long LOCK_EXPAND_TOTAL_MS = 1150;

    /**
     * 「自上次熄屏以来，锁屏那段扩散是否已经启动」——**两槽 Engine 共享**（同进程 static）：
     * 锁屏期间桌面 Engine（{@link QihangHomeWallpaper}）忽略 mode=1 保持在 AOD，
     * 只有锁屏 Engine（{@link QihangWallpaper}）真正收到并播了 mode=1；
     * 解锁进桌面时桌面 Engine 读这里的共享状态，才知道「锁屏已经扩过了 / 播到第几毫秒」，
     * 从而接力续跑或直接切全屏，避免二次重播。
     */
    private static volatile boolean sLockExpandShown;
    private static volatile long sLockExpandShownAt;
    private static volatile int sLockExpandFrom = Motion.AOD;
    private static volatile int sLockExpandTo = Motion.LOCK;
    private static volatile boolean sPendingAodCollapse;
    /** 全局跨槽位记录当前所处模式（HOME=2, LOCK=1, AOD=0）。 */
    private static volatile int sCurrentGlobalMode = Motion.LOCK;
    private static volatile QihangEngine sLockEngine;
    private static volatile QihangEngine sHomeEngine;

    @Override
    public Engine onCreateEngine() {
        return new QihangEngine();
    }

    private final class QihangEngine extends Engine {

        /** true = 桌面槽位（QihangHomeWallpaper, which=1）；false = 锁屏/息屏槽位（QihangWallpaper, which=2）。 */
        private final boolean homeSlot = QihangWallpaper.this instanceof QihangHomeWallpaper;
        private LensRenderer renderer;
        private BroadcastReceiver receiver;
        private Handler heartbeat;
        private android.hardware.display.DisplayManager displayManager;
        private android.hardware.display.DisplayManager.DisplayListener displayListener;
        private boolean wasDozing;
        private volatile int mode = homeSlot ? Motion.HOME : Motion.LOCK;
        /** 最近一次**权威指令**（SystemUI 广播 / system_server 前馈 / onCommand）要求的目标态。 */
        private volatile int lastCmdMode = -1;
        private volatile long lastCmdMs = 0;
        /** 最近一次进入 AOD 的时刻（display 直驱防抖用）。 */
        private volatile long aodSinceMs;

        private boolean isLandscape() {
            try {
                if (displayManager != null) {
                    android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                    if (d != null) {
                        int rot = d.getRotation();
                        if (rot == 1 || rot == 3) {
                            return true;
                        }
                        android.graphics.Point size = new android.graphics.Point();
                        d.getRealSize(size);
                        if (size.x > size.y) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private boolean isLandscapeSleep() {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                String val = (String) c.getMethod("get", String.class, String.class).invoke(null, "sys.qihang.sleep_land", "0");
                return "1".equals(val);
            } catch (Throwable ignored) {
            }
            return false;
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            if (homeSlot) {
                sHomeEngine = this;
            } else {
                sLockEngine = this;
            }
            setOffsetNotificationsEnabled(false);
            renderer = new LensRenderer(QihangWallpaper.this, holder, homeSlot);
            renderer.setAodStyle(AodStyleSettings.getStyle(QihangWallpaper.this));
            renderer.setMode(mode, false);
            renderer.start();

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (AodStyleSettings.ACTION_STYLE_CHANGED.equals(action)) {
                        int style = intent.getIntExtra(AodStyleSettings.KEY_STYLE,
                                AodStyleSettings.getStyle(context));
                        Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]") + " 收到息屏风格切换: " + style);
                        renderer.setAodStyle(style);
                        return;
                    }
                    if (intent.getBooleanExtra("reload_photo", false)) {
                        Log.i(TAG, "重新加载照片");
                        renderer.requestPhotoReloadForce();
                        return;
                    }
                    int next = intent.getIntExtra(XposedInit.EXTRA_MODE, mode);
                    boolean animate = intent.getBooleanExtra(XposedInit.EXTRA_ANIMATE, true);
                    String state = intent.getStringExtra(XposedInit.EXTRA_STATE);
                    Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]")
                            + " 壁纸收到 mode=" + next + " state=" + state + " animate=" + animate);
                    if (next == Motion.AOD && mode != Motion.AOD) {
                        // 刚进入息屏时同步一次「当前桌面壁纸」，避免同轮 power+doze 重复重载纹理
                        renderer.requestPhotoReload();
                    }
                    // 桌面槽位（QihangHomeWallpaper）在锁屏期间忽略 mode=1，保持在 AOD 态，
                    // 等解锁收到 mode=2 时才能根据 sLockExpandShown 续跑或直接落全屏；
                    // 若当前已经处于或正在前往桌面态（HOME），两槽都必须忽略 mode=1 锁屏指令，
                    // 避免被解锁唤醒时伴随的异步 wake / KEYGUARD 广播打退回锁屏！
                    if (next == Motion.LOCK) {
                        if (homeSlot || sCurrentGlobalMode == Motion.HOME || mode == Motion.HOME) {
                            Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]")
                                    + " 当前处于/正在前往桌面态(HOME)，忽略 mode=1 锁屏指令");
                            return;
                        }
                    }
                    applyCommand(next, animate);
                }
            };
            IntentFilter filter = new IntentFilter(XposedInit.ACTION_MODE);
            filter.addAction(AodStyleSettings.ACTION_STYLE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }

            // ★ 即亮即动：自己监听显示状态。系统从 DOZE 变成 ON 的那一刻就切到锁屏态，
            //   交给 SystemUI 的 ScrimController 回调会晚约 1 秒（实测）。
            try {
                displayManager = (android.hardware.display.DisplayManager)
                        getSystemService(Context.DISPLAY_SERVICE);
                if (displayManager != null) {
                    displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                        @Override
                        public void onDisplayAdded(int displayId) {
                        }

                        @Override
                        public void onDisplayRemoved(int displayId) {
                        }

                        @Override
                        public void onDisplayChanged(int displayId) {
                            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                                applyDisplayState();
                            }
                        }
                    };
                    displayManager.registerDisplayListener(displayListener,
                            new Handler(Looper.getMainLooper()));
                    applyDisplayState();
                }
            } catch (Throwable t) {
                Log.w(TAG, "注册显示状态监听失败", t);
            }

            heartbeat = new Handler(Looper.getMainLooper());
            heartbeat.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]")
                            + " beat mode=" + mode + " visible=" + isVisible()
                            + " surface=" + (getSurfaceHolder().getSurface() != null
                            && getSurfaceHolder().getSurface().isValid()));
                    if (renderer != null) {
                        // 壁纸被换掉也能在下一次心跳跟上（内部按 stat 指纹判断，未变不重载）
                        renderer.requestPhotoReload();
                    }
                    heartbeat.postDelayed(this, 30000);
                }
            }, 5000);
            Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]") + " 启航壁纸引擎已创建");
        }

        /**
         * 三态指令的统一入口（广播 / onCommand / 显示直驱都走这里）。
         *
         * <p>扩散**只播一次**：
         * <ul>
         *   <li>熄屏 → 锁屏 → 桌面：锁屏那段把扩散播**完**了就直接进桌面（不重播）；
         *       还在播就**接着当前进度续跑**（桌面 Engine 锁屏期间不渲染，先对齐进度）；</li>
         *   <li>熄屏 →（不经锁屏）→ 桌面：完整播一遍 tr2。</li>
         * </ul>
         */
        private void applyCommand(int next, boolean animate) {
            if (renderer == null) {
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (next != Motion.AOD) {
                sPendingAodCollapse = false;
            }
            int curMotion = renderer.currentMotionMode();
            boolean changed = (next != mode) || (curMotion != next);
            if (!changed) {
                if (next == Motion.LOCK && sLockExpandShownAt == 0) {
                    sLockExpandShownAt = now;
                }
                return;
            }
            if (next == Motion.AOD) {
                if (!homeSlot) {
                    if (isLandscape() || isLandscapeSleep()) {
                        Log.i(TAG, "[锁屏槽] 横屏熄屏：推迟回缩动画，等待切回竖屏 AOD 点亮时播放");
                        mode = next;
                        lastCmdMode = next;
                        lastCmdMs = now;
                        aodSinceMs = now;
                        sPendingAodCollapse = true;
                        sCurrentGlobalMode = Motion.AOD;
                        sLockExpandShown = false;
                        sLockExpandShownAt = 0;
                        sLockExpandFrom = Motion.AOD;
                        sLockExpandTo = Motion.LOCK;
                        return;
                    }
                    if (mode == Motion.HOME || sCurrentGlobalMode == Motion.HOME) {
                        if (sHomeEngine != null && sHomeEngine.renderer != null
                                && sHomeEngine.renderer.motion() != null
                                && sHomeEngine.renderer.motion().scale() > 0.36f) {
                            // 桌面槽存在且处于桌面态或扩散途中：无缝继承桌面槽当前的实际尺寸与进度！
                            // 这样从中途按电源键熄屏时，收缩动画直接从当前实际大小平滑回缩，绝不强行跳跃到 1.24f 全屏闪现壁纸！
                            renderer.copyStateFrom(sHomeEngine.renderer);
                        } else {
                            renderer.ensureHomeStateBeforeAod();
                        }
                    }
                    // 若前态是锁屏（mode == Motion.LOCK），绝不覆盖锁屏槽状态！
                    // 锁屏槽自身已处于 LOCK 态（scale=1.24f），直接播放 tr1 平滑回缩动画（650ms）到 AOD！
                }
                sCurrentGlobalMode = Motion.AOD;
                sLockExpandShown = false;     // 又熄屏了 → 下一轮重新计
                sLockExpandShownAt = 0;
                sLockExpandFrom = Motion.AOD;
                sLockExpandTo = Motion.LOCK;
            } else if (next == Motion.LOCK) {
                sCurrentGlobalMode = Motion.LOCK;
                if (!homeSlot && !sLockExpandShown) {
                    sLockExpandShown = true;
                    sLockExpandShownAt = now; // tr0 从这里开始计时（两槽共享）
                    sLockExpandFrom = Motion.AOD;
                    sLockExpandTo = Motion.LOCK;
                }
            } else if (next == Motion.HOME) {
                sCurrentGlobalMode = Motion.HOME;
                if (!sLockExpandShown) {
                    // 指纹直接从 AOD 息屏解锁进桌面（未经过锁屏 mode=1）：
                    // 标记为 AOD→HOME 直达，让仍在顶层可见的锁屏槽与桌面槽同步播放 tr2（1000ms 爆发扩散）！
                    sLockExpandShown = true;
                    sLockExpandShownAt = now;
                    sLockExpandFrom = Motion.AOD;
                    sLockExpandTo = Motion.HOME;
                }
            }
            mode = next;
            lastCmdMode = next;
            lastCmdMs = now;
            if (next == Motion.AOD) {
                aodSinceMs = now;
            }
            if (!homeSlot && next == Motion.HOME) {
                if (sLockExpandTo == Motion.HOME) {
                    // 指纹从 AOD 直达桌面期间，澎湃 OS 锁屏壁纸层（which=2）在解锁过渡的 ~1000ms 内仍处于最上层可见！
                    // 必须由锁屏槽同步播放 tr2 爆发扩散，绝不能直接 ensureHomeStateBeforeAod() 瞬切全屏导致扩散动画消失！
                    Log.i(TAG, "[锁屏槽] 指纹从 AOD 直达桌面 → 播放 tr2 大圆扩散动画");
                    renderer.setMode(Motion.HOME, animate);
                    return;
                }
                // 正常从锁屏（mode=1）解锁进桌面时，锁屏槽摆到 HOME 全屏稳态，
                // 保证下次用户在桌面上按电源键熄屏时，锁屏槽以全屏为起点，完整播放 tr3 回缩动画！
                renderer.ensureHomeStateBeforeAod();
                return;
            }
            if (next == Motion.HOME && homeSlot) {
                if (sLockExpandShown && sLockExpandTo != Motion.HOME) {
                    long elapsed = now - sLockExpandShownAt;
                    if (elapsed >= LOCK_EXPAND_TOTAL_MS) {
                        if (LastSet.isHomeDistinct(QihangWallpaper.this) && animate) {
                            Log.i(TAG, "锁屏扩散已播完（" + elapsed + "ms），桌面独立壁纸 → 播 500ms 交叉渐变(tr4)");
                            renderer.transitionLockToHome();
                        } else {
                            Log.i(TAG, "锁屏扩散已播完（" + elapsed + "ms）→ 直接进桌面（不重播）");
                            renderer.setMode(Motion.HOME, false);
                        }
                    } else {
                        Log.i(TAG, "锁屏扩散仍在播（已 " + elapsed + "ms）→ 接着播到桌面");
                        renderer.seekThenContinue(Motion.AOD, Motion.LOCK, elapsed, Motion.HOME);
                    }
                } else {
                    // 指纹直接从 AOD 息屏解锁进桌面（未经过锁屏 mode=1）：
                    // 以当前 AOD 缩放为起点完整播放 tr2（1000ms 大圆爆发扩散）！
                    Log.i(TAG, "[桌面槽] 指纹直达桌面 → 播放 tr2 大圆扩散动画");
                    renderer.setMode(Motion.HOME, animate);
                }
                return;
            }
            renderer.setMode(next, animate);
        }

        /**
         * 按显示状态直驱三态：
         * <ul>
         *   <li>屏幕进入 DOZE/DOZE_SUSPEND/OFF → 息屏态；</li>
         *   <li>屏幕**从 DOZE/OFF 真正跃迁回 ON**（{@code wasDozing && !dozing}）且锁屏槽当前还在息屏态 → 锁屏态。</li>
         * </ul>
         * 注意：全屏 AOD 下熄屏头 5.6s 内 displayState 一直保持 STATE_ON(2)，
         * 只认 {@code wasDozing -> !dozing} 边沿才不会在全屏 AOD 渐暗期间误触发，也无需 2.5s 死锁。
         */
        private void applyDisplayState() {
            if (renderer == null || displayManager == null) {
                return;
            }
            android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d == null) {
                return;
            }
            int st = d.getState();
            boolean dozing = st == android.view.Display.STATE_DOZE
                    || st == android.view.Display.STATE_DOZE_SUSPEND
                    || st == android.view.Display.STATE_OFF;
            boolean wokeFromDoze = wasDozing && !dozing;
            wasDozing = dozing;
            int cur = renderer.currentMotionMode();
            long now = android.os.SystemClock.uptimeMillis();
            if (dozing) {
                // 如果最近 800ms 内收到过非 AOD 指令（正在亮屏 / 解锁），底层 display 状态仍处于 DOZE 瞬态，
                // 绝不能把它拉回 AOD，否则会掐死正在播的 tr0 / tr2 扩散动画！
                if (lastCmdMode != Motion.AOD && now - lastCmdMs < 1200) {
                    return;
                }
                if (cur != Motion.AOD) {
                    applyCommand(Motion.AOD, true);
                    Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]")
                            + " 显示直驱 → 息屏 displayState=" + st);
                }
            } else if (wokeFromDoze && !homeSlot
                    && lastCmdMode != Motion.HOME && sCurrentGlobalMode != Motion.HOME) {
                long since = now - aodSinceMs;
                if (since < 400) {
                    return;
                }
                applyCommand(Motion.LOCK, true);
                Log.i(TAG, "[锁屏槽] 显示直驱 息屏→锁屏 displayState=" + st + "（从 DOZE 唤醒 cur=" + cur + "）");
            }
        }

        /** 跨槽同步：桌面槽可见时，通知锁屏槽记录 HOME 态。注意绝不能在扩散途中调用 ensureHomeStateBeforeAod() 抢先拉升到全屏。 */
        void syncToHomeState() {
            mode = Motion.HOME;
            lastCmdMode = Motion.HOME;
            lastCmdMs = android.os.SystemClock.uptimeMillis();
            sCurrentGlobalMode = Motion.HOME;
            Log.i(TAG, "[锁屏槽] 跨槽同步为桌面态(HOME)");
        }

        @Override
        public void onDestroy() {
            if (homeSlot) {
                if (sHomeEngine == this) {
                    sHomeEngine = null;
                }
            } else {
                if (sLockEngine == this) {
                    sLockEngine = null;
                }
            }
            if (displayManager != null && displayListener != null) {
                try {
                    displayManager.unregisterDisplayListener(displayListener);
                } catch (Throwable ignored) {
                }
                displayListener = null;
            }
            if (heartbeat != null) {
                heartbeat.removeCallbacksAndMessages(null);
            }
            if (receiver != null) {
                try {
                    unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
            }
            if (renderer != null) {
                renderer.stop();
            }
            super.onDestroy();
            Log.i(TAG, "启航壁纸引擎已销毁");
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.i(TAG, (homeSlot ? "[桌面槽]" : "[锁屏槽]")
                    + " 可见性变化 visible=" + visible + " mode=" + mode
                    + " curMotion=" + (renderer == null ? "?" : renderer.currentMotionMode()));
            if (renderer != null) {
                if (homeSlot && visible) {
                    if (sCurrentGlobalMode == Motion.AOD) {
                        Log.i(TAG, "[桌面槽] visible=true 但全局处于息屏态(AOD)，忽略桌面接力");
                    } else {
                        sCurrentGlobalMode = Motion.HOME;
                        if (mode != Motion.HOME) {
                            Log.i(TAG, "[桌面槽] visible=true 提前触发解锁接力 → mode=2");
                            applyCommand(Motion.HOME, true);
                        }
                        if (sLockEngine != null) {
                            sLockEngine.syncToHomeState();
                        }
                    }
                } else if (!homeSlot && visible) {
                    // 锁屏槽重新变为可见：
                    // 只有在明确处于唤醒/锁屏模式（mode == Motion.LOCK），但渲染引擎仍滞留在 AOD 态时，
                    // 才补发锁屏扩散动画！
                    // 绝不能在 mode == Motion.AOD 时误触发扩散（例如从全屏横屏游戏按电源键熄屏时，
                    // 锁屏槽变为 visible 且 mode 是 AOD，若误触发 LOCK 会强行打断熄屏并铺开全屏壁纸！）
                    int curMotion = renderer.currentMotionMode();
                    if (curMotion == Motion.AOD && mode == Motion.LOCK) {
                        Log.i(TAG, "[锁屏槽] 唤醒亮屏且仍停在 AOD 态，补发扩散动画");
                        applyCommand(Motion.LOCK, true);
                    } else if (sPendingAodCollapse || (mode == Motion.AOD && curMotion != Motion.AOD)) {
                        sPendingAodCollapse = false;
                        try {
                            Class<?> c = Class.forName("android.os.SystemProperties");
                            c.getMethod("set", String.class, String.class).invoke(null, "sys.qihang.sleep_land", "0");
                        } catch (Throwable ignored) {
                        }
                        Log.i(TAG, "[锁屏槽] 横屏熄屏后 AOD 点亮：开始播放回缩动画 curMotion=" + curMotion);
                        renderer.ensureHomeStateBeforeAod();
                        renderer.setMode(Motion.AOD, true);
                    }
                } else if (!homeSlot && !visible) {
                    if (mode == Motion.HOME && !renderer.isTransitionRunning()) {
                        renderer.ensureHomeStateBeforeAod();
                    }
                }
                renderer.setVisible(visible);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            Log.i(TAG, "surface " + width + "x" + height);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            Log.i(TAG, "surface created（surface 重建，会重新初始化 GL）");
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (renderer != null) {
                renderer.setVisible(false);
            }
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public android.os.Bundle onCommand(String action, int x, int y, int z,
                                           android.os.Bundle extras, boolean resultRequested) {
            if (extras != null && extras.containsKey(XposedInit.EXTRA_MODE) && renderer != null) {
                int next = extras.getInt(XposedInit.EXTRA_MODE, mode);
                boolean animate = extras.getBoolean(XposedInit.EXTRA_ANIMATE, true);
                Log.i(TAG, "onCommand mode=" + next + " action=" + action);
                applyCommand(next, animate);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }
    }
}