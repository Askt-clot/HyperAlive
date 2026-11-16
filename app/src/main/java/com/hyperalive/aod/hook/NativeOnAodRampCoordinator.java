package com.hyperalive.aod.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Integrated coordinator (enabled by default within capability, primary display 0, lock-wallpaper
 * owner, AOD policy, non-fullscreen, and sleep-window guards; emergency opt-out via
 * {@code debug.hyperalive.on_ramp_aod=0}) that preserves primary display {@code STATE_ON (2)} while
 * dimming smoothly toward a measured native AOD brightness along Xiaomi's native ~450ms curve,
 * then hands off once to {@code STATE_DOZE (3)} without {@code STATE_OFF (1)} or ColorFade veil.
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Leaf memory-only lock: no reflection, native calls, property reads, logging, or Handler
 *       operations ever execute under {@code LOCK}.</li>
 *   <li>Strict primary-display scoping: secondary and unknown displays always fall back to exact
 *       54aa behavior.</li>
 *   <li>Fail-closed on missing capabilities, missing handler/thread affinity, missing native
 *       bounds/AOD targets, unknown AOD endpoint mode/getters, armed native waits, or post-entry
 *       errors (never guesses brightness or borrows ON range minimum as AOD target).</li>
 *   <li>Lifecycle cancellation runs even after {@code debug.hyperalive.on_ramp_aod} flips to 0,
 *       with an in-flight completion fence preventing stale dim writes after wake/cancel.</li>
 * </ul>
 */
public final class NativeOnAodRampCoordinator {

    public static final String PROP_ENABLED = "debug.hyperalive.on_ramp_aod";

    public static final int NOMINAL_RAMP_MS = 450;
    public static final int MIN_RETARGET_MS = 60;
    public static final long MAX_SESSION_MS = 800L;
    public static final long SLEEP_WINDOW_MS = 1500L;
    public static final float TARGET_EPSILON = 1e-4f;
    public static final int MAX_RETARGETS = 8;

    private static final int DISPLAY_STATE_OFF = 1;
    private static final int DISPLAY_STATE_ON = 2;
    private static final int DISPLAY_STATE_DOZE = 3;
    private static final int DISPLAY_STATE_DOZE_SUSPEND = 4;

    private static final int POLICY_OFF = 0;
    private static final int POLICY_DOZE = 1;
    private static final int POLICY_DIM = 2;
    private static final int POLICY_BRIGHT = 3;

    private static final int REASON_DOZE = 2;
    private static final int RATE_MODIFIER_DOZE = 4;
    private static final String DOZE_STRATEGY_NAME = "DozeBrightnessStrategy";
    private static final String BOOST_STRATEGY_NAME = "BoostBrightnessStrategy";
    private static final String OVERRIDE_STRATEGY_NAME = "OverrideBrightnessStrategy";
    private static final String TEMPORARY_STRATEGY_NAME = "TemporaryBrightnessStrategy";
    private static final String FOLLOWER_STRATEGY_NAME = "FollowerBrightnessStrategy";

    /**
     * Minimal retained package-private test seam (null in production, zero production overhead) so
     * JVM adversarial tests can deterministically pause between fence check and final write without
     * altering any runtime locking or completion fences.
     */
    static volatile Runnable sTestBeforeFinalWriteHook = null;
    private static volatile Method sSystemPropertiesGetMethod = null;
    private static volatile boolean sSystemPropertiesResolved = false;

    private enum Phase {
        HOLD_AND_SEED,
        RAMPING,
        COMPLETING,
        CANCELLED,
        DONE
    }

    private static final class Bounds {
        final float min;
        final float max;
        Bounds(float min, float max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class CleanupSnapshot {
        final WeakReference<Object> dpcRef;
        final WeakReference<Handler> handlerRef;
        final Runnable timeoutTask;
        final boolean strategyCaptured;
        final WeakReference<Object> dbcRef;
        final WeakReference<Object> savedOrigStrategyRef;
        final WeakReference<Object> appliedDozeStrategyRef;
        final boolean rateCaptured;
        final WeakReference<Object> rrcRef;
        final int origDurationDozeDimming;
        final int appliedDurationDozeDimming;
        final long origDozeTimeMillis;
        final long appliedDozeTimeMillis;
        final boolean origHadDozeMod;

        CleanupSnapshot(Session s) {
            this.dpcRef = s.dpcRef;
            this.handlerRef = s.handlerRef;
            this.timeoutTask = s.timeoutTask;
            this.strategyCaptured = s.strategyCaptured;
            this.dbcRef = s.dbcRef;
            this.savedOrigStrategyRef = s.savedOrigStrategyRef;
            this.appliedDozeStrategyRef = s.appliedDozeStrategyRef;
            this.rateCaptured = s.rateCaptured;
            this.rrcRef = s.rrcRef;
            this.origDurationDozeDimming = s.origDurationDozeDimming;
            this.appliedDurationDozeDimming = s.appliedDurationDozeDimming;
            this.origDozeTimeMillis = s.origDozeTimeMillis;
            this.appliedDozeTimeMillis = s.appliedDozeTimeMillis;
            this.origHadDozeMod = s.origHadDozeMod;
        }
    }

    private static final class Session {
        final long id;
        final long generation;
        final long sleepTokenMs;
        final long startUptimeMs;
        final long deadlineMs;
        final WeakReference<Object> dpcRef;
        final WeakReference<Handler> handlerRef;
        final float startBrightness;
        final float startSdrBrightness;

        Phase phase;
        boolean cancelled;
        String cancelReason;
        float targetBrightness;
        float sdrTargetBrightness;
        String targetSource;
        boolean hasDistinctSdrTarget;
        boolean seeded;
        int durationMs;
        int retargetCount;
        Runnable timeoutTask;

        // Owned native state tracking (read/restored strictly outside LOCK on DPC handler thread)
        boolean strategyCaptured;
        WeakReference<Object> dbcRef;
        WeakReference<Object> savedOrigStrategyRef;
        WeakReference<Object> appliedDozeStrategyRef;

        boolean rateCaptured;
        WeakReference<Object> rrcRef;
        int origDurationDozeDimming;
        int appliedDurationDozeDimming;
        long origDozeTimeMillis;
        long appliedDozeTimeMillis;
        boolean origHadDozeMod;

        Session(long id, long generation, long sleepTokenMs, long nowMs, Object dpc, Handler handler,
                float startBrightness, float startSdrBrightness, float targetBrightness,
                float sdrTargetBrightness, String targetSource) {
            this.id = id;
            this.generation = generation;
            this.sleepTokenMs = sleepTokenMs;
            this.startUptimeMs = nowMs;
            this.deadlineMs = nowMs + MAX_SESSION_MS;
            this.dpcRef = new WeakReference<>(dpc);
            this.handlerRef = new WeakReference<>(handler);
            this.startBrightness = startBrightness;
            this.startSdrBrightness = startSdrBrightness;
            this.phase = Phase.HOLD_AND_SEED;
            this.cancelled = false;
            this.targetBrightness = targetBrightness;
            this.sdrTargetBrightness = sdrTargetBrightness;
            this.targetSource = (targetSource != null) ? targetSource : "unknown";
            this.hasDistinctSdrTarget = Math.abs(sdrTargetBrightness - targetBrightness) > TARGET_EPSILON;
            this.seeded = false;
            this.durationMs = NOMINAL_RAMP_MS;
            this.retargetCount = 0;
        }
    }

    private static final class EndpointResolution {
        final boolean fullMode;
        final float brightness;
        final String source;

        EndpointResolution(boolean fullMode, float brightness, String source) {
            this.fullMode = fullMode;
            this.brightness = brightness;
            this.source = source;
        }
    }

    private static final class EnvSnapshot {
        final boolean propEnabled;
        final boolean aodPolicyActive;
        final boolean fullScreenAod;
        final boolean asleep;
        final long sleepTokenMs;
        final long nowUptimeMs;

        EnvSnapshot(boolean propEnabled, boolean aodPolicyActive, boolean fullScreenAod,
                    boolean asleep, long sleepTokenMs, long nowUptimeMs) {
            this.propEnabled = propEnabled;
            this.aodPolicyActive = aodPolicyActive;
            this.fullScreenAod = fullScreenAod;
            this.asleep = asleep;
            this.sleepTokenMs = sleepTokenMs;
            this.nowUptimeMs = nowUptimeMs;
        }

        boolean isValidForRamp() {
            return propEnabled
                    && aodPolicyActive
                    && !fullScreenAod
                    && asleep
                    && sleepTokenMs > 0L;
        }
    }

    /** Leaf memory-only lock. NEVER hold across reflection, native calls, Handler, logging, or props. */
    private static final Object LOCK = new Object();

    private static volatile boolean sCapabilitiesReady = false;
    private static long sNextSessionId = 1L;
    private static long sGeneration = 1L;
    private static long sConsumedSleepAtMs = -1L;
    private static Session sSession = null;
    private static volatile long sHandoffSuppressColorFadeUntilMs = -1L;

    private static WeakReference<Object> sPrimaryDpcRef = new WeakReference<>(null);
    private static WeakReference<Object> sPrimaryDbcRef = new WeakReference<>(null);
    private static WeakReference<Object> sPrimaryPowerStateRef = new WeakReference<>(null);
    private static WeakReference<Object> sPrimaryDpcImplRef = new WeakReference<>(null);
    private static WeakReference<Object> sPrimaryRampRateRef = new WeakReference<>(null);
    private static final Set<Object> sSecondaryComponents =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    private static volatile long sLastSparseLogTokenMs = -1L;
    private static volatile String sLastSparseLogReason = null;

    private NativeOnAodRampCoordinator() {}

    public static boolean isEnabled() {
        return readPropEnabledSafe();
    }

    public static boolean isCapabilitiesReady() {
        return sCapabilitiesReady;
    }

    public static boolean isSessionActive() {
        synchronized (LOCK) {
            return sSession != null
                    && !sSession.cancelled
                    && (sSession.phase == Phase.HOLD_AND_SEED || sSession.phase == Phase.RAMPING);
        }
    }

    private static boolean hasTrackedSession() {
        synchronized (LOCK) {
            return sSession != null;
        }
    }

    public static void install(ClassLoader cl) {
        CleanupSnapshot oldCleanup = resetForInstallMemoryOnly();
        if (oldCleanup != null) {
            postCleanupAndReevaluate(oldCleanup);
        }
        if (cl == null) {
            safeLog("OnAodRamp capability check failed: null ClassLoader (54aa fallback)");
            return;
        }
        try {
            int dpcStateHooks = HookUtils.hookAll(cl, "com.android.server.display.DisplayPowerController",
                    "animateScreenStateChange", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            onBeforeAnimateScreenStateChange(param);
                        }
                    });

            int dpcBrightHooks = hookDpcAnimateScreenBrightness(cl);

            int dbcUpdateHooks = HookUtils.hookAll(cl,
                    "com.android.server.display.brightness.DisplayBrightnessController",
                    "updateBrightness", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onAfterDbcUpdateBrightness(param);
                        }
                    });

