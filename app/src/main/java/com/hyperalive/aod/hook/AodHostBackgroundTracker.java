package com.hyperalive.aod.hook;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tracks and reversibly suppresses the confirmed opaque-black AOD host container background
 * (e.g. {@code com.miui.aod.AODView / aod_mode_layout_horizontal}) during non-fullscreen AOD.
 * Uses weak references, main-thread mutations, and sentinel-identity checks so native Drawables
 * are restored cleanly without clobbering newer native backgrounds or non-target child views.
 */
public final class AodHostBackgroundTracker {

    private static final class BgRecord {
        Drawable originalBg;
        ColorDrawable sentinelBg;
        WeakReference<View> rootRef;
        View.OnAttachStateChangeListener detachListener;

        BgRecord(Drawable originalBg, ColorDrawable sentinelBg, View root) {
            this.originalBg = originalBg;
            this.sentinelBg = sentinelBg;
            this.rootRef = root != null ? new WeakReference<>(root) : null;
        }

        boolean matchesRoot(View v, View targetRoot) {
            if (targetRoot == null) return false;
            if (v == targetRoot) return true;
            View savedRoot = rootRef != null ? rootRef.get() : null;
            if (savedRoot == targetRoot) return true;
            if (v != null && v.getRootView() == targetRoot) return true;
            View cur = v;
            while (cur != null) {
                if (cur == targetRoot) return true;
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
            return false;
        }
    }

    public static final String PROP_TRACE = "debug.qihang.aod_bg_trace";

    private static final WeakHashMap<View, BgRecord> TRACKED = new WeakHashMap<>();

    private AodHostBackgroundTracker() {}

    public static boolean isTraceEnabled() {
        return "1".equals(HookUtils.propGet(PROP_TRACE, "0"));
    }

    private static String describeDrawable(Drawable d) {
        if (d == null) return "null";
        if (d instanceof ColorDrawable) {
            return "Color(0x" + Integer.toHexString(((ColorDrawable) d).getColor()) + ")";
        }
        return d.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(d));
    }

