package io.github.askt_clot.hyperalive.hook;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

/** Aligns the native Magazine C lock digits with the laid-out AOD digits during the move. */
final class NativeClockHandoff {
    private static NativeClockHandoff active;

    private final View lockContainer;
    private final TextView lockHour;
    private final TextView lockMinute;
    private final float startY;
    private final float hourBaseY;
    private final float minuteBaseY;
    private View aodContainer;
    private float endY;
    private float hourDelta;
    private float minuteDelta;
    private boolean measured;
    private float lastHourY;
    private float lastMinuteY;

    private NativeClockHandoff(View container, TextView hour, TextView minute) {
        lockContainer = container;
        lockHour = hour;
        lockMinute = minute;
        startY = container.getTranslationY();
        hourBaseY = lastHourY = hour.getTranslationY();
        minuteBaseY = lastMinuteY = minute.getTranslationY();
    }

    static void begin(Object animation) {
        clear(null);
        if (!"MagazineCClockAnimation".equals(animation.getClass().getSimpleName())
                || Boolean.TRUE.equals(HookUtils.getField(animation, "needToSplitClock"))) return;
        Object container = HookUtils.getField(animation, "mClockContainer");
        Object hour = HookUtils.getField(animation, "fullHour");
        Object minute = HookUtils.getField(animation, "fullMinute");
        if (container instanceof View && hour instanceof TextView && minute instanceof TextView
                && under((View) hour, (View) container) && under((View) minute, (View) container)) {
            active = new NativeClockHandoff((View) container, (TextView) hour, (TextView) minute);
        }
    }

    static void setDestination(View view, float translationY) {
        NativeClockHandoff handoff = active;
        if (handoff == null || view.getRootView() != handoff.lockContainer.getRootView()) return;
        handoff.aodContainer = view;
        handoff.endY = translationY;
        handoff.measured = false;
    }

    static void align(View root, List<WeakReference<TextView>> hours, List<WeakReference<TextView>> minutes) {
        NativeClockHandoff handoff = active;
        if (handoff == null || handoff.aodContainer == null || root != handoff.lockContainer.getRootView()) return;
        if (!handoff.lockContainer.isAttachedToWindow() || !handoff.aodContainer.isAttachedToWindow()) {
            clear(root);
            return;
        }
        if (!handoff.measured && !handoff.measure(hours, minutes)) return;
        float distance = handoff.endY - handoff.startY;
        float progress = Math.abs(distance) < 1f ? 1f
                : (handoff.lockContainer.getTranslationY() - handoff.startY) / distance;
        float weight = Math.max(0f, Math.min(1f, 2f * progress));
        handoff.lastHourY = handoff.hourBaseY + weight * handoff.hourDelta;
        handoff.lastMinuteY = handoff.minuteBaseY + weight * handoff.minuteDelta;
        if (handoff.lockHour.getTranslationY() != handoff.lastHourY) {
            handoff.lockHour.setTranslationY(handoff.lastHourY);
        }
        if (handoff.lockMinute.getTranslationY() != handoff.lastMinuteY) {
            handoff.lockMinute.setTranslationY(handoff.lastMinuteY);
        }
    }

    static void clear(View root) {
        NativeClockHandoff handoff = active;
        if (handoff == null || (root != null && root != handoff.lockContainer.getRootView())) return;
        active = null;
        if (handoff.lockHour.getTranslationY() == handoff.lastHourY && handoff.lastHourY != handoff.hourBaseY) {
            handoff.lockHour.setTranslationY(handoff.hourBaseY);
        }
        if (handoff.lockMinute.getTranslationY() == handoff.lastMinuteY && handoff.lastMinuteY != handoff.minuteBaseY) {
            handoff.lockMinute.setTranslationY(handoff.minuteBaseY);
        }
    }

    private boolean measure(List<WeakReference<TextView>> hours, List<WeakReference<TextView>> minutes) {
        TextView aodHour = findTime(hours, lockHour);
        TextView aodMinute = findTime(minutes, lockMinute);
        if (aodHour == null || aodMinute == null) return false;
        float lockRemaining = parentScaleY(lockContainer) * (endY - lockContainer.getTranslationY());
        float aodRemaining = parentScaleY(aodContainer) * (endY - aodContainer.getTranslationY());
        hourDelta = (inkCenterY(aodHour) + aodRemaining - inkCenterY(lockHour) - lockRemaining)
                / parentScaleY(lockHour);
        minuteDelta = (inkCenterY(aodMinute) + aodRemaining - inkCenterY(lockMinute) - lockRemaining)
                / parentScaleY(lockMinute);
        float limit = 12f * lockContainer.getResources().getDisplayMetrics().density;
        if (!Float.isFinite(hourDelta) || !Float.isFinite(minuteDelta)
                || Math.abs(hourDelta) > limit || Math.abs(minuteDelta) > limit) return false;
        measured = true;
        HookUtils.log("Native lock clock aligned to AOD digits: hourDy=" + hourDelta + " minuteDy=" + minuteDelta);
        return true;
    }

    private TextView findTime(List<WeakReference<TextView>> entries, TextView source) {
        if (source.getLayout() == null || source.length() == 0) return null;
        for (WeakReference<TextView> entry : entries) {
            TextView view = entry.get();
            // The native AOD clock is INVISIBLE during linkage, but has a valid text layout.
            if (view != null && view.isAttachedToWindow() && view.getLayout() != null && view.getHeight() > 0
                    && under(view, aodContainer) && source.getText().toString().contentEquals(view.getText())) {
                return view;
            }
        }
        return null;
    }

    private static boolean under(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    private static float inkCenterY(TextView view) {
        Rect ink = new Rect();
        String time = view.getText().toString();
        view.getPaint().getTextBounds(time, 0, time.length(), ink);
        Matrix global = new Matrix();
        view.transformMatrixToGlobal(global);
        float[] point = {0f, view.getBaseline() + ink.exactCenterY()};
        global.mapPoints(point);
        return point[1];
    }

    private static float parentScaleY(View view) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof View)) return 1f;
        Matrix global = new Matrix();
        ((View) parent).transformMatrixToGlobal(global);
        float[] vertical = {0f, 1f};
        global.mapVectors(vertical);
        return vertical[1];
    }
}
