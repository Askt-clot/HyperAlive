package com.qihang.aod.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import com.qihang.aod.AodStyleSettings;
import com.qihang.aod.ClockSettings;
import com.qihang.aod.FlymeClockView;
import com.qihang.aod.TransitionPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 跨进程三态协同与调度协调器：
 * 统筹管理电源前馈事件、SystemUI Scrim 场景状态、时钟同步、遮罩保护以及防冻结解冻逻辑。
 */
public final class StateCoordinator {

    public static final String ACTION_MODE = "com.qihang.aod.CMD";
    public static final String ACTION_ALIVE = "com.flyme.alivewallpaper.ACTION_ALIVE_WALLPAPER";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ANIMATE = "animate";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ALIVE_CMD = "command";

    public static final String PROP_SLEEP_AT = "sys.qihang.sleep_at";
    public static final String PROP_WAKE_AT = "sys.qihang.wake_at";

    public static volatile Context sContext;
    public static volatile View sAodRoot;
    public static volatile String sStateName;
    public static volatile int sScrimMode = 1;
    public static volatile int sLastMode = -1;

    public static volatile boolean sAsleep;
    public static volatile long sSleepAtMs;
    public static volatile long sWakeAtMs;
    public static volatile long sBiometricWakeAtMs;
    private static volatile long sPowerGeneration;

    public static volatile boolean sLinkageAnimating = false;
    public static volatile long sLinkageStartTime = 0L;
    public static volatile long sLinkageEpoch = 0L;
    private static volatile long sLinkageToken = 0L;