    private static String describeView(View v) {
        if (v == null) return "null";
        String id = idNameOf(v);
        return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v))
                + ":" + (id.isEmpty() ? ("#" + Integer.toHexString(v.getId())) : id);
    }

    private static float effectiveAlpha(View v) {
        float eff = 1.0f;
        View cur = v;
        while (cur != null) {
            eff *= cur.getAlpha() * cur.getTransitionAlpha();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return eff;
    }

    static void traceEvent(
            String event,
            String reason,
            View host,
            View root,
            Drawable beforeBg,
            Drawable afterBg,
            boolean lockOwned,
            boolean aodOn,
            boolean fsAod,
            boolean sleeping) {
        if (!isTraceEnabled()) return;
        View effectiveRoot = root != null ? root : (host != null ? host.getRootView() : null);
        int w = host != null ? host.getWidth() : 0;
        int h = host != null ? host.getHeight() : 0;
        int rw = effectiveRoot != null ? effectiveRoot.getWidth() : 0;
        int rh = effectiveRoot != null ? effectiveRoot.getHeight() : 0;
        int vis = host != null ? host.getVisibility() : -1;
        String eff = host != null ? String.format(Locale.US, "%.2f", effectiveAlpha(host)) : "na";
        HookUtils.log("[AOD_BG_TRACE] " + event
                + " up=" + SystemClock.uptimeMillis()
                + " nano=" + System.nanoTime()
                + " reason=" + reason
                + " root=" + describeView(effectiveRoot)
                + " host=" + describeView(host)
                + " dim=" + w + "x" + h + "/" + rw + "x" + rh
                + " vis=" + vis
                + " eff=" + eff
                + " beforeBg=" + describeDrawable(beforeBg)
                + " afterBg=" + describeDrawable(afterBg)
                + " gates=" + lockOwned + "/" + aodOn + "/" + fsAod + "/" + sleeping);
    }

    public static void traceRootReplaced(View prevRoot, View nextRoot) {
        if (!isTraceEnabled()) return;
        HookUtils.log("[AOD_BG_TRACE] ROOT_REPLACE up=" + SystemClock.uptimeMillis()
                + " nano=" + System.nanoTime()
                + " prevRoot=" + describeView(prevRoot)
                + " nextRoot=" + describeView(nextRoot));
    }

    public static boolean shouldSuppressAodHostBg(
            boolean lockOwned, boolean aodEnabled, boolean fullScreenAod, boolean sleeping) {
        return lockOwned && aodEnabled && !fullScreenAod && sleeping;
    }

    public static boolean isOpaqueBlackColor(int argb) {
        int a = (argb >>> 24) & 0xff;
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;
        return a >= 250 && r <= 8 && g <= 8 && b <= 8;
    }

    public static boolean isOpaqueBlackDrawable(Drawable d) {
        return d instanceof ColorDrawable && isOpaqueBlackColor(((ColorDrawable) d).getColor());
    }

    public static boolean isHostDescriptorCandidate(String className, String simpleName, String idName) {
        String sn = simpleName != null ? simpleName : "";
        String cn = className != null ? className : "";
        String id = idName != null ? idName : "";

        // Explicit exclusions: never mutate custom clock, SystemUI scrims/shade, keyguard roots, or native clock digits.
        if ("FlymeClockView".equals(sn)
                || "NotificationShadeWindowView".equals(sn)
                || "ScrimView".equals(sn)
                || "TimeView".equals(sn)
                || "keyguard_root_view".equals(id)
                || "legacy_window_root".equals(id)
                || sn.contains("LinkageStyleClock")
                || sn.contains("ClassicClockView")
                || sn.contains("HorizontalClockView")
                || sn.contains("OnelineClockView")
                || sn.contains("VerticalClockView")
                || "AllInOneSingleClock".equals(sn)
                || "AllInOneHourClock".equals(sn)
                || "AllInOneMinuteClock".equals(sn)
                || "AODBatteryMeterView".equals(sn)
                || "KeyguardClockContainer".equals(sn)
                || "KeyguardClockView".equals(sn)
                || "miui_keyguard_clock_container".equals(id)
                || "miui_keyguard_foreground_clock_container".equals(id)
                || "clock_container".equals(id)
                || "single_clock_container".equals(id)
                || "clock_horizontal_container".equals(id)
                || "clock2_container".equals(id)
                || "color_ribbons_container".equals(id)
                || id.startsWith("aod_battery_")
                || id.startsWith("clock_time")) {
            return false;
        }

        if ("AODView".equals(sn)
                || "AodView".equals(sn)
                || "AodContainerView".equals(sn)
                || "aod_root_view".equals(id)
                || id.startsWith("aod_mode_layout")
                || id.startsWith("aod_container")) {
            return true;
        }
        return cn.startsWith("com.miui.aod.") && (sn.contains("AOD") || sn.contains("Aod"));
    }

    public static boolean isConfirmedAodHostCandidate(
            String className,
            String simpleName,
            String idName,
            int width,
            int height,
            int rootWidth,
            int rootHeight) {
        if (!isHostDescriptorCandidate(className, simpleName, idName)) {
            return false;
        }
        if (width < 400 || height < 400) {
            return false;
        }
        if (rootWidth > 0 && width < (int) (rootWidth * 0.95f)) {
            return false;
        }
        if (rootHeight > 0 && height < (int) (rootHeight * 0.95f)) {
            return false;
        }
        return true;
    }

    public static boolean isHostViewDescriptor(View v) {
        if (v == null) return false;
        return isHostDescriptorCandidate(
                v.getClass().getName(),
                v.getClass().getSimpleName(),
                idNameOf(v));
    }

    public static boolean isConfirmedHostView(View v, View root) {
        if (v == null) return false;
        View r = root != null ? root : v.getRootView();
        int rw = r != null ? r.getWidth() : 0;
        int rh = r != null ? r.getHeight() : 0;
        return isConfirmedAodHostCandidate(
                v.getClass().getName(),
                v.getClass().getSimpleName(),
                idNameOf(v),
                v.getWidth(),
                v.getHeight(),
                rw,
                rh);
    }

    public static String idNameOf(View v) {
        if (v == null) return "";
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                return v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    static boolean isMainThread() {
        try {
            Looper main = Looper.getMainLooper();
            return main == null || Looper.myLooper() == main;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void reconcileHost(View v, View root, boolean suppress) {
        reconcileHost(v, root, suppress, "reconcile", suppress, suppress, false, suppress);
    }

    public static void reconcileHost(
            View v,
            View root,
            boolean suppress,
            String reason,
            boolean lockOwned,
            boolean aodOn,
            boolean fsAod,
            boolean sleeping) {
        if (!isMainThread() || v == null) return;
        if (!suppress) {
            restoreView(v, reason != null ? reason : "suppress_off");
            return;
        }
        View effectiveRoot = root != null ? root : v.getRootView();
        BgRecord existing = TRACKED.get(v);
        Drawable cur = v.getBackground();
        if (existing != null) {
            if (effectiveRoot != null && (existing.rootRef == null || existing.rootRef.get() != effectiveRoot)) {
                existing.rootRef = new WeakReference<>(effectiveRoot);
            }
            if (existing.sentinelBg != null && cur == existing.sentinelBg) {
                int sentinelColor = existing.sentinelBg.getColor();
                if (sentinelColor == 0) {
                    return;
                }
                if (isOpaqueBlackColor(sentinelColor)) {
                    // Android View.setBackgroundColor mutates an existing ColorDrawable in place.
                    if (!(existing.originalBg instanceof ColorDrawable)
                            || ((ColorDrawable) existing.originalBg).getColor() != sentinelColor) {
                        existing.originalBg = new ColorDrawable(sentinelColor);
                    }
                    applySentinelSuppression(v, existing, reason, effectiveRoot, cur,
                            lockOwned, aodOn, fsAod, sleeping);
                } else {
                    // Native code mutated our sentinel in place to a non-black color; untrack without clobbering.
                    untrackNativeBg(v, existing, reason, effectiveRoot, cur,
                            lockOwned, aodOn, fsAod, sleeping);
                }
                return;
            }
            if (isOpaqueBlackDrawable(cur)) {
                existing.originalBg = cur;
                applySentinelSuppression(v, existing, reason, effectiveRoot, cur,
                        lockOwned, aodOn, fsAod, sleeping);
            } else {
                // Native code replaced our sentinel with null or a non-black drawable; do not override or clobber later.
                untrackNativeBg(v, existing, reason, effectiveRoot, cur,
                        lockOwned, aodOn, fsAod, sleeping);
            }
            return;
        }

        if (!isConfirmedHostView(v, effectiveRoot)) {
            if (reason != null && !reason.startsWith("predraw")) {
                traceEvent("SKIP_NOT_CONFIRMED", reason, v, effectiveRoot, cur, cur,
                        lockOwned, aodOn, fsAod, sleeping);
            }
            return;
        }
        if (!isOpaqueBlackDrawable(cur)) {
            if (reason != null && !reason.startsWith("predraw")) {
                traceEvent("SKIP_NON_BLACK", reason, v, effectiveRoot, cur, cur,
                        lockOwned, aodOn, fsAod, sleeping);
            }
            return;
        }

        ColorDrawable sentinel = new ColorDrawable(0);
        BgRecord rec = new BgRecord(cur, sentinel, effectiveRoot);
        View.OnAttachStateChangeListener detachListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attached) {}

            @Override
            public void onViewDetachedFromWindow(View detached) {
                restoreView(detached, "host_detach");
            }
        };
        rec.detachListener = detachListener;
        TRACKED.put(v, rec);
        v.addOnAttachStateChangeListener(detachListener);
        v.setBackground(sentinel);
        if (v.isAttachedToWindow()) {
            v.invalidate();
        }
        traceEvent("CLEAR", reason, v, effectiveRoot, cur, sentinel,
                lockOwned, aodOn, fsAod, sleeping);
    }

    public static void reconcileCachedHosts(View root, List<WeakReference<View>> hosts, boolean suppress) {
        reconcileCachedHosts(root, hosts, suppress, "reconcile_cached", suppress, suppress, false, suppress);
    }

    public static void reconcileCachedHosts(
            View root,
            List<WeakReference<View>> hosts,
            boolean suppress,
            String reason,
            boolean lockOwned,
            boolean aodOn,
            boolean fsAod,
            boolean sleeping) {
        if (!isMainThread()) return;
        if (!suppress) {
            restoreAll(reason != null ? reason : "suppress_off");
            return;
        }
        if (hosts != null) {
            for (int i = 0; i < hosts.size(); i++) {
                View v = hosts.get(i).get();
                if (v != null) {
                    reconcileHost(v, root, true, reason, lockOwned, aodOn, fsAod, sleeping);
                }
            }
        }
        if (!TRACKED.isEmpty()) {
            View effectiveRoot = root != null ? root.getRootView() : null;
            for (Map.Entry<View, BgRecord> e : new ArrayList<>(TRACKED.entrySet())) {
                View v = e.getKey();
                BgRecord rec = e.getValue();
                if (v == null || rec == null) continue;
                if (effectiveRoot == null || rec.matchesRoot(v, effectiveRoot)) {
                    reconcileHost(v, effectiveRoot, true, reason, lockOwned, aodOn, fsAod, sleeping);
                }
            }
        }
    }

    public static void restoreView(View v) {
        restoreView(v, "restore_view");
    }

    public static void restoreView(View v, String reason) {
        if (!isMainThread() || v == null) return;
        BgRecord rec = TRACKED.remove(v);
        if (rec == null) return;
        removeListenerQuietly(v, rec);
        Drawable cur = v.getBackground();
        View savedRoot = rec.rootRef != null ? rec.rootRef.get() : null;
        if (rec.sentinelBg != null && cur == rec.sentinelBg) {
            int curColor = rec.sentinelBg.getColor();
            if (curColor == 0 || isOpaqueBlackColor(curColor)) {
                Drawable restoreTarget = rec.originalBg;
                if (isOpaqueBlackColor(curColor)
                        && (!(restoreTarget instanceof ColorDrawable)
                            || ((ColorDrawable) restoreTarget).getColor() != curColor)) {
                    restoreTarget = new ColorDrawable(curColor);
                }
                v.setBackground(restoreTarget);
                if (v.isAttachedToWindow()) {
                    v.invalidate();
                }
                traceEvent("RESTORE", reason, v, savedRoot, cur, restoreTarget,
                        false, false, false, false);
                return;
            }
        }
        traceEvent("RESTORE_SKIP_CHANGED", reason, v, savedRoot, cur, cur,
                false, false, false, false);
    }

    public static void restoreForRoot(View root) {
        restoreForRoot(root, "restore_root");
    }

    public static void restoreForRoot(View root, String reason) {
        if (!isMainThread() || root == null || TRACKED.isEmpty()) return;
        for (Map.Entry<View, BgRecord> e : new ArrayList<>(TRACKED.entrySet())) {
            View v = e.getKey();
            BgRecord rec = e.getValue();
            if (v != null && rec != null && rec.matchesRoot(v, root)) {
                restoreView(v, reason);
            }
        }
    }

    public static void restoreForOtherRoots(View activeRoot) {
        restoreForOtherRoots(activeRoot, "other_root");
    }

    public static void restoreForOtherRoots(View activeRoot, String reason) {
        if (!isMainThread() || activeRoot == null || TRACKED.isEmpty()) return;
        for (Map.Entry<View, BgRecord> e : new ArrayList<>(TRACKED.entrySet())) {
            View v = e.getKey();
            BgRecord rec = e.getValue();
            if (v != null && rec != null && !rec.matchesRoot(v, activeRoot)) {
                restoreView(v, reason);
            }
        }
    }

    public static void restoreAll() {
        restoreAll("restore_all");
    }

    public static void restoreAll(String reason) {
        if (!isMainThread() || TRACKED.isEmpty()) return;
        for (View v : new ArrayList<>(TRACKED.keySet())) {
            if (v != null) {
                restoreView(v, reason);
            }
        }
    }

    public static boolean hasTrackedHostForRoot(View root) {
        if (root == null || TRACKED.isEmpty()) return false;
        View top = root.getRootView();
        for (Map.Entry<View, BgRecord> e : TRACKED.entrySet()) {
            View v = e.getKey();
            BgRecord rec = e.getValue();
            if (v != null && rec != null && rec.matchesRoot(v, top)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasTrackedHosts() {
        return !TRACKED.isEmpty();
    }

    public static int trackedCount() {
        return TRACKED.size();
    }

    public static void resetForTests() {
        if (!isMainThread()) return;
        for (Map.Entry<View, BgRecord> e : new ArrayList<>(TRACKED.entrySet())) {
            removeListenerQuietly(e.getKey(), e.getValue());
        }
        TRACKED.clear();
    }

    private static void applySentinelSuppression(
            View v,
            BgRecord existing,
            String reason,
            View effectiveRoot,
            Drawable beforeBg,
            boolean lockOwned,
            boolean aodOn,
            boolean fsAod,
            boolean sleeping) {
        if (existing.sentinelBg == null || existing.sentinelBg.getColor() != 0) {
            existing.sentinelBg = new ColorDrawable(0);
        }
        v.setBackground(existing.sentinelBg);
        if (v.isAttachedToWindow()) {
            v.invalidate();
        }
        traceEvent("RESUPPRESS", reason, v, effectiveRoot, beforeBg, existing.sentinelBg,
                lockOwned, aodOn, fsAod, sleeping);
    }

    private static void untrackNativeBg(
            View v,
            BgRecord existing,
            String reason,
            View effectiveRoot,
            Drawable cur,
            boolean lockOwned,
            boolean aodOn,
            boolean fsAod,
            boolean sleeping) {
        untrackOnly(v, existing);
        traceEvent("UNTRACK_NATIVE_BG", reason, v, effectiveRoot, cur, cur,
                lockOwned, aodOn, fsAod, sleeping);
    }

    private static void untrackOnly(View v, BgRecord rec) {
        TRACKED.remove(v);
        removeListenerQuietly(v, rec);
    }

    private static void removeListenerQuietly(View v, BgRecord rec) {
        if (v != null && rec != null && rec.detachListener != null) {
            try {
                v.removeOnAttachStateChangeListener(rec.detachListener);
            } catch (Throwable ignored) {}
            rec.detachListener = null;
        }
    }
}
