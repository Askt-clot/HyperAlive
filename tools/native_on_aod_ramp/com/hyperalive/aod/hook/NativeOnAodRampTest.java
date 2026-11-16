package com.hyperalive.aod.hook;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayPowerController;
import com.android.server.display.DisplayPowerState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.power.PowerGroup;
import com.android.server.power.PowerManagerService;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class NativeOnAodRampTest {

    public static final class UnknownDisplayTarget {
        public final String tag = "NoDisplayIdFieldTarget";
    }

    public static final class UnknownModeStub {
        public final String tag = "NoModeMethodOrField";
    }

    public static final class FullModeMissingAutoStub {
        public boolean getFullAodState() { return true; }
    }

    public static final class LegacyModeMissingClampStub {
        public boolean getFullAodState() { return false; }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ASSERTION FAILED: " + msg);
        }
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError("ASSERTION FAILED (" + msg + "): expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual) {
            throw new AssertionError("ASSERTION FAILED (" + msg + "): expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertClose(float expected, float actual, float eps, String msg) {
        if (Float.isNaN(actual) || Math.abs(expected - actual) > eps) {
            throw new AssertionError("ASSERTION FAILED (" + msg + "): expected=" + expected + " actual=" + actual);
        }
    }

    private static void resetCoordinatorStatics() throws Exception {
        NativeOnAodRampCoordinator.sTestBeforeFinalWriteHook = null;
        // Clear any leftover session or consumed token via reflection reset before installing fresh
        for (String fname : new String[]{"sSession"}) {
            Field f = NativeOnAodRampCoordinator.class.getDeclaredField(fname);
            f.setAccessible(true);
            f.set(null, null);
        }
        Field tokF = NativeOnAodRampCoordinator.class.getDeclaredField("sConsumedSleepAtMs");
        tokF.setAccessible(true);
        tokF.setLong(null, 0L);
        Field handoffF = NativeOnAodRampCoordinator.class.getDeclaredField("sHandoffSuppressColorFadeUntilMs");
        handoffF.setAccessible(true);
        handoffF.setLong(null, -1L);
    }

    private static void setupInstalledEnv(boolean featureEnabled) throws Exception {
        Looper.setCurrentLooperForTest(Looper.getMainLooper());
        SystemProperties.sThrowOnGet = false;
        SystemProperties.PROPS.clear();
        Log.LOGS.clear();
        XposedBridge.HOOKS.clear();
        SystemClock.sNowMs = 10000L;
        resetCoordinatorStatics();

        StateCoordinator.sLockWallpaperActive = true;
        StateCoordinator.sHyperAliveActive = true;
        StateCoordinator.sAodPolicyActive = true;
        StateCoordinator.sFullScreenAod = false;
        StateCoordinator.sAsleep = false;
        StateCoordinator.sSleepAtMs = 0L;
        StateCoordinator.sThawedCalls = 0;

        if (!featureEnabled) {
            // Emergency opt-out to authentic 54aa fallback (absent property enables guarded default path)
            SystemProperties.set(NativeOnAodRampCoordinator.PROP_ENABLED, "0");
        }

        SystemServerHook.install(NativeOnAodRampTest.class.getClassLoader());
        assertTrue(NativeOnAodRampCoordinator.isCapabilitiesReady(), "capabilities should be ready");
    }

    private static void advanceAndStepVsync(DisplayPowerController dpc, long totalMs, int stepMs) {
        long end = SystemClock.sNowMs + totalMs;
        while (SystemClock.sNowMs < end && dpc.mScreenBrightnessRampAnimator.isAnimating()) {
            SystemClock.sNowMs += stepMs;
            dpc.mScreenBrightnessRampAnimator.stepVsync(stepMs / 1000.0f);
            dpc.mHandler.runReadyTasks();
        }
    }

    private static void testDisabledPreservesExact54aaAllTargets() throws Exception {
        setupInstalledEnv(false);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController primary = new DisplayPowerController(0);
        DisplayPowerController secondary = new DisplayPowerController(1);
        primary.mPowerState.setScreenBrightness(0.50f);
        secondary.mPowerState.setScreenBrightness(0.40f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        assertTrue(StateCoordinator.sAsleep, "StateCoordinator sAsleep should arm via PMS hook");

        primary.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        primary.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "coordinator must stay inactive when disabled");
        assertEquals(3, primary.mPowerState.getScreenState(), "54aa must rewrite STATE_OFF(1)->STATE_DOZE(3) on primary");
        assertEquals(0, primary.mPowerState.colorFadePreparedCount, "54aa must suppress prepareColorFade on primary");

        secondary.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        secondary.updatePowerStateInternal();
        assertEquals(3, secondary.mPowerState.getScreenState(), "disabled 54aa rewrites secondary 1->3 identically");
        assertEquals(0, secondary.mPowerState.colorFadePreparedCount, "disabled 54aa suppresses prepareColorFade on secondary");

        // Unknown target without mDisplayId field also falls through cleanly to 54aa when disabled
        UnknownDisplayTarget unknown = new UnknownDisplayTarget();
        XC_MethodHook.MethodHookParam ssParam = new XC_MethodHook.MethodHookParam();
        ssParam.thisObject = unknown;
        ssParam.args = new Object[]{1, 0};
        boolean handled = NativeOnAodRampCoordinator.handleSetScreenState(ssParam, 1);
        assertTrue(!handled, "disabled coordinator must return false for unknown target");

        XC_MethodHook.MethodHookParam cfParam = new XC_MethodHook.MethodHookParam();
        cfParam.thisObject = unknown;
        cfParam.args = new Object[]{null, 2};
        assertTrue(!NativeOnAodRampCoordinator.handlePrepareColorFade(cfParam),
                "disabled coordinator must return false for unknown prepareColorFade");
    }

    private static void testEnabledPrimarySleepFromPolicyOffHoldsOnThenHandoffsDoze() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = Float.NaN;
        dpc.updatePowerStateInternal();

        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session should be active after POLICY_OFF Call #1");
        assertEquals(2, dpc.mPowerState.getScreenState(), "display must stay STATE_ON(2) during ramp");
        assertTrue(!dpc.mPendingScreenOff, "mPendingScreenOff must remain cleared");
        assertEquals(0, dpc.mDisplayPowerControllerImpl.waitingAnimatorTrueCount, "ScreenOff shader must not arm");
        assertEquals(0, dpc.mPowerState.colorFadePreparedCount, "ColorFade must remain suppressed");
        assertTrue(dpc.mScreenBrightnessRampAnimator.isAnimating(), "DualRampAnimator must be animating");
        assertEquals(0, dpc.mScreenBrightnessRampAnimator.snapCount, "must not snap with rate=0");
        assertTrue(dpc.mDisplayPowerControllerImpl.mRampRateController.mRateRecord.appliedDozeDimming(),
                "RampRateController must arm modifier 4");

        SystemClock.sNowMs += 50L;
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
        dpc.updatePowerStateInternal();
        assertEquals(2, dpc.mPowerState.getScreenState(), "must still hold STATE_ON while animator running");

        advanceAndStepVsync(dpc, 600L, 16);

        assertTrue(!dpc.mScreenBrightnessRampAnimator.isAnimating(), "animator should reach target cleanly");
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "session should complete on handoff");
        assertEquals(3, dpc.mPowerState.getScreenState(), "display must hand off cleanly to STATE_DOZE(3)");
        assertEquals(0, dpc.mScreenBrightnessRampAnimator.snapCount, "must complete without rate<=0 snaps");

        List<Float> steps = dpc.mScreenBrightnessRampAnimator.brightnessSteps;
        assertTrue(steps.size() >= 10, "exponential curve should span many VSync steps: " + steps.size());
        for (int i = 1; i < steps.size(); i++) {
            assertTrue(steps.get(i) <= steps.get(i - 1) + 1e-6f, "brightness steps must be monotonically non-increasing");
            assertTrue(steps.get(i) > 0.0f && Float.isFinite(steps.get(i)), "brightness must remain finite positive");
        }
        for (Integer st : dpc.mPowerState.stateTransitions) {
            assertTrue(st != 1, "must never observe STATE_OFF(1)");
        }
        assertEquals(1, dpc.mPowerState.stateTransitions.size(), "must perform exactly one state handoff to DOZE(3)");
        for (DisplayPowerState.HardwareEvent ev : dpc.mPowerState.hardwareEvents) {
            assertTrue(!"BRIGHTNESS_WHILE_OFF".equals(ev.kind), "must never write brightness while OFF");
        }
    }

    private static void testEnabledPrimarySleepFromPolicyDozeHoldsOnThenHandoffsDoze() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.45f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.useNormalBrightnessForDoze = true;
        dpc.mPowerRequest.dozeScreenBrightness = Float.NaN;
        dpc.updatePowerStateInternal();

        assertEquals(2, dpc.mPowerState.getScreenState(), "must hold STATE_ON on Call #1 even if policy already DOZE");
        assertTrue(dpc.mScreenBrightnessRampAnimator.isAnimating(), "must seed exponential ramp to AOD brightness");
        assertTrue(dpc.mDisplayBrightnessController.isAppliedDozeBrightnessStrategy(),
                "must switch DBC strategy to DozeBrightnessStrategy");

        advanceAndStepVsync(dpc, 150L, 16);
        dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
        advanceAndStepVsync(dpc, 450L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "must hand off to STATE_DOZE(3) once complete");
        assertEquals(0, dpc.mScreenBrightnessRampAnimator.snapCount, "no rate=0 snaps");
    }

    private static void testMidRampFiniteDozeRetargeting() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();

        advanceAndStepVsync(dpc, 112L, 16);
        assertTrue(dpc.mScreenBrightnessRampAnimator.isAnimating(), "still animating at 112ms");

        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.045f;
        dpc.updatePowerStateInternal();

        int dur = dpc.mDisplayPowerControllerImpl.mRampRateController.mAnimationDurationDozeDimming;
        assertTrue(dur < 450 && dur >= NativeOnAodRampCoordinator.MIN_RETARGET_MS,
                "retarget must shrink remaining duration instead of restarting 450ms: " + dur);

        advanceAndStepVsync(dpc, 500L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "retargeted ramp must finish in DOZE(3)");
        assertClose(0.045f, dpc.mPowerState.getScreenBrightness(), 1e-4f, "final brightness matches retarget 0.045");
    }

    private static void testDuplicateAndRateZeroImmunity() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.40f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();

        for (int i = 0; i < 4; i++) {
            SystemClock.sNowMs += 16L;
            dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
            dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
            dpc.updatePowerStateInternal();
            dpc.mScreenBrightnessRampAnimator.stepVsync(0.016f);
        }
        assertEquals(0, dpc.mScreenBrightnessRampAnimator.snapCount, "duplicate/rate=0 ticks must never snap");
        assertEquals(2, dpc.mPowerState.getScreenState(), "must keep holding STATE_ON during ticks");
    }

    private static void testRetargetFloodOverMaxRetargetsTracksLatestTarget() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.60f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();

        // 15 distinct retargets + interleaved tiny < 1e-4f jitter
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        float finalTarget = 0.0f;
        for (int i = 0; i < 15; i++) {
            SystemClock.sNowMs += 8L;
            finalTarget = 0.020f + (i * 0.003f);
            dpc.mPowerRequest.dozeScreenBrightness = finalTarget;
            dpc.updatePowerStateInternal();
            // Jitter below TARGET_EPSILON (1e-4f)
            dpc.mPowerRequest.dozeScreenBrightness = finalTarget + 0.00003f;
            dpc.updatePowerStateInternal();
            dpc.mScreenBrightnessRampAnimator.stepVsync(0.008f);
        }
        int retargetLogs = 0;
        for (String line : Log.LOGS) {
            if (line.contains("OnAodRamp RETARGET")) retargetLogs++;
        }
        assertTrue(retargetLogs <= NativeOnAodRampCoordinator.MAX_RETARGETS,
                "RRC retargets must stay bounded <= 8, got " + retargetLogs);

        // Final target was 0.020 + 14*0.003 = 0.06203 -> clamped in stub to max 0.080 -> 0.06203f
        // Ensure updates #9..#15 STILL tracked the latest target (not stuck at update #8 = 0.041f)!
        advanceAndStepVsync(dpc, 700L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "flooded session finishes in DOZE(3)");
        assertClose(finalTarget + 0.00003f, dpc.mPowerState.getScreenBrightness(), 1e-4f,
                "must end at LATEST target after MAX_RETARGETS, not stale #8 target");
    }

    private static void testSecondaryAndUnknownTargetMatchAuthenticDisabled54aaWhenEnabled() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController primary = new DisplayPowerController(0);
        DisplayPowerController secondary = new DisplayPowerController(1);
        primary.mPowerState.setScreenBrightness(0.50f);
        secondary.mPowerState.setScreenBrightness(0.40f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        primary.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        primary.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "primary session active");
        assertEquals(2, primary.mPowerState.getScreenState(), "primary holding STATE_ON(2)");

        // Secondary display goes to sleep while primary session is active:
        // Must NOT touch primary's session and must match disabled 54aa baseline (STATE_DOZE(3), ColorFade suppressed)
        secondary.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        secondary.updatePowerStateInternal();
        assertEquals(3, secondary.mPowerState.getScreenState(),
                "secondary must match authentic 54aa STATE_DOZE(3) instead of STATE_ON hold or hijacking primary");
        assertEquals(0, secondary.mPowerState.colorFadePreparedCount,
                "secondary prepareColorFade must fall back to 54aa suppression");
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "primary session must stay intact");
        assertEquals(2, primary.mPowerState.getScreenState(), "primary must keep STATE_ON(2)");

        // Unknown target without mDisplayId must NEVER be hijacked or held ON by coordinator
        UnknownDisplayTarget unknown = new UnknownDisplayTarget();
        XC_MethodHook.MethodHookParam ssParam = new XC_MethodHook.MethodHookParam();
        ssParam.thisObject = unknown;
        ssParam.args = new Object[]{1, 0};
        boolean handled = NativeOnAodRampCoordinator.handleSetScreenState(ssParam, 1);
        assertTrue(!handled, "unknown target without mDisplayId must return false so 54aa handles it");
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "primary session must stay untouched by unknown target");

        XC_MethodHook.MethodHookParam cfParam = new XC_MethodHook.MethodHookParam();
        cfParam.thisObject = unknown;
        cfParam.args = new Object[]{null, 2};
        assertTrue(!NativeOnAodRampCoordinator.handlePrepareColorFade(cfParam),
                "unknown target prepareColorFade must return false so 54aa handles it");

        // Primary finishes normally (including coherent retarget to 0.020f within MAX_SESSION_MS)
        primary.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        primary.mPowerRequest.dozeScreenBrightness = 0.020f;
        advanceAndStepVsync(primary, 900L, 16);
        assertEquals(3, primary.mPowerState.getScreenState(), "primary completes cleanly to DOZE(3)");
    }

    private static void testPreflightFailClosedInvalidInputsNeverFabricateOrBreakNative() throws Exception {
        // A: Missing handler
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mHandler = null;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "missing handler must fail closed");
        assertEquals(3, dpc.mPowerState.getScreenState(), "falls back cleanly to 54aa STATE_DOZE(3)");

        // B: Wrong looper thread executing DPC update
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        Looper offLooper = new Looper();
        Looper.setCurrentLooperForTest(offLooper);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "wrong looper thread must fail closed");
        assertEquals(3, dpc.mPowerState.getScreenState(), "wrong looper falls back to 54aa");
        Looper.setCurrentLooperForTest(Looper.getMainLooper());

        // C: postDelayed returns false -> fail closed cleanly before committing session
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mHandler.failNextPostDelayed = true;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "postDelayed=false must fail closed (never stuck ON)");
        assertEquals(3, dpc.mPowerState.getScreenState(), "postDelayed=false falls back to 54aa DOZE(3)");

        // D: Missing / NaN bounds -> fail closed, no fabricated min/max
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mBrightnessRangeController.returnNaNMin = true;
        dpc.mScreenBrightnessRangeMinimum = Float.NaN;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "NaN min bound must fail closed");
        assertEquals(3, dpc.mPowerState.getScreenState(), "NaN bound falls back to 54aa DOZE(3)");

        // E: Missing/NaN AOD target -> NO fabricated 0.05/0.02/1e-4 and NO forceNoFade override
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mDisplayBrightnessController.mDozeStrategy.mStub.mLastDozeBrightness = Float.NaN;
        dpc.mDisplayBrightnessController.mDozeStrategy.mStub.autoDozeBrightness = Float.NaN;
        dpc.mDisplayBrightnessController.mDozeStrategy.mStub.mMinDozeBrightnessFloat = Float.NaN;
        dpc.mDisplayBrightnessController.mDozeStrategy.mDefaultDozeBrightness = Float.NaN;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = Float.NaN;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "unresolvable AOD target must fail closed");
        assertEquals(3, dpc.mPowerState.getScreenState(), "missing AOD target falls back to 54aa DOZE(3)");

        // F: Native armed waits / blockers present -> fail closed without interfering
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mDisplayPowerControllerImpl.mWaitingScreenOffAnimator = true;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "armed waitingScreenOffAnimator must fail closed");

        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mPendingScreenOffUnblocker = new Object();
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "pending ScreenOffUnblocker must fail closed");

        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mColorFadeOffAnimator.started = true;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "started ColorFadeOffAnimator must fail closed");
    }

    private static void testImmediateCancelOnWakeAndBiometricWakeAndSameToken() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active");

        SystemClock.sNowMs += 80L;
        pms.wakeUp(SystemClock.sNowMs, 0, "power_button");
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "wakeUp must immediately cancel session");

        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpc.updatePowerStateInternal();
        assertEquals(2, dpc.mPowerState.getScreenState(), "display must stay ON on wake");

        // Fresh sleep -> finish -> same sleep token never retriggers
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        long firstSleepToken = StateCoordinator.sSleepAtMs;
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "new sleep token starts fresh session");

        advanceAndStepVsync(dpc, 600L, 16);
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "handoff completed");
        assertEquals(3, dpc.mPowerState.getScreenState(), "at DOZE(3)");

        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "consumed sleep token must not retrigger");
        assertEquals(firstSleepToken, StateCoordinator.sSleepAtMs, "sanity: same token");

        // PowerGroup wakefulness -> 1 + biometric wake cancel
        PowerGroup pg = new PowerGroup();
        pg.mWakefulness = 3;
        SystemClock.sNowMs += 500L;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerState.setScreenState(2, 0);
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "fresh session");
        pg.setWakefulnessLocked(1, SystemClock.sNowMs, 0, 17, 0, "fp", "biometric");
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "PowerGroup wakefulness=1 must cancel session");
    }

    private static void testImmediateCancelOnOwnerLossFullscreenAndPropDisableIncludingVsyncOnly() throws Exception {
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc;

        // 1. Owner loss via DPC tick
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "active before owner loss");
        StateCoordinator.sAodPolicyActive = false;
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "owner loss cancels immediately");
        advanceAndStepVsync(dpc, 600L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "native finishes to DOZE cleanly once unlocked");

        // 2. Owner loss observed ONLY during VSync updateBrightnessRate hook (zero DPC hook in between!)
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "active before vsync owner loss");
        StateCoordinator.sAodPolicyActive = false;
        SystemClock.sNowMs += 16L;
        dpc.mScreenBrightnessRampAnimator.stepVsync(0.016f);
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "VSync hook must cancel immediately on owner loss");
        assertTrue(dpc.mHandler.pendingCount() >= 1, "VSync owner loss must post DPC reevaluation");
        dpc.mHandler.runReadyTasks();
        assertEquals(1, dpc.mPowerState.getScreenState(),
                "with policy=OFF and owner lost, posted reevaluation yields cleanly to stock STATE_OFF(1)");

        // 3. Fullscreen AOD enabled mid-ramp
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        StateCoordinator.sFullScreenAod = true;
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "fullscreen AOD cancels immediately");

        // 4. Property turned off mid-ramp with NO intervening DPC hook until deadline (800ms)
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "active before prop=0");
        float beforeDeadlineB = dpc.mPowerState.getScreenBrightness();
        SystemProperties.set(NativeOnAodRampCoordinator.PROP_ENABLED, "0");
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
        SystemClock.sNowMs += NativeOnAodRampCoordinator.MAX_SESSION_MS;
        dpc.mHandler.runReadyTasks();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "deadline must abort when prop=0");
        assertEquals(0, dpc.mScreenBrightnessRampAnimator.snapCount,
                "deadline must NOT emit stale snap when prop=0 caused cancel before write");
        assertClose(beforeDeadlineB, dpc.mPowerState.getScreenBrightness(), 1e-6f,
                "must NOT snap brightness when prop=0 aborted deadline");
        dpc.mHandler.runReadyTasks();
        advanceAndStepVsync(dpc, 700L, 16);
        dpc.mHandler.runReadyTasks();
        assertEquals(3, dpc.mPowerState.getScreenState(), "cleanup reevaluation yields cleanly to 54aa DOZE(3)");
    }

    private static void testAsyncCancellationBarrierRaceAndLockInversion() throws Exception {
        // Barrier Test: deadline enters Phase.COMPLETING on DPC handler thread, pauses right before final write;
        // Thread B holding PMS lock calls wakeUp -> must invalidate fence so DPC thread never snaps or enters DOZE!
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active before barrier");

        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.015f;
        float brightnessBeforeBarrier = dpc.mPowerState.getScreenBrightness();

        CountDownLatch enteredBarrier = new CountDownLatch(1);
        CountDownLatch releaseBarrier = new CountDownLatch(1);
        AtomicReference<Throwable> threadErr = new AtomicReference<>();

        NativeOnAodRampCoordinator.sTestBeforeFinalWriteHook = () -> {
            enteredBarrier.countDown();
            try {
                releaseBarrier.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        };

        Thread handlerThread = new Thread(() -> {
            Looper.setCurrentLooperForTest(Looper.getMainLooper());
            try {
                SystemClock.sNowMs += NativeOnAodRampCoordinator.MAX_SESSION_MS;
                dpc.mHandler.runReadyTasks();
            } catch (Throwable t) {
                threadErr.compareAndSet(null, t);
            }
        }, "TestDpcHandler");

        Thread pmsThread = new Thread(() -> {
            try {
                assertTrue(enteredBarrier.await(2, TimeUnit.SECONDS), "handler must reach barrier before final write");
                dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_BRIGHT;
                pms.wakeUp(SystemClock.sNowMs, 0, "race_wake");
            } catch (Throwable t) {
                threadErr.compareAndSet(null, t);
            } finally {
                releaseBarrier.countDown();
            }
        }, "TestPmsBinderThread");

        handlerThread.start();
        pmsThread.start();
        handlerThread.join(3000);
        pmsThread.join(3000);
        NativeOnAodRampCoordinator.sTestBeforeFinalWriteHook = null;

        assertTrue(threadErr.get() == null, "no thread errors in barrier test: " + threadErr.get());
        assertTrue(!handlerThread.isAlive() && !pmsThread.isAlive(), "both threads finished with zero deadlock");
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "session cleared");
        assertEquals(0, dpc.mScreenBrightnessRampAnimator.snapCount,
                "woken barrier race must NEVER perform final snap");
        assertClose(brightnessBeforeBarrier, dpc.mPowerState.getScreenBrightness(), 1e-6f,
                "must NOT overwrite brightness during interrupted completing phase");
        // Drain cleanup reevaluation on main looper
        dpc.mHandler.runReadyTasks();
        assertEquals(2, dpc.mPowerState.getScreenState(), "display must end in STATE_ON(2), never DOZE(3)");

        // Lock-order inversion test: PMS thread holds pms.mLock calling wakeUp while Handler thread holds dpc.mDpcLock
        // calling updatePowerStateInternal concurrently. Verify off-handler wake never acquires dpc.mDpcLock synchronously!
        setupInstalledEnv(true);
        DisplayPowerController dpc2 = new DisplayPowerController(0);
        dpc2.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc2.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc2.updatePowerStateInternal();

        CountDownLatch dpcHoldingLock = new CountDownLatch(1);
        CountDownLatch pmsWakeDone = new CountDownLatch(1);
        AtomicReference<Throwable> invErr = new AtomicReference<>();

        Thread dpcLockHolder = new Thread(() -> {
            Looper.setCurrentLooperForTest(Looper.getMainLooper());
            synchronized (dpc2.mDpcLock) {
                dpcHoldingLock.countDown();
                try {
                    // Wait until PMS thread finishes wakeUp while holding pms.mLock!
                    assertTrue(pmsWakeDone.await(2, TimeUnit.SECONDS),
                            "off-handler wakeUp must complete without waiting for dpc.mDpcLock (deadlock avoidance)");
                } catch (Throwable t) {
                    invErr.compareAndSet(null, t);
                }
            }
        }, "DpcLockHolder");

        Thread pmsCaller = new Thread(() -> {
            try {
                assertTrue(dpcHoldingLock.await(2, TimeUnit.SECONDS), "dpc lock holder ready");
                pms.wakeUp(SystemClock.sNowMs, 0, "lock_inversion_check");
                pmsWakeDone.countDown();
            } catch (Throwable t) {
                invErr.compareAndSet(null, t);
            }
        }, "PmsCaller");

        dpcLockHolder.start();
        pmsCaller.start();
        dpcLockHolder.join(3000);
        pmsCaller.join(3000);
        assertTrue(invErr.get() == null, "zero lock-inversion errors: " + invErr.get());
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "wake invalidated session cleanly under lock contention");
    }

    private static void testDistinctSdrHdrEndpointsAndMidRampBoundsNarrowing() throws Exception {
        // 1. Distinct HDR (0.080f) vs SDR (0.020f) endpoints ramped smoothly to completion
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.60f, 0.30f);

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.020f;
        dpc.mDisplayBrightnessController.forcedHdrBrightness = 0.080f;
        dpc.updatePowerStateInternal();

        assertTrue(dpc.mScreenBrightnessRampAnimator.isAnimating(), "animator active for distinct HDR/SDR");
        advanceAndStepVsync(dpc, 650L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "completed in DOZE(3)");
        assertClose(0.080f, dpc.mPowerState.getScreenBrightness(), 1e-4f, "HDR primary reaches 0.080f");
        assertClose(0.020f, dpc.mPowerState.getSdrScreenBrightness(), 1e-4f, "SDR lane reaches distinct 0.020f (never clobbered)");

        // 2. Deadline snap preserves distinct HDR & SDR endpoints
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.60f, 0.30f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.025f;
        dpc.mDisplayBrightnessController.forcedHdrBrightness = 0.070f;
        dpc.updatePowerStateInternal();

        SystemClock.sNowMs += NativeOnAodRampCoordinator.MAX_SESSION_MS;
        dpc.mHandler.runReadyTasks();
        assertEquals(3, dpc.mPowerState.getScreenState(), "deadline reaches DOZE(3)");
        assertClose(0.070f, dpc.mPowerState.getScreenBrightness(), 1e-4f, "deadline snap preserves HDR 0.070f");
        assertClose(0.025f, dpc.mPowerState.getSdrScreenBrightness(), 1e-4f, "deadline snap preserves SDR 0.025f");

        // 3. Mid-ramp bounds narrowing clamps target cleanly
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenBrightness = 0.060f;
        dpc.updatePowerStateInternal();
        advanceAndStepVsync(dpc, 100L, 16);

        dpc.mBrightnessRangeController.max = 0.035f;
        dpc.updatePowerStateInternal();
        advanceAndStepVsync(dpc, 600L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "narrowed bounds hand off cleanly");
        assertClose(0.035f, dpc.mPowerState.getScreenBrightness(), 1e-4f, "final brightness obeys narrowed max 0.035f");
    }

    private static void testOverridesAndModifiersPreservedAndOwnedCleanupNonDestructive() throws Exception {
        // A: Thermal/low-power modifiers & settings flag preserved by Builder.from(origResult)
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        dpc.mDisplayBrightnessController.forcedModifiers =
                BrightnessReason.MODIFIER_THERMAL | BrightnessReason.MODIFIER_LOW_POWER;
        dpc.mDisplayBrightnessController.forcedShouldUpdateSetting = true;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();

        DisplayBrightnessState rewritten = dpc.mDisplayBrightnessController.updateBrightness(
                dpc.mPowerRequest, dpc.mPowerState.getScreenState(), null, false, false);
        assertTrue(rewritten != null, "rewritten state non-null");
        assertEquals(BrightnessReason.MODIFIER_THERMAL | BrightnessReason.MODIFIER_LOW_POWER,
                rewritten.getBrightnessReason().getModifier(),
                "Builder.from must preserve existing thermal/low-power modifiers");
        assertTrue(rewritten.shouldUpdateScreenBrightnessSetting(),
                "Builder.from must preserve shouldUpdateScreenBrightnessSetting");

        // B: Legitimate non-sleep override strategies cancel/yield cleanly without overwrite
        for (String overrideStrat : new String[]{
                "BoostBrightnessStrategy", "OverrideBrightnessStrategy",
                "TemporaryBrightnessStrategy", "FollowerBrightnessStrategy"}) {
            setupInstalledEnv(true);
            dpc = new DisplayPowerController(0);
            dpc.mPowerState.setScreenBrightness(0.50f);
            pms.goToSleep(SystemClock.sNowMs, 0, 0);
            dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
            dpc.updatePowerStateInternal();
            assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active before " + overrideStrat);

            dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
            dpc.mDisplayBrightnessController.forcedOverrideStrategyName = overrideStrat;
            dpc.mDisplayBrightnessController.forcedOverrideBrightness = 0.85f;
            dpc.updatePowerStateInternal();
            assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), overrideStrat + " must cancel coordinator session");
            DisplayBrightnessState afterOverride = dpc.mDisplayBrightnessController.updateBrightness(
                    dpc.mPowerRequest, dpc.mPowerState.getScreenState(), null, false, false);
            assertEquals(overrideStrat, afterOverride.getDisplayBrightnessStrategyName(),
                    overrideStrat + " strategy name must remain intact");
            assertClose(0.85f, afterOverride.getBrightness(), 1e-4f,
                    overrideStrat + " brightness must never be overwritten by Doze");
        }

        // Request boost/override flags yield cleanly
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.boostScreenBrightness = true;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "boostScreenBrightness must fail closed / yield");

        // C: Cleanup restores mDisplayBrightnessStrategy & session-owned RampRateController state,
        // WITHOUT clobbering concurrent native updates!
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        Object origStrategy = "FallbackBrightnessStrategy";
        dpc.mDisplayBrightnessController.mDisplayBrightnessStrategy = origStrategy;
        int origDur = dpc.mDisplayPowerControllerImpl.mRampRateController.mAnimationDurationDozeDimming;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(dpc.mDisplayBrightnessController.isAppliedDozeBrightnessStrategy(),
                "strategy swapped to Doze during ramp");

        // Native writes a newer mStartDozeDimmingTimeMills before wake cleanup runs:
        long concurrentNativeTimestamp = 999999999L;
        dpc.mDisplayPowerControllerImpl.mRampRateController.mRateRecord.setDozeDimmingTimeMills(concurrentNativeTimestamp);
        dpc.mDisplayPowerControllerImpl.mRampRateController.mRateRecord.addRateModifier(8); // unrelated bit

        pms.wakeUp(SystemClock.sNowMs + 50L, 0, "cleanup_check");
        dpc.mHandler.runReadyTasks();

        assertEquals(origDur, dpc.mDisplayPowerControllerImpl.mRampRateController.mAnimationDurationDozeDimming,
                "owned mAnimationDurationDozeDimming restored");
        assertEquals(concurrentNativeTimestamp,
                dpc.mDisplayPowerControllerImpl.mRampRateController.mRateRecord.mStartDozeDimmingTimeMills,
                "must NOT overwrite concurrent native mStartDozeDimmingTimeMills update");
        assertTrue((dpc.mDisplayPowerControllerImpl.mRampRateController.mRateRecord.mModifier & 8) != 0,
                "must preserve unrelated RampRateController modifier bits");
    }

    private static void testFaultContainmentNeverThrowsAndRecoversCleanly() throws Exception {
        // 1. SystemProperties.get throws inside hook -> caught safely, zero uncaught Throwable
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        SystemProperties.sThrowOnGet = true;
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        SystemProperties.sThrowOnGet = false;
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "faulted preflight leaves session inactive");

        // 2. Null DisplayBrightnessState from DBC -> safely tolerated without NPE
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        dpc.mDisplayBrightnessController.returnNullNext = true;
        dpc.mDisplayBrightnessController.updateBrightness(
                dpc.mPowerRequest, dpc.mPowerState.getScreenState(), null, false, false);

        // 3. DualRampAnimator.animateTo throws on deadline snap -> contained cleanly, session cleared
        dpc.mScreenBrightnessRampAnimator.throwNextAnimateTo = true;
        SystemClock.sNowMs += NativeOnAodRampCoordinator.MAX_SESSION_MS;
        dpc.mHandler.runReadyTasks();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "faulted deadline still cleans up session");
    }

    private static void testCapabilityFailClosedWhenHooksMissing() throws Exception {
        Looper.setCurrentLooperForTest(Looper.getMainLooper());
        SystemProperties.sThrowOnGet = false;
        SystemProperties.PROPS.clear();
        XposedBridge.HOOKS.clear();
        resetCoordinatorStatics();
        SystemProperties.set(NativeOnAodRampCoordinator.PROP_ENABLED, "1");

        ClassLoader emptyLoader = new URLClassLoader(new URL[0], null);
        NativeOnAodRampCoordinator.install(emptyLoader);

        assertTrue(!NativeOnAodRampCoordinator.isCapabilitiesReady(),
                "missing runtime classes must fail closed with isCapabilitiesReady() == false");
    }

    private static void testFullAodWarmTraceFreshAutoBeatsRepeatedStaleRequest() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.37486267f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        dpc.mDisplayBrightnessController.mFullAod = true;
        stub.mIsFullAod = true;
        StateCoordinator.sFullScreenAod = false; // special localized full AOD: mIsFullAod=true despite user setting off
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.mLastDozeBrightness = Float.NaN;
        stub.autoDozeBrightness = Float.NaN;

        // Call #1 (POLICY_OFF): stale request carries 5 nit (0.006043218f)
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = 0.006043218f;
        dpc.updatePowerStateInternal();

        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "full AOD warm trace session must start");
        assertEquals(2, dpc.mPowerState.getScreenState(), "display must hold STATE_ON(2) during ramp");
        assertTrue(Float.isNaN(stub.mLastDozeBrightness),
                "stale req.dozeScreenBrightness must NOT poison mLastDozeBrightness in mIsFullAod=true mode");
        boolean sawSeedNativeMin = false;
        for (String line : Log.LOGS) {
            if (line.contains("OnAodRamp START") && line.contains("mode=full") && line.contains("source=nativeMin")) {
                sawSeedNativeMin = true;
            }
        }
        assertTrue(sawSeedNativeMin, "seed must log mode=full source=nativeMin");

        // +10ms: sensor delivers fresh 12.85 nit (0.011956316f)
        SystemClock.sNowMs += 10L;
        dpc.mScreenBrightnessRampAnimator.stepVsync(0.010f);
        dpc.mHandler.runReadyTasks();
        stub.autoDozeBrightness = 0.011956316f;
        dpc.updatePowerStateInternal();

        assertClose(0.011956316f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "fresh sensor must retarget ramp to 0.011956316");
        boolean sawRetargetAuto = false;
        for (String line : Log.LOGS) {
            if (line.contains("RETARGET") && line.contains("source=nativeAuto") && line.contains("0.011956316")) {
                sawRetargetAuto = true;
            }
        }
        assertTrue(sawRetargetAuto, "must log RETARGET source=nativeAuto to 0.011956316");

        // Record log index after fresh retarget, then bombard with repeated stale 5 nit requests (+60ms, +120ms)
        int logCountAfterFreshAuto = Log.LOGS.size();
        SystemClock.sNowMs += 50L;
        dpc.mScreenBrightnessRampAnimator.stepVsync(0.016f);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = 0.006043218f;
        dpc.updatePowerStateInternal();

        SystemClock.sNowMs += 60L;
        dpc.mScreenBrightnessRampAnimator.stepVsync(0.016f);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenState = 3;
        dpc.mPowerRequest.dozeScreenBrightness = 0.006043218f;
        dpc.updatePowerStateInternal();

        assertClose(0.011956316f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "repeated stale 5 nit request must NEVER overwrite fresh 12.85 nit nativeAuto target");
        for (int i = logCountAfterFreshAuto; i < Log.LOGS.size(); i++) {
            String line = Log.LOGS.get(i);
            assertTrue(!line.contains("RETARGET") || !line.contains("0.006043218"),
                    "must NOT retarget back to stale 0.006043218: " + line);
        }

        // Complete ramp cleanly and confirm handoff at 12.85 nit + zero DOZE jump afterward
        advanceAndStepVsync(dpc, 1200L, 16);
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "session must complete at handoff");
        assertEquals(3, dpc.mPowerState.getScreenState(), "display must hand off cleanly to STATE_DOZE(3)");
        assertClose(0.011956316f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "handoff brightness must match fresh 0.011956316 (no stale 0.006043218)");
        for (int st : dpc.mPowerState.stateTransitions) {
            assertTrue(st != 1, "STATE_OFF(1) must never be emitted");
        }
        boolean sawHandoffAuto = false;
        for (String line : Log.LOGS) {
            if (line.contains("HANDOFF") && line.contains("source=nativeAuto") && line.contains("0.011956316")) {
                sawHandoffAuto = true;
            }
        }
        assertTrue(sawHandoffAuto, "HANDOFF must record source=nativeAuto target=0.011956316");

        // Subsequent native DOZE tick still yields zero jump
        dpc.updatePowerStateInternal();
        assertClose(0.011956316f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "native DOZE after handoff must remain steady at 0.011956316 without jump");
    }

    private static void testLegacyNonFullModeObeysClampedRequestEvenWithSensorValue() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.40f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        stub.mIsFullAod = false;
        stub.autoDozeBrightness = 0.011956316f; // ignored when mIsFullAod == false
        stub.mMinDozeBrightnessFloat = 0.006f;
        stub.mMaxDozeBrightnessFloat = 0.080f;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = 0.045f;
        dpc.updatePowerStateInternal();

        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "legacy non-full mode session must start");
        assertClose(0.045f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "non-full mode must obey clamped request 0.045 instead of sensor 0.011956316");
        boolean sawLegacyClampedReq = false;
        for (String line : Log.LOGS) {
            if (line.contains("mode=legacy") && line.contains("source=nativeClampedRequest")) {
                sawLegacyClampedReq = true;
            }
        }
        assertTrue(sawLegacyClampedReq, "non-full mode must log mode=legacy source=nativeClampedRequest");

        advanceAndStepVsync(dpc, 1200L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "legacy non-full mode must hand off to STATE_DOZE(3)");
        assertClose(0.045f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "legacy non-full mode handoff must settle at 0.045");
    }

    private static void testUnknownModeAndMethodFailClosedCleanly() throws Exception {
        Object[] badStubs = new Object[]{
                new UnknownModeStub(),
                new FullModeMissingAutoStub(),
                new LegacyModeMissingClampStub()
        };
        for (Object badStub : badStubs) {
            setupInstalledEnv(true);
            PowerManagerService pms = new PowerManagerService();
            DisplayPowerController dpc = new DisplayPowerController(0);
            dpc.mPowerState.setScreenBrightness(0.45f);
            dpc.mDisplayBrightnessController.mDozeStrategy.customStub = badStub;

            pms.goToSleep(SystemClock.sNowMs, 0, 0);
            dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
            dpc.mPowerRequest.dozeScreenBrightness = 0.020f;
            dpc.updatePowerStateInternal();

            assertTrue(!NativeOnAodRampCoordinator.isSessionActive(),
                    "unknown mode/method stub " + badStub.getClass().getSimpleName() + " must fail closed");
            assertEquals(3, dpc.mPowerState.getScreenState(),
                    "fail-closed must preserve 54aa fallback STATE_DOZE(3) for " + badStub.getClass().getSimpleName());
        }
    }

    private static void testLateEndpointChangeNearHandoffAndDifferingSdrCoherent() throws Exception {
        // Part 1: Coherent retarget right as DualRampAnimator finishes at 5 nit inside active window
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.30f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        dpc.mDisplayBrightnessController.mFullAod = true;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.mLastDozeBrightness = Float.NaN;
        stub.autoDozeBrightness = Float.NaN;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = 0.006043218f;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active");

        // Advance time only 450ms (remaining ~350ms > MIN_RETARGET_MS=80), settle animator manually to 5 nit & stop
        SystemClock.sNowMs += 450L;
        dpc.mScreenBrightnessRampAnimator.mAnimating = false;
        dpc.mScreenBrightnessRampAnimator.mCurrentValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mAnimatedValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mTargetValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mCurrentSdrValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mAnimatedSdrValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mTargetSdrValue = 0.006043218f;
        dpc.mPowerState.setScreenBrightness(0.006043218f);

        // Fresh sensor arrives right as onAnimationEnd/handoff triggers updatePowerStateInternal!
        stub.autoDozeBrightness = 0.011956316f;
        dpc.sendUpdatePowerState();

        assertTrue(NativeOnAodRampCoordinator.isSessionActive(),
                "late endpoint shift with sufficient budget must retarget coherently instead of stale handoff");
        assertEquals(2, dpc.mPowerState.getScreenState(), "must hold STATE_ON(2) during coherent late retarget");
        assertTrue(dpc.mScreenBrightnessRampAnimator.isAnimating(), "animator must re-arm smoothly to new endpoint");
        assertClose(0.011956316f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "coherent late retarget must target fresh 0.011956316");

        advanceAndStepVsync(dpc, 600L, 16);
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "session finishes after coherent late retarget");
        assertEquals(3, dpc.mPowerState.getScreenState(), "hands off cleanly to STATE_DOZE(3)");
        assertClose(0.011956316f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "hands off at updated 0.011956316 (no stale 0.006043218)");

        // Part 2: Near-deadline differing HDR/SDR endpoint shift (< MIN_RETARGET_MS remaining) snaps coherently to latest
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.35f, 0.25f);
        stub = dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.autoDozeBrightness = Float.NaN;
        dpc.mDisplayBrightnessController.mFullAod = true;
        dpc.mDisplayBrightnessController.forcedHdrBrightness = 0.030f;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.mPowerRequest.dozeScreenState = 3;
        dpc.mPowerRequest.dozeScreenBrightness = Float.NaN;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active with distinct SDR/HDR");
        assertClose(0.030f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f, "HDR tracks 0.030");
        assertClose(0.006043218f, dpc.mScreenBrightnessRampAnimator.mTargetSdrValue, 1e-6f, "SDR tracks 0.006043218");

        // Advance right near deadline (< 80ms remaining), shift sensor SDR -> 0.011956316 and HDR -> 0.032
        SystemClock.sNowMs += (NativeOnAodRampCoordinator.MAX_SESSION_MS - 40L);
        stub.autoDozeBrightness = 0.011956316f;
        dpc.mDisplayBrightnessController.forcedHdrBrightness = 0.032f;
        dpc.updatePowerStateInternal();
        SystemClock.sNowMs += 50L;
        dpc.mHandler.runReadyTasks();

        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "deadline snaps & ends session cleanly");
        assertEquals(3, dpc.mPowerState.getScreenState(), "deadline hands off to STATE_DOZE(3)");
        assertClose(0.032f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "near-deadline differing HDR must settle at 0.032");
        assertClose(0.011956316f, dpc.mPowerState.getSdrScreenBrightness(), 1e-5f,
                "near-deadline differing SDR must settle at latest nativeAuto 0.011956316");
        for (int st : dpc.mPowerState.stateTransitions) {
            assertTrue(st != 1, "no STATE_OFF(1) emitted during late differing-SDR handoff");
        }
    }

    private static void testDefaultEnableAbsentPropertyExplicitOneKillSwitchZeroAndZeroLeakage() throws Exception {
        // 1. Property COMPLETELY ABSENT enables guarded primary AOD ramp + zero ordinary interactive/override leakage
        setupInstalledEnv(true);
        assertTrue(!SystemProperties.PROPS.containsKey(NativeOnAodRampCoordinator.PROP_ENABLED),
                "property must be absent to prove default-enabled path");
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.50f);

        // Ordinary interactive states & manual override/boost BEFORE sleep must NEVER trigger session
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpc.updatePowerStateInternal();
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DIM;
        dpc.updatePowerStateInternal();
        dpc.mPowerRequest.screenBrightnessOverride = 0.70f;
        dpc.updatePowerStateInternal();
        dpc.mPowerRequest.screenBrightnessOverride = Float.NaN;
        dpc.mPowerRequest.boostScreenBrightness = true;
        dpc.updatePowerStateInternal();
        dpc.mPowerRequest.boostScreenBrightness = false;
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(),
                "default-enabled coordinator must NEVER touch interactive/DIM/override/boost transitions");

        // Owner inactive with absent property -> retains authentic 54aa fallback
        StateCoordinator.sLockWallpaperActive = false;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(),
                "inactive lock-wallpaper owner with absent property must NOT activate coordinator");

        // Restore active owner + wake back to STATE_ON(2), then fresh sleep -> absent property enables smooth ON->DOZE handoff
        StateCoordinator.sLockWallpaperActive = true;
        SystemClock.sNowMs += 2000L;
        pms.wakeUp(SystemClock.sNowMs, 0, "wake_before_sleep");
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpc.updatePowerStateInternal();
        assertEquals(2, dpc.mPowerState.getScreenState(), "screen restored to STATE_ON(2) before sleep");
        dpc.mPowerState.setScreenBrightness(0.45f);
        dpc.mDisplayBrightnessController.mDozeStrategy.mStub.mIsFullAod = true;
        dpc.mDisplayBrightnessController.mDozeStrategy.mStub.autoDozeBrightness = 0.022f;
        SystemClock.sNowMs += 100L;
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = Float.NaN;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(),
                "absent property MUST enable guarded primary sleep path");
        assertEquals(2, dpc.mPowerState.getScreenState(), "default-enabled path holds STATE_ON(2)");
        advanceAndStepVsync(dpc, 700L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "default-enabled path completes to DOZE(3)");
        assertClose(0.022f, dpc.mPowerState.getScreenBrightness(), 1e-4f,
                "default-enabled path settles at nativeAuto 0.022f");

        // 2. Explicit PROP_ENABLED="1" also enables guarded path cleanly
        setupInstalledEnv(true);
        SystemProperties.set(NativeOnAodRampCoordinator.PROP_ENABLED, "1");
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.42f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = 0.018f;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "explicit =1 enables coordinator");
        advanceAndStepVsync(dpc, 700L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "explicit =1 hands off cleanly to DOZE(3)");

        // 3. Emergency opt-out PROP_ENABLED="0" restores exact 54aa fallback
        setupInstalledEnv(false);
        assertEquals("0", SystemProperties.get(NativeOnAodRampCoordinator.PROP_ENABLED, ""),
                "kill switch must set =0");
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.42f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "emergency =0 keeps coordinator inactive");
        assertEquals(3, dpc.mPowerState.getScreenState(), "emergency =0 yields exact 54aa STATE_DOZE(3)");
        assertEquals(0, dpc.mPowerState.colorFadePreparedCount, "emergency =0 suppresses ColorFade via 54aa");

        // 4. Unrecognized property value fails closed to 54aa
        setupInstalledEnv(true);
        SystemProperties.set(NativeOnAodRampCoordinator.PROP_ENABLED, "invalid_value");
        dpc = new DisplayPowerController(0);
        dpc.mPowerState.setScreenBrightness(0.42f);
        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "unrecognized prop value fails closed");
        assertEquals(3, dpc.mPowerState.getScreenState(), "unrecognized prop value preserves 54aa STATE_DOZE(3)");
    }

    private static void testFullAodSubLegacyMinAutoSeedRetargetAndSmoothHandoff() throws Exception {
        // Reproduces auto_brightness_live_log: 0.004655516 (3.9 nit) < legacy mMinDozeBrightnessFloat (0.006043218 / 5 nit)
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.002f;
        dpc.mBrightnessRangeController.max = 1.0f;
        dpc.mPowerState.setScreenBrightness(0.018000819f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        dpc.mDisplayBrightnessController.mFullAod = true;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.mLastDozeBrightness = 0.006043218f;
        stub.autoDozeBrightness = Float.NaN;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = Float.NaN;
        dpc.updatePowerStateInternal();

        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active for sub-5-nit auto ramp");
        assertClose(0.006043218f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "initial seed uses nativeLast 0.006043218");

        // +78ms: native DozeAutoBrightnessController emits 0.004655516 (3.9 nit)
        SystemClock.sNowMs += 78L;
        dpc.mScreenBrightnessRampAnimator.stepVsync(0.016f);
        stub.autoDozeBrightness = 0.004655516f;
        dpc.updatePowerStateInternal();

        assertClose(0.004655516f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "fullMode retarget MUST propagate 0.004655516 instead of clamping to legacy 0.006043218");

        advanceAndStepVsync(dpc, 900L, 16);
        assertTrue(!NativeOnAodRampCoordinator.isSessionActive(), "session finished");
        assertEquals(3, dpc.mPowerState.getScreenState(), "handed off to STATE_DOZE(3)");
        assertClose(0.004655516f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "handoff brightness matches sub-5-nit nativeAuto 0.004655516");
        for (int st : dpc.mPowerState.stateTransitions) {
            assertTrue(st != 1, "no STATE_OFF(1) emitted");
        }
        // Subsequent native DOZE pass matches identically with zero step-down drop
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_DOZE;
        dpc.updatePowerStateInternal();
        assertClose(0.004655516f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "native DOZE after handoff stays at 0.004655516 without jump");

        // Direct Call #1 seed when nativeAuto is already 0.004655516 from start
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.002f;
        dpc.mBrightnessRangeController.max = 1.0f;
        dpc.mPowerState.setScreenBrightness(0.30f);
        stub = dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.autoDozeBrightness = 0.004655516f;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertClose(0.004655516f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "direct Call #1 seed accepts sub-5-nit nativeAuto 0.004655516");
        advanceAndStepVsync(dpc, 700L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "direct sub-5-nit seed finishes at DOZE(3)");
        assertClose(0.004655516f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "direct sub-5-nit handoff settles at 0.004655516");
    }

    private static void testFullAodSubLegacyMinLatePreHandoffAndDeadlineSnap() throws Exception {
        // Part A: Reproduces manual_max 0.0039238185 (3.32 nit) shifting right at onAnimationEnd pre-handoff
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.002f;
        dpc.mBrightnessRangeController.max = 1.0f;
        dpc.mPowerState.setScreenBrightness(0.37486267f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        dpc.mDisplayBrightnessController.mFullAod = true;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.mLastDozeBrightness = 0.006043218f;
        stub.autoDozeBrightness = Float.NaN;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertTrue(NativeOnAodRampCoordinator.isSessionActive(), "session active");

        // Animator settles at 0.006043218f before onAnimationEnd triggers sendUpdatePowerState
        SystemClock.sNowMs += 450L;
        dpc.mScreenBrightnessRampAnimator.mAnimating = false;
        dpc.mScreenBrightnessRampAnimator.mCurrentValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mAnimatedValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mTargetValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mCurrentSdrValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mAnimatedSdrValue = 0.006043218f;
        dpc.mScreenBrightnessRampAnimator.mTargetSdrValue = 0.006043218f;
        dpc.mPowerState.setScreenBrightness(0.006043218f);

        stub.autoDozeBrightness = 0.0039238185f;
        dpc.sendUpdatePowerState();

        assertTrue(NativeOnAodRampCoordinator.isSessionActive(),
                "pre-handoff mismatch to sub-5-nit 0.0039238185 must coherently retarget while holding STATE_ON(2)");
        assertEquals(2, dpc.mPowerState.getScreenState(), "must hold STATE_ON(2) during coherent retarget");
        assertClose(0.0039238185f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "coherent retarget targets 0.0039238185");

        advanceAndStepVsync(dpc, 600L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "hands off to DOZE(3)");
        assertClose(0.0039238185f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "pre-handoff coherent retarget settles at 0.0039238185 (no 5->3.32 nit drop)");

        // Part B: Deadline snap propagates sub-5-nit 0.0039238185 accurately
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.002f;
        dpc.mBrightnessRangeController.max = 1.0f;
        dpc.mPowerState.setScreenBrightness(0.37486267f);
        stub = dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.mLastDozeBrightness = 0.006043218f;
        stub.autoDozeBrightness = Float.NaN;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        stub.autoDozeBrightness = 0.0039238185f;
        SystemClock.sNowMs += NativeOnAodRampCoordinator.MAX_SESSION_MS;
        dpc.mHandler.runReadyTasks();

        assertEquals(3, dpc.mPowerState.getScreenState(), "deadline reaches DOZE(3)");
        assertClose(0.0039238185f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "deadline snap settles at 0.0039238185 instead of legacy 0.006043218");
    }

    private static void testFullAodStillEnforcesHardwarePanelFloorAndThermalHbmCaps() throws Exception {
        // Panel floor guard (minB = 0.0042f > raw sensor 0.0030f < legacyMin 0.006043218f)
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.0042f;
        dpc.mBrightnessRangeController.max = 1.0f;
        dpc.mPowerState.setScreenBrightness(0.30f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        dpc.mDisplayBrightnessController.mFullAod = true;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.autoDozeBrightness = 0.0030f;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertClose(0.0042f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "hardware panel floor 0.0042f remains enforced in fullMode");
        advanceAndStepVsync(dpc, 700L, 16);
        assertClose(0.0042f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "panel-floor clamp preserved at handoff");

        // Thermal/HBM ceiling guard (maxB = 0.035f < raw sensor 0.045f)
        setupInstalledEnv(true);
        dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.002f;
        dpc.mBrightnessRangeController.max = 0.035f;
        dpc.mPowerState.setScreenBrightness(0.30f);
        stub = dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        stub.mIsFullAod = true;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.autoDozeBrightness = 0.045f;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.updatePowerStateInternal();
        assertClose(0.035f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "thermal/HBM max 0.035f remains enforced in fullMode");
        advanceAndStepVsync(dpc, 700L, 16);
        assertClose(0.035f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "thermal/HBM cap preserved at handoff");
    }

    private static void testLegacyNonFullSubMinRequestStillClampedToLegacyMin() throws Exception {
        setupInstalledEnv(true);
        PowerManagerService pms = new PowerManagerService();
        DisplayPowerController dpc = new DisplayPowerController(0);
        dpc.mBrightnessRangeController.min = 0.002f;
        dpc.mBrightnessRangeController.max = 1.0f;
        dpc.mPowerState.setScreenBrightness(0.35f);

        com.android.server.display.brightness.strategy.DozeBrightnessStrategy.StubImpl stub =
                dpc.mDisplayBrightnessController.mDozeStrategy.mStub;
        stub.mIsFullAod = false;
        stub.mMinDozeBrightnessFloat = 0.006043218f;
        stub.mMaxDozeBrightnessFloat = 0.080f;
        stub.autoDozeBrightness = 0.0039238185f;

        pms.goToSleep(SystemClock.sNowMs, 0, 0);
        dpc.mPowerRequest.policy = DisplayPowerRequest.POLICY_OFF;
        dpc.mPowerRequest.dozeScreenBrightness = 0.0040f;
        dpc.updatePowerStateInternal();

        assertClose(0.006043218f, dpc.mScreenBrightnessRampAnimator.mTargetValue, 1e-6f,
                "legacy non-full mode must still clamp sub-5-nit request up to legacy min 0.006043218");
        advanceAndStepVsync(dpc, 700L, 16);
        assertEquals(3, dpc.mPowerState.getScreenState(), "legacy non-full reaches DOZE(3)");
        assertClose(0.006043218f, dpc.mPowerState.getScreenBrightness(), 1e-5f,
                "legacy non-full handoff stays clamped at 0.006043218");
    }

    public static void main(String[] args) throws Exception {
        try {
            runAllTests();
        } catch (Throwable t) {
            System.out.println("=== FAILURE LOG DUMP ===");
            for (String line : Log.LOGS) {
                System.out.println("  " + line);
            }
            throw t;
        }
    }

    private static void runAllTests() throws Exception {
        testDisabledPreservesExact54aaAllTargets();
        System.out.println("PASS: testDisabledPreservesExact54aaAllTargets");
        testEnabledPrimarySleepFromPolicyOffHoldsOnThenHandoffsDoze();
        System.out.println("PASS: testEnabledPrimarySleepFromPolicyOffHoldsOnThenHandoffsDoze");
        testEnabledPrimarySleepFromPolicyDozeHoldsOnThenHandoffsDoze();
        System.out.println("PASS: testEnabledPrimarySleepFromPolicyDozeHoldsOnThenHandoffsDoze");
        testMidRampFiniteDozeRetargeting();
        System.out.println("PASS: testMidRampFiniteDozeRetargeting");
        testDuplicateAndRateZeroImmunity();
        System.out.println("PASS: testDuplicateAndRateZeroImmunity");
        testRetargetFloodOverMaxRetargetsTracksLatestTarget();
        System.out.println("PASS: testRetargetFloodOverMaxRetargetsTracksLatestTarget");
        testSecondaryAndUnknownTargetMatchAuthenticDisabled54aaWhenEnabled();
        System.out.println("PASS: testSecondaryAndUnknownTargetMatchAuthenticDisabled54aaWhenEnabled");
        testPreflightFailClosedInvalidInputsNeverFabricateOrBreakNative();
        System.out.println("PASS: testPreflightFailClosedInvalidInputsNeverFabricateOrBreakNative");
        testImmediateCancelOnWakeAndBiometricWakeAndSameToken();
        System.out.println("PASS: testImmediateCancelOnWakeAndBiometricWakeAndSameToken");
        testImmediateCancelOnOwnerLossFullscreenAndPropDisableIncludingVsyncOnly();
        System.out.println("PASS: testImmediateCancelOnOwnerLossFullscreenAndPropDisableIncludingVsyncOnly");
        testAsyncCancellationBarrierRaceAndLockInversion();
        System.out.println("PASS: testAsyncCancellationBarrierRaceAndLockInversion");
        testDistinctSdrHdrEndpointsAndMidRampBoundsNarrowing();
        System.out.println("PASS: testDistinctSdrHdrEndpointsAndMidRampBoundsNarrowing");
        testOverridesAndModifiersPreservedAndOwnedCleanupNonDestructive();
        System.out.println("PASS: testOverridesAndModifiersPreservedAndOwnedCleanupNonDestructive");
        testFaultContainmentNeverThrowsAndRecoversCleanly();
        System.out.println("PASS: testFaultContainmentNeverThrowsAndRecoversCleanly");
        testCapabilityFailClosedWhenHooksMissing();
        System.out.println("PASS: testCapabilityFailClosedWhenHooksMissing");
        testFullAodWarmTraceFreshAutoBeatsRepeatedStaleRequest();
        System.out.println("PASS: testFullAodWarmTraceFreshAutoBeatsRepeatedStaleRequest");
        testLegacyNonFullModeObeysClampedRequestEvenWithSensorValue();
        System.out.println("PASS: testLegacyNonFullModeObeysClampedRequestEvenWithSensorValue");
        testUnknownModeAndMethodFailClosedCleanly();
        System.out.println("PASS: testUnknownModeAndMethodFailClosedCleanly");
        testLateEndpointChangeNearHandoffAndDifferingSdrCoherent();
        System.out.println("PASS: testLateEndpointChangeNearHandoffAndDifferingSdrCoherent");
        testDefaultEnableAbsentPropertyExplicitOneKillSwitchZeroAndZeroLeakage();
        System.out.println("PASS: testDefaultEnableAbsentPropertyExplicitOneKillSwitchZeroAndZeroLeakage");
        testFullAodSubLegacyMinAutoSeedRetargetAndSmoothHandoff();
        System.out.println("PASS: testFullAodSubLegacyMinAutoSeedRetargetAndSmoothHandoff");
        testFullAodSubLegacyMinLatePreHandoffAndDeadlineSnap();
        System.out.println("PASS: testFullAodSubLegacyMinLatePreHandoffAndDeadlineSnap");
        testFullAodStillEnforcesHardwarePanelFloorAndThermalHbmCaps();
        System.out.println("PASS: testFullAodStillEnforcesHardwarePanelFloorAndThermalHbmCaps");
        testLegacyNonFullSubMinRequestStillClampedToLegacyMin();
        System.out.println("PASS: testLegacyNonFullSubMinRequestStillClampedToLegacyMin");
        System.out.println("ALL 24 ADVERSARIAL TESTS PASSED");
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError("ASSERTION FAILED (" + msg + "): expected=" + expected + " actual=" + actual);
        }
    }
}
