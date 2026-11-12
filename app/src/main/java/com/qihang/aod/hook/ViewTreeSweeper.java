package com.qihang.aod.hook;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.qihang.aod.ClockSettings;
import com.qihang.aod.FlymeClockView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Clock ownership is exclusive. Native AOD takes over only after its first visible draw. */
public final class ViewTreeSweeper {
    private static final String TAG_LOCK = "qihang_flyme_clock_lock";
    private static final String TAG_AOD = "qihang_flyme_clock_aod";
    private static final String TAG_BRIDGE = "qihang_flyme_clock_bridge";
    private static final WeakHashMap<View, RootState> ROOTS = new WeakHashMap<>();
    private static Runnable sSweep;
    private static boolean sApplying;

    private static final class Saved {
        float transitionAlpha;
        Saved(View v) { transitionAlpha = v.getTransitionAlpha(); }
    }
    private static final class RootState {
        final ArrayList<WeakReference<View>> lockNodes = new ArrayList<>();
        final ArrayList<WeakReference<View>> aodNodes = new ArrayList<>();
        final WeakHashMap<View, Saved> hidden = new WeakHashMap<>();
        WeakReference<View> drawnAod;
        long drawnSleepAt;
        long nativeRevealCycle;
        long nativeRevealUntil;
        boolean nativeRevealActive;
        boolean nativeDiagnosticLogged;
        final ArrayList<WeakReference<View>> nativeDigits = new ArrayList<>();
        final ArrayList<WeakReference<TextView>> nativeClockTexts = new ArrayList<>();
        FlymeClockView lockClock;
        FlymeClockView aodClock;
        FlymeClockView bridgeClock;
        boolean customAod;
        boolean customLock;
        boolean lockClockHidden;
        boolean aodClockHidden;
        boolean nativeKeyguard;
        ClockSettings.Config aodConfig;
        ClockSettings.Config lockConfig;
        String lastPolicy;
        long bridgeSleepEpoch;
        boolean bridgeSleepPending;
        boolean bridgeLockPending;
        boolean lastSleeping;
        boolean lastLockScene;
        boolean transitionInitialized;
        int lastBridgeMode = -1;
    }

    private ViewTreeSweeper() {}

    /** Sleep properties cover the power event; display state covers wake/sleep callback gaps. */
    public static boolean isClockSleeping(Context ctx) {
        if (StateCoordinator.isSleepingActive()) return true;
        long wake = StateCoordinator.propGetLong(StateCoordinator.PROP_WAKE_AT, 0);
        if (wake > 0 && SystemClock.uptimeMillis() - wake < 1200L) return false;
        return StateCoordinator.isDozeLike(ctx);
    }