            int rrcStateHooks = HookUtils.hookAll(cl,
                    "com.android.server.display.RampRateController",
                    "updateBrightnessState", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onAfterRampRateUpdateState(param);
                        }
                    });

            int rrcRateHooks = HookUtils.hookAll(cl,
                    "com.android.server.display.RampRateController",
                    "updateBrightnessRate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onAfterRampRateUpdateRate(param);
                        }
                    });

            int implRateHooks = HookUtils.hookAll(cl,
                    "com.android.server.display.DisplayPowerControllerImpl",
                    "updateRampRate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onAfterImplUpdateRampRate(param);
                        }
                    });

            boolean builderClsReady = hasDisplayBrightnessStateBuilder(cl);
            boolean ready = dpcStateHooks > 0 && dpcBrightHooks > 0 && dbcUpdateHooks > 0
                    && (rrcRateHooks > 0 || implRateHooks > 0) && builderClsReady;
            sCapabilitiesReady = ready;
            safeLog("OnAodRamp capabilities: ready=" + ready
                    + " dpcState=" + dpcStateHooks
                    + " dpcBright=" + dpcBrightHooks
                    + " dbcUpdate=" + dbcUpdateHooks
                    + " rrcState=" + rrcStateHooks
                    + " rrcRate=" + rrcRateHooks
                    + " implRate=" + implRateHooks
                    + " builder=" + builderClsReady);
        } catch (Throwable t) {
            sCapabilitiesReady = false;
            safeLog("OnAodRamp install failed (fail-closed 54aa): " + t);
        }
    }

    private static int hookDpcAnimateScreenBrightness(ClassLoader cl) {
        Class<?> dpcCls = HookUtils.findClass("com.android.server.display.DisplayPowerController", cl);
        if (dpcCls == null) return 0;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                onBeforeAnimateScreenBrightness(param);
            }
        };
        if (HookUtils.hookMethod(dpcCls, "animateScreenBrightness", hook,
                float.class, float.class, float.class, boolean.class)) {
            return 1;
        }
        return HookUtils.hookAllMethods(dpcCls, "animateScreenBrightness", hook);
    }

    private static CleanupSnapshot resetForInstallMemoryOnly() {
        CleanupSnapshot cleanup = null;
        synchronized (LOCK) {
            if (sSession != null) {
                sSession.cancelled = true;
                sSession.cancelReason = "reinstall";
                sSession.phase = Phase.CANCELLED;
                cleanup = new CleanupSnapshot(sSession);
                sSession = null;
            }
            sGeneration++;
            sConsumedSleepAtMs = -1L;
            sCapabilitiesReady = false;
            sHandoffSuppressColorFadeUntilMs = -1L;
            sPrimaryDpcRef = new WeakReference<>(null);
            sPrimaryDbcRef = new WeakReference<>(null);
            sPrimaryPowerStateRef = new WeakReference<>(null);
            sPrimaryDpcImplRef = new WeakReference<>(null);
            sPrimaryRampRateRef = new WeakReference<>(null);
            sSecondaryComponents.clear();
        }
        sLastSparseLogTokenMs = -1L;
        sLastSparseLogReason = null;
        return cleanup;
    }

    public static void onPowerSleepSignal(String source) {
        // Sleep token refresh; if a previous stale session somehow lingered across a new sleep, cancel it cleanly.
        try {
            if (!hasTrackedSession()) return;
            long newToken = StateCoordinator.sSleepAtMs;
            CleanupSnapshot cleanup = null;
            long cancelledId = -1L;
            synchronized (LOCK) {
                if (sSession != null && newToken > 0L && sSession.sleepTokenMs != newToken) {
                    sSession.cancelled = true;
                    sSession.cancelReason = "new_sleep_" + source;
                    sSession.phase = Phase.CANCELLED;
                    cancelledId = sSession.id;
                    cleanup = new CleanupSnapshot(sSession);
                    sSession = null;
                    sGeneration++;
                }
            }
            if (cleanup != null) {
                safeLog("OnAodRamp CANCEL reason=new_sleep_" + source + " id=" + cancelledId);
                postCleanupAndReevaluate(cleanup);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Off-handler wake signal (PMS lock may be held). NEVER acquires native DPC/RateRecord locks or calls
     * synchronous sendUpdatePowerState; only invalidates memory under leaf LOCK and posts handler recovery.
     */
    public static void onPowerWakeSignal(String source) {
        try {
            if (!hasTrackedSession()) return;
            final CleanupSnapshot cleanup;
            final long id;
            synchronized (LOCK) {
                if (sSession == null) {
                    return;
                }
                sSession.cancelled = true;
                sSession.cancelReason = "power_" + source;
                sSession.phase = Phase.CANCELLED;
                id = sSession.id;
                if (sSession.sleepTokenMs > 0L) {
                    sConsumedSleepAtMs = sSession.sleepTokenMs;
                }
                cleanup = new CleanupSnapshot(sSession);
                sSession = null;
                sGeneration++;
                sHandoffSuppressColorFadeUntilMs = -1L;
            }
            safeLog("OnAodRamp CANCEL reason=power_" + source + " id=" + id);
            postCleanupAndReevaluate(cleanup);
        } catch (Throwable ignored) {}
    }

    /**
     * Strict primary-scoped integration into SystemServerHook's {@code setScreenState} hook.
     * Returns {@code true} ONLY when an actual verified primary-session action handled/blocked {@code param};
     * returns {@code false} for secondary/unknown displays, disabled/unready state, or inactive sessions so
     * exact protected 54aa logic runs untouched.
     */
    public static boolean handleSetScreenState(XC_MethodHook.MethodHookParam param, int state) {
        try {
            if (param == null || param.thisObject == null) {
                return false;
            }
            Integer displayId = resolveDisplayIdStrict(param.thisObject);
            if (displayId == null || displayId.intValue() != 0) {
                return false;
            }

            boolean tracked = hasTrackedSession();
            if (!tracked) {
                return false;
            }

            EnvSnapshot env = readEnvSnapshotSafe();
            CleanupSnapshot cancelCleanup = null;
            String cancelReason = null;
            long cancelledId = -1L;
            boolean blockedHoldOn = false;

            synchronized (LOCK) {
                Session s = sSession;
                if (s == null) {
                    return false;
                }
                if (state == DISPLAY_STATE_ON) {
                    cancelReason = "set_screen_state_on";
                } else if (!env.isValidForRamp()) {
                    cancelReason = env.propEnabled ? "guard_lost_set_state" : "prop_disabled_set_state";
                } else if (s.cancelled) {
                    cancelReason = "already_cancelled_set_state";
                } else if (env.nowUptimeMs >= s.deadlineMs) {
                    cancelReason = "deadline_reached_set_state";
                } else if (s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    // Handing off cleanly to STATE_DOZE(3) - do NOT block.
                    return false;
                } else if (state == DISPLAY_STATE_OFF || state == DISPLAY_STATE_DOZE || state == DISPLAY_STATE_DOZE_SUSPEND) {
                    blockedHoldOn = true;
                }

                if (cancelReason != null) {
                    s.cancelled = true;
                    s.cancelReason = cancelReason;
                    s.phase = Phase.CANCELLED;
                    cancelledId = s.id;
                    if (s.sleepTokenMs > 0L) {
                        sConsumedSleepAtMs = s.sleepTokenMs;
                    }
                    cancelCleanup = new CleanupSnapshot(s);
                    sSession = null;
                    sGeneration++;
                    sHandoffSuppressColorFadeUntilMs = -1L;
                }
            }

            if (cancelCleanup != null) {
                safeLog("OnAodRamp CANCEL reason=" + cancelReason + " id=" + cancelledId);
                cleanupIfOnHandlerElsePost(cancelCleanup, true);
                return false;
            }

            if (blockedHoldOn) {
                setBooleanMethodBlockedResult(param);
                return true;
            }
            return false;
        } catch (Throwable t) {
            abortActiveSessionSafely("err_set_screen_state", t);
            return false;
        }
    }

    /**
     * Primary-scoped {@code DisplayPowerState.prepareColorFade} hook. Returns {@code false} for secondary/unknown
     * displays, disabled/unready state, or when no verified primary ramp/handoff session owns the fade decision,
     * letting exact protected 54aa fallback handle it.
     */
    public static boolean handlePrepareColorFade(XC_MethodHook.MethodHookParam param) {
        try {
            if (param == null || param.thisObject == null) {
                return false;
            }
            Integer displayId = resolveDisplayIdStrict(param.thisObject);
            if (displayId == null || displayId.intValue() != 0) {
                return false;
            }
            if (!hasTrackedSession() && SystemClock.uptimeMillis() > sHandoffSuppressColorFadeUntilMs) {
                return false;
            }
            EnvSnapshot env = readEnvSnapshotSafe();
            CleanupSnapshot cancelCleanup = null;
            boolean suppress = false;

            synchronized (LOCK) {
                Session s = sSession;
                if (s != null) {
                    if (!env.isValidForRamp() || env.nowUptimeMs >= s.deadlineMs || s.cancelled) {
                        s.cancelled = true;
                        s.phase = Phase.CANCELLED;
                        if (s.sleepTokenMs > 0L) {
                            sConsumedSleepAtMs = s.sleepTokenMs;
                        }
                        cancelCleanup = new CleanupSnapshot(s);
                        sSession = null;
                        sGeneration++;
                        sHandoffSuppressColorFadeUntilMs = -1L;
                    } else {
                        suppress = true;
                    }
                } else if (sCapabilitiesReady && env.isValidForRamp()
                        && env.nowUptimeMs <= sHandoffSuppressColorFadeUntilMs) {
                    suppress = true;
                }
            }

            if (cancelCleanup != null) {
                cleanupIfOnHandlerElsePost(cancelCleanup, true);
                return false;
            }
            if (suppress) {
                param.setResult(Boolean.FALSE);
                return true;
            }
            return false;
        } catch (Throwable t) {
            abortActiveSessionSafely("err_prepare_color_fade", t);
            return false;
        }
    }

    private static void onBeforeAnimateScreenStateChange(XC_MethodHook.MethodHookParam param) {
        try {
            if (param == null || param.thisObject == null || param.args == null || param.args.length < 3) {
                return;
            }
            Object dpc = param.thisObject;
            Integer displayId = getIntField(dpc, "mDisplayId");
            if (displayId == null || displayId.intValue() != 0) {
                recordSecondaryComponentsOutsideLock(dpc);
                return;
            }
            registerPrimaryRefsOutsideLock(dpc);

            int target = (param.args[0] instanceof Integer) ? ((Integer) param.args[0]).intValue() : 0;
            Object req = HookUtils.getField(dpc, "mPowerRequest");
            int policy = getPolicyFromRequest(req, -1);

            EnvSnapshot env = readEnvSnapshotSafe();

            if (target == DISPLAY_STATE_ON || policy == POLICY_DIM || policy == POLICY_BRIGHT) {
                cancelExistingSessionOutsideLock("wake_state_" + target + "_policy_" + policy, true, true);
                return;
            }

            if (target != DISPLAY_STATE_OFF && target != DISPLAY_STATE_DOZE && target != DISPLAY_STATE_DOZE_SUSPEND) {
                return;
            }

            // Inspect existing active session first (cleanup eligibility runs even if flag turned 0).
            Session existingSnapshot;
            synchronized (LOCK) {
                existingSnapshot = sSession;
            }
            if (existingSnapshot != null) {
                handleActiveSessionInAnimateStateChange(param, dpc, target, policy, env, existingSnapshot);
                return;
            }

            // Starting a brand-new session requires enabled flag + full runtime preflight validation.
            if (!sCapabilitiesReady || !env.isValidForRamp()) {
                return;
            }
            tryStartNewSession(param, dpc, req, target, policy, env);
        } catch (Throwable t) {
            abortActiveSessionSafely("err_animate_state", t);
        }
    }

    private static void handleActiveSessionInAnimateStateChange(
            XC_MethodHook.MethodHookParam param, Object dpc, int origTarget, int policy,
            EnvSnapshot env, Session sessionSnapshot) {
        if (!isOnDpcHandlerThread(dpc)) {
            cancelExistingSessionOutsideLock("off_handler_state_change", true, true);
            return;
        }
        if (sessionSnapshot.cancelled || !env.isValidForRamp() || (policy != POLICY_OFF && policy != POLICY_DOZE)) {
            cancelExistingSessionOutsideLock(
                    env.propEnabled ? "invalid_env_state_change" : "prop_off_state_change", true, true);
            return;
        }
        if (env.nowUptimeMs >= sessionSnapshot.deadlineMs) {
            completeSessionAtHandoffOrDeadline(param, dpc, origTarget, sessionSnapshot, env, true);
            return;
        }
        boolean animating = isAnimatorAnimating(dpc);
        if (sessionSnapshot.seeded && !animating) {
            if (maybeRetargetCoherentlyBeforeHandoff(param, dpc, sessionSnapshot, env)) {
                param.args[0] = Integer.valueOf(DISPLAY_STATE_DOZE);
                param.args[2] = Boolean.FALSE;
                return;
            }
            boolean forceDeadlineSnap = (env.nowUptimeMs + MIN_RETARGET_MS >= sessionSnapshot.deadlineMs)
                    || doesLatestNativeDifferFromSession(dpc, sessionSnapshot);
            completeSessionAtHandoffOrDeadline(param, dpc, origTarget, sessionSnapshot, env, forceDeadlineSnap);
            return;
        }
        // Still holding STATE_ON during ramp: let native animateScreenStateChange run safely as
        // (target=DOZE, performScreenOffTransition=false); our setScreenState hook holds ON(2).
        param.args[0] = Integer.valueOf(DISPLAY_STATE_DOZE);
        param.args[2] = Boolean.FALSE;
    }

    private static boolean doesLatestNativeDifferFromSession(Object dpc, Session session) {
        if (dpc == null || session == null) return false;
        Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
        Object req = HookUtils.getField(dpc, "mPowerRequest");
        EndpointResolution ep = resolveNativeAodEndpoint(dbc, req);
        Bounds bounds = (ep != null) ? readIntersectionBounds(dpc, dbc, ep.fullMode) : null;
        if (ep == null || bounds == null) return false;
        float latestClamped = clampStrict(ep.brightness, bounds);
        if (!isFinitePositive(latestClamped)) return false;
        synchronized (LOCK) {
            if (sSession == null || sSession.id != session.id) return false;
            if (Math.abs(latestClamped - sSession.sdrTargetBrightness) > TARGET_EPSILON) {
                return true;
            }
            return !sSession.hasDistinctSdrTarget
                    && Math.abs(latestClamped - sSession.targetBrightness) > TARGET_EPSILON;
        }
    }

    private static boolean maybeRetargetCoherentlyBeforeHandoff(
            XC_MethodHook.MethodHookParam param, Object dpc, Session session, EnvSnapshot env) {
        Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
        Object req = HookUtils.getField(dpc, "mPowerRequest");
        if (dbc == null || hasActiveNonSleepRequestOverride(dbc, req)) {
            return false;
        }
        EndpointResolution ep = resolveNativeAodEndpoint(dbc, req);
        Bounds bounds = (ep != null) ? readIntersectionBounds(dpc, dbc, ep.fullMode) : null;
        if (ep == null || bounds == null) {
            return false;
        }
        float latestClamped = clampStrict(ep.brightness, bounds);
        if (!isFinitePositive(latestClamped)) {
            return false;
        }

        final boolean mismatch;
        final boolean armRrc;
        final int durMs;
        final int logCount;
        final float nextHdrTarget;
        final float startB;
        synchronized (LOCK) {
            Session s = sSession;
            if (s == null || s.id != session.id || s.cancelled || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                return false;
            }
            boolean sdrDiff = Math.abs(latestClamped - s.sdrTargetBrightness) > TARGET_EPSILON;
            boolean priDiff = !s.hasDistinctSdrTarget && Math.abs(latestClamped - s.targetBrightness) > TARGET_EPSILON;
            mismatch = sdrDiff || priDiff;
            if (!mismatch) {
                s.targetSource = ep.source;
                return false;
            }
            long remainingMs = s.deadlineMs - env.nowUptimeMs;
            if (remainingMs < MIN_RETARGET_MS) {
                return false;
            }
            s.sdrTargetBrightness = latestClamped;
            if (!s.hasDistinctSdrTarget) {
                s.targetBrightness = latestClamped;
            }
            s.targetSource = ep.source;
            nextHdrTarget = s.targetBrightness;
            durMs = (int) Math.max(MIN_RETARGET_MS, Math.min(NOMINAL_RAMP_MS, remainingMs));
            s.durationMs = durMs;
            if (s.retargetCount < MAX_RETARGETS) {
                s.retargetCount++;
                armRrc = true;
            } else {
                armRrc = false;
            }
            logCount = s.retargetCount;
            startB = s.startBrightness;
            s.phase = Phase.RAMPING;
        }

        Object powerState = HookUtils.getField(dpc, "mPowerState");
        float currentB = resolveCurrentBrightness(dpc, powerState);
        float currentSdrB = resolveCurrentSdrBrightness(powerState);
        float safeCurrent = isFinitePositive(currentB) ? currentB : startB;
        float smoothRate = computeSmoothExpOrLinearRate(startB, safeCurrent, nextHdrTarget, durMs);
        if (!isFinitePositive(smoothRate)) {
            return false;
        }

        if (armRrc) {
            Object dpcImpl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
            Object rrc = (dpcImpl != null) ? HookUtils.getField(dpcImpl, "mRampRateController") : null;
            if (rrc != null) {
                if (isFinitePositive(currentB)) {
                    HookUtils.setField(rrc, "mBrightness", Float.valueOf(currentB));
                }
                if (isFinitePositive(currentSdrB)) {
                    HookUtils.setField(rrc, "mSdrBrightness", Float.valueOf(currentSdrB));
                }
                captureAndArmNativeDozeModifier(session, rrc, durMs);
            }
        }

        Object anim = HookUtils.getField(dpc, "mScreenBrightnessRampAnimator");
        if (anim == null) {
            return false;
        }
        try {
            Method m = findMethod(anim.getClass(), "animateTo",
                    float.class, float.class, float.class, boolean.class, float.class);
            if (m != null) {
                m.invoke(anim, Float.valueOf(nextHdrTarget), Float.valueOf(latestClamped),
                        Float.valueOf(smoothRate), Boolean.TRUE, Float.valueOf(-1.0f));
            }
        } catch (Throwable t) {
            safeLog("OnAodRamp coherent retarget error: " + t);
            return false;
        }
        if (!isAnimatorAnimating(dpc)) {
            return false;
        }
        safeLog("OnAodRamp RETARGET id=" + session.id + " target=" + nextHdrTarget
                + " sdr=" + latestClamped + " source=" + ep.source
                + " remainingMs=" + durMs + " count=" + logCount);
        return true;
    }

    private static void completeSessionAtHandoffOrDeadline(
            XC_MethodHook.MethodHookParam param, Object dpc, int origTarget,
            Session session, EnvSnapshot env, boolean fromDeadline) {
        final float finalTarget;
        final float finalSdr;
        final String targetSource;
        final boolean distinctSdr;
        final long expectedGen;
        synchronized (LOCK) {
            if (sSession == null || sSession.id != session.id || sSession.cancelled
                    || (sSession.phase != Phase.HOLD_AND_SEED && sSession.phase != Phase.RAMPING)) {
                return;
            }
            sSession.phase = Phase.COMPLETING;
            expectedGen = sSession.generation;
            finalTarget = sSession.targetBrightness;
            finalSdr = sSession.sdrTargetBrightness;
            targetSource = sSession.targetSource;
            distinctSdr = sSession.hasDistinctSdrTarget;
        }

        Runnable barrier = sTestBeforeFinalWriteHook;
        if (barrier != null) {
            try { barrier.run(); } catch (Throwable ignored) {}
        }

        if (isFenceBroken(session, expectedGen, dpc)) {
            CleanupSnapshot cs = abortCompletingIfPresent(session.id, "fenced_state_handoff");
            if (cs != null) cleanupIfOnHandlerElsePost(cs, false);
            return;
        }

        float loggedTarget = finalTarget;
        float loggedSdr = finalSdr;
        String loggedSource = targetSource;

        if (fromDeadline) {
            CleanupSnapshot preSnapCs;
            synchronized (LOCK) {
                preSnapCs = (sSession != null && sSession.id == session.id) ? new CleanupSnapshot(sSession) : null;
            }
            restoreOwnedRateState(preSnapCs);
            Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
            Object req = HookUtils.getField(dpc, "mPowerRequest");
            EndpointResolution latestEp = resolveNativeAodEndpoint(dbc, req);
            Bounds bounds = (latestEp != null) ? readIntersectionBounds(dpc, dbc, latestEp.fullMode) : null;
            if (latestEp == null || bounds == null) {
                CleanupSnapshot cs = abortCompletingIfPresent(session.id, "fenced_unknown_endpoint_before_snap");
                if (cs != null) cleanupIfOnHandlerElsePost(cs, false);
                return;
            }
            float cSdr = clampStrict(latestEp.brightness, bounds);
            float cTgt = distinctSdr ? clampStrict(finalTarget, bounds) : cSdr;
            if (!isFinitePositive(cTgt) || !isFinitePositive(cSdr)) {
                CleanupSnapshot cs = abortCompletingIfPresent(session.id, "fenced_invalid_endpoint_before_snap");
                if (cs != null) cleanupIfOnHandlerElsePost(cs, false);
                return;
            }
            snapAnimatorWithFence(dpc, cTgt, cSdr, session, expectedGen);
            loggedTarget = cTgt;
            loggedSdr = cSdr;
            loggedSource = latestEp.source;
        } else {
            Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
            Object req = HookUtils.getField(dpc, "mPowerRequest");
            EndpointResolution latestEp = resolveNativeAodEndpoint(dbc, req);
            Bounds bounds = (latestEp != null) ? readIntersectionBounds(dpc, dbc, latestEp.fullMode) : null;
            if (latestEp == null || bounds == null || !isFinitePositive(clampStrict(latestEp.brightness, bounds))) {
                CleanupSnapshot cs = abortCompletingIfPresent(session.id, "fenced_unknown_endpoint_handoff");
                if (cs != null) cleanupIfOnHandlerElsePost(cs, false);
                return;
            }
            loggedSource = latestEp.source;
        }

        if (isFenceBroken(session, expectedGen, dpc)) {
            CleanupSnapshot cs = abortCompletingIfPresent(session.id, "fenced_after_snap");
            if (cs != null) cleanupIfOnHandlerElsePost(cs, false);
            return;
        }

        CleanupSnapshot finishCleanup;
        synchronized (LOCK) {
            if (sSession == null || sSession.id != session.id) {
                return;
            }
            sSession.phase = Phase.DONE;
            if (sSession.sleepTokenMs > 0L) {
                sConsumedSleepAtMs = sSession.sleepTokenMs;
            }
            sHandoffSuppressColorFadeUntilMs = SystemClock.uptimeMillis() + 500L;
            finishCleanup = new CleanupSnapshot(sSession);
            sSession = null;
        }

        restoreOwnedNativeState(finishCleanup);
        removeTimeoutCallback(finishCleanup);
        safeLog("OnAodRamp " + (fromDeadline ? "TIMEOUT_HANDOFF" : "HANDOFF")
                + " id=" + session.id + " elapsed=" + (env.nowUptimeMs - session.startUptimeMs)
                + "ms target=" + loggedTarget + " sdr=" + loggedSdr
                + " source=" + loggedSource + " retargets=" + session.retargetCount);

        param.args[0] = Integer.valueOf(DISPLAY_STATE_DOZE);
        param.args[2] = Boolean.FALSE;
    }

    private static void tryStartNewSession(
            XC_MethodHook.MethodHookParam param, Object dpc, Object req,
            int origTarget, int policy, EnvSnapshot env) {
        if (policy != POLICY_OFF && policy != POLICY_DOZE) {
            return;
        }
        long elapsed = env.nowUptimeMs - env.sleepTokenMs;
        if (elapsed < 0L || elapsed >= SLEEP_WINDOW_MS) {
            return;
        }
        synchronized (LOCK) {
            if (env.sleepTokenMs == sConsumedSleepAtMs || sSession != null) {
                return;
            }
        }

        // 1. Validate handler & thread affinity.
        Handler h = getHandler(dpc);
        if (h == null || h.getLooper() == null || Looper.myLooper() != h.getLooper()) {
            logSparse(env.sleepTokenMs, "preflight_no_handler_affinity");
            return;
        }

        // 2. Validate current screen state & ensure no native waits/animators/blockers already armed.
        Object powerState = HookUtils.getField(dpc, "mPowerState");
        if (powerState == null || getPowerStateScreenState(powerState) != DISPLAY_STATE_ON) {
            logSparse(env.sleepTokenMs, "preflight_not_state_on");
            return;
        }
        if (hasNativeArmedWaitsOrBlockers(dpc)) {
            logSparse(env.sleepTokenMs, "preflight_native_wait_armed");
            return;
        }

        // 3. Reject if a non-sleep brightness override is active.
        Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
        if (dbc == null || hasActiveNonSleepRequestOverride(dbc, req)) {
            logSparse(env.sleepTokenMs, "preflight_override_or_no_dbc");
            return;
        }

        // 4. Validate DozeStrategy, Builder, RampRateController, Animator.
        Object dozeStrategy = invokeNoArg(dbc, "getDozeBrightnessStrategy");
        if (dozeStrategy == null || !hasDisplayBrightnessStateBuilder(dpc.getClass().getClassLoader())) {
            logSparse(env.sleepTokenMs, "preflight_missing_strategy_or_builder");
            return;
        }
        Object dpcImpl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
        Object rrc = (dpcImpl != null) ? HookUtils.getField(dpcImpl, "mRampRateController") : null;
        if (!hasRampRateCapabilities(rrc) || !hasAnimatorCapabilities(dpc)) {
            logSparse(env.sleepTokenMs, "preflight_missing_ramp_caps");
            return;
        }

        // 5. Measure live start brightness, native AOD endpoint, and mode-appropriate bounds (ZERO guessing).
        float startB = resolveCurrentBrightness(dpc, powerState);
        float startSdrB = resolveCurrentSdrBrightness(powerState);
        if (!isFinitePositive(startB) || !isFinitePositive(startSdrB)) {
            logSparse(env.sleepTokenMs, "preflight_invalid_start_brightness");
            return;
        }
        EndpointResolution ep = resolveNativeAodEndpoint(dbc, req);
        if (ep == null || !isFinitePositive(ep.brightness)) {
            logSparse(env.sleepTokenMs, "preflight_unknown_or_no_native_endpoint");
            return;
        }
        Bounds bounds = readIntersectionBounds(dpc, dbc, ep.fullMode);
        if (bounds == null) {
            logSparse(env.sleepTokenMs, "preflight_missing_bounds");
            return;
        }
        float initialTgt = clampStrict(ep.brightness, bounds);
        float initialSdrTgt = initialTgt;
        if (!isFinitePositive(initialTgt) || startB <= initialTgt + TARGET_EPSILON) {
            // No valid AOD target or already at/below target: fail closed cleanly to protected 54aa.
            logSparse(env.sleepTokenMs, "preflight_already_below_or_out_of_bounds");
            return;
        }

        // 6. Post deadline runnable BEFORE committing session (fail closed if postDelayed throws or returns false).
        final long sid;
        final long gen;
        synchronized (LOCK) {
            if (sSession != null || env.sleepTokenMs == sConsumedSleepAtMs) {
                return;
            }
            sid = sNextSessionId++;
            gen = ++sGeneration;
        }

        final Session created = new Session(sid, gen, env.sleepTokenMs, env.nowUptimeMs, dpc, h,
                startB, startSdrB, initialTgt, initialSdrTgt, ep.source);
        Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                onDeadlineRunnable(created);
            }
        };
        created.timeoutTask = timeoutTask;

        boolean posted = false;
        try {
            posted = h.postDelayed(timeoutTask, MAX_SESSION_MS);
        } catch (Throwable t) {
            logSparse(env.sleepTokenMs, "preflight_post_delayed_throw");
            return;
        }
        if (!posted) {
            logSparse(env.sleepTokenMs, "preflight_post_delayed_false");
            return;
        }

        boolean committed = false;
        synchronized (LOCK) {
            if (sSession == null && sGeneration == gen && env.sleepTokenMs != sConsumedSleepAtMs) {
                sSession = created;
                committed = true;
            }
        }
        if (!committed) {
            safeRemoveCallbacks(h, timeoutTask);
            return;
        }

        safeLog("OnAodRamp START id=" + created.id
                + " token=" + created.sleepTokenMs
                + " policy=" + policy
                + " targetState=" + origTarget
                + " start=" + created.startBrightness
                + " startSdr=" + created.startSdrBrightness
                + " seedTarget=" + created.targetBrightness
                + " mode=" + (ep.fullMode ? "full" : "legacy")
                + " source=" + created.targetSource);
        try {
            StateCoordinator.ensureQihangThawed();
        } catch (Throwable ignored) {}

        // Let native animateScreenStateChange run as (DOZE, performScreenOffTransition=false):
        // cleans curtain & sets setWaitingScreenOffAnimator(false) naturally while our hook holds ON(2).
        param.args[0] = Integer.valueOf(DISPLAY_STATE_DOZE);
        param.args[2] = Boolean.FALSE;
    }

    private static void onDeadlineRunnable(Session session) {
        try {
            Object dpc = session.dpcRef.get();
            EnvSnapshot env = readEnvSnapshotSafe();
            int policy = (dpc != null) ? getPolicyFromRequest(HookUtils.getField(dpc, "mPowerRequest"), -1) : -1;

            final float finalTarget;
            final boolean distinctSdr;
            final long expectedGen;
            CleanupSnapshot abortCleanup = null;

            synchronized (LOCK) {
                if (sSession == null || sSession.id != session.id) {
                    return;
                }
                if (sSession.cancelled || sSession.generation != session.generation
                        || !env.isValidForRamp() || (policy != POLICY_OFF && policy != POLICY_DOZE)) {
                    sSession.cancelled = true;
                    sSession.phase = Phase.CANCELLED;
                    if (sSession.sleepTokenMs > 0L) {
                        sConsumedSleepAtMs = sSession.sleepTokenMs;
                    }
                    abortCleanup = new CleanupSnapshot(sSession);
                    sSession = null;
                    sGeneration++;
                    sHandoffSuppressColorFadeUntilMs = -1L;
                    finalTarget = Float.NaN;
                    distinctSdr = false;
                    expectedGen = 0L;
                } else {
                    sSession.phase = Phase.COMPLETING;
                    expectedGen = sSession.generation;
                    finalTarget = sSession.targetBrightness;
                    distinctSdr = sSession.hasDistinctSdrTarget;
                }
            }

            if (abortCleanup != null) {
                safeLog("OnAodRamp DEADLINE_CANCELLED id=" + session.id + " envOrPolicyInvalid");
                restoreOwnedNativeState(abortCleanup);
                requestDpcUpdate(dpc);
                return;
            }

            Runnable barrier = sTestBeforeFinalWriteHook;
            if (barrier != null) {
                try { barrier.run(); } catch (Throwable ignored) {}
            }

            if (isFenceBroken(session, expectedGen, dpc)) {
                CleanupSnapshot fenced = abortCompletingIfPresent(session.id, "deadline_fenced_before_snap");
                if (fenced != null) {
                    restoreOwnedNativeState(fenced);
                    safeLog("OnAodRamp DEADLINE_FENCED_ABORT id=" + session.id);
                    requestDpcUpdate(dpc);
                }
                return;
            }

            CleanupSnapshot preSnapCs;
            synchronized (LOCK) {
                preSnapCs = (sSession != null && sSession.id == session.id) ? new CleanupSnapshot(sSession) : null;
            }
            restoreOwnedRateState(preSnapCs);

            Object dbc = (dpc != null) ? HookUtils.getField(dpc, "mDisplayBrightnessController") : null;
            Object req = (dpc != null) ? HookUtils.getField(dpc, "mPowerRequest") : null;
            EndpointResolution latestEp = resolveNativeAodEndpoint(dbc, req);
            Bounds bounds = (latestEp != null) ? readIntersectionBounds(dpc, dbc, latestEp.fullMode) : null;
            if (latestEp == null || bounds == null || !isFinitePositive(latestEp.brightness)) {
                CleanupSnapshot fenced = abortCompletingIfPresent(session.id, "deadline_unknown_or_invalid_endpoint");
                if (fenced != null) {
                    restoreOwnedNativeState(fenced);
                    safeLog("OnAodRamp DEADLINE_FENCED_ABORT id=" + session.id + " unknownEndpoint");
                    requestDpcUpdate(dpc);
                }
                return;
            }
            float snapSdr = clampStrict(latestEp.brightness, bounds);
            float snapTgt = distinctSdr ? clampStrict(finalTarget, bounds) : snapSdr;
            if (!isFinitePositive(snapTgt) || !isFinitePositive(snapSdr)) {
                CleanupSnapshot fenced = abortCompletingIfPresent(session.id, "deadline_clamped_invalid");
                if (fenced != null) {
                    restoreOwnedNativeState(fenced);
                    requestDpcUpdate(dpc);
                }
                return;
            }

            snapAnimatorWithFence(dpc, snapTgt, snapSdr, session, expectedGen);

            if (isFenceBroken(session, expectedGen, dpc)) {
                CleanupSnapshot fenced = abortCompletingIfPresent(session.id, "deadline_fenced_after_snap");
                if (fenced != null) {
                    restoreOwnedNativeState(fenced);
                    requestDpcUpdate(dpc);
                }
                return;
            }

            CleanupSnapshot doneCleanup;
            synchronized (LOCK) {
                if (sSession == null || sSession.id != session.id) {
                    return;
                }
                sSession.phase = Phase.DONE;
                if (sSession.sleepTokenMs > 0L) {
                    sConsumedSleepAtMs = sSession.sleepTokenMs;
                }
                sHandoffSuppressColorFadeUntilMs = SystemClock.uptimeMillis() + 500L;
                doneCleanup = new CleanupSnapshot(sSession);
                sSession = null;
            }

            restoreOwnedNativeState(doneCleanup);
            safeLog("OnAodRamp DEADLINE id=" + session.id + " elapsed="
                    + (SystemClock.uptimeMillis() - session.startUptimeMs)
                    + "ms -> handoff tgt=" + snapTgt + " sdr=" + snapSdr
                    + " source=" + latestEp.source);
            requestDpcUpdate(dpc);
        } catch (Throwable t) {
            abortActiveSessionSafely("err_deadline", t);
        }
    }

    private static void onAfterDbcUpdateBrightness(XC_MethodHook.MethodHookParam param) {
        try {
            if (param == null || param.thisObject == null) return;
            Object dbc = param.thisObject;
            Integer displayId = resolveDisplayIdStrict(dbc);
            if (displayId == null || displayId.intValue() != 0) {
                return;
            }
            if (!hasTrackedSession()) {
                return;
            }

            EnvSnapshot env = readEnvSnapshotSafe();
            Object req = (param.args != null && param.args.length >= 1) ? param.args[0] : null;
            int policy = getPolicyFromRequest(req, -1);

            final Session active;
            final Object dpc;
            CleanupSnapshot cancelCleanup = null;
            String cancelReason = null;

            synchronized (LOCK) {
                Session s = sSession;
                if (s == null || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    return;
                }
                dpc = s.dpcRef.get();
                if (s.cancelled) {
                    cancelReason = "dbc_already_cancelled";
                } else if (policy == POLICY_DIM || policy == POLICY_BRIGHT) {
                    cancelReason = "dbc_wake_policy_" + policy;
                } else if (!env.isValidForRamp()) {
                    cancelReason = env.propEnabled ? "dbc_env_invalid" : "dbc_prop_disabled";
                } else if (env.nowUptimeMs >= s.deadlineMs) {
                    cancelReason = "dbc_deadline_expired";
                }
                if (cancelReason != null) {
                    s.cancelled = true;
                    s.cancelReason = cancelReason;
                    s.phase = Phase.CANCELLED;
                    if (s.sleepTokenMs > 0L) {
                        sConsumedSleepAtMs = s.sleepTokenMs;
                    }
                    cancelCleanup = new CleanupSnapshot(s);
                    sSession = null;
                    sGeneration++;
                }
                active = s;
            }

            if (cancelCleanup != null) {
                safeLog("OnAodRamp CANCEL reason=" + cancelReason + " id=" + active.id);
                cleanupIfOnHandlerElsePost(cancelCleanup, true);
                return;
            }
            if (policy != POLICY_OFF && policy != POLICY_DOZE) {
                return;
            }
            if (dpc == null || !isOnDpcHandlerThread(dpc)) {
                cancelExistingSessionOutsideLock("dbc_off_handler", true, true);
                return;
            }

            Object origResult = param.getResult();
            if (origResult == null) {
                cancelExistingSessionOutsideLock("dbc_null_orig_result", true, true);
                return;
            }

            String origStrategy = readStrategyName(origResult);
            if (isLegitimateNonSleepOverride(origStrategy, dbc, req)) {
                // Guard legitimate override: yield cleanly and do not overwrite strategy or result.
                cancelExistingSessionOutsideLock("dbc_non_sleep_override_" + origStrategy, true, true);
                return;
            }

            float origBrightness = readStateBrightness(origResult);
            EndpointResolution ep = resolveNativeAodEndpoint(dbc, req);
            Bounds bounds = (ep != null) ? readIntersectionBounds(dpc, dbc, ep.fullMode) : null;
            if (ep == null || bounds == null || !isFinitePositive(ep.brightness)) {
                cancelExistingSessionOutsideLock("dbc_unknown_or_invalid_endpoint", true, true);
                return;
            }
            float clampedCandidate = clampStrict(ep.brightness, bounds);
            if (!isFinitePositive(clampedCandidate)) {
                cancelExistingSessionOutsideLock("dbc_clamped_invalid", true, true);
                return;
            }

            Object dozeStrategy = invokeNoArg(dbc, "getDozeBrightnessStrategy");
            if (dozeStrategy == null) {
                cancelExistingSessionOutsideLock("dbc_missing_doze_strategy", true, true);
                return;
            }

            final boolean rearmed;
            final int durationForLog;
            final int countForLog;
            synchronized (LOCK) {
                Session s = sSession;
                if (s == null || s.id != active.id || s.cancelled || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    return;
                }
                // Always track latest authoritative native endpoint, preserving distinct HDR when active.
                float prevPri = s.targetBrightness;
                float prevSdr = s.sdrTargetBrightness;
                float nextPri = s.hasDistinctSdrTarget ? prevPri : clampedCandidate;
                float nextSdr = clampedCandidate;
                boolean deltaPri = Math.abs(nextPri - prevPri) > TARGET_EPSILON;
                boolean deltaSdr = Math.abs(nextSdr - prevSdr) > TARGET_EPSILON;
                if (deltaPri || deltaSdr) {
                    s.targetBrightness = nextPri;
                    s.sdrTargetBrightness = nextSdr;
                    s.targetSource = ep.source;
                    if (s.seeded && (deltaPri || deltaSdr) && s.retargetCount < MAX_RETARGETS) {
                        long elapsedMs = Math.max(0L, env.nowUptimeMs - s.startUptimeMs);
                        s.durationMs = (int) Math.max(MIN_RETARGET_MS,
                                Math.min(NOMINAL_RAMP_MS, NOMINAL_RAMP_MS - elapsedMs));
                        s.retargetCount++;
                        rearmed = true;
                    } else {
                        rearmed = false;
                    }
                } else {
                    s.targetSource = ep.source;
                    rearmed = false;
                }
                durationForLog = s.durationMs;
                countForLog = s.retargetCount;
            }

            if (rearmed) {
                safeLog("OnAodRamp RETARGET id=" + active.id + " target=" + clampedCandidate
                        + " source=" + ep.source + " remainingMs=" + durationForLog + " count=" + countForLog);
            }

            boolean needRebuild = !DOZE_STRATEGY_NAME.equals(origStrategy)
                    || Math.abs(origBrightness - clampedCandidate) > 1e-6f
                    || !readIsSlowChange(origResult);

            if (needRebuild) {
                Object rebuilt = buildDozeDisplayBrightnessStateFrom(
                        origResult, clampedCandidate, !DOZE_STRATEGY_NAME.equals(origStrategy));
                if (rebuilt == null) {
                    cancelExistingSessionOutsideLock("dbc_builder_failed", true, true);
                    return;
                }
                captureAndApplyDbcStrategy(active, dbc, dozeStrategy);
                param.setResult(rebuilt);
            }
        } catch (Throwable t) {
            abortActiveSessionSafely("err_dbc_update", t);
        }
    }

    private static void onBeforeAnimateScreenBrightness(XC_MethodHook.MethodHookParam param) {
        try {
            if (param == null || param.thisObject == null || param.args == null || param.args.length < 3) {
                return;
            }
            Object dpc = param.thisObject;
            Integer displayId = getIntField(dpc, "mDisplayId");
            if (displayId == null || displayId.intValue() != 0) {
                recordSecondaryComponentsOutsideLock(dpc);
                return;
            }
            if (!hasTrackedSession()) {
                return;
            }

            EnvSnapshot env = readEnvSnapshotSafe();
            Object req = HookUtils.getField(dpc, "mPowerRequest");
            int policy = getPolicyFromRequest(req, -1);

            if (!isOnDpcHandlerThread(dpc)) {
                cancelExistingSessionOutsideLock("bright_off_handler", true, true);
                return;
            }

            Object powerState = HookUtils.getField(dpc, "mPowerState");
            Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
            float currentB = resolveCurrentBrightness(dpc, powerState);
            float currentSdrB = resolveCurrentSdrBrightness(powerState);

            final float argTarget = (param.args[0] instanceof Float) ? ((Float) param.args[0]).floatValue() : Float.NaN;
            final float argSdr = (param.args[1] instanceof Float) ? ((Float) param.args[1]).floatValue() : Float.NaN;
            final float argRate = (param.args[2] instanceof Float) ? ((Float) param.args[2]).floatValue() : 0.0f;

            final Session active;
            CleanupSnapshot cancelCleanup = null;
            String cancelReason = null;

            synchronized (LOCK) {
                Session s = sSession;
                if (s == null || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    return;
                }
                if (s.cancelled) {
                    cancelReason = "bright_already_cancelled";
                } else if (policy == POLICY_DIM || policy == POLICY_BRIGHT) {
                    cancelReason = "bright_wake_policy_" + policy;
                } else if (!env.isValidForRamp()) {
                    cancelReason = env.propEnabled ? "bright_env_invalid" : "bright_prop_disabled";
                } else if (env.nowUptimeMs >= s.deadlineMs) {
                    cancelReason = "bright_deadline_expired";
                }
                if (cancelReason != null) {
                    s.cancelled = true;
                    s.cancelReason = cancelReason;
                    s.phase = Phase.CANCELLED;
                    if (s.sleepTokenMs > 0L) {
                        sConsumedSleepAtMs = s.sleepTokenMs;
                    }
                    cancelCleanup = new CleanupSnapshot(s);
                    sSession = null;
                    sGeneration++;
                }
                active = s;
            }

            if (cancelCleanup != null) {
                safeLog("OnAodRamp CANCEL reason=" + cancelReason + " id=" + active.id);
                cleanupIfOnHandlerElsePost(cancelCleanup, true);
                return;
            }

            EndpointResolution ep = resolveNativeAodEndpoint(dbc, req);
            Bounds bounds = (ep != null) ? readIntersectionBounds(dpc, dbc, ep.fullMode) : null;
            if (ep == null || bounds == null || !isFinitePositive(ep.brightness)
                    || !isFinitePositive(argTarget) || !isFinitePositive(argSdr)) {
                cancelExistingSessionOutsideLock("bright_unknown_or_invalid_args", true, true);
                return;
            }

            float authTarget = clampStrict(ep.brightness, bounds);
            boolean distinctHdr = Math.abs(argTarget - argSdr) > TARGET_EPSILON;
            float clampedTarget = distinctHdr ? clampStrict(argTarget, bounds) : authTarget;
            float clampedSdr = authTarget;
            if (!isFinitePositive(clampedTarget) || !isFinitePositive(clampedSdr)) {
                cancelExistingSessionOutsideLock("bright_clamped_invalid", true, true);
                return;
            }

            boolean shouldArmRrc = false;
            int durationMs;
            float startB;
            final boolean needRetargetLog;
            final int logRetargetCount;
            final boolean isSeedCall;

            synchronized (LOCK) {
                Session s = sSession;
                if (s == null || s.id != active.id || s.cancelled || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    return;
                }
                boolean primaryDelta = Math.abs(clampedTarget - s.targetBrightness) > TARGET_EPSILON;
                boolean sdrDelta = Math.abs(clampedSdr - s.sdrTargetBrightness) > TARGET_EPSILON;

                if (!s.seeded) {
                    s.targetBrightness = clampedTarget;
                    s.sdrTargetBrightness = clampedSdr;
                    s.targetSource = ep.source;
                    s.hasDistinctSdrTarget = Math.abs(clampedSdr - clampedTarget) > TARGET_EPSILON;
                    shouldArmRrc = true;
                    needRetargetLog = false;
                    isSeedCall = true;
                } else {
                    // NEVER drop latest valid target/sdrTarget even if retargetCount >= MAX_RETARGETS.
                    if (primaryDelta || sdrDelta) {
                        s.targetBrightness = clampedTarget;
                        s.sdrTargetBrightness = clampedSdr;
                        s.targetSource = ep.source;
                        s.hasDistinctSdrTarget = Math.abs(clampedSdr - clampedTarget) > TARGET_EPSILON;
                        if (s.retargetCount < MAX_RETARGETS) {
                            long elapsedMs = Math.max(0L, env.nowUptimeMs - s.startUptimeMs);
                            s.durationMs = (int) Math.max(MIN_RETARGET_MS,
                                    Math.min(NOMINAL_RAMP_MS, NOMINAL_RAMP_MS - elapsedMs));
                            s.retargetCount++;
                            shouldArmRrc = true;
                            needRetargetLog = true;
                        } else {
                            // Keep smoothing rate bounded toward deadline without churning RampRateController.
                            long remainingToDeadline = Math.max(MIN_RETARGET_MS, s.deadlineMs - env.nowUptimeMs);
                            s.durationMs = (int) Math.min(NOMINAL_RAMP_MS, remainingToDeadline);
                            shouldArmRrc = false;
                            needRetargetLog = false;
                        }
                    } else {
                        s.targetSource = ep.source;
                        needRetargetLog = false;
                    }
                    isSeedCall = false;
                }
                durationMs = s.durationMs;
                startB = s.startBrightness;
                logRetargetCount = s.retargetCount;
                s.seeded = true;
                s.phase = Phase.RAMPING;
            }

            if (isSeedCall) {
                safeLog("OnAodRamp SEED id=" + active.id + " target=" + clampedTarget
                        + " sdr=" + clampedSdr + " source=" + ep.source);
            } else if (needRetargetLog) {
                safeLog("OnAodRamp RETARGET id=" + active.id + " target=" + clampedTarget
                        + " sdr=" + clampedSdr + " source=" + ep.source
                        + " remainingMs=" + durationMs + " count=" + logRetargetCount);
            }

            param.args[0] = Float.valueOf(clampedTarget);
            param.args[1] = Float.valueOf(clampedSdr);

            if (!Float.isFinite(argRate) || argRate <= 0.0f) {
                float safeCurrent = isFinitePositive(currentB) ? currentB : startB;
                float computed = computeSmoothExpOrLinearRate(startB, safeCurrent, clampedTarget, durationMs);
                if (!isFinitePositive(computed)) {
                    cancelExistingSessionOutsideLock("bright_invalid_rate", true, true);
                    return;
                }
                param.args[2] = Float.valueOf(computed);
            }

            Object dpcImpl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
            Object rrc = (dpcImpl != null) ? HookUtils.getField(dpcImpl, "mRampRateController") : null;
            if (rrc != null && (shouldArmRrc || isSeedCall)) {
                sPrimaryRampRateRef = new WeakReference<>(rrc);
                if (isFinitePositive(currentB)) {
                    HookUtils.setField(rrc, "mBrightness", Float.valueOf(currentB));
                }
                if (isFinitePositive(currentSdrB)) {
                    HookUtils.setField(rrc, "mSdrBrightness", Float.valueOf(currentSdrB));
                }
                captureAndArmNativeDozeModifier(active, rrc, durationMs);
            }
        } catch (Throwable t) {
            abortActiveSessionSafely("err_animate_brightness", t);
        }
    }

    private static void onAfterRampRateUpdateState(XC_MethodHook.MethodHookParam param) {
        try {
            if (param == null || param.thisObject == null || !hasTrackedSession()) {
                return;
            }
            Object rrc = param.thisObject;
            if (!isPrimaryRampRateController(rrc)) {
                return;
            }
            EnvSnapshot env = readEnvSnapshotSafe();
            final Session active;
            final int durationMs;
            final float fallbackStart;
            final float fallbackStartSdr;
            CleanupSnapshot cancelCleanup = null;
            String cancelReason = null;

            synchronized (LOCK) {
                Session s = sSession;
                if (s == null || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    return;
                }
                if (s.cancelled) {
                    cancelReason = "rrc_state_cancelled";
                } else if (!env.isValidForRamp()) {
                    cancelReason = env.propEnabled ? "rrc_state_env_invalid" : "rrc_state_prop_off";
                } else if (env.nowUptimeMs >= s.deadlineMs) {
                    cancelReason = "rrc_state_deadline";
                }
                if (cancelReason != null) {
                    s.cancelled = true;
                    s.phase = Phase.CANCELLED;
                    if (s.sleepTokenMs > 0L) {
                        sConsumedSleepAtMs = s.sleepTokenMs;
                    }
                    cancelCleanup = new CleanupSnapshot(s);
                    sSession = null;
                    sGeneration++;
                    durationMs = 0;
                    fallbackStart = Float.NaN;
                    fallbackStartSdr = Float.NaN;
                } else {
                    durationMs = s.durationMs;
                    fallbackStart = s.startBrightness;
                    fallbackStartSdr = s.startSdrBrightness;
                }
                active = s;
            }

            if (cancelCleanup != null) {
                safeLog("OnAodRamp CANCEL reason=" + cancelReason + " id=" + active.id);
                cleanupIfOnHandlerElsePost(cancelCleanup, true);
                return;
            }

            Object dpc = active.dpcRef.get();
            if (!isOnDpcHandlerThread(dpc)) {
                cancelExistingSessionOutsideLock("rrc_state_off_handler", true, true);
                return;
            }

            captureAndArmNativeDozeModifier(active, rrc, durationMs);
            Object mBrightnessObj = HookUtils.getField(rrc, "mBrightness");
            if (!(mBrightnessObj instanceof Float) || !isFinitePositive(((Float) mBrightnessObj).floatValue())) {
                HookUtils.setField(rrc, "mBrightness", Float.valueOf(fallbackStart));
            }
            Object mSdrBrightnessObj = HookUtils.getField(rrc, "mSdrBrightness");
            if (!(mSdrBrightnessObj instanceof Float) || !isFinitePositive(((Float) mSdrBrightnessObj).floatValue())) {
                HookUtils.setField(rrc, "mSdrBrightness", Float.valueOf(fallbackStartSdr));
            }
        } catch (Throwable t) {
            abortActiveSessionSafely("err_rrc_state", t);
        }
    }

    private static void onAfterRampRateUpdateRate(XC_MethodHook.MethodHookParam param) {
        applyExpRateFloorOrCancelIfActive(param, param != null ? param.thisObject : null);
    }

    private static void onAfterImplUpdateRampRate(XC_MethodHook.MethodHookParam param) {
        try {
            if (param == null || param.thisObject == null || !hasTrackedSession()) {
                return;
            }
            Integer displayId = getIntField(param.thisObject, "mDisplayId");
            if (displayId == null || displayId.intValue() != 0) {
                return;
            }
            Object rrc = HookUtils.getField(param.thisObject, "mRampRateController");
            applyExpRateFloorOrCancelIfActive(param, rrc);
        } catch (Throwable t) {
            abortActiveSessionSafely("err_impl_rate", t);
        }
    }

    private static void applyExpRateFloorOrCancelIfActive(XC_MethodHook.MethodHookParam param, Object rrc) {
        try {
            if (param == null || param.args == null || param.args.length < 4 || !hasTrackedSession()) {
                return;
            }
            if (rrc != null && !isPrimaryRampRateController(rrc)) {
                return;
            }
            if (rrc == null && !isKnownPrimaryComponent(param.thisObject)) {
                return;
            }

            EnvSnapshot env = readEnvSnapshotSafe();
            final Session active;
            final float startB;
            final float startSdrB;
            final int durationMs;
            CleanupSnapshot cancelCleanup = null;
            String cancelReason = null;

            synchronized (LOCK) {
                Session s = sSession;
                if (s == null || s.phase == Phase.COMPLETING || s.phase == Phase.DONE) {
                    return;
                }
                if (s.cancelled) {
                    cancelReason = "vsync_rate_cancelled";
                } else if (!env.isValidForRamp()) {
                    cancelReason = env.propEnabled ? "vsync_env_invalid" : "vsync_prop_disabled";
                } else if (env.nowUptimeMs >= s.deadlineMs) {
                    cancelReason = "vsync_deadline_expired";
                }
                if (cancelReason != null) {
                    s.cancelled = true;
                    s.cancelReason = cancelReason;
                    s.phase = Phase.CANCELLED;
                    if (s.sleepTokenMs > 0L) {
                        sConsumedSleepAtMs = s.sleepTokenMs;
                    }
                    cancelCleanup = new CleanupSnapshot(s);
                    sSession = null;
                    sGeneration++;
                    startB = Float.NaN;
                    startSdrB = Float.NaN;
                    durationMs = 0;
                } else {
                    startB = s.startBrightness;
                    startSdrB = s.startSdrBrightness;
                    durationMs = s.durationMs;
                }
                active = s;
            }

            if (cancelCleanup != null) {
                // Critical: when owner loss/prop-off occurs during VSync rate hook, cancel & request DPC
                // reevaluation immediately so the ramp does not keep running or emit stale writes.
                safeLog("OnAodRamp CANCEL reason=" + cancelReason + " id=" + active.id);
                cleanupIfOnHandlerElsePost(cancelCleanup, true);
                return;
            }

            Object dpc = active.dpcRef.get();
            if (!isOnDpcHandlerThread(dpc)) {
                cancelExistingSessionOutsideLock("vsync_off_handler", true, true);
                return;
            }

            String propName = (param.args[0] instanceof String) ? (String) param.args[0] : "";
            boolean isSdrLane = propName.toLowerCase().contains("sdr");
            float laneStart = isSdrLane ? startSdrB : startB;

            float currentB = (param.args[1] instanceof Float) ? ((Float) param.args[1]).floatValue() : Float.NaN;
            float targetB = (param.args[2] instanceof Float) ? ((Float) param.args[2]).floatValue() : Float.NaN;
            Object resObj = param.getResult();
            float outRate = (resObj instanceof Float) ? ((Float) resObj).floatValue() : Float.NaN;

            if (!Float.isFinite(outRate) || outRate <= 0.0f) {
                if (!isFinitePositive(targetB) || !isFinitePositive(currentB)) {
                    // Fail closed rather than inventing a fallback target/rate.
                    cancelExistingSessionOutsideLock("vsync_invalid_target_or_current", true, true);
                    return;
                }
                float fallback = computeSmoothExpOrLinearRate(laneStart, currentB, targetB, durationMs);
                if (!isFinitePositive(fallback)) {
                    cancelExistingSessionOutsideLock("vsync_invalid_computed_rate", true, true);
                    return;
                }
                param.setResult(Float.valueOf(fallback));
            }
        } catch (Throwable t) {
            abortActiveSessionSafely("err_vsync_rate", t);
        }
    }

    // =========================================================================
    // Cleanup, Ownership Capture/Restore, and Handler Dispatch
    // =========================================================================

    private static void captureAndApplyDbcStrategy(Session session, Object dbc, Object dozeStrategy) {
        if (session == null || dbc == null || dozeStrategy == null) return;
        boolean needCapture;
        synchronized (LOCK) {
            needCapture = !session.strategyCaptured;
        }
        Object prevStrategy = null;
        if (needCapture) {
            prevStrategy = HookUtils.getField(dbc, "mDisplayBrightnessStrategy");
            synchronized (LOCK) {
                if (!session.strategyCaptured) {
                    session.strategyCaptured = true;
                    session.dbcRef = new WeakReference<>(dbc);
                    session.savedOrigStrategyRef = new WeakReference<>(prevStrategy);
                    session.appliedDozeStrategyRef = new WeakReference<>(dozeStrategy);
                }
            }
        }
        HookUtils.setField(dbc, "mDisplayBrightnessStrategy", dozeStrategy);
    }

    private static void captureAndArmNativeDozeModifier(Session session, Object rrc, int durationMs) {
        if (session == null || rrc == null) return;
        boolean needCapture;
        synchronized (LOCK) {
            needCapture = !session.rateCaptured;
        }
        int origDur = NOMINAL_RAMP_MS;
        long origTime = 0L;
        boolean origHadMod = false;
        Object rateRecord = HookUtils.getField(rrc, "mRateRecord");
        if (needCapture && rateRecord != null) {
            Integer durField = getIntField(rrc, "mAnimationDurationDozeDimming");
            origDur = (durField != null) ? durField.intValue() : NOMINAL_RAMP_MS;
            origTime = readRateRecordDozeTime(rateRecord);
            origHadMod = readRateRecordHasDozeMod(rateRecord);
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        HookUtils.setField(rrc, "mAnimationDurationDozeDimming", Integer.valueOf(durationMs));
        if (rateRecord != null) {
            // Add modifier bit 4 directly (already higher priority than 1|2|8|16|32|128|256|1024 in
            // RampRateController.updateBrightnessRate) without blindly wiping unrelated modifiers (1435).
            invokeLongArg(rateRecord, "setDozeDimmingTimeMills", nowElapsed);
            invokeIntArg(rateRecord, "addRateModifier", RATE_MODIFIER_DOZE);
        }

        synchronized (LOCK) {
            if (!session.rateCaptured) {
                session.rateCaptured = true;
                session.rrcRef = new WeakReference<>(rrc);
                session.origDurationDozeDimming = origDur;
                session.origDozeTimeMillis = origTime;
                session.origHadDozeMod = origHadMod;
            }
            session.appliedDurationDozeDimming = durationMs;
            session.appliedDozeTimeMillis = nowElapsed;
        }
    }

    private static void restoreOwnedNativeState(CleanupSnapshot cs) {
        if (cs == null) return;
        restoreOwnedStrategyState(cs);
        restoreOwnedRateState(cs);
    }

    private static void restoreOwnedStrategyState(CleanupSnapshot cs) {
        if (cs == null) return;
        Object dpc = (cs.dpcRef != null) ? cs.dpcRef.get() : null;
        if (dpc != null && !isOnDpcHandlerThread(dpc)) {
            return;
        }
        if (cs.strategyCaptured) {
            Object dbc = (cs.dbcRef != null) ? cs.dbcRef.get() : null;
            Object appliedDoze = (cs.appliedDozeStrategyRef != null) ? cs.appliedDozeStrategyRef.get() : null;
            Object prevOrig = (cs.savedOrigStrategyRef != null) ? cs.savedOrigStrategyRef.get() : null;
            if (dbc != null && appliedDoze != null) {
                try {
                    Object curStrat = HookUtils.getField(dbc, "mDisplayBrightnessStrategy");
                    if (curStrat == appliedDoze && prevOrig != null) {
                        HookUtils.setField(dbc, "mDisplayBrightnessStrategy", prevOrig);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void restoreOwnedRateState(CleanupSnapshot cs) {
        if (cs == null) return;
        Object dpc = (cs.dpcRef != null) ? cs.dpcRef.get() : null;
        if (dpc != null && !isOnDpcHandlerThread(dpc)) {
            return;
        }
        if (cs.rateCaptured) {
            Object rrc = (cs.rrcRef != null) ? cs.rrcRef.get() : null;
            if (rrc != null) {
                try {
                    Integer curDur = getIntField(rrc, "mAnimationDurationDozeDimming");
                    if (curDur != null && curDur.intValue() == cs.appliedDurationDozeDimming) {
                        HookUtils.setField(rrc, "mAnimationDurationDozeDimming",
                                Integer.valueOf(cs.origDurationDozeDimming));
                    }
                    Object rateRecord = HookUtils.getField(rrc, "mRateRecord");
                    if (rateRecord != null) {
                        long curTime = readRateRecordDozeTime(rateRecord);
                        if (curTime == cs.appliedDozeTimeMillis) {
                            invokeLongArg(rateRecord, "setDozeDimmingTimeMills", cs.origDozeTimeMillis);
                            if (!cs.origHadDozeMod) {
                                invokeIntArg(rateRecord, "clearRateModifier", RATE_MODIFIER_DOZE);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void postCleanupAndReevaluate(final CleanupSnapshot cs) {
        if (cs == null) return;
        Handler h = (cs.handlerRef != null) ? cs.handlerRef.get() : null;
        if (h == null) {
            h = getHandler(cs.dpcRef != null ? cs.dpcRef.get() : null);
        }
        if (h == null) return;
        safeRemoveCallbacks(h, cs.timeoutTask);
        try {
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        restoreOwnedNativeState(cs);
                        Object dpc = (cs.dpcRef != null) ? cs.dpcRef.get() : null;
                        requestDpcUpdate(dpc);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void cleanupIfOnHandlerElsePost(final CleanupSnapshot cs, final boolean postReevaluate) {
        if (cs == null) return;
        Object dpc = (cs.dpcRef != null) ? cs.dpcRef.get() : null;
        Handler h = (cs.handlerRef != null) ? cs.handlerRef.get() : null;
        if (h == null) h = getHandler(dpc);
        if (h != null && h.getLooper() != null && Looper.myLooper() == h.getLooper()) {
            safeRemoveCallbacks(h, cs.timeoutTask);
            restoreOwnedNativeState(cs);
            if (postReevaluate) {
                try {
                    final Object targetDpc = dpc;
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            try { requestDpcUpdate(targetDpc); } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } else {
            postCleanupAndReevaluate(cs);
        }
    }

    private static void cancelExistingSessionOutsideLock(String reason, boolean consumeToken, boolean postReevaluate) {
        CleanupSnapshot cleanup = null;
        long cancelledId = -1L;
        synchronized (LOCK) {
            Session s = sSession;
            if (s != null) {
                s.cancelled = true;
                s.cancelReason = reason;
                s.phase = Phase.CANCELLED;
                cancelledId = s.id;
                if (consumeToken && s.sleepTokenMs > 0L) {
                    sConsumedSleepAtMs = s.sleepTokenMs;
                }
                cleanup = new CleanupSnapshot(s);
                sSession = null;
                sGeneration++;
                sHandoffSuppressColorFadeUntilMs = -1L;
            }
        }
        if (cleanup != null) {
            safeLog("OnAodRamp CANCEL reason=" + reason + " id=" + cancelledId);
            cleanupIfOnHandlerElsePost(cleanup, postReevaluate);
        }
    }

    private static CleanupSnapshot abortCompletingIfPresent(long sessionId, String reason) {
        synchronized (LOCK) {
            Session s = sSession;
            if (s != null && s.id == sessionId) {
                s.cancelled = true;
                s.cancelReason = reason;
                s.phase = Phase.CANCELLED;
                if (s.sleepTokenMs > 0L) {
                    sConsumedSleepAtMs = s.sleepTokenMs;
                }
                CleanupSnapshot cs = new CleanupSnapshot(s);
                sSession = null;
                sGeneration++;
                sHandoffSuppressColorFadeUntilMs = -1L;
                return cs;
            }
            return null;
        }
    }

    private static void abortActiveSessionSafely(String reason, Throwable t) {
        try {
            safeLog("OnAodRamp ERROR " + reason + ": " + t);
            cancelExistingSessionOutsideLock(reason, true, true);
        } catch (Throwable ignored) {}
    }

    private static boolean isFenceBroken(Session session, long expectedGen, Object dpc) {
        synchronized (LOCK) {
            if (sSession == null || sSession.id != session.id || sSession.cancelled
                    || sSession.generation != expectedGen || sGeneration != expectedGen) {
                return true;
            }
        }
        EnvSnapshot env = readEnvSnapshotSafe();
        if (!env.isValidForRamp()) {
            return true;
        }
        if (dpc != null) {
            int policy = getPolicyFromRequest(HookUtils.getField(dpc, "mPowerRequest"), -1);
            if (policy != POLICY_OFF && policy != POLICY_DOZE) {
                return true;
            }
        }
        return false;
    }

    private static void snapAnimatorWithFence(Object dpc, float target, float sdrTarget,
                                              Session session, long expectedGen) {
        if (dpc == null || !isFinitePositive(target) || !isFinitePositive(sdrTarget)) return;
        if (!isOnDpcHandlerThread(dpc)) return;
        Object anim = HookUtils.getField(dpc, "mScreenBrightnessRampAnimator");
        if (anim == null) return;
        if (!Boolean.TRUE.equals(invokeNoArg(anim, "isAnimating"))) return;
        if (isFenceBroken(session, expectedGen, dpc)) return;
        try {
            Method m = findMethod(anim.getClass(), "animateTo",
                    float.class, float.class, float.class, boolean.class, float.class);
            if (m != null) {
                m.invoke(anim, Float.valueOf(target), Float.valueOf(sdrTarget),
                        Float.valueOf(0.0f), Boolean.TRUE, Float.valueOf(-1.0f));
            }
        } catch (Throwable t) {
            safeLog("OnAodRamp snapAnimatorWithFence error: " + t);
        }
    }

    // =========================================================================
    // Strict Preflight Validation & Native Resolvers (NO fabricated fallbacks)
    // =========================================================================

    private static boolean hasNativeArmedWaitsOrBlockers(Object dpc) {
        if (Boolean.TRUE.equals(HookUtils.getField(dpc, "mPendingScreenOff"))) {
            return true;
        }
        Object colorFadeOffAnim = HookUtils.getField(dpc, "mColorFadeOffAnimator");
        if (colorFadeOffAnim != null && Boolean.TRUE.equals(invokeNoArg(colorFadeOffAnim, "isStarted"))) {
            return true;
        }
        Object dpcImpl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
        if (dpcImpl != null) {
            if (Boolean.TRUE.equals(HookUtils.getField(dpcImpl, "mWaitingScreenOffAnimator"))
                    || Boolean.TRUE.equals(invokeNoArg(dpcImpl, "isWaitingScreenOffAnimator"))) {
                return true;
            }
        }
        if (HookUtils.getField(dpc, "mPendingScreenOffUnblocker") != null
                || HookUtils.getField(dpc, "mPendingScreenOnUnblocker") != null
                || HookUtils.getField(dpc, "mPendingScreenOnUnblockerByDisplayOffload") != null) {
            return true;
        }
        return Boolean.TRUE.equals(HookUtils.getField(dpc, "mWaitingForScreenOnFromDozeReady"));
    }

    private static boolean hasActiveNonSleepRequestOverride(Object dbc, Object req) {
        if (req != null) {
            if (Boolean.TRUE.equals(HookUtils.getField(req, "boostScreenBrightness"))) {
                return true;
            }
            float overrideB = getFloatField(req, "screenBrightnessOverride", Float.NaN);
            if (isFinitePositive(overrideB)) {
                return true;
            }
        }
        if (dbc != null && Boolean.TRUE.equals(invokeNoArg(dbc, "isAppliedOverrideStrategy"))) {
            return true;
        }
        return false;
    }

    private static boolean isLegitimateNonSleepOverride(String stratName, Object dbc, Object req) {
        if (BOOST_STRATEGY_NAME.equals(stratName)
                || OVERRIDE_STRATEGY_NAME.equals(stratName)
                || TEMPORARY_STRATEGY_NAME.equals(stratName)
                || FOLLOWER_STRATEGY_NAME.equals(stratName)) {
            return true;
        }
        return hasActiveNonSleepRequestOverride(dbc, req);
    }

    private static Bounds readIntersectionBounds(Object dpc, Object dbc, boolean fullMode) {
        if (dpc == null) return null;
        float minB = Float.NaN;
        float maxB = Float.NaN;
        Object brc = HookUtils.getField(dpc, "mBrightnessRangeController");
        if (brc != null) {
            Object minObj = invokeNoArg(brc, "getCurrentBrightnessMin");
            Object maxObj = invokeNoArg(brc, "getCurrentBrightnessMax");
            if (minObj instanceof Float) minB = ((Float) minObj).floatValue();
            if (maxObj instanceof Float) maxB = ((Float) maxObj).floatValue();
        }
        if (!isFinitePositive(minB)) {
            minB = getFloatField(dpc, "mScreenBrightnessRangeMinimum", Float.NaN);
        }
        if (!isFinitePositive(maxB)) {
            maxB = getFloatField(dpc, "mScreenBrightnessRangeMaximum", Float.NaN);
        }
        if (!isFinitePositive(minB) || !isFinitePositive(maxB) || maxB < minB) {
            return null;
        }
        if (fullMode) {
            // Full/localized AOD (DozeBrightnessStrategyImpl L72-79 & DisplayPowerController L1283-1285,
            // L2007-2012) bypasses legacy clampDozeBrightness(mMinDozeBrightnessFloat..mMaxDozeBrightnessFloat)
            // and is clamped only by mBrightnessRangeController [minB, maxB].
            return new Bounds(minB, maxB);
        }

        if (dbc != null) {
            Object dozeStrategy = invokeNoArg(dbc, "getDozeBrightnessStrategy");
            Object stub = (dozeStrategy != null) ? invokeNoArg(dozeStrategy, "getStrategyStub") : null;
            if (stub != null) {
                float minDoze = getFloatField(stub, "mMinDozeBrightnessFloat", Float.NaN);
                float maxDoze = getFloatField(stub, "mMaxDozeBrightnessFloat", Float.NaN);
                if (Float.isFinite(minDoze) || Float.isFinite(maxDoze)) {
                    float effMin = isFinitePositive(minDoze) ? Math.max(minB, minDoze) : minB;
                    float effMax = isFinitePositive(maxDoze) ? Math.min(maxB, maxDoze) : maxB;
                    if (effMin > effMax) {
                        return null;
                    }
                    return new Bounds(effMin, effMax);
                }
            }
        }
        return new Bounds(minB, maxB);
    }

    private static float clampStrict(float v, Bounds b) {
        if (!isFinitePositive(v) || b == null) return Float.NaN;
        return Math.max(b.min, Math.min(b.max, v));
    }

    private static float resolveCurrentBrightness(Object dpc, Object powerState) {
        Object res = invokeNoArg(powerState, "getScreenBrightness");
        if (res instanceof Float && isFinitePositive(((Float) res).floatValue())) {
            return ((Float) res).floatValue();
        }
        Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
        Object dbcCur = invokeNoArg(dbc, "getCurrentBrightness");
        if (dbcCur instanceof Float && isFinitePositive(((Float) dbcCur).floatValue())) {
            return ((Float) dbcCur).floatValue();
        }
        return Float.NaN;
    }

    private static float resolveCurrentSdrBrightness(Object powerState) {
        if (powerState == null) return Float.NaN;
        Method sdrM = findMethod(powerState.getClass(), "getSdrScreenBrightness");
        if (sdrM != null) {
            try {
                Object res = sdrM.invoke(powerState);
                if (res instanceof Float && isFinitePositive(((Float) res).floatValue())) {
                    return ((Float) res).floatValue();
                }
                return Float.NaN;
            } catch (Throwable ignored) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }

    private static Boolean readFullAodMode(Object stub) {
        if (stub == null) return null;
        Method fullM = findMethod(stub.getClass(), "getFullAodState");
        if (fullM != null) {
            try {
                Object res = fullM.invoke(stub);
                if (res instanceof Boolean) {
                    return (Boolean) res;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        Field f = findField(stub.getClass(), "mIsFullAod");
        if (f != null) {
            try {
                Object val = f.get(stub);
                if (val instanceof Boolean) {
                    return (Boolean) val;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Float readNativeAutoDozeBrightness(Object stub) {
        if (stub == null) return null;
        Method autoM = findMethod(stub.getClass(), "getAodScreenBrightness");
        if (autoM != null) {
            try {
                Object res = autoM.invoke(stub);
                if (res instanceof Float) {
                    return (Float) res;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        Object autoCtrl = HookUtils.getField(stub, "mDozeAutoBrightnessController");
        if (autoCtrl != null) {
            Method ctrlM = findMethod(autoCtrl.getClass(), "getDozeBrightness");
            if (ctrlM != null) {
                try {
                    Object res = ctrlM.invoke(autoCtrl);
                    if (res instanceof Float) {
                        return (Float) res;
                    }
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static EndpointResolution resolveNativeAodEndpoint(Object dbc, Object req) {
        if (dbc == null || req == null) return null;
        Object dozeStrategy = invokeNoArg(dbc, "getDozeBrightnessStrategy");
        if (dozeStrategy == null) return null;
        Object stub = invokeNoArg(dozeStrategy, "getStrategyStub");
        if (stub == null) return null;

        Boolean fullMode = readFullAodMode(stub);
        if (fullMode == null) {
            // Unknown native AOD mode: fail closed cleanly to protected 54aa baseline.
            return null;
        }

        if (fullMode.booleanValue()) {
            // Full/localized AOD mode (DozeBrightnessStrategyImpl L72-79):
            // Ignore legacy req.dozeScreenBrightness and never call clampDozeBrightness(req).
            Float autoObj = readNativeAutoDozeBrightness(stub);
            if (autoObj == null) {
                return null;
            }
            float autoB = autoObj.floatValue();
            if (!Float.isNaN(autoB)) {
                return isFinitePositive(autoB) ? new EndpointResolution(true, autoB, "nativeAuto") : null;
            }
            float lastDoze = getFloatField(stub, "mLastDozeBrightness", Float.NaN);
            if (isFinitePositive(lastDoze)) {
                return new EndpointResolution(true, lastDoze, "nativeLast");
            }
            float minDoze = getFloatField(stub, "mMinDozeBrightnessFloat", Float.NaN);
            if (isFinitePositive(minDoze)) {
                return new EndpointResolution(true, minDoze, "nativeMin");
            }
            return null;
        }

        // Legacy non-full AOD mode (DozeBrightnessStrategyImpl L81 & L171-187):
        // Obey native clampDozeBrightness(req.dozeScreenBrightness).
        Method clampM = findMethod(stub.getClass(), "clampDozeBrightness", float.class);
        if (clampM == null) {
            return null;
        }
        float reqDoze = getFloatField(req, "dozeScreenBrightness", Float.NaN);
        Object clampedObj;
        try {
            clampedObj = clampM.invoke(stub, Float.valueOf(reqDoze));
        } catch (Throwable ignored) {
            return null;
        }
        if (!(clampedObj instanceof Float)) {
            return null;
        }
        float clampedVal = ((Float) clampedObj).floatValue();
        if (!isFinitePositive(clampedVal)) {
            return null;
        }
        String src;
        if (!Float.isNaN(reqDoze)) {
            src = "nativeClampedRequest";
        } else {
            float lastDoze = getFloatField(stub, "mLastDozeBrightness", Float.NaN);
            src = isFinitePositive(lastDoze) ? "nativeLast" : "nativeMin";
        }
        return new EndpointResolution(false, clampedVal, src);
    }

    private static float computeSmoothExpOrLinearRate(float start, float current, float target, int durationMs) {
        if (!isFinitePositive(start) || !isFinitePositive(current) || !isFinitePositive(target) || durationMs <= 0) {
            return Float.NaN;
        }
        if (Math.abs(current - target) <= 1e-6f) {
            return Math.max(0.01f, current / 10.0f);
        }
        double durationSec = Math.max(0.06d, durationMs / 1000.0d);
        if (target < start && current > target) {
            double coeff = (Math.log((double) target / (double) start) * 1000.0d) / (double) durationMs;
            double raw = Math.abs(coeff * (double) current);
            if (Double.isFinite(raw) && raw > 0.0d) {
                return (float) raw;
            }
        }
        // Smooth linear fallback for upward ambient sensor retargets within AOD ramp.
        double lin = Math.abs((double) current - (double) target) / durationSec;
        return (Double.isFinite(lin) && lin > 0.0d) ? (float) lin : Float.NaN;
    }

    private static Object buildDozeDisplayBrightnessStateFrom(
            Object origState, float targetBrightness, boolean clearStaleNonDozeHdr) {
        if (origState == null) return null;
        try {
            Class<?> stateCls = origState.getClass();
            Class<?> builderCls = Class.forName(stateCls.getName() + "$Builder", false, stateCls.getClassLoader());
            Method fromM = builderCls.getMethod("from", stateCls);
            Object builder = fromM.invoke(null, origState);
            if (builder == null) return null;
            invokeFloatArg(builder, "setBrightness", targetBrightness);
            if (clearStaleNonDozeHdr) {
                invokeFloatArg(builder, "setHdrBrightness", -1.0f);
            }
            applyDozeBrightnessReasonPreservingModifier(builder, origState);
            invokeStringArg(builder, "setDisplayBrightnessStrategyName", DOZE_STRATEGY_NAME);
            invokeBooleanArg(builder, "setIsSlowChange", true);
            return invokeNoArg(builder, "build");
        } catch (Throwable t) {
            safeLog("OnAodRamp buildDisplayBrightnessState(from) failed: " + t);
            return null;
        }
    }

    private static void applyDozeBrightnessReasonPreservingModifier(Object builder, Object origState) {
        try {
            Object origReason = invokeNoArg(origState, "getBrightnessReason");
            if (origReason != null) {
                Method getModM = findMethod(origReason.getClass(), "getModifier");
                Object modObj = (getModM != null) ? getModM.invoke(origReason) : null;
                int origMod = (modObj instanceof Integer) ? ((Integer) modObj).intValue() : 0;
                Method setReasonObjM = findMethod(builder.getClass(), "setBrightnessReason", origReason.getClass());
                if (setReasonObjM != null) {
                    Constructor<?> ctor = origReason.getClass().getDeclaredConstructor(int.class);
                    ctor.setAccessible(true);
                    Object newReason = ctor.newInstance(Integer.valueOf(REASON_DOZE));
                    if (origMod != 0) {
                        invokeIntArg(newReason, "addModifier", origMod);
                    }
                    setReasonObjM.invoke(builder, newReason);
                    return;
                }
            }
        } catch (Throwable ignored) {}
        invokeIntArg(builder, "setBrightnessReason", REASON_DOZE);
    }

    private static boolean hasDisplayBrightnessStateBuilder(ClassLoader cl) {
        try {
            Class<?> stateCls = Class.forName("com.android.server.display.DisplayBrightnessState", false, cl);
            Class<?> builderCls = Class.forName("com.android.server.display.DisplayBrightnessState$Builder", false, cl);
            builderCls.getMethod("from", stateCls);
            findMethod(builderCls, "setBrightness", float.class);
            findMethod(builderCls, "setDisplayBrightnessStrategyName", String.class);
            findMethod(builderCls, "setIsSlowChange", boolean.class);
            findMethod(builderCls, "build");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasRampRateCapabilities(Object rrc) {
        if (rrc == null) return false;
        Object rateRecord = HookUtils.getField(rrc, "mRateRecord");
        if (rateRecord == null) return false;
        if (findField(rrc.getClass(), "mAnimationDurationDozeDimming") == null) return false;
        return findMethod(rateRecord.getClass(), "setDozeDimmingTimeMills", long.class) != null
                && findMethod(rateRecord.getClass(), "addRateModifier", int.class) != null
                && findMethod(rateRecord.getClass(), "clearRateModifier", int.class) != null;
    }

    private static boolean hasAnimatorCapabilities(Object dpc) {
        Object anim = HookUtils.getField(dpc, "mScreenBrightnessRampAnimator");
        if (anim == null) return false;
        return findMethod(anim.getClass(), "isAnimating") != null
                && findMethod(anim.getClass(), "animateTo",
                        float.class, float.class, float.class, boolean.class, float.class) != null;
    }

    // =========================================================================
    // Identity & Helpers
    // =========================================================================

    private static void registerPrimaryRefsOutsideLock(Object dpc) {
        if (dpc == null) return;
        Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
        Object ps = HookUtils.getField(dpc, "mPowerState");
        Object impl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
        Object rrc = (impl != null) ? HookUtils.getField(impl, "mRampRateController") : null;
        synchronized (LOCK) {
            sPrimaryDpcRef = new WeakReference<>(dpc);
            if (dbc != null) sPrimaryDbcRef = new WeakReference<>(dbc);
            if (ps != null) sPrimaryPowerStateRef = new WeakReference<>(ps);
            if (impl != null) sPrimaryDpcImplRef = new WeakReference<>(impl);
            if (rrc != null) sPrimaryRampRateRef = new WeakReference<>(rrc);
        }
    }

    private static void recordSecondaryComponentsOutsideLock(Object dpc) {
        if (dpc == null) return;
        Object ps = HookUtils.getField(dpc, "mPowerState");
        Object dbc = HookUtils.getField(dpc, "mDisplayBrightnessController");
        Object impl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
        Object rrc = (impl != null) ? HookUtils.getField(impl, "mRampRateController") : null;
        synchronized (LOCK) {
            sSecondaryComponents.add(dpc);
            if (ps != null) sSecondaryComponents.add(ps);
            if (dbc != null) sSecondaryComponents.add(dbc);
            if (impl != null) sSecondaryComponents.add(impl);
            if (rrc != null) sSecondaryComponents.add(rrc);
        }
    }

    private static boolean isPrimaryRampRateController(Object rrc) {
        if (rrc == null) return false;
        synchronized (LOCK) {
            if (rrc == sPrimaryRampRateRef.get()) return true;
            if (sSecondaryComponents.contains(rrc)) return false;
        }
        Object impl = HookUtils.getField(rrc, "mDisplayPowerControllerImpl");
        if (impl != null) {
            Integer id = getIntField(impl, "mDisplayId");
            if (id != null) {
                if (id.intValue() == 0) {
                    synchronized (LOCK) {
                        sPrimaryDpcImplRef = new WeakReference<>(impl);
                        sPrimaryRampRateRef = new WeakReference<>(rrc);
                    }
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private static Integer resolveDisplayIdStrict(Object obj) {
        if (obj == null) return null;
        Integer id = getIntField(obj, "mDisplayId");
        if (id != null) return id;
        synchronized (LOCK) {
            if (sSecondaryComponents.contains(obj)) {
                return Integer.valueOf(1);
            }
            if (obj == sPrimaryDpcRef.get()
                    || obj == sPrimaryDbcRef.get()
                    || obj == sPrimaryPowerStateRef.get()
                    || obj == sPrimaryDpcImplRef.get()
                    || obj == sPrimaryRampRateRef.get()) {
                return Integer.valueOf(0);
            }
        }
        return null;
    }

    private static boolean isKnownPrimaryComponent(Object obj) {
        if (obj == null) return false;
        synchronized (LOCK) {
            return obj == sPrimaryDpcRef.get()
                    || obj == sPrimaryDbcRef.get()
                    || obj == sPrimaryPowerStateRef.get()
                    || obj == sPrimaryDpcImplRef.get()
                    || obj == sPrimaryRampRateRef.get();
        }
    }

    private static boolean isOnDpcHandlerThread(Object dpc) {
        Handler h = getHandler(dpc);
        return h != null && h.getLooper() != null && Looper.myLooper() == h.getLooper();
    }

    private static EnvSnapshot readEnvSnapshotSafe() {
        boolean prop = readPropEnabledSafe();
        boolean aod = false;
        boolean fs = false;
        boolean asleep = false;
        long token = 0L;
        long now = SystemClock.uptimeMillis();
        try {
            aod = StateCoordinator.isAodPolicyActiveFast();
            fs = StateCoordinator.isFullScreenAodFast();
            asleep = StateCoordinator.sAsleep;
            token = StateCoordinator.sSleepAtMs;
        } catch (Throwable ignored) {
            prop = false;
        }
        return new EnvSnapshot(prop, aod, fs, asleep, token, now);
    }

    private static boolean readPropEnabledSafe() {
        try {
            Method m = sSystemPropertiesGetMethod;
            if (!sSystemPropertiesResolved) {
                Class<?> c = Class.forName("android.os.SystemProperties");
                m = c.getMethod("get", String.class, String.class);
                sSystemPropertiesGetMethod = m;
                sSystemPropertiesResolved = true;
            }
            if (m == null) {
                return false;
            }
            Object raw = m.invoke(null, PROP_ENABLED, "");
            if (!(raw instanceof String)) {
                return false;
            }
            String val = ((String) raw).trim();
            if ("0".equals(val) || "false".equalsIgnoreCase(val) || "off".equalsIgnoreCase(val)) {
                return false;
            }
            return val.isEmpty()
                    || "1".equals(val)
                    || "true".equalsIgnoreCase(val)
                    || "on".equalsIgnoreCase(val);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void requestDpcUpdate(Object dpc) {
        if (dpc == null) return;
        if (!isOnDpcHandlerThread(dpc)) {
            Handler h = getHandler(dpc);
            if (h != null) {
                try {
                    final Object target = dpc;
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            try { requestDpcUpdateOnHandler(target); } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }
            return;
        }
        requestDpcUpdateOnHandler(dpc);
    }

    private static void requestDpcUpdateOnHandler(Object dpc) {
        if (dpc == null) return;
        Method m = findMethod(dpc.getClass(), "sendUpdatePowerState");
        if (m != null) {
            try {
                m.invoke(dpc);
                return;
            } catch (Throwable ignored) {}
        }
        Object impl = HookUtils.getField(dpc, "mDisplayPowerControllerImpl");
        if (impl != null) {
            invokeNoArg(impl, "updateBrightness");
        }
    }

    private static void removeTimeoutCallback(CleanupSnapshot cs) {
        if (cs == null || cs.timeoutTask == null) return;
        Handler h = (cs.handlerRef != null) ? cs.handlerRef.get() : null;
        if (h == null && cs.dpcRef != null) {
            h = getHandler(cs.dpcRef.get());
        }
        safeRemoveCallbacks(h, cs.timeoutTask);
    }

    private static void safeRemoveCallbacks(Handler h, Runnable r) {
        if (h != null && r != null) {
            try {
                h.removeCallbacks(r);
            } catch (Throwable ignored) {}
        }
    }

    private static void safeLog(String msg) {
        try {
            HookUtils.log(msg);
        } catch (Throwable ignored) {}
    }

    private static void logSparse(long sleepTokenMs, String reason) {
        if (sleepTokenMs == sLastSparseLogTokenMs && reason.equals(sLastSparseLogReason)) {
            return;
        }
        sLastSparseLogTokenMs = sleepTokenMs;
        sLastSparseLogReason = reason;
        safeLog("OnAodRamp fallback 54aa: " + reason + " token=" + sleepTokenMs);
    }

    private static long readRateRecordDozeTime(Object rateRecord) {
        for (String name : new String[]{"mStartDozeDimmingTimeMills", "mDozeDimmingTimeMillis"}) {
            Object v = HookUtils.getField(rateRecord, name);
            if (v instanceof Long) return ((Long) v).longValue();
        }
        return 0L;
    }

    private static boolean readRateRecordHasDozeMod(Object rateRecord) {
        return Boolean.TRUE.equals(invokeNoArg(rateRecord, "appliedDozeDimming"));
    }

    private static boolean isAnimatorAnimating(Object dpc) {
        Object anim = HookUtils.getField(dpc, "mScreenBrightnessRampAnimator");
        return anim != null && Boolean.TRUE.equals(invokeNoArg(anim, "isAnimating"));
    }

    private static void setBooleanMethodBlockedResult(XC_MethodHook.MethodHookParam param) {
        if (param.method instanceof Method) {
            Class<?> ret = ((Method) param.method).getReturnType();
            if (ret == boolean.class || ret == Boolean.class) {
                param.setResult(Boolean.FALSE);
                return;
            }
        }
        param.setResult(null);
    }

    private static int getPowerStateScreenState(Object powerState) {
        Object st = invokeNoArg(powerState, "getScreenState");
        return (st instanceof Integer) ? ((Integer) st).intValue() : 0;
    }

    private static int getPolicyFromRequest(Object req, int def) {
        Integer p = getIntField(req, "policy");
        return p != null ? p.intValue() : def;
    }

    private static Handler getHandler(Object dpc) {
        if (dpc == null) return null;
        Object h = HookUtils.getField(dpc, "mHandler");
        return (h instanceof Handler) ? (Handler) h : null;
    }

    private static String readStrategyName(Object state) {
        Object res = invokeNoArg(state, "getDisplayBrightnessStrategyName");
        return (res instanceof String) ? (String) res : null;
    }

    private static float readStateBrightness(Object state) {
        Object res = invokeNoArg(state, "getBrightness");
        return (res instanceof Float) ? ((Float) res).floatValue() : Float.NaN;
    }

    private static boolean readIsSlowChange(Object state) {
        return Boolean.TRUE.equals(invokeNoArg(state, "isSlowChange"));
    }

    private static boolean isFinitePositive(float v) {
        return Float.isFinite(v) && v > 0.0f;
    }

    private static Integer getIntField(Object obj, String name) {
        Object v = HookUtils.getField(obj, name);
        return (v instanceof Integer) ? (Integer) v : null;
    }

    private static float getFloatField(Object obj, String name, float def) {
        Object v = HookUtils.getField(obj, name);
        return (v instanceof Float) ? ((Float) v).floatValue() : def;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name);
            if (m != null) return m.invoke(target);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeIntArg(Object target, String name, int arg) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, int.class);
            if (m != null) return m.invoke(target, Integer.valueOf(arg));
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeLongArg(Object target, String name, long arg) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, long.class);
            if (m != null) return m.invoke(target, Long.valueOf(arg));
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeFloatArg(Object target, String name, float arg) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, float.class);
            if (m != null) return m.invoke(target, Float.valueOf(arg));
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeBooleanArg(Object target, String name, boolean arg) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, boolean.class);
            if (m != null) return m.invoke(target, Boolean.valueOf(arg));
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeStringArg(Object target, String name, String arg) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, String.class);
            if (m != null) return m.invoke(target, arg);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