    public static synchronized void onLinkageAnimStarted(String why) {
        long currentEpoch = propGetLong(PROP_SLEEP_AT, 0L);
        boolean is5Arg = why != null && why.contains("5-arg");
        if (!is5Arg && !isSleepingActive()) return;
        Context ctx = sContext != null ? sContext : systemContext();
        if (ctx != null) {
            if (!isAodEnabled(ctx) || isFullScreenAod(ctx)
                    || isCustomClockEnabled(ctx, 0) || isCustomClockEnabled(ctx, 1)) {
                return;
            }
        }
        if (currentEpoch <= 0) {
            currentEpoch = SystemClock.uptimeMillis();
        }
        boolean isNewRound = !sLinkageAnimating || is5Arg || (currentEpoch != sLinkageEpoch);
        if (!isNewRound) {
            return;
        }
        if (sLinkageAnimating) {
            HookUtils.log("LinkageAnim SUPERSEDED by " + why + ": oldToken=" + sLinkageToken
                    + " oldEpoch=" + sLinkageEpoch + " -> newEpoch=" + currentEpoch);
        }
        sLinkageAnimating = true;
        sLinkageStartTime = SystemClock.uptimeMillis();
        sLinkageEpoch = currentEpoch;
        final long token = ++sLinkageToken;
        final long epoch = currentEpoch;
        HookUtils.log("LinkageAnim START: " + why + " (token=" + token + ", epoch=" + epoch + ")");
        if (sAodRoot != null) {
            ViewTreeSweeper.sweep(sAodRoot);
        }
        mainHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (StateCoordinator.class) {
                    if (sLinkageAnimating && sLinkageToken == token && sLinkageEpoch == epoch) {
                        onLinkageAnimFinished("timeout_fallback (" + (SystemClock.uptimeMillis() - sLinkageStartTime) + "ms)");
                    }
                }
            }
        }, 1200L);
    }

    public static synchronized void onLinkageAnimFinished(String why) {
        if (sLinkageAnimating) {
            long elapsed = SystemClock.uptimeMillis() - sLinkageStartTime;
            sLinkageAnimating = false;
            sLinkageStartTime = 0L;
            sLinkageEpoch = 0L;
            sLinkageToken++;
            HookUtils.log("LinkageAnim END: " + why + " (elapsed=" + elapsed + "ms)");
            if (sAodRoot != null) {
                ViewTreeSweeper.sweep(sAodRoot);
            }
        }
    }

    public static boolean isLinkageAnimating() {
        return sLinkageAnimating;
    }

    public static volatile boolean sLastFodVisible = true;
    public static volatile boolean sLastSleeping = false;
    public static volatile boolean sLastAodEnabled = true;
    public static volatile String sLastStateName = null;

    private static Handler sMain;
    private static volatile int sCachedFsAod = -1;
    private static volatile int sCachedAodEnabled = -1;
    private static volatile boolean sFsAodObserverRegistered;
    private static volatile boolean sClockReceiverRegistered;
    private static volatile boolean sDisplayRegistered;

    public static final List<WeakReference<FlymeClockView>> sClocks = new ArrayList<>();
    public static volatile int sQihangUid = -1;
    public static final Set<Integer> sQihangPids = Collections.synchronizedSet(new HashSet<Integer>());

    // 桌面 -> 息屏 180ms 闪现保护遮罩
    private static final long HOME_SLEEP_MASK_MAX_MS = 2500;
    private static volatile boolean sHomeSleepMask;
    private static long sHomeSleepMaskAt;
    private static WeakReference<View> sKeyguardRootRef;
    private static Runnable sHomeSleepMaskTick;

    private StateCoordinator() {}

    private static final java.util.concurrent.ExecutorService sAsync =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "QihangAsync");
                    t.setPriority(Thread.MAX_PRIORITY);
                    return t;
                }
            });

    public static void postAsync(Runnable r) {
        if (r == null) return;
        try {
            sAsync.execute(r);
        } catch (Throwable t) {
            mainHandler().post(r);
        }
    }

    public static Handler mainHandler() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }

    public static boolean isResetAllowed() {
        int caller = android.os.Binder.getCallingUid();
        if (caller == 0) {
            return true; // 仅放行 root
        }
        try {
            String o = HookUtils.propGet("sys.qihang.allow_reset", "0");
            if ("1".equals(o)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static int getQihangUid() {
        if (sQihangUid > 0) return sQihangUid;
        try {
            Context ctx = sContext != null ? sContext : systemContext();
            if (ctx != null) {
                sQihangUid = ctx.getPackageManager().getPackageUid(HookUtils.PKG_SELF, 0);
                return sQihangUid;
            }
        } catch (Throwable ignored) {}
        return sQihangUid;
    }

    public static void updateUidFromPid(int pid) {
        if (sQihangUid > 0 || pid <= 0) return;
        try {
            File f = new File("/proc/" + pid + "/status");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            sQihangUid = Integer.parseInt(parts[1]);
                        }
                        break;
                    }
                }
                br.close();
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isQihangPid(int pid) {
        if (pid <= 0) return false;
        if (sQihangPids.contains(pid)) return true;
        try {
            File f = new File("/proc/" + pid + "/cmdline");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String cmd = br.readLine();
                br.close();
                if (cmd != null && (cmd.contains(HookUtils.PKG_SELF) || cmd.contains(HookUtils.PKG_ALIVE))) {
                    sQihangPids.add(pid);
                    updateUidFromPid(pid);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isQihangPath(String path) {
        if (path == null) return false;
        int qUid = getQihangUid();
        if (qUid > 0 && path.contains("uid_" + qUid)) return true;
        for (Integer pid : sQihangPids) {
            if (path.contains("pid_" + pid)) return true;
        }
        return false;
    }

    public static void ensureQihangThawed() {
        try {
            int qUid = getQihangUid();
            if (qUid <= 0) return;
            File uidDir = new File("/sys/fs/cgroup/apps/uid_" + qUid);
            if (!uidDir.exists() || !uidDir.isDirectory()) return;
            File[] files = uidDir.listFiles();
            if (files == null) return;
            for (File sub : files) {
                if (sub.isDirectory() && sub.getName().startsWith("pid_")) {
                    File freezeFile = new File(sub, "cgroup.freeze");
                    if (freezeFile.exists()) {
                        File eventsFile = new File(sub, "cgroup.events");
                        boolean isFrozen = false;
                        if (eventsFile.exists()) {
                            BufferedReader br = new BufferedReader(new FileReader(eventsFile));
                            String el;
                            while ((el = br.readLine()) != null) {
                                if (el.contains("frozen 1")) {
                                    isFrozen = true;
                                    break;
                                }
                            }
                            br.close();
                        }
                        if (isFrozen) {
                            FileOutputStream fos = new FileOutputStream(freezeFile);
                            fos.write("0\n".getBytes());
                            fos.flush();
                            fos.close();
                            HookUtils.log("★ ensureQihangThawed: 主动解冻 " + sub.getName() + " 成功！");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            HookUtils.log("ensureQihangThawed 异常: " + t);
        }
    }

    public static boolean isAodEnabled(Context ctx) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                int val = android.provider.Settings.Secure.getInt(
                        c.getContentResolver(), "doze_always_on", 1);
                sCachedAodEnabled = val;
                return val == 1;
            }
        } catch (Throwable ignored) {}
        return sCachedAodEnabled != 0;
    }

    public static boolean isFullScreenAod(Context ctx) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                int val = android.provider.Settings.Secure.getInt(
                        c.getContentResolver(), "full_screen_aod_on", 0);
                sCachedFsAod = val;
                return val == 1;
            }
        } catch (Throwable ignored) {}
        return sCachedFsAod == 1;
    }

    public static boolean isSleepingActive() {
        long at = propGetLong(PROP_SLEEP_AT, 0L);
        long wakeAt = propGetLong(PROP_WAKE_AT, 0L);
        return at > 0 && at >= wakeAt;
    }

    public static boolean isDozeLike(Context ctx) {
        if (ctx == null) return false;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return false;
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return false;
            int st = d.getState();
            if (st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND) {
                return true;
            }
            return st == Display.STATE_OFF && isSleepingActive();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void ensureFsAodObserver(final Context ctx) {
        if (ctx == null || sFsAodObserverRegistered) return;
        try {
            android.database.ContentObserver observer = new android.database.ContentObserver(mainHandler()) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    boolean fs = isFullScreenAod(ctx);
                    boolean aodOn = isAodEnabled(ctx);
                    HookUtils.log("ContentObserver: AOD 设置变更 -> aodEnabled=" + aodOn + " full_screen_aod_on=" + fs);
                    recompute(ctx);
                    if (sAodRoot != null) {
                        ViewTreeSweeper.sweep(sAodRoot);
                    }
                }
            };
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("full_screen_aod_on"),
                    false, observer);
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("doze_always_on"),
                    false, observer);
            sFsAodObserverRegistered = true;
            HookUtils.log("full_screen_aod_on & doze_always_on ContentObserver 注册成功");
        } catch (Throwable t) {
            HookUtils.log("注册 full_screen_aod_on ContentObserver 异常: " + t);
        }
    }

    public static synchronized void onSleepIntent(final String source) {
        long now = SystemClock.uptimeMillis();
        if (sAsleep && now - sSleepAtMs < 400) return;
        sAsleep = true;
        sSleepAtMs = now;
        sBiometricWakeAtMs = 0;
        final long generation = ++sPowerGeneration;
        sLastFodVisible = true;
        HookUtils.propSet("sys.qihang.fod_visible", "1");
        HookUtils.propSet(PROP_SLEEP_AT, String.valueOf(now));
        HookUtils.propSet(PROP_WAKE_AT, "0");
        postAsync(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
                if (generation != sPowerGeneration) return;
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
                    if (!isAodEnabled(ctx)) {
                        HookUtils.log("熄屏前馈：" + source + "，系统已关闭息屏显示 → mode=1");
                        broadcastMode(ctx, 1, "power_sleep_aod_off");
                        sLastMode = 1;
                        return;
                    }
                    boolean fsAod = isFullScreenAod(ctx);
                    int mode = fsAod ? 1 : 0;
                    HookUtils.log("熄屏前馈：" + source + " (fsAod=" + fsAod + ") → 广播 mode=" + mode);
                    broadcastMode(ctx, mode, fsAod ? "power_sleep_fsaod" : "power_sleep");
                    sLastMode = mode;
                }
            }
        });
        mainHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
            }
        }, 1000L);
        mainHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
            }
        }, 2000L);
    }

    public static synchronized void onWakeIntent() {
        long now = SystemClock.uptimeMillis();
        // A normal PMS wake callback can arrive after successful authentication.
        if (sBiometricWakeAtMs > 0 && now - sBiometricWakeAtMs < 1200L) return;
        final long generation = ++sPowerGeneration;
        sAsleep = false;
        sSleepAtMs = 0;
        sWakeAtMs = now;
        onLinkageAnimFinished("wake_intent");
        HookUtils.propSet(PROP_SLEEP_AT, "0");
        HookUtils.propSet(PROP_WAKE_AT, String.valueOf(now));
        postAsync(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
                if (generation != sPowerGeneration) return;
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
                    broadcastMode(ctx, 1, "power_wake");
                    sLastMode = 1;
                }
            }
        });
    }

    public static synchronized void onBiometricWakeIntent() {
        long now = SystemClock.uptimeMillis();
        final long generation = ++sPowerGeneration;
        sAsleep = false;
        sSleepAtMs = 0;
        sWakeAtMs = now;
        sBiometricWakeAtMs = now;
        onLinkageAnimFinished("biometric_wake");
        HookUtils.propSet(PROP_SLEEP_AT, "0");
        HookUtils.propSet(PROP_WAKE_AT, String.valueOf(now));
        postAsync(new Runnable() {
            @Override
            public void run() {
                ensureQihangThawed();
                if (generation != sPowerGeneration) return;
                mainHandler().post(new Runnable() {
                    @Override public void run() { releaseHomeSleepMask("biometric_wake"); }
                });
                Context ctx = sContext != null ? sContext : systemContext();
                if (ctx != null) {
                    broadcastMode(ctx, 2, "biometric_wake");
                    sLastMode = 2;
                }
            }
        });
    }

    public static void onScrim(Object scrim) {
        String state = readState(scrim);
        if (state == null) return;

        if (sStateName == null || !sStateName.equals(state)) {
            String prev = sStateName;
            sStateName = state;
            sScrimMode = modeOf(state);
            HookUtils.log("scrim state=" + state + " -> mode=" + sScrimMode);
            if ("UNLOCKED".equals(prev) && isSleepingActive()) {
                Context probeCtx = sContext != null ? sContext : findContext(scrim);
                if (isAodEnabled(probeCtx) && !isFullScreenAod(probeCtx)) {
                    armHomeSleepMask("scrim " + prev + "->" + state);
                }
            }
        }

        View root = findView(scrim);
        Context ctx = sContext != null ? sContext : (root != null ? root.getContext() : findContext(scrim));
        if (ctx != null) {
            sContext = ctx;
            ensureDisplayListener(ctx);
            ensureClockReceiver(ctx);
            ensureFsAodObserver(ctx);
        }
        if (root != null) {
            sAodRoot = root.getRootView();
        }

        boolean aodEnabled = isAodEnabled(ctx);
        if (aodEnabled && (sScrimMode == 0 || isSleepingActive())) {
            for (String fName : new String[]{"mBehindAlpha", "mInFrontAlpha", "mNotificationsAlpha"}) {
                try {
                    Field f = scrim.getClass().getDeclaredField(fName);
                    f.setAccessible(true);
                    f.setFloat(scrim, 0.0f);
                } catch (Throwable ignored) {}
            }
        }

        recompute(ctx);
    }

    public static void recompute(Context ctx) {
        if (ctx == null) return;
        boolean aodEnabled = isAodEnabled(ctx);
        boolean doze = isDozeLike(ctx);
        boolean sleepIntent = isSleepingActive();
        long now = SystemClock.uptimeMillis();
        long wakeAt = propGetLong(PROP_WAKE_AT, 0L);
        boolean wakeGrace = wakeAt > 0 && now >= wakeAt && now - wakeAt < 1200L;
        boolean sleeping = sleepIntent || (doze && !wakeGrace);
        boolean fsAod = isFullScreenAod(ctx);
        int mode;

        boolean recentBiometric = sBiometricWakeAtMs > 0 && now >= sBiometricWakeAtMs
                && now - sBiometricWakeAtMs < 1200L && !sleepIntent;
        mode = TransitionPolicy.resolveMode(sleepIntent, doze, wakeGrace, recentBiometric,
                sScrimMode, aodEnabled, fsAod);

        if (mode == 2 && !sleeping) {
            sleeping = false;
            sAsleep = false;
            releaseHomeSleepMask("unlocked/bio");
        }
        boolean modeOrSleepChanged = (mode != sLastMode) || (sleeping != sLastSleeping) || (aodEnabled != sLastAodEnabled);
        boolean stateNameChanged = (sStateName != null && !sStateName.equals(sLastStateName));
        sLastSleeping = sleeping;
        sLastAodEnabled = aodEnabled;
        sLastStateName = sStateName;
        if (!sleeping) {
            onLinkageAnimFinished("awake");
        }
        updateClocksVisibility(false, mode);
        if (!modeOrSleepChanged && !stateNameChanged) return;

        sLastMode = mode;
        HookUtils.log("-> mode=" + mode + " (scrim=" + sStateName + " doze=" + doze + " sleeping=" + sleeping + " aodEnabled=" + aodEnabled + " fsAod=" + fsAod + ")");
        if (mode == 0 && "UNLOCKED".equals(sStateName)) {
            if (aodEnabled && !fsAod) {
                armHomeSleepMask("sleep from UNLOCKED");
            }
        } else if (mode != 0) {
            releaseHomeSleepMask("mode=" + mode);
        }
        if (modeOrSleepChanged) {
            broadcastMode(ctx, mode, mode == 0 ? "doze" : (sleeping && !aodEnabled ? "sleep_aod_off" : (fsAod && sleeping ? "doze_fsaod" : (sStateName != null ? sStateName : "KEYGUARD"))));
        }
        boolean showFlymeClock = (mode == 0 || mode == 1) && !(sleeping && (!aodEnabled || fsAod));
        updateClocksVisibility(showFlymeClock, mode);

        if (sleeping) {
            ViewTreeSweeper.stopSweep();
            ViewTreeSweeper.startSweep(sAodRoot, true);
        } else {
            ViewTreeSweeper.stopSweep();
            if (sAodRoot != null) {
                ViewTreeSweeper.sweep(sAodRoot);
            }
        }
    }

    public static synchronized void registerClock(FlymeClockView clock) {
        if (clock == null) return;
        boolean found = false;
        Iterator<WeakReference<FlymeClockView>> it = sClocks.iterator();
        while (it.hasNext()) {
            FlymeClockView c = it.next().get();
            if (c == null) {
                it.remove();
            } else if (c == clock) {
                found = true;
            }
        }
        if (!found) {
            sClocks.add(new WeakReference<>(clock));
        }
    }

    public static synchronized boolean hasActiveClock() {
        for (WeakReference<FlymeClockView> ref : sClocks) {
            FlymeClockView c = ref.get();
            if (c != null && c.isAttachedToWindow() && c.isShown()) {
                return true;
            }
        }
        return false;
    }

    private static boolean sClockSyncPosted;

    public static void updateClocksVisibility(boolean visible, int mode) {
        // View hierarchy changes must run on SystemUI's main thread. One policy
        // owns both native and custom clocks so two receivers cannot expose both.
        synchronized (StateCoordinator.class) {
            if (sClockSyncPosted) return;
            sClockSyncPosted = true;
        }
        mainHandler().post(new Runnable() {
            @Override public void run() {
                synchronized (StateCoordinator.class) { sClockSyncPosted = false; }
                ViewTreeSweeper.sweep(sAodRoot);
            }
        });
    }

    public static boolean isCustomClockEnabled(Context ctx, int mode) {
        try {
            Context c = (ctx != null) ? ctx : (sContext != null ? sContext : systemContext());
            if (c != null) {
                if (mode == 0 && isFullScreenAod(c)) {
                    return false;
                }
                return ClockSettings.isClockEnabled(c, mode);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void ensureClockReceiver(final Context ctx) {
        if (ctx == null || sClockReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ClockSettings.ACTION_STYLE_CHANGED);
            f.addAction(AodStyleSettings.ACTION_STYLE_CHANGED);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    HookUtils.log("收到时钟配置变更广播: " + intent.getAction());
                    if (ClockSettings.ACTION_STYLE_CHANGED.equals(intent.getAction())) {
                        try {
                            ClockSettings.Config aod = ClockSettings.fromIntentAod(intent);
                            ClockSettings.Config lock = ClockSettings.fromIntentLock(intent);
                            ClockSettings.saveLocal(context, aod, lock);
                        } catch (Throwable ignored) {}
                    } else if (AodStyleSettings.ACTION_STYLE_CHANGED.equals(intent.getAction())) {
                        try {
                            int style = intent.getIntExtra(AodStyleSettings.KEY_STYLE, AodStyleSettings.STYLE_SET_SAIL);
                            context.getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE)
                                    .edit().putInt(AodStyleSettings.KEY_STYLE, style).commit();
                        } catch (Throwable ignored) {}
                    }
                    int currentMode = sLastMode >= 0 ? sLastMode : 0;
                    boolean sleeping = isSleepingActive() || isDozeLike(context);
                    boolean aodEnabled = isAodEnabled(context);
                    boolean fsAod = isFullScreenAod(context);
                    boolean showFlymeClock = (currentMode == 0 || currentMode == 1) && !(sleeping && (!aodEnabled || fsAod));
                    updateClocksVisibility(showFlymeClock, currentMode);
                    if (sAodRoot != null) {
                        ViewTreeSweeper.sweep(sAodRoot);
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            sClockReceiverRegistered = true;
            HookUtils.log("时钟配置广播接收器已注册");
            ensureFsAodObserver(ctx);
        } catch (Throwable t) {
            HookUtils.log("注册时钟配置广播接收器失败: " + t);
        }
    }

    private static void ensureDisplayListener(final Context ctx) {
        if (sDisplayRegistered) return;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}
                @Override
                public void onDisplayRemoved(int displayId) {}
                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        recompute(sContext);
                    }
                }
            }, mainHandler());
            sDisplayRegistered = true;
            HookUtils.log("display listener registered");
        } catch (Throwable t) {
            HookUtils.log("registerDisplayListener 失败: " + t);
        }
    }

    public static void armHomeSleepMask(String why) {
        long now = SystemClock.uptimeMillis();
        if (!sHomeSleepMask) {
            HookUtils.log("桌面熄屏遮罩 ON (" + why + ")");
        }
        sHomeSleepMask = true;
        sHomeSleepMaskAt = now;
        View root = findKeyguardRoot();
        if (root != null) root.setTransitionAlpha(0.0f);
        if (sHomeSleepMaskTick != null) return;
        sHomeSleepMaskTick = new Runnable() {
            @Override
            public void run() {
                if (!sHomeSleepMask) {
                    sHomeSleepMaskTick = null;
                    return;
                }
                View r = findKeyguardRoot();
                long elapsed = SystemClock.uptimeMillis() - sHomeSleepMaskAt;
                if (!isSleepingActive()) {
                    releaseHomeSleepMask("已唤醒");
                } else if ("AOD".equalsIgnoreCase(sStateName) && !systemShowsKeyguard(r)) {
                    releaseHomeSleepMask("系统已收起锁屏层 +" + elapsed + "ms");
                } else if (elapsed > HOME_SLEEP_MASK_MAX_MS) {
                    releaseHomeSleepMask("超时 +" + elapsed + "ms");
                } else {
                    if (r != null) r.setTransitionAlpha(0.0f);
                    mainHandler().postDelayed(this, 16);
                    return;
                }
                sHomeSleepMaskTick = null;
            }
        };
        mainHandler().post(sHomeSleepMaskTick);
    }

    public static void releaseHomeSleepMask(String why) {
        if (!sHomeSleepMask) return;
        sHomeSleepMask = false;
        View root = findKeyguardRoot();
        if (root != null) root.setTransitionAlpha(1.0f);
        if (sHomeSleepMaskTick != null) {
            mainHandler().removeCallbacks(sHomeSleepMaskTick);
            sHomeSleepMaskTick = null;
        }
        HookUtils.log("桌面熄屏遮罩 OFF (" + why + ")");
    }

    private static View findKeyguardRoot() {
        View cached = sKeyguardRootRef != null ? sKeyguardRootRef.get() : null;
        if (cached != null && cached.isAttachedToWindow()) return cached;
        View found = findByIdName(sAodRoot, "keyguard_root_view");
        sKeyguardRootRef = found != null ? new WeakReference<>(found) : null;
        return found;
    }

    public static View findByIdName(View v, String name) {
        if (v == null) return null;
        if (name.equals(idNameOf(v))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByIdName(g.getChildAt(i), name);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean systemShowsKeyguard(View root) {
        if (root == null || !root.isShown()) return false;
        float a = 1.0f;
        View cur = root;
        while (cur != null) {
            a *= cur.getAlpha();
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return a > 0.01f;
    }

    public static String idNameOf(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                return v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static int modeOf(String state) {
        String s = state.toUpperCase();
        if (s.contains("AOD") || s.contains("PULSING") || s.contains("DOZE")) {
            return 0;
        }
        if (s.contains("KEYGUARD") || s.contains("BOUNCER")) {
            return 1;
        }
        return 2;
    }

    public static String readState(Object scrim) {
        for (String name : new String[]{"mState", "state", "mScrimState"}) {
            Object v = HookUtils.getField(scrim, name);
            if (v != null) {
                String s = v instanceof Enum ? ((Enum<?>) v).name() : String.valueOf(v);
                if (s.length() > 0) return s;
            }
        }
        return null;
    }

    public static String aliveCmd(int mode) {
        if (mode == 0) return "action_alive_aod";
        if (mode == 1) return "action_alive_lock";
        return "action_alive_launcher";
    }

    public static void broadcastMode(Context ctx, int mode, String state) {
        Intent i = new Intent(ACTION_MODE);
        i.setPackage(HookUtils.PKG_SELF);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_ANIMATE, true);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra("fod_visible", !"0".equals(HookUtils.propGet("sys.qihang.fod_visible", "1")));
        sendBroadcastSmart(ctx, i);

        Intent alive = new Intent(ACTION_ALIVE);
        alive.setPackage(HookUtils.PKG_ALIVE);
        alive.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        alive.putExtra(EXTRA_ALIVE_CMD, aliveCmd(mode));
        sendBroadcastSmart(ctx, alive);
    }

    public static void broadcastFodVisible(Context ctx, boolean visible) {
        if (sLastFodVisible == visible) return;
        sLastFodVisible = visible;
        HookUtils.propSet("sys.qihang.fod_visible", visible ? "1" : "0");
        if (ctx == null) ctx = sContext != null ? sContext : systemContext();
        if (ctx == null) return;
        try {
            Intent intent = new Intent(ACTION_MODE);
            intent.setPackage(HookUtils.PKG_SELF);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("fod_visible", visible);
            intent.putExtra("fod_changed", true);
            sendBroadcastSmart(ctx, intent);
        } catch (Throwable t) {
            HookUtils.log("broadcastFodVisible 失败: " + t);
        }
    }

    public static void sendBroadcastSmart(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        try {
            if (android.os.Process.myUid() == 1000) {
                try {
                    Class<?> uhClass = Class.forName("android.os.UserHandle");
                    Field allField = uhClass.getField("ALL");
                    Object userAll = allField.get(null);
                    Method m = Context.class.getMethod("sendBroadcastAsUser", Intent.class, uhClass);
                    m.invoke(ctx, intent, userAll);
                    return;
                } catch (Throwable ignored) {}
            }
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            HookUtils.log("sendBroadcast 失败: " + t);
        }
    }

    public static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            Object c = at.getMethod("getSystemContext").invoke(t);
            return c instanceof Context ? (Context) c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static long propGetLong(String k, long def) {
        try {
            String s = HookUtils.propGet(k, "").trim();
            if (s.length() > 0) return Long.parseLong(s);
        } catch (Throwable ignored) {}
        return def;
    }

    public static View findView(Object obj) {
        if (obj == null) return null;
        for (String fieldName : new String[]{"mScrimInFront", "mScrimBehind", "mRootView"}) {
            Object v = HookUtils.getField(obj, fieldName);
            if (v instanceof View) return (View) v;
        }
        return null;
    }

    public static Context findContext(Object obj) {
        if (obj == null) return null;
        Object c = HookUtils.getField(obj, "mContext");
        return c instanceof Context ? (Context) c : null;
    }
}