    private static boolean isLockScene(Context ctx) {
        if (isClockSleeping(ctx)) return false;
        String state = StateCoordinator.sStateName;
        if ("KEYGUARD".equals(state)) return true;
        if (state != null && !("AOD".equals(state) || "PULSING".equals(state))) return false;
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) { return false; }
    }

    public static void sweep(final View root) {
        if (root == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            StateCoordinator.mainHandler().post(new Runnable() { public void run() { sweep(root); } });
            return;
        }
        final View top = root.getRootView();
        RootState state = ROOTS.get(top);
        if (state == null) {
            state = new RootState();
            ROOTS.put(top, state);
            final WeakReference<View> ref = new WeakReference<>(top);
            top.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    View v = ref.get();
                    RootState s = v == null ? null : ROOTS.get(v);
                    if (s != null) applyPolicy(v, s);
                    return true;
                }
            });
        }
        Context ctx = top.getContext();
        StateCoordinator.ensureClockReceiver(ctx);
        ClockSettings.Config aodConfig = ClockSettings.get(ctx, 0);
        ClockSettings.Config lockConfig = ClockSettings.get(ctx, 1);
        boolean configChanged = state.aodConfig == null || !state.aodConfig.isSameStyle(aodConfig)
                || state.lockConfig == null || !state.lockConfig.isSameStyle(lockConfig);
        state.aodConfig = aodConfig.copy();
        state.lockConfig = lockConfig.copy();
        state.customAod = aodConfig.enabled;
        state.customLock = lockConfig.enabled;
        if (configChanged) state.transitionInitialized = false;
        boolean revealNative = isClockSleeping(ctx) && StateCoordinator.isAodEnabled(ctx)
                && !StateCoordinator.isFullScreenAod(ctx) && !state.customAod;
        if (revealNative && !state.nativeRevealActive) {
            state.nativeRevealCycle = SystemClock.uptimeMillis();
            state.nativeRevealUntil = state.nativeRevealCycle + 2500L;
            state.nativeDiagnosticLogged = false;
        }
        state.nativeRevealActive = revealNative;
        state.lockNodes.clear();
        state.aodNodes.clear();
        state.nativeDigits.clear();
        state.nativeClockTexts.clear();
        collect(top, state, false, false, StateCoordinator.isAodEnabled(ctx));
        View aod = findHost(top, true);
        View lock = findHost(top, false);
        boolean aodClockChanged = false;
        if (state.customAod && aod instanceof ViewGroup) {
            // The dynamically loaded AOD host can read stale MIUI AOD preferences.
            // Use the same settings context as the clock ownership policy.
            FlymeClockView next = ensure((ViewGroup) aod, ctx, TAG_AOD, 0);
            aodClockChanged = state.aodClock != next;
            if (aodClockChanged && state.aodClock != null) state.aodClock.setVisibility(View.GONE);
            state.aodClock = next;
        }
        if (state.customLock && lock instanceof ViewGroup) {
            FlymeClockView next = ensure((ViewGroup) lock, lock.getContext(), TAG_LOCK, 1);
            if (state.lockClock != null && state.lockClock != next) state.lockClock.setVisibility(View.GONE);
            state.lockClock = next;
        }
        if ((state.customAod || state.customLock) && top instanceof ViewGroup) {
            state.bridgeClock = ensureBridge((ViewGroup) top, ctx);
        }
        // A reused AOD view may have been detached when the settings broadcast arrived.
        if ((configChanged || aodClockChanged) && state.aodClock != null) state.aodClock.reloadConfig();
        if (configChanged && state.lockClock != null) state.lockClock.reloadConfig();
        if (configChanged && state.bridgeClock != null) state.bridgeClock.reloadConfig();
        applyPolicy(top, state);
    }

    private static void collect(View v, RootState state, boolean inAod, boolean inLock, boolean aodEnabled) {
        if (v instanceof FlymeClockView) {
            // Remove overlays from old releases; only explicitly owned hosts remain.
            Object tag = v.getTag();
            if ((!TAG_AOD.equals(tag) && !TAG_LOCK.equals(tag) && !TAG_BRIDGE.equals(tag))
                    || (TAG_AOD.equals(tag) && v != state.aodClock)
                    || (TAG_LOCK.equals(tag) && v != state.lockClock)
                    || (TAG_BRIDGE.equals(tag) && v != state.bridgeClock)) v.setVisibility(View.GONE);
            return;
        }
        String id = StateCoordinator.idNameOf(v);
        String cls = v.getClass().getSimpleName();
        if ("TimeView".equals(cls)) {
            // MIUI dynamically loads AOD code into SystemUI with its own loader.
            // Package callbacks alone do not necessarily see this copy of TimeView.
            SystemUiHook.installClockDrawGate(v.getClass().getClassLoader());
        }
        boolean aodRoot = "AODView".equals(cls) || "AodView".equals(cls) || "aod_root_view".equals(id);
        inAod |= aodRoot;
        if ("AODView".equals(cls) || "AodContainerView".equals(cls) || cls.contains("LinkageStyleClockView")) {
            AodHook.ensureNativeAodVisible(v, state.nativeRevealActive, state.nativeRevealCycle);
        }
        if (inAod && "TimeView".equals(cls)) state.nativeDigits.add(new WeakReference<>(v));
        if (inAod && !inLock && isNativeClockTextView(v, id)) {
            state.nativeClockTexts.add(new WeakReference<>((TextView) v));
        }
        if (aodEnabled && inAod && isDarkBackground(v)) v.setBackground(null);
        boolean shell = "miui_keyguard_clock_container".equals(id)
                || "miui_keyguard_foreground_clock_container".equals(id)
                || "KeyguardClockContainer".equals(cls) || "KeyguardClockView".equals(cls);
        if (shell && !inAod) { state.lockNodes.add(new WeakReference<>(v)); inLock = true; }
        // Never hide keyguard_panel_view, AODView, or clock_animation_container: they host shared content.
        if (inAod && !inLock && !aodRoot && isAodClock(cls, id)) state.aodNodes.add(new WeakReference<>(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), state, inAod, inLock, aodEnabled);
        }
    }

    private static void applyPolicy(View root, RootState s) {
        if (sApplying) return;
        sApplying = true;
        try {
            Context ctx = root.getContext();
            boolean sleeping = isClockSleeping(ctx);
            boolean aodOn = StateCoordinator.isAodEnabled(ctx);
            boolean fullAod = StateCoordinator.isFullScreenAod(ctx);
            boolean lockScene = isLockScene(ctx);
            boolean useCustomAod = aodOn && !fullAod && s.customAod;

            long sleepEpoch = StateCoordinator.propGetLong(StateCoordinator.PROP_SLEEP_AT, 0);
            boolean enteringSleep = sleeping && (!s.transitionInitialized || !s.lastSleeping
                    || s.bridgeSleepEpoch != sleepEpoch);
            boolean enteringLock = !sleeping && lockScene && (!s.transitionInitialized || !s.lastLockScene || s.lastSleeping);

            if (sleeping) {
                if (enteringSleep) {
                    s.bridgeSleepEpoch = sleepEpoch;
                    s.bridgeSleepPending = useCustomAod || (s.customLock && aodOn && !fullAod);
                    s.bridgeLockPending = false;
                }
                if (!useCustomAod && (!s.customLock || !aodOn || fullAod)) {
                    s.bridgeSleepPending = false;
                }
            } else {
                s.bridgeSleepPending = false;
                if (enteringLock) {
                    s.bridgeLockPending = s.customLock;
                } else if (!lockScene) {
                    s.bridgeLockPending = false;
                }
                if (!s.customLock) {
                    s.bridgeLockPending = false;
                }
            }
            s.lastSleeping = sleeping;
            s.lastLockScene = lockScene;
            s.transitionInitialized = true;

            boolean showAod = sleeping && useCustomAod;
            boolean showLock = !sleeping && lockScene && s.customLock;

            updateClock(s.aodClock, showAod, 0);
            updateClock(s.lockClock, showLock, 1);

            // Sample custom host readiness AFTER updateClock
            boolean aodReady = visibleContent(s.aodClock);
            boolean lockReady = visibleContent(s.lockClock);

            if (sleeping) {
                if (useCustomAod && aodReady) {
                    s.bridgeSleepPending = false;
                }
            } else {
                if (lockReady) {
                    s.bridgeLockPending = false;
                }
            }

            boolean showBridge = false;
            int bridgeMode = 0;
            if (sleeping) {
                if (s.bridgeSleepPending) {
                    showBridge = true;
                    bridgeMode = useCustomAod ? 0 : 1;
                }
            } else {
                if (lockScene && s.bridgeLockPending) {
                    showBridge = true;
                    bridgeMode = 1;
                }
            }

            if (showBridge && s.bridgeClock == null && root instanceof ViewGroup) {
                s.bridgeClock = ensureBridge((ViewGroup) root, ctx);
            }

            updateBridgeClock(s, s.bridgeClock, showBridge, bridgeMode);

            boolean customReady = showAod ? aodReady : (showLock ? lockReady : false);
            if (!sleeping && StateCoordinator.isLinkageAnimating()) {
                StateCoordinator.onLinkageAnimFinished("awake_policy");
            }
            // The AOD tree can still be visible during wake. Keep its native clock and date
            // hidden while SystemUI dismisses it, and restore them when native AOD is selected.
            if (StateCoordinator.isLinkageAnimating()) {
                s.drawnAod = null;
                s.drawnSleepAt = 0;
            }
            boolean hideNativeAod = useCustomAod
                    || (sleeping && !s.customAod && StateCoordinator.isLinkageAnimating());
            boolean redrawAod = !hideNativeAod && s.aodClockHidden;
            s.aodClockHidden = hideNativeAod;
            for (WeakReference<View> ref : s.aodNodes) {
                View node = ref.get();
                setHidden(s, node, hideNativeAod);
            }
            if (redrawAod) {
                for (WeakReference<View> ref : s.nativeDigits) {
                    View digit = ref.get();
                    if (digit != null) digit.invalidate();
                }
                HookUtils.log("Redraw AOD clock digits triggered on linkage exit");
            }
            if (s.nativeRevealActive && sleeping && !s.customAod && !StateCoordinator.isLinkageAnimating()
                    && SystemClock.uptimeMillis() < s.nativeRevealUntil) {
                if (!s.nativeDiagnosticLogged && !s.nativeDigits.isEmpty()) {
                    s.nativeDiagnosticLogged = true;
                    logNativeChain(s.nativeDigits.get(0).get());
                }
                for (WeakReference<View> ref : s.nativeDigits) revealNativeDigits(ref.get());
            }
            boolean nativeReady = nativeAodReady(s);
            if (sleeping && !useCustomAod && s.bridgeSleepPending) {
                boolean nativeTargetReady;
                if (!s.nativeDigits.isEmpty()) {
                    nativeTargetReady = nativeReady;
                } else if (!s.nativeClockTexts.isEmpty()) {
                    nativeTargetReady = nativeClockTextReady(s);
                } else {
                    nativeTargetReady = aodHostActive(root) && !StateCoordinator.isLinkageAnimating();
                }
                if (nativeTargetReady) {
                    s.bridgeSleepPending = false;
                    if (showBridge) {
                        showBridge = false;
                        updateBridgeClock(s, s.bridgeClock, false, bridgeMode);
                    }
                }
            }
            boolean wakingFromCustomAod = StateCoordinator.sScrimMode == 0 && s.customAod && aodOn && !fullAod;
            boolean hideLock = sleeping
                    ? (!aodOn || (!fullAod && (useCustomAod || s.customLock || (nativeReady && !StateCoordinator.isLinkageAnimating()))))
                    : hideNativeLockWhileAwake(s, lockScene, wakingFromCustomAod);
            boolean nativeKeyguard = !sleeping && "KEYGUARD".equals(StateCoordinator.sStateName) && !showLock;
            boolean redrawLock = !hideLock && (s.lockClockHidden || (nativeKeyguard && !s.nativeKeyguard));
            s.lockClockHidden = hideLock;
            s.nativeKeyguard = nativeKeyguard;
            for (WeakReference<View> ref : s.lockNodes) {
                View node = ref.get();
                setHidden(s, node, hideLock);
                if (nativeKeyguard && !hideLock && isNativeLockContainer(node)) {
                    restoreResidualLockAlpha(s, node);
                }
                if (redrawLock && node != null) invalidateClockDigits(node);
            }
            String policy = "sleep=" + sleeping + " lockScene=" + lockScene
                    + " customAod=" + s.customAod + " customLock=" + s.customLock
                    + " nativeAodReady=" + nativeReady + " customReady=" + customReady
                    + " aodOverlay=" + showAod + " lockOverlay=" + showLock
                    + " bridge=" + showBridge + "(m" + bridgeMode + ",p=" + (sleeping ? s.bridgeSleepPending : s.bridgeLockPending) + ") hideNativeLock=" + hideLock;
            if (!policy.equals(s.lastPolicy)) {
                s.lastPolicy = policy;
                HookUtils.log("Clock policy: " + policy);
            }
            if (!sleeping) { s.drawnAod = null; s.drawnSleepAt = 0; }
        } finally { sApplying = false; }
    }

    private static boolean isNativeLockContainer(View v) {
        if (v == null) return false;
        String id = StateCoordinator.idNameOf(v);
        return "miui_keyguard_clock_container".equals(id)
                || "miui_keyguard_foreground_clock_container".equals(id);
    }

    private static void restoreResidualLockAlpha(RootState s, View node) {
        if (node == null) return;
        Saved saved = s.hidden.get(node);
        if (saved == null) return;
        float current = node.getTransitionAlpha();
        if (current <= 0.01f) {
            node.setTransitionAlpha(1.0f);
            node.invalidate();
            invalidateClockDigits(node);
            String id = StateCoordinator.idNameOf(node);
            HookUtils.log("Restored native lock container transitionAlpha: container=" + id
                    + " old=" + current + " saved=" + saved.transitionAlpha + " state=" + StateCoordinator.sStateName);
        }
        s.hidden.remove(node);
    }

    private static void setHidden(RootState s, View v, boolean hide) {
        if (v == null) return;
        if (hide) {
            float current = v.getTransitionAlpha();
            Saved saved = s.hidden.get(v);
            if (saved == null) {
                saved = new Saved(v);
                s.hidden.put(v, saved);
            } else if (current > 0.01f && isNativeLockContainer(v)) {
                saved.transitionAlpha = current;
            }
            // A separate multiplicative property avoids overriding the system's alpha/visibility
            // animation, including the split minute container synchronized by setVisibility().
            if (current != 0f) v.setTransitionAlpha(0f);
        } else {
            Saved saved = s.hidden.get(v);
            if (saved == null) return;
            if (isNativeLockContainer(v)) {
                float current = v.getTransitionAlpha();
                if (current > 0.01f) {
                    s.hidden.remove(v);
                } else if (saved.transitionAlpha > 0.01f) {
                    v.setTransitionAlpha(saved.transitionAlpha);
                    v.invalidate();
                    s.hidden.remove(v);
                }
                // When saved == 0 and current == 0: keep saved in s.hidden until true nativeKeyguard.
            } else {
                s.hidden.remove(v);
                v.setTransitionAlpha(saved.transitionAlpha);
                v.invalidate();
            }
        }
    }

    private static void invalidateClockDigits(View clock) {
        // A TimeView skipped by the draw gate can keep an empty cached display list
        // even after its parent becomes visible again.
        if ("TimeView".equals(clock.getClass().getSimpleName())) {
            clock.invalidate();
        } else if (clock instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) clock;
            for (int i = 0; i < group.getChildCount(); i++) invalidateClockDigits(group.getChildAt(i));
        }
    }

    private static void updateClock(FlymeClockView clock, boolean visible, int mode) {
        if (clock == null) return;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (clock.getVisibility() != visibility) clock.setVisibility(visibility);
        if (visible) {
            clock.setClockMode(mode, false);
            clock.setAlpha(1f);
            clock.bringToFront();
        }
    }

    private static void updateBridgeClock(RootState s, FlymeClockView clock, boolean visible, int mode) {
        if (clock == null) return;
        int visibility = visible ? View.VISIBLE : View.GONE;
        boolean visChanged = clock.getVisibility() != visibility;
        boolean modeChanged = s != null && s.lastBridgeMode != mode;
        if (visChanged) {
            clock.setVisibility(visibility);
            if (visible) clock.bringToFront();
        }
        if (visible) {
            if (visChanged || modeChanged) {
                clock.setClockMode(mode, false);
                clock.updateTime();
                if (s != null) s.lastBridgeMode = mode;
            }
            if (clock.getAlpha() != 1f) clock.setAlpha(1f);
        } else if (s != null) {
            s.lastBridgeMode = -1;
        }
    }

    /** AOD ready is proved by the real digit draw, and invalidated by visibility/alpha or a new sleep. */
    private static boolean nativeAodReady(RootState s) {
        if (StateCoordinator.isLinkageAnimating()) return false;
        View v = s.drawnAod == null ? null : s.drawnAod.get();
        return s.drawnSleepAt == StateCoordinator.propGetLong(StateCoordinator.PROP_SLEEP_AT, 0)
                && visibleContent(v);
    }

    private static boolean isNativeClockTextView(View v, String id) {
        if (!(v instanceof TextView) || id == null) return false;
        if (id.contains("date") || id.contains("battery") || id.contains("lunar")
                || id.contains("calendar") || id.contains("title") || id.contains("signature")
                || id.contains("owner") || id.contains("sub") || id.contains("unit")
                || id.contains("percent") || id.contains("colon")) {
            return false;
        }
        return id.startsWith("clock_horizontal")
                || id.startsWith("clock_vertical")
                || id.startsWith("clock_oneline")
                || id.startsWith("clock_classic")
                || "clock_time".equals(id)
                || "single_clock_time".equals(id)
                || "horizontal_clock_time".equals(id)
                || "vertical_clock_time".equals(id)
                || "clock_hour".equals(id)
                || "clock_minute".equals(id);
    }

    private static boolean nativeClockTextReady(RootState s) {
        if (s == null || s.nativeClockTexts.isEmpty()) return false;
        for (int i = 0; i < s.nativeClockTexts.size(); i++) {
            TextView tv = s.nativeClockTexts.get(i).get();
            if (tv != null && visibleContent(tv)) {
                CharSequence text = tv.getText();
                if (text != null && text.length() > 0) return true;
            }
        }
        return false;
    }

    private static boolean aodHostActive(View root) {
        View aodHost = findHost(root, true);
        return aodHost != null && visibleContent(aodHost);
    }

    public static void onNativeClockDrawn(View v) {
        if (v == null || nativeClockRole(v) != 0 || shouldSuppressNative(v) || !visibleContent(v) || !isClockSleeping(v.getContext())) return;
        RootState s = ROOTS.get(v.getRootView());
        if (s == null) { sweep(v.getRootView()); s = ROOTS.get(v.getRootView()); }
        if (s != null) {
            s.drawnAod = new WeakReference<>(v);
            s.drawnSleepAt = StateCoordinator.propGetLong(StateCoordinator.PROP_SLEEP_AT, 0);
            applyPolicy(v.getRootView(), s);
        }
    }

    public static boolean shouldSuppressNative(View v) {
        if (v == null || sApplying) return false;
        int role = nativeClockRole(v);
        if (role < 0) return false;
        RootState s = ROOTS.get(v.getRootView());
        if (s == null) return false;
        Context ctx = v.getRootView().getContext();
        boolean sleeping = isClockSleeping(ctx);
        if (sleeping) {
            if (!StateCoordinator.isAodEnabled(ctx)) return true;
            if (StateCoordinator.isFullScreenAod(ctx)) return false;
            if (s.customAod) return true;
            if (s.customLock && role == 1) return true;
            if (StateCoordinator.isLinkageAnimating()) return role == 0;
            return role == 1 && nativeAodReady(s);
        }
        if (role == 0) return true;
        boolean wakingFromCustomAod = StateCoordinator.sScrimMode == 0 && s.customAod && StateCoordinator.isAodEnabled(ctx)
                && !StateCoordinator.isFullScreenAod(ctx);
        return hideNativeLockWhileAwake(s, isLockScene(ctx), wakingFromCustomAod);
    }

    private static boolean hideNativeLockWhileAwake(RootState s, boolean lockScene, boolean wakingFromCustomAod) {
        // The linked native clock still has its AOD layout until the scrim leaves AOD.
        if (!lockScene || wakingFromCustomAod) return true;
        return s.customLock;
    }

    // Return AOD before the outer keyguard panel: linkage clocks can share that ancestor.
    private static int nativeClockRole(View v) {
        View cur = v;
        while (cur != null) {
            String cls = cur.getClass().getSimpleName();
            String id = StateCoordinator.idNameOf(cur);
            if ("AllInOneSingleClock".equals(cls) || "AODView".equals(cls) || "AodView".equals(cls)
                    || "aod_root_view".equals(id) || cls.contains("LinkageStyleClock")) return 0;
            if ("AllInOneHourClock".equals(cls) || "AllInOneMinuteClock".equals(cls)
                    || "miui_keyguard_clock_container".equals(id)
                    || "miui_keyguard_foreground_clock_container".equals(id)) return 1;
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return -1;
    }

    private static void logNativeChain(View digit) {
        StringBuilder line = new StringBuilder("Native AOD first frame:");
        View cur = digit;
        for (int n = 0; cur != null && n < 16; n++) {
            line.append(" > ").append(cur.getClass().getSimpleName())
                    .append(':').append(StateCoordinator.idNameOf(cur))
                    .append(" vis=").append(cur.getVisibility()).append(" a=").append(cur.getAlpha())
                    .append(" ta=").append(cur.getTransitionAlpha());
            if (cur == digit) line.append(" fontAlpha=").append(HookUtils.getField(cur, "fontAlpha"));
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        HookUtils.log(line.toString());
    }

    /** Only the inner clock branch is repaired during initial handoff; outer AOD window
     * visibility and its 10-second timeout remain entirely owned by the system. */
    private static void revealNativeDigits(View digit) {
        if (digit == null) return;
        Object alpha = HookUtils.getField(digit, "fontAlpha");
        if (alpha instanceof Float && ((Float) alpha) < 0.99f) {
            HookUtils.setField(digit, "fontAlpha", 1f);
            digit.invalidate();
        }
        View cur = digit;
        while (cur != null) {
            String cls = cur.getClass().getSimpleName();
            if ("AODView".equals(cls) || "AodView".equals(cls) || "aod_root_view".equals(StateCoordinator.idNameOf(cur))) break;
            if (cur.getAlpha() < 0.99f) cur.setAlpha(1f);
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
    }

    private static boolean visibleContent(View v) {
        if (v == null || !v.isAttachedToWindow() || v.getWidth() <= 0 || v.getHeight() <= 0 || !v.isShown()) return false;
        Object fontAlpha = HookUtils.getField(v, "fontAlpha");
        if (fontAlpha instanceof Float && ((Float) fontAlpha) <= 0.01f) return false;
        View cur = v;
        while (cur != null) {
            if (cur.getAlpha() <= 0.01f || cur.getTransitionAlpha() <= 0.01f) return false;
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    private static boolean isAodClock(String cls, String id) {
        return cls.contains("LinkageStyleClockView") || cls.contains("AodContainerView")
                || cls.contains("ClassicClockView") || cls.contains("HorizontalClockView")
                || cls.contains("OnelineClockView") || cls.contains("VerticalClockView")
                || cls.contains("AllInOneSingleClock") || cls.contains("AODBatteryMeterView")
                || "clock_container".equals(id) || "single_clock_container".equals(id)
                || "clock_horizontal_container".equals(id) || "clock2_container".equals(id)
                || "color_ribbons_container".equals(id) || id.startsWith("aod_battery_");
    }

    private static View findHost(View root, boolean aod) {
        if (aod) {
            View exact = findClassHost(root, "AODView");
            if (exact != null) return exact;
            return StateCoordinator.findByIdName(root, "aod_root_view");
        }
        for (String id : new String[]{"keyguard_translation_info", "keyguard_info_layer", "keyguard_foreground_layer", "keyguard_root_view"}) {
            View host = StateCoordinator.findByIdName(root, id);
            if (host instanceof ViewGroup) return host;
        }
        return null;
    }

    private static View findClassHost(View v, String cls) {
        if (v instanceof FlymeClockView) return null;
        if (v.getClass().getSimpleName().equals(cls)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findClassHost(g.getChildAt(i), cls);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static FlymeClockView ensure(ViewGroup host, Context context, String tag, int mode) {
        View existing = host.findViewWithTag(tag);
        if (existing instanceof FlymeClockView) return (FlymeClockView) existing;
        try {
            FlymeClockView clock = new FlymeClockView(context);
            // HyperOS keyguard root is a ConstraintLayout; ConstraintSet.clone
            // rejects any child without an id, including injected overlays.
            clock.setId(View.generateViewId());
            clock.setTag(tag);
            clock.setVisibility(View.GONE);
            host.addView(clock, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            clock.setClockMode(mode, false);
            clock.setVisibility(View.GONE);
            StateCoordinator.registerClock(clock);
            HookUtils.log("Flyme clock host=" + host.getClass().getName() + " role=" + mode);
            return clock;
        } catch (Throwable t) { HookUtils.log("Cannot attach Flyme clock", t); return null; }
    }

    private static FlymeClockView ensureBridge(ViewGroup host, Context context) {
        View existing = host.findViewWithTag(TAG_BRIDGE);
        if (existing instanceof FlymeClockView) return (FlymeClockView) existing;
        try {
            FlymeClockView clock = new FlymeClockView(context);
            clock.setId(View.generateViewId());
            clock.setTag(TAG_BRIDGE);
            clock.setClickable(false);
            clock.setFocusable(false);
            host.addView(clock, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int initialMode = ClockSettings.get(context, 1).enabled ? 1 : 0;
            clock.setClockMode(initialMode, false);
            clock.reloadConfig();
            int w = host.getWidth() > 0 ? host.getWidth() : context.getResources().getDisplayMetrics().widthPixels;
            int h = host.getHeight() > 0 ? host.getHeight() : context.getResources().getDisplayMetrics().heightPixels;
            if (w > 0 && h > 0) {
                clock.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                clock.layout(0, 0, w, h);
            }
            clock.setVisibility(View.GONE);
            StateCoordinator.registerClock(clock);
            HookUtils.log("Flyme bridge clock attached to root: " + host.getClass().getName());
            return clock;
        } catch (Throwable t) {
            HookUtils.log("Cannot attach Flyme bridge clock", t);
            return null;
        }
    }

    public static void ensureFlymeClock(View v) { if (v != null) sweep(v.getRootView()); }
    public static void ensureFlymeClockInAod(ViewGroup parent) { if (parent != null) sweep(parent.getRootView()); }

    public static void startSweep(View root, final boolean isAod) {
        if (root != null) StateCoordinator.sAodRoot = root.getRootView();
        if (sSweep != null) return;
        sSweep = new Runnable() {
            private int count;
            public void run() {
                sweep(StateCoordinator.sAodRoot);
                if (++count < (isAod ? 30 : 15)) StateCoordinator.mainHandler().postDelayed(this, isAod ? 100 : 200);
                else sSweep = null;
            }
        };
        StateCoordinator.mainHandler().post(sSweep);
    }

    public static void stopSweep() {
        if (sSweep != null) { StateCoordinator.mainHandler().removeCallbacks(sSweep); sSweep = null; }
    }

    private static boolean isDarkBackground(View v) {
        Drawable d = v.getBackground();
        if (!(d instanceof ColorDrawable)) return false;
        int color = ((ColorDrawable) d).getColor();
        return (color >>> 24) == 255 && ((color >> 16) & 255) < 24 && ((color >> 8) & 255) < 24 && (color & 255) < 24;
    }
}
