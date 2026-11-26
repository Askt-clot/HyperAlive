#!/usr/bin/env python3
"""Targeted JVM lifecycle & ownership-churn tests for the AOD host background fix.

Compiles production Java classes (AodHostBackgroundTracker, ViewTreeSweeper, StateCoordinator,
TransitionPolicy) against stateful Android View/Drawable/Looper stubs so tests exercise real
production bytecode end-to-end:
- Narrow host matching & explicit exclusions (children, ScrimView, NotificationShade, FlymeClockView)
- Replacement of ViewTreeSweeper.collect() broad untracked setBackground(null)
- Pre-draw/reapply suppression and sentinel-checked restoration without clobbering new Drawables
- End-to-end exits: wake, biometric wake, AOD disabled, fullscreen AOD, ownership loss, detach, root swap
- Main-thread mutation guard & weak reference lifecycle
- StateCoordinator ownership TTL-expiry deferral vs confirmed epoch/loss/failure/grace-timeout exits
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP_JAVA = ROOT / "app/src/main/java"


def _resolve_jdk_tool(name: str) -> str:
    exe = f"{name}.exe" if os.name == "nt" else name
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = Path(java_home) / "bin" / exe
        if cand.is_file():
            return str(cand)
    found = shutil.which(name)
    if found:
        return found
    raise SystemExit(f"Missing JDK tool '{name}': configure JAVA_HOME or PATH")


SHIM_SOURCES = {
    "android/os/Looper.java": r"""
package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper("main");
    private static final Thread MAIN_THREAD = Thread.currentThread();
    private static final ThreadLocal<Looper> TLS = new ThreadLocal<>();
    private final String name;
    private Looper(String name) { this.name = name; }
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() {
        Looper override = TLS.get();
        if (override != null) return override;
        return Thread.currentThread() == MAIN_THREAD ? MAIN : null;
    }
    public static boolean isMainThreadForTest() {
        return myLooper() == MAIN;
    }
    public static void setThreadLooperForTest(Looper l) { TLS.set(l); }
    public static Looper newNonMainLooperForTest() { return new Looper("worker"); }
}
""",
    "android/os/Handler.java": r"""
package android.os;

import java.util.concurrent.ConcurrentLinkedQueue;

public class Handler {
    private static final ConcurrentLinkedQueue<Runnable> MAIN_QUEUE = new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable r) {
        if (r == null) return false;
        if (Looper.isMainThreadForTest()) {
            r.run();
        } else {
            MAIN_QUEUE.add(r);
        }
        return true;
    }
    public boolean postDelayed(Runnable r, long delayMillis) {
        if (r != null) MAIN_QUEUE.add(r);
        return true;
    }
    public void removeCallbacks(Runnable r) {
        if (r != null) MAIN_QUEUE.remove(r);
    }
    public static void clearQueueForTest() { MAIN_QUEUE.clear(); }
    public static int drainMainQueueForTest() {
        int count = 0;
        Runnable r;
        while ((r = MAIN_QUEUE.poll()) != null) {
            r.run();
            count++;
        }
        return count;
    }
}
""",
    "android/os/SystemClock.java": r"""
package android.os;

public final class SystemClock {
    public static long uptimeMs = 1000L;
    public static long uptimeMillis() { return uptimeMs; }
}
""",
    "android/os/Binder.java": r"""
package android.os;

public class Binder {
    public static int getCallingUid() { return 1000; }
}
""",
    "android/os/Build.java": r"""
package android.os;

public class Build {
    public static final class VERSION {
        public static final int SDK_INT = 35;
    }
}
""",
    "android/os/Process.java": r"""
package android.os;

public final class Process {
    public static int myUid() { return 1000; }
}
""",
    "android/os/UserHandle.java": r"""
package android.os;

public final class UserHandle {
    public static final UserHandle ALL = new UserHandle();
}
""",
    "android/net/Uri.java": r"""
package android.net;

public class Uri {
    public static Uri parse(String s) { return new Uri(); }
}
""",
    "android/database/ContentObserver.java": r"""
package android.database;

import android.net.Uri;
import android.os.Handler;

public abstract class ContentObserver {
    public ContentObserver(Handler handler) {}
    public void onChange(boolean selfChange, Uri uri) {}
}
""",
    "android/content/res/Resources.java": r"""
package android.content.res;

import java.util.HashMap;
import java.util.Map;

public class Resources {
    private final Map<Integer, String> names = new HashMap<>();
    private final android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
    public void registerEntryName(int id, String name) { names.put(id, name); }
    public String getResourceEntryName(int id) {
        String n = names.get(id);
        if (n == null) throw new RuntimeException("NotFound:" + id);
        return n;
    }
    public android.util.DisplayMetrics getDisplayMetrics() { return dm; }
}
""",
    "android/util/DisplayMetrics.java": r"""
package android.util;
public class DisplayMetrics {
    public int widthPixels = 1200;
    public int heightPixels = 2608;
}
""",
    "android/graphics/drawable/Drawable.java": r"""
package android.graphics.drawable;

public class Drawable {
    public final String label;
    public Drawable() { this("drawable"); }
    public Drawable(String label) { this.label = label; }
    public Drawable mutate() { return this; }
}
""",
    "android/graphics/drawable/ColorDrawable.java": r"""
package android.graphics.drawable;

public class ColorDrawable extends Drawable {
    private int color;
    public ColorDrawable(int color) {
        super("color:0x" + Integer.toHexString(color));
        this.color = color;
    }
    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }
}
""",
    "android/view/ViewParent.java": r"""
package android.view;

public interface ViewParent {
    ViewParent getParent();
}
""",
    "android/view/ViewTreeObserver.java": r"""
package android.view;

import java.util.ArrayList;
import java.util.List;

public final class ViewTreeObserver {
    public interface OnPreDrawListener {
        boolean onPreDraw();
    }
    private final List<OnPreDrawListener> listeners = new ArrayList<>();
    public void addOnPreDrawListener(OnPreDrawListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }
    public void removeOnPreDrawListener(OnPreDrawListener l) {
        listeners.remove(l);
    }
    public void dispatchOnPreDrawForTest() {
        for (OnPreDrawListener l : new ArrayList<>(listeners)) {
            l.onPreDraw();
        }
    }
    public int listenerCountForTest() { return listeners.size(); }
}
""",
    "android/view/Display.java": r"""
package android.view;

public final class Display {
    public static final int DEFAULT_DISPLAY = 0;
    public static final int STATE_OFF = 1;
    public static final int STATE_ON = 2;
    public static final int STATE_DOZE = 3;
    public static final int STATE_DOZE_SUSPEND = 4;
    public static int currentState = STATE_OFF;
    public int getState() { return currentState; }
}
""",
    "android/hardware/display/DisplayManager.java": r"""
package android.hardware.display;

import android.os.Handler;
import android.view.Display;

public class DisplayManager {
    public interface DisplayListener {
        void onDisplayAdded(int displayId);
        void onDisplayRemoved(int displayId);
        void onDisplayChanged(int displayId);
    }
    private final Display display = new Display();
    public Display getDisplay(int displayId) { return display; }
    public void registerDisplayListener(DisplayListener l, Handler h) {}
}
""",
    "android/content/Context.java": r"""
package android.content;

import android.content.pm.PackageManager;
import android.content.res.Resources;

public class Context {
    public static final String WALLPAPER_SERVICE = "wallpaper";
    public static final String DISPLAY_SERVICE = "display";
    public static final String KEYGUARD_SERVICE = "keyguard";
    public static final int MODE_PRIVATE = 0;
    public static final int RECEIVER_EXPORTED = 2;
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public Resources getResources() { return null; }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public PackageManager getPackageManager() { return null; }
    public void sendBroadcast(Intent intent) {}
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
}
""",
    "android/content/ContentResolver.java": r"""
package android.content;

import android.database.ContentObserver;
import android.net.Uri;
import java.util.HashMap;
import java.util.Map;

public class ContentResolver {
    public static final Map<String, Integer> SECURE_INTS = new HashMap<>();
    public void registerContentObserver(Uri uri, boolean notifyForDescendants, ContentObserver observer) {}
}
""",
    "android/content/BroadcastReceiver.java": r"""
package android.content;

public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
}
""",
    "android/content/Intent.java": r"""
package android.content;

public class Intent {
    public static final String ACTION_WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED";
    public static final int FLAG_RECEIVER_FOREGROUND = 0x10000000;
    private String action;
    private final java.util.Map<String, String> strings = new java.util.HashMap<>();
    public Intent() {}
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent setPackage(String pkg) { return this; }
    public Intent addFlags(int flags) { return this; }
    public Intent putExtra(String k, int v) { return this; }
    public Intent putExtra(String k, boolean v) { return this; }
    public Intent putExtra(String k, String v) { strings.put(k, v); return this; }
    public String getStringExtra(String k) { return strings.get(k); }
    public int getIntExtra(String k, int def) { return def; }
}
""",
    "android/content/IntentFilter.java": r"""
package android.content;

public class IntentFilter {
    public void addAction(String action) {}
}
""",
    "android/content/SharedPreferences.java": r"""
package android.content;

public interface SharedPreferences {
    interface Editor {
        Editor putInt(String key, int value);
        boolean commit();
    }
    Editor edit();
}
""",
    "android/content/pm/PackageManager.java": r"""
package android.content.pm;

public class PackageManager {
    public int getPackageUid(String pkg, int flags) { return -1; }
}
""",
    "android/app/WallpaperInfo.java": r"""
package android.app;

public class WallpaperInfo {
    private final String pkg;
    private final String cls;
    public WallpaperInfo(String pkg, String cls) { this.pkg = pkg; this.cls = cls; }
    public String getPackageName() { return pkg; }
    public String getServiceName() { return cls; }
}
""",
    "android/app/WallpaperManager.java": r"""
package android.app;

public class WallpaperManager {
    public static final int FLAG_SYSTEM = 1;
    public static final int FLAG_LOCK = 2;
    public volatile WallpaperInfo mockSystemInfo = null;
    public volatile WallpaperInfo mockLockInfo = null;
    public volatile int mockLockId = -1;
    public volatile boolean throwOnQuery = false;
    public volatile int queryCount = 0;
    public WallpaperInfo getWallpaperInfo() {
        queryCount++;
        if (throwOnQuery) throw new RuntimeException("mock WMS error");
        return mockSystemInfo;
    }
    public WallpaperInfo getWallpaperInfo(int which) {
        queryCount++;
        if (throwOnQuery) throw new RuntimeException("mock WMS error");
        return which == FLAG_LOCK ? mockLockInfo : mockSystemInfo;
    }
    public int getWallpaperId(int which) {
        if (throwOnQuery) throw new RuntimeException("mock WMS error");
        return mockLockId;
    }
}
""",
    "android/app/KeyguardManager.java": r"""
package android.app;

public class KeyguardManager {
    public boolean locked = false;
    public boolean isKeyguardLocked() { return locked; }
}
""",
    "android/provider/Settings.java": r"""
package android.provider;

import android.content.ContentResolver;
import android.net.Uri;

public final class Settings {
    public static final class Secure {
        public static Uri getUriFor(String name) { return new Uri(); }
        public static int getInt(ContentResolver cr, String name, int def) {
            Integer v = ContentResolver.SECURE_INTS.get(name);
            return v != null ? v : def;
        }
    }
}
""",
    "android/view/View.java": r"""
package android.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

public class View implements ViewParent {
    public static final int NO_ID = -1;
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    public interface OnAttachStateChangeListener {
        void onViewAttachedToWindow(View v);
        void onViewDetachedFromWindow(View v);
    }

    private Context context;
    private Resources resources;
    private int id = NO_ID;
    private int width;
    private int height;
    private Drawable background;
    private ViewParent parent;
    private boolean attached;
    private Object tag;
    private int visibility = VISIBLE;
    private float alpha = 1f;
    private float transitionAlpha = 1f;
    public int invalidateCount;
    private final List<OnAttachStateChangeListener> attachListeners = new ArrayList<>();
    private final ViewTreeObserver vto = new ViewTreeObserver();

    public View(Context context) { this.context = context; }
    public Context getContext() { return context; }
    public void setResources(Resources res) { this.resources = res; }
    public Resources getResources() { return resources != null ? resources : (context != null ? context.getResources() : null); }
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public void setSize(int w, int h) { this.width = w; this.height = h; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Drawable getBackground() { return background; }
    public void setBackground(Drawable bg) { this.background = bg; }
    public void setBackgroundColor(int color) {
        if (background instanceof android.graphics.drawable.ColorDrawable) {
            ((android.graphics.drawable.ColorDrawable) background.mutate()).setColor(color);
        } else {
            setBackground(new android.graphics.drawable.ColorDrawable(color));
        }
    }
    public ViewParent getParent() { return parent; }
    public void setParentInternal(ViewParent p) { this.parent = p; }
    public boolean isAttachedToWindow() { return attached; }
    public void setAttachedForTest(boolean a) { this.attached = a; }
    public void dispatchDetachedForTest() {
        this.attached = false;
        for (OnAttachStateChangeListener l : new ArrayList<>(attachListeners)) {
            l.onViewDetachedFromWindow(this);
        }
    }
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) {
        if (l != null && !attachListeners.contains(l)) attachListeners.add(l);
    }
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) {
        attachListeners.remove(l);
    }
    public int attachListenerCountForTest() { return attachListeners.size(); }
    public View getRootView() {
        View cur = this;
        while (cur.parent instanceof View) cur = (View) cur.parent;
        return cur;
    }
    public void invalidate() { invalidateCount++; }
    public Object getTag() { return tag; }
    public void setTag(Object tag) { this.tag = tag; }
    public int getVisibility() { return visibility; }
    public void setVisibility(int v) { this.visibility = v; }
    public float getAlpha() { return alpha; }
    public void setAlpha(float a) { this.alpha = a; }
    public float getTransitionAlpha() { return transitionAlpha; }
    public void setTransitionAlpha(float a) { this.transitionAlpha = a; }
    public boolean isShown() { return visibility == VISIBLE && attached; }
    public ViewTreeObserver getViewTreeObserver() { return vto; }
    public void bringToFront() {}
    public void measure(int w, int h) {}
    public void layout(int l, int t, int r, int b) {}
    public static int generateViewId() { return 1000; }
    public static final class MeasureSpec {
        public static final int EXACTLY = 1;
        public static int makeMeasureSpec(int size, int mode) { return size; }
    }
}
""",
    "android/view/ViewGroup.java": r"""
package android.view;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

public class ViewGroup extends View {
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public LayoutParams(int w, int h) {}
    }
    private final List<View> children = new ArrayList<>();
    public ViewGroup(Context ctx) { super(ctx); }
    public void addView(View v) {
        children.add(v);
        v.setParentInternal(this);
        v.setAttachedForTest(isAttachedToWindow());
    }
    public void addView(View v, LayoutParams lp) { addView(v); }
    public void removeView(View v) {
        if (children.remove(v)) {
            v.setParentInternal(null);
            v.dispatchDetachedForTest();
        }
    }
    public int getChildCount() { return children.size(); }
    public View getChildAt(int idx) { return children.get(idx); }
    public View findViewWithTag(Object tag) { return null; }
}
""",
    "android/widget/TextView.java": r"""
package android.widget;

import android.content.Context;
import android.view.View;

public class TextView extends View {
    private CharSequence text = "";
    public TextView(Context ctx) { super(ctx); }
    public CharSequence getText() { return text; }
    public void setText(CharSequence t) { text = t; }
}
""",
    "com/hyperalive/aod/AodStyleSettings.java": r"""
package com.hyperalive.aod;
public final class AodStyleSettings {
    public static final String ACTION_STYLE_CHANGED = "com.hyperalive.aod.STYLE_CHANGED";
    public static final String KEY_STYLE = "aod_style";
    public static final int STYLE_SET_SAIL = 0;
}
""",
    "com/hyperalive/aod/ClockSettings.java": r"""
package com.hyperalive.aod;
import android.content.Context;
import android.content.Intent;
public final class ClockSettings {
    public static final String ACTION_STYLE_CHANGED = "com.hyperalive.aod.CLOCK_CHANGED";
    public static final String PREF_NAME = "clock_prefs";
    public static final class Config {
        public boolean enabled;
        public boolean isSameStyle(Config o) { return o != null && o.enabled == enabled; }
        public Config copy() { Config c = new Config(); c.enabled = enabled; return c; }
    }
    public static Config get(Context c, int m) { return new Config(); }
    public static boolean isClockEnabled(Context c, int m) { return false; }
    public static Config fromIntentAod(Intent i) { return new Config(); }
    public static Config fromIntentLock(Intent i) { return new Config(); }
    public static void saveLocal(Context c, Config a, Config l) {}
}
""",
    "com/hyperalive/aod/FlymeClockView.java": r"""
package com.hyperalive.aod;
import android.content.Context;
import android.view.View;
public class FlymeClockView extends View {
    public FlymeClockView(Context c) { super(c); }
    public void setClockMode(int m, boolean a) {}
    public void reloadConfig() {}
    public void updateTime() {}
    public void setClickable(boolean b) {}
    public void setFocusable(boolean b) {}
}
""",
    "com/hyperalive/aod/hook/HookUtils.java": r"""
package com.hyperalive.aod.hook;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public final class HookUtils {
    public static final String PKG_SELF = "com.hyperalive.aod";
    public static final String PKG_ALIVE = "com.flyme.alivewallpaper";
    public static final Map<String, String> PROPS = new HashMap<>();
    public static final List<String> LOGS = new ArrayList<>();
    public static void log(String msg) { if (msg != null) LOGS.add(msg); }
    public static void log(String msg, Throwable t) { log(msg); }
    public static String propGet(String k, String def) {
        String v = PROPS.get(k);
        return v != null ? v : def;
    }
    public static void propSet(String k, String v) { PROPS.put(k, v); }
    public static Object getField(Object obj, String name) {
        if (obj == null || name == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }
    public static void setField(Object obj, String name, Object val) {}
}
""",
    "com/hyperalive/aod/hook/SystemUiHook.java": r"""
package com.hyperalive.aod.hook;
public final class SystemUiHook {
    public static void installClockDrawGate(ClassLoader cl) {}
}
""",
    "com/hyperalive/aod/hook/AodHook.java": r"""
package com.hyperalive.aod.hook;
import android.view.View;
public final class AodHook {
    public static void ensureNativeAodVisible(View v, boolean active, long cycle) {}
    public static void installHostLifecycleHooks(View v) {}
}
""",
    "com/hyperalive/aod/hook/NativeClockHandoff.java": r"""
package com.hyperalive.aod.hook;
import android.view.View;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.List;
final class NativeClockHandoff {
    static void align(View root, List<WeakReference<TextView>> hours, List<WeakReference<TextView>> minutes) {}
    static void clear(View root) {}
}
""",
}

TEST_CLASSES = {
    "com/miui/aod/AODView.java": r"""
package com.miui.aod;
import android.content.Context;
import android.view.ViewGroup;
public class AODView extends ViewGroup {
    public AODView(Context ctx) { super(ctx); }
}
""",
    "com/android/systemui/statusbar/phone/ScrimView.java": r"""
package com.android.systemui.statusbar.phone;
import android.content.Context;
import android.view.View;
public class ScrimView extends View {
    public ScrimView(Context ctx) { super(ctx); }
}
""",
    "com/android/systemui/shade/NotificationShadeWindowView.java": r"""
package com.android.systemui.shade;
import android.content.Context;
import android.view.ViewGroup;
public class NotificationShadeWindowView extends ViewGroup {
    public NotificationShadeWindowView(Context ctx) { super(ctx); }
}
""",
    "com/miui/clock/allInOne/TimeView.java": r"""
package com.miui.clock.allInOne;
import android.content.Context;
import android.view.View;
public class TimeView extends View {
    public TimeView(Context ctx) { super(ctx); }
}
""",
    "AodHostBgLifecycleTest.java": r"""
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.statusbar.phone.ScrimView;
import com.miui.aod.AODView;
import com.miui.clock.allInOne.TimeView;
import com.hyperalive.aod.FlymeClockView;
import com.hyperalive.aod.hook.AodHostBackgroundTracker;
import com.hyperalive.aod.hook.HookUtils;
import com.hyperalive.aod.hook.StateCoordinator;
import com.hyperalive.aod.hook.ViewTreeSweeper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AodHostBgLifecycleTest {
    private static int assertions = 0;

    private static void check(String name, boolean cond) {
        assertions++;
        if (!cond) {
            throw new AssertionError("FAIL: " + name);
        }
    }

    private static final int ID_LEGACY_ROOT = 1;
    private static final int ID_KEYGUARD_ROOT = 2;
    private static final int ID_SCRIM_BEHIND = 3;
    private static final int ID_AOD_LAYOUT = 4;
    private static final int ID_CLOCK_CONTAINER = 5;
    private static final int ID_CLOCK_TIME = 6;
    private static final int ID_AOD_ROOT = 7;

    private static Resources buildRes() {
        Resources res = new Resources();
        res.registerEntryName(ID_LEGACY_ROOT, "legacy_window_root");
        res.registerEntryName(ID_KEYGUARD_ROOT, "keyguard_root_view");
        res.registerEntryName(ID_SCRIM_BEHIND, "scrim_behind");
        res.registerEntryName(ID_AOD_LAYOUT, "aod_mode_layout_horizontal");
        res.registerEntryName(ID_CLOCK_CONTAINER, "clock_container");
        res.registerEntryName(ID_CLOCK_TIME, "clock_time");
        res.registerEntryName(ID_AOD_ROOT, "aod_root_view");
        return res;
    }

    private static Context buildCtx(final Resources res) {
        final ContentResolver cr = new ContentResolver();
        final DisplayManager dm = new DisplayManager();
        final KeyguardManager km = new KeyguardManager();
        return new Context() {
            @Override public Resources getResources() { return res; }
            @Override public ContentResolver getContentResolver() { return cr; }
            @Override public Object getSystemService(String name) {
                if (DISPLAY_SERVICE.equals(name)) return dm;
                if (KEYGUARD_SERVICE.equals(name)) return km;
                return null;
            }
        };
    }

    private static void setSleepState(boolean sleeping, boolean aodEnabled, boolean fsAod) {
        ContentResolver.SECURE_INTS.put("doze_always_on", aodEnabled ? 1 : 0);
        ContentResolver.SECURE_INTS.put("full_screen_aod_on", fsAod ? 1 : 0);
        if (sleeping) {
            long t = SystemClock.uptimeMillis();
            HookUtils.propSet(StateCoordinator.PROP_SLEEP_AT, String.valueOf(t));
            HookUtils.propSet(StateCoordinator.PROP_WAKE_AT, "0");
            Display.currentState = Display.STATE_DOZE;
        } else {
            long t = SystemClock.uptimeMillis();
            HookUtils.propSet(StateCoordinator.PROP_SLEEP_AT, "0");
            HookUtils.propSet(StateCoordinator.PROP_WAKE_AT, String.valueOf(t));
            Display.currentState = Display.STATE_ON;
        }
    }

    private static void testClassifierAndExclusions() {
        check("suppress only when lockOwned && aodEnabled && !fullScreenAod && sleeping",
                AodHostBackgroundTracker.shouldSuppressAodHostBg(true, true, false, true)
                        && !AodHostBackgroundTracker.shouldSuppressAodHostBg(false, true, false, true)
                        && !AodHostBackgroundTracker.shouldSuppressAodHostBg(true, false, false, true)
                        && !AodHostBackgroundTracker.shouldSuppressAodHostBg(true, true, true, true)
                        && !AodHostBackgroundTracker.shouldSuppressAodHostBg(true, true, false, false));

        check("opaque black detection accepts 0xff000000 and rejects translucent/grey/color",
                AodHostBackgroundTracker.isOpaqueBlackColor(0xff000000)
                        && AodHostBackgroundTracker.isOpaqueBlackColor(0xfa050505)
                        && !AodHostBackgroundTracker.isOpaqueBlackColor(0x00000000)
                        && !AodHostBackgroundTracker.isOpaqueBlackColor(0x80000000)
                        && !AodHostBackgroundTracker.isOpaqueBlackColor(0xff202020)
                        && !AodHostBackgroundTracker.isOpaqueBlackColor(0xff0044ff));

        check("confirmed host matches full-screen com.miui.aod.AODView / aod_mode_layout_horizontal",
                AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                        "com.miui.aod.AODView", "AODView", "aod_mode_layout_horizontal",
                        1200, 2608, 1200, 2608));
        check("confirmed host rejects unmeasured 0x0 or partial child size",
                !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                        "com.miui.aod.AODView", "AODView", "aod_mode_layout_horizontal",
                        0, 0, 1200, 2608)
                        && !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                                "com.miui.aod.AODView", "AODView", "aod_mode_layout_horizontal",
                                600, 600, 1200, 2608));
        check("non-targets excluded even at full-screen dimensions",
                !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                        "com.android.systemui.shade.NotificationShadeWindowView", "NotificationShadeWindowView", "legacy_window_root",
                        1200, 2608, 1200, 2608)
                        && !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                                "com.android.systemui.statusbar.phone.ScrimView", "ScrimView", "scrim_behind",
                                1200, 2608, 1200, 2608)
                        && !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                                "android.widget.FrameLayout", "FrameLayout", "keyguard_root_view",
                                1200, 2608, 1200, 2608)
                        && !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                                "com.hyperalive.aod.FlymeClockView", "FlymeClockView", "",
                                1200, 2608, 1200, 2608)
                        && !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                                "android.widget.FrameLayout", "FrameLayout", "clock_container",
                                1200, 2608, 1200, 2608)
                        && !AodHostBackgroundTracker.isConfirmedAodHostCandidate(
                                "com.miui.clock.allInOne.TimeView", "TimeView", "clock_time",
                                1200, 2608, 1200, 2608));
    }

    private static void testRealSweeperEndToEndLifecycle(Context ctx, Resources res) {
        AodHostBackgroundTracker.resetForTests();
        SystemClock.uptimeMs = 5000L;
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        setSleepState(true, true, false);

        NotificationShadeWindowView root = new NotificationShadeWindowView(ctx);
        root.setResources(res);
        root.setId(ID_LEGACY_ROOT);
        root.setSize(1200, 2608);
        root.setAttachedForTest(true);
        Drawable rootOrigBg = new ColorDrawable(0xff000000);
        root.setBackground(rootOrigBg);

        ScrimView scrim = new ScrimView(ctx);
        scrim.setResources(res);
        scrim.setId(ID_SCRIM_BEHIND);
        scrim.setSize(1200, 2608);
        Drawable scrimOrigBg = new ColorDrawable(0xff000000);
        scrim.setBackground(scrimOrigBg);

        ViewGroup keyguardRoot = new ViewGroup(ctx);
        keyguardRoot.setResources(res);
        keyguardRoot.setId(ID_KEYGUARD_ROOT);
        keyguardRoot.setSize(1200, 2608);
        Drawable kgOrigBg = new ColorDrawable(0xff000000);
        keyguardRoot.setBackground(kgOrigBg);

        AODView aodView = new AODView(ctx);
        aodView.setResources(res);
        aodView.setId(ID_AOD_LAYOUT);
        aodView.setSize(0, 0); // Pre-layout when onAttachedToWindow fires
        Drawable aodOrigBg = new ColorDrawable(0xff000000);
        aodView.setBackground(aodOrigBg);

        // Dark non-host children inside AODView MUST NOT be wiped by ViewTreeSweeper.collect!
        ViewGroup clockContainer = new ViewGroup(ctx);
        clockContainer.setResources(res);
        clockContainer.setId(ID_CLOCK_CONTAINER);
        clockContainer.setSize(1200, 2608);
        Drawable childDarkBg = new ColorDrawable(0xff000000);
        clockContainer.setBackground(childDarkBg);

        TimeView timeView = new TimeView(ctx);
        timeView.setResources(res);
        timeView.setId(ID_CLOCK_TIME);
        timeView.setSize(300, 200);
        Drawable digitBg = new ColorDrawable(0xff000000);
        timeView.setBackground(digitBg);

        FlymeClockView flymeClock = new FlymeClockView(ctx);
        flymeClock.setResources(res);
        flymeClock.setSize(1200, 2608);
        Drawable flymeBg = new ColorDrawable(0xff000000);
        flymeClock.setBackground(flymeBg);

        clockContainer.addView(timeView);
        aodView.addView(clockContainer);
        aodView.addView(flymeClock);
        root.addView(scrim);
        root.addView(keyguardRoot);
        root.addView(aodView);

        // 1. onAodHostAttached (before layout): registers OnPreDrawListener and does NOT wipe 0x0 host or dark children.
        ViewTreeSweeper.onAodHostAttached(aodView);
        check("pre-layout attach arms OnPreDrawListener on root",
                root.getViewTreeObserver().listenerCountForTest() == 1);
        check("pre-layout attach does not mutate 0x0 AODView yet",
                aodView.getBackground() == aodOrigBg);

        // 2. ViewTreeSweeper.sweep collects tree; dark children must remain completely intact (legacy setBackground(null) removed).
        ViewTreeSweeper.sweep(root);
        check("ViewTreeSweeper.collect preserves dark non-host children",
                clockContainer.getBackground() == childDarkBg
                        && timeView.getBackground() == digitBg
                        && flymeClock.getBackground() == flymeBg);

        // 3. Layout completes right before first obstructing draw -> OnPreDrawListener suppresses AODView!
        aodView.setSize(1200, 2608);
        root.getViewTreeObserver().dispatchOnPreDrawForTest();
        Drawable sentinel = aodView.getBackground();
        check("onPreDraw clears laid-out AODView to transparent ColorDrawable(0)",
                sentinel instanceof ColorDrawable && sentinel != aodOrigBg
                        && ((ColorDrawable) sentinel).getColor() == 0);
        check("onPreDraw preserves root/scrim/keyguard/clockContainer/timeView/flyme backgrounds",
                root.getBackground() == rootOrigBg
                        && scrim.getBackground() == scrimOrigBg
                        && keyguardRoot.getBackground() == kgOrigBg
                        && clockContainer.getBackground() == childDarkBg
                        && timeView.getBackground() == digitBg
                        && flymeClock.getBackground() == flymeBg);

        // 4. MIUI resets background on reuse -> ViewTreeSweeper.onAodHostReused re-suppresses and caches reset Drawable.
        Drawable resetBg = new ColorDrawable(0xff000000);
        aodView.setBackground(resetBg);
        ViewTreeSweeper.onAodHostReused(aodView);
        Drawable sentinelAfterReuse = aodView.getBackground();
        check("onAodHostReused re-suppresses black reset to sentinel",
                sentinelAfterReuse instanceof ColorDrawable && ((ColorDrawable) sentinelAfterReuse).getColor() == 0);

        // 5. Full-screen AOD turned on -> StateCoordinator.recompute restores cached Drawable synchronously.
        setSleepState(true, true, true);
        StateCoordinator.recompute(ctx);
        check("fullscreen AOD restores cached Drawable",
                aodView.getBackground() == resetBg && AodHostBackgroundTracker.trackedCount() == 0);

        // 6. Full-screen AOD turned off -> recompute suppresses again before draw!
        setSleepState(true, true, false);
        StateCoordinator.recompute(ctx);
        check("exiting fullscreen AOD while sleeping re-suppresses AODView",
                aodView.getBackground() != resetBg && AodHostBackgroundTracker.trackedCount() == 1);

        // 7. AOD disabled -> recompute restores cached Drawable.
        setSleepState(true, false, false);
        StateCoordinator.recompute(ctx);
        check("disabling AOD restores cached Drawable",
                aodView.getBackground() == resetBg && AodHostBackgroundTracker.trackedCount() == 0);

        // 8. Re-enable & re-enter AOD, then wake via recompute -> restores cached Drawable!
        setSleepState(true, true, false);
        StateCoordinator.recompute(ctx);
        check("re-enabled AOD suppresses AODView again",
                aodView.getBackground() != resetBg && AodHostBackgroundTracker.trackedCount() == 1);
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        check("wake exit restores cached Drawable",
                aodView.getBackground() == resetBg && AodHostBackgroundTracker.trackedCount() == 0);

        // 9. Biometric wake exit -> onBiometricWakeIntent restores cached Drawable via main handler!
        SystemClock.uptimeMs += 2000L;
        setSleepState(true, true, false);
        StateCoordinator.recompute(ctx);
        check("suppressed prior to biometric wake", aodView.getBackground() != resetBg);
        StateCoordinator.sContext = ctx;
        StateCoordinator.onBiometricWakeIntent();
        check("biometric wake restores cached Drawable",
                aodView.getBackground() == resetBg && AodHostBackgroundTracker.trackedCount() == 0);

        // 10. True ownership loss (epoch bump) -> clearWhenNotOwned / recompute restores cached Drawable!
        SystemClock.uptimeMs += 2000L;
        setSleepState(true, true, false);
        StateCoordinator.recompute(ctx);
        check("suppressed prior to ownership loss", aodView.getBackground() != resetBg);
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.recompute(ctx);
        check("ownership loss restores cached Drawable",
                aodView.getBackground() == resetBg && AodHostBackgroundTracker.trackedCount() == 0);
    }

    private static void testNativeReplacementNotClobbered(Context ctx, Resources res) {
        AodHostBackgroundTracker.resetForTests();
        NotificationShadeWindowView root = new NotificationShadeWindowView(ctx);
        root.setResources(res);
        root.setId(ID_LEGACY_ROOT);
        root.setSize(1200, 2608);
        root.setAttachedForTest(true);

        AODView aodView = new AODView(ctx);
        aodView.setResources(res);
        aodView.setId(ID_AOD_LAYOUT);
        aodView.setSize(1200, 2608);
        Drawable origBg = new ColorDrawable(0xff000000);
        aodView.setBackground(origBg);
        root.addView(aodView);

        List<WeakReference<View>> hosts = Arrays.asList(new WeakReference<View>(aodView));
        AodHostBackgroundTracker.reconcileCachedHosts(root, hosts, true);
        check("suppressed to transparent sentinel", aodView.getBackground() != origBg);

        // Native code installs a brand new non-black Drawable after our replacement; restore must NOT clobber it!
        Drawable customNativeBg = new ColorDrawable(0xff102030);
        aodView.setBackground(customNativeBg);
        AodHostBackgroundTracker.restoreAll();
        check("restoreAll preserves newly installed native Drawable",
                aodView.getBackground() == customNativeBg && AodHostBackgroundTracker.trackedCount() == 0);

        // While suppressed, installing a non-black drawable also untracks cleanly without stripping it.
        aodView.setBackground(origBg);
        AodHostBackgroundTracker.reconcileCachedHosts(root, hosts, true);
        check("re-suppressed for second cycle", AodHostBackgroundTracker.trackedCount() == 1);
        aodView.setBackground(customNativeBg);
        AodHostBackgroundTracker.reconcileCachedHosts(root, hosts, true);
        check("reconcile while suppressed does not strip non-black custom Drawable",
                aodView.getBackground() == customNativeBg && AodHostBackgroundTracker.trackedCount() == 0);
    }

    private static void testDetachRootReplacementAndMainThreadGuard(Context ctx, Resources res) {
        AodHostBackgroundTracker.resetForTests();

        NotificationShadeWindowView root1 = new NotificationShadeWindowView(ctx);
        root1.setResources(res);
        root1.setId(ID_LEGACY_ROOT);
        root1.setSize(1200, 2608);
        root1.setAttachedForTest(true);

        AODView aod1 = new AODView(ctx);
        aod1.setResources(res);
        aod1.setId(ID_AOD_LAYOUT);
        aod1.setSize(1200, 2608);
        Drawable orig1 = new ColorDrawable(0xff000000);
        aod1.setBackground(orig1);
        root1.addView(aod1);

        AodHostBackgroundTracker.reconcileHost(aod1, root1, true);
        check("aod1 tracked and listener attached",
                aod1.getBackground() != orig1 && AodHostBackgroundTracker.trackedCount() == 1
                        && aod1.attachListenerCountForTest() == 1);

        // Child detach triggers automatic restore & removes listener.
        root1.removeView(aod1);
        check("host detach restores original Drawable and cleans listener",
                aod1.getBackground() == orig1 && AodHostBackgroundTracker.trackedCount() == 0
                        && aod1.attachListenerCountForTest() == 0);

        // Root replacement via StateCoordinator.updateAodRoot restores old root while keeping new root suppressed!
        root1.addView(aod1);
        AodHostBackgroundTracker.reconcileHost(aod1, root1, true);
        NotificationShadeWindowView root2 = new NotificationShadeWindowView(ctx);
        root2.setResources(res);
        root2.setId(ID_LEGACY_ROOT);
        root2.setSize(1200, 2608);
        root2.setAttachedForTest(true);
        AODView aod2 = new AODView(ctx);
        aod2.setResources(res);
        aod2.setId(ID_AOD_LAYOUT);
        aod2.setSize(1200, 2608);
        Drawable orig2 = new ColorDrawable(0xff000000);
        aod2.setBackground(orig2);
        root2.addView(aod2);
        AodHostBackgroundTracker.reconcileHost(aod2, root2, true);
        check("both roots initially tracked", AodHostBackgroundTracker.trackedCount() == 2);

        StateCoordinator.sAodRoot = root1;
        StateCoordinator.updateAodRoot(root2);
        check("root replacement restores old root host and keeps new root suppressed",
                aod1.getBackground() == orig1
                        && aod2.getBackground() != orig2
                        && AodHostBackgroundTracker.trackedCount() == 1
                        && StateCoordinator.sAodRoot == root2);

        // Off-main-thread call refuses mutation.
        Looper worker = Looper.newNonMainLooperForTest();
        Looper.setThreadLooperForTest(worker);
        try {
            AodHostBackgroundTracker.restoreAll();
            check("off-main-thread restore refuses View mutation",
                    aod2.getBackground() != orig2 && AodHostBackgroundTracker.trackedCount() == 1);
        } finally {
            Looper.setThreadLooperForTest(Looper.getMainLooper());
        }
        AodHostBackgroundTracker.restoreForRoot(root2);
        check("main-thread restoreForRoot restores remaining host",
                aod2.getBackground() == orig2 && AodHostBackgroundTracker.trackedCount() == 0);
    }

    private static void testWeakReferenceGc(Context ctx, Resources res) {
        AodHostBackgroundTracker.resetForTests();
        NotificationShadeWindowView root = new NotificationShadeWindowView(ctx);
        root.setResources(res);
        root.setId(ID_LEGACY_ROOT);
        root.setSize(1200, 2608);
        root.setAttachedForTest(true);

        AODView aod = new AODView(ctx);
        aod.setResources(res);
        aod.setId(ID_AOD_LAYOUT);
        aod.setSize(1200, 2608);
        aod.setBackground(new ColorDrawable(0xff000000));
        root.addView(aod);
        AodHostBackgroundTracker.reconcileHost(aod, root, true);
        check("tracked before GC", AodHostBackgroundTracker.trackedCount() == 1);

        WeakReference<AODView> weakAod = new WeakReference<>(aod);
        root.removeView(aod);
        aod = null;
        for (int i = 0; i < 6 && weakAod.get() != null; i++) {
            System.gc();
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        check("detached host is GC-collectible (no static strong leak)", weakAod.get() == null);
    }

    private static void testOwnershipChurnDecisions() {
        long ttl = 10000L;
        long grace = StateCoordinator.OWNERSHIP_PENDING_REFRESH_GRACE_MS; // 1500ms

        // Scenario A: AOD idled 24s -> TTL expired, async refresh in flight -> global fast check fails open,
        // AND shouldDeferNotOwnedRestore avoids spurious not_owned RESTORE -> CLEAR churn!
        StateCoordinator.OwnershipSnapshot snapOwned =
                new StateCoordinator.OwnershipSnapshot(true, true, true, 5L, 1000L);
        check("strict snapshot check remains false when TTL expired",
                !StateCoordinator.isSnapshotActive(snapOwned, 25000L, ttl, 5L, true));
        check("same-epoch expired lock-owned snapshot with active refresh defers destructive restore",
                StateCoordinator.shouldDeferNotOwnedRestore(snapOwned, 25000L, ttl, 5L, true, 24990L, false, grace));

        // Scenario B: Pending refresh grace exceeded -> stops deferring (fail-open).
        check("exceeding pending refresh grace stops deferring",
                !StateCoordinator.shouldDeferNotOwnedRestore(snapOwned, 26500L, ttl, 5L, true, 24990L, false, grace));

        // Scenario C: No refresh in flight -> stops deferring (fail-open).
        check("without in-flight refresh, expired snapshot does not defer",
                !StateCoordinator.shouldDeferNotOwnedRestore(snapOwned, 25000L, ttl, 5L, false, 24990L, false, grace));

        // Scenario D: WMS refresh failed (queriedOk=false) -> stops deferring immediately!
        check("refresh failure disables deferral (fail-open)",
                !StateCoordinator.shouldDeferNotOwnedRestore(snapOwned, 25000L, ttl, 5L, true, 24990L, true, grace));

        // Scenario E: Real wallpaper change bumps epoch (5 -> 6) -> releases immediately!
        check("epoch bump invalidates deferral immediately on real wallpaper change",
                !StateCoordinator.shouldDeferNotOwnedRestore(snapOwned, 25000L, ttl, 6L, true, 24990L, false, grace));

        // Scenario F: Cold/invalidated snapshot never defers.
        StateCoordinator.OwnershipSnapshot snapInvalid =
                new StateCoordinator.OwnershipSnapshot(false, true, true, 5L, 1000L);
        check("invalid snapshot never defers",
                !StateCoordinator.shouldDeferNotOwnedRestore(snapInvalid, 25000L, ttl, 5L, true, 24990L, false, grace));

        // Scenario G: Confirmed lock loss (lockOwned=false) never defers.
        StateCoordinator.OwnershipSnapshot snapLost =
                new StateCoordinator.OwnershipSnapshot(true, true, false, 5L, 1000L);
        check("confirmed not lock-owned snapshot never defers",
                !StateCoordinator.shouldDeferNotOwnedRestore(snapLost, 25000L, ttl, 5L, true, 24990L, false, grace));

        // Scenario H: Live StateCoordinator expiry preserves tracked host while async refresh is in-flight,
        // keeps host suppressed if refresh recovers ownership, tears down on refresh failure/loss or epoch bump!
        AodHostBackgroundTracker.resetForTests();
        android.os.Handler.clearQueueForTest();
        SystemClock.uptimeMs = 1000L;
        final android.app.WallpaperManager wm = new android.app.WallpaperManager();
        wm.mockSystemInfo = new android.app.WallpaperInfo("com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaperService");
        wm.mockLockId = -1;
        final ContentResolver liveCr = new ContentResolver();
        final DisplayManager liveDm = new DisplayManager();
        final KeyguardManager liveKm = new KeyguardManager();
        Context resCtx = new Context() {
            @Override public Resources getResources() { return buildRes(); }
            @Override public ContentResolver getContentResolver() { return liveCr; }
            @Override public Object getSystemService(String name) {
                if (WALLPAPER_SERVICE.equals(name)) return wm;
                if (DISPLAY_SERVICE.equals(name)) return liveDm;
                if (KEYGUARD_SERVICE.equals(name)) return liveKm;
                return null;
            }
        };
        StateCoordinator.sContext = resCtx;
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        setSleepState(true, true, false);
        NotificationShadeWindowView r = new NotificationShadeWindowView(resCtx);
        r.setSize(1200, 2608);
        r.setAttachedForTest(true);
        AODView host = new AODView(resCtx);
        host.setId(ID_AOD_LAYOUT);
        host.setSize(1200, 2608);
        Drawable bg = new ColorDrawable(0xff000000);
        host.setBackground(bg);
        r.addView(host);
        StateCoordinator.sAodRoot = r;
        AodHostBackgroundTracker.reconcileHost(host, r, true);
        check("setup active cleared host", host.getBackground() != bg && AodHostBackgroundTracker.trackedCount() == 1);

        // Advance past 10s TTL (no pre-refresh ran): fast check fails open AND defers destructive restore
        // while async WMS verification runs; when WMS confirms lockOwned=true, host stays cleared!
        SystemClock.uptimeMs = 25000L;
        StateCoordinator.recompute(resCtx);
        check("pending async refresh defers immediate host restore during TTL expiry",
                host.getBackground() != bg && AodHostBackgroundTracker.trackedCount() == 1);
        long deadline = System.currentTimeMillis() + 2000L;
        while (wm.queryCount == 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        android.os.Handler.drainMainQueueForTest();
        check("async refresh confirmation recovers lock ownership without churning host background",
                StateCoordinator.isLockWallpaperActiveFast()
                        && host.getBackground() != bg
                        && AodHostBackgroundTracker.trackedCount() == 1);

        // Next TTL expiry where WMS reports another wallpaper took over -> restores cached Drawable!
        wm.mockSystemInfo = new android.app.WallpaperInfo("com.miui.home", "com.miui.home.StockWallpaper");
        int prevQueries = wm.queryCount;
        SystemClock.uptimeMs = 45000L;
        StateCoordinator.recompute(resCtx);
        deadline = System.currentTimeMillis() + 2000L;
        while (wm.queryCount == prevQueries && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        android.os.Handler.drainMainQueueForTest();
        check("confirmed lock ownership loss via async refresh restores cached Drawable",
                !StateCoordinator.isLockWallpaperActiveFast()
                        && host.getBackground() == bg
                        && AodHostBackgroundTracker.trackedCount() == 0);

        // Re-acquire lock & suppress host again, then verify WMS query failure restores host immediately after completion!
        wm.mockSystemInfo = new android.app.WallpaperInfo("com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaperService");
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        AodHostBackgroundTracker.reconcileHost(host, r, true);
        check("re-suppressed prior to refresh failure test",
                host.getBackground() != bg && AodHostBackgroundTracker.trackedCount() == 1);
        wm.throwOnQuery = true;
        prevQueries = wm.queryCount;
        SystemClock.uptimeMs = 65000L;
        StateCoordinator.recompute(resCtx);
        deadline = System.currentTimeMillis() + 2000L;
        while (wm.queryCount == prevQueries && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        android.os.Handler.drainMainQueueForTest();
        check("WMS refresh failure stops deferring and restores cached Drawable",
                host.getBackground() == bg && AodHostBackgroundTracker.trackedCount() == 0);

        // Finally verify real epoch invalidation restores host immediately without waiting!
        wm.throwOnQuery = false;
        SystemClock.uptimeMs = 70000L;
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        AodHostBackgroundTracker.reconcileHost(host, r, true);
        check("re-suppressed prior to epoch bump", host.getBackground() != bg && AodHostBackgroundTracker.trackedCount() == 1);
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.recompute(resCtx);
        check("epoch bump immediately restores host background via StateCoordinator.recompute",
                host.getBackground() == bg && AodHostBackgroundTracker.trackedCount() == 0);
    }

    private static Object scrimObj(final String stateName) {
        return new Object() {
            final String mState = stateName;
        };
    }

    private static void testLateAttachAfterSweepAndMaskOff(Context ctx, Resources res) {
        // Case A: Exact HOME->AOD timing bug reproduction:
        // 1. AodView (aod_root_view, bg=null) is already in tree; AODView is NOT attached yet.
        // 2. sweep() + recompute() run BEFORE AODView attaches (e.g. Clock policy + onScrim(KEYGUARD)).
        // 3. Plugin AODView (aod_mode_layout_horizontal, bg=0xff000000) attaches later without Xposed onAttachedToWindow firing.
        // 4. Subsequent recompute() / onScrim("AOD") MUST synchronously discover and suppress AODView!
        AodHostBackgroundTracker.resetForTests();
        HookUtils.PROPS.clear();
        HookUtils.LOGS.clear();
        SystemClock.uptimeMs = 80000L;
        StateCoordinator.sContext = ctx;
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        setSleepState(true, true, false);

        NotificationShadeWindowView root = new NotificationShadeWindowView(ctx);
        root.setResources(res);
        root.setId(ID_LEGACY_ROOT);
        root.setSize(1200, 2608);
        root.setAttachedForTest(true);

        ViewGroup keyguardRoot = new ViewGroup(ctx);
        keyguardRoot.setResources(res);
        keyguardRoot.setId(ID_KEYGUARD_ROOT);
        keyguardRoot.setSize(1200, 2608);
        root.addView(keyguardRoot);

        View aodRootContainer = new View(ctx);
        aodRootContainer.setResources(res);
        aodRootContainer.setId(ID_AOD_ROOT);
        aodRootContainer.setSize(1200, 2608);
        root.addView(aodRootContainer);

        StateCoordinator.sAodRoot = root;
        ViewTreeSweeper.sweep(root);
        StateCoordinator.onScrim(scrimObj("KEYGUARD"));
        check("AodView(bg=null) present alone must not create tracked entry",
                AodHostBackgroundTracker.trackedCount() == 0);

        AODView lateAodView = new AODView(ctx);
        lateAodView.setResources(res);
        lateAodView.setId(ID_AOD_LAYOUT);
        lateAodView.setSize(1200, 2608);
        Drawable blackBg = new ColorDrawable(0xff000000);
        lateAodView.setBackground(blackBg);
        root.addView(lateAodView);

        StateCoordinator.onScrim(scrimObj("AOD"));
        check("Case A: onScrim(AOD)/recompute synchronously discovers late-attached AODView even after premature sweep",
                lateAodView.getBackground() != blackBg
                        && AodHostBackgroundTracker.trackedCount() == 1
                        && AodHostBackgroundTracker.hasTrackedHostForRoot(root));

        // Case B: Late-attached AODView added AFTER recompute, caught synchronously by OnPreDrawListener before draw,
        // and subsequent steady-state PreDraws do not churn once tracked.
        AodHostBackgroundTracker.resetForTests();
        NotificationShadeWindowView rootB = new NotificationShadeWindowView(ctx);
        rootB.setResources(res);
        rootB.setId(ID_LEGACY_ROOT);
        rootB.setSize(1200, 2608);
        rootB.setAttachedForTest(true);
        View aodRootB = new View(ctx);
        aodRootB.setResources(res);
        aodRootB.setId(ID_AOD_ROOT);
        aodRootB.setSize(1200, 2608);
        rootB.addView(aodRootB);
        StateCoordinator.sAodRoot = rootB;

        ViewTreeSweeper.sweep(rootB);
        StateCoordinator.recompute(ctx);

        AODView predrawLateView = new AODView(ctx);
        predrawLateView.setResources(res);
        predrawLateView.setId(ID_AOD_LAYOUT);
        predrawLateView.setSize(1200, 2608);
        Drawable blackBgB = new ColorDrawable(0xff000000);
        predrawLateView.setBackground(blackBgB);
        rootB.addView(predrawLateView);

        rootB.getViewTreeObserver().dispatchOnPreDrawForTest();
        check("Case B: OnPreDrawListener discovers and suppresses late-attached AODView before first draw",
                predrawLateView.getBackground() != blackBgB
                        && AodHostBackgroundTracker.trackedCount() == 1);

        // Case C: armHomeSleepMask + late-attached AODView + releaseHomeSleepMask suppresses BEFORE unmasking!
        AodHostBackgroundTracker.resetForTests();
        NotificationShadeWindowView rootC = new NotificationShadeWindowView(ctx);
        rootC.setResources(res);
        rootC.setId(ID_LEGACY_ROOT);
        rootC.setSize(1200, 2608);
        rootC.setAttachedForTest(true);
        ViewGroup keyguardC = new ViewGroup(ctx);
        keyguardC.setResources(res);
        keyguardC.setId(ID_KEYGUARD_ROOT);
        keyguardC.setSize(1200, 2608);
        rootC.addView(keyguardC);
        StateCoordinator.sAodRoot = rootC;

        ViewTreeSweeper.sweep(rootC);
        StateCoordinator.armHomeSleepMask("test_home_sleep");

        AODView maskLateView = new AODView(ctx);
        maskLateView.setResources(res);
        maskLateView.setId(ID_AOD_LAYOUT);
        maskLateView.setSize(1200, 2608);
        Drawable blackBgC = new ColorDrawable(0xff000000);
        maskLateView.setBackground(blackBgC);
        rootC.addView(maskLateView);

        StateCoordinator.releaseHomeSleepMask("test_unmask");
        check("Case C: releaseHomeSleepMask synchronously discovers and suppresses late-attached AODView before unmasking",
                maskLateView.getBackground() != blackBgC
                        && AodHostBackgroundTracker.trackedCount() == 1);
    }

    private static int countTraceLogs() {
        int count = 0;
        for (String line : HookUtils.LOGS) {
            if (line != null && line.contains("[AOD_BG_TRACE]")) count++;
        }
        return count;
    }

    private static boolean hasTraceLog(String eventToken) {
        for (String line : HookUtils.LOGS) {
            if (line != null && line.contains("[AOD_BG_TRACE] " + eventToken)) return true;
        }
        return false;
    }

    private static void testOptInTraceSwitchAndSteadyStateSilence(Context ctx, Resources res) {
        AodHostBackgroundTracker.resetForTests();
        HookUtils.PROPS.clear();
        HookUtils.LOGS.clear();
        SystemClock.uptimeMs = 90000L;
        StateCoordinator.sContext = ctx;
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        setSleepState(true, true, false);

        NotificationShadeWindowView root = new NotificationShadeWindowView(ctx);
        root.setResources(res);
        root.setId(ID_LEGACY_ROOT);
        root.setSize(1200, 2608);
        root.setAttachedForTest(true);
        AODView host = new AODView(ctx);
        host.setResources(res);
        host.setId(ID_AOD_LAYOUT);
        host.setSize(1200, 2608);
        Drawable origBg = new ColorDrawable(0xff000000);
        host.setBackground(origBg);
        root.addView(host);
        StateCoordinator.sAodRoot = root;

        // Default OFF: zero [AOD_BG_TRACE] output across full lifecycle.
        ViewTreeSweeper.sweep(root);
        StateCoordinator.onScrim(scrimObj("AOD"));
        root.getViewTreeObserver().dispatchOnPreDrawForTest();
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        AodHostBackgroundTracker.traceRootReplaced(root, root);
        check("debug.qihang.aod_bg_trace default OFF produces zero trace logs",
                countTraceLogs() == 0);

        // Opt-in ON: discrete CLEAR, steady-state silence, RESUPPRESS, RESTORE, ROOT_REPLACE.
        AodHostBackgroundTracker.resetForTests();
        HookUtils.LOGS.clear();
        HookUtils.propSet(AodHostBackgroundTracker.PROP_TRACE, "1");
        setSleepState(true, true, false);

        ViewTreeSweeper.sweep(root);
        check("opt-in trace emits CLEAR with monotonic timestamps and gates",
                hasTraceLog("CLEAR ")
                        && HookUtils.LOGS.toString().contains("up=")
                        && HookUtils.LOGS.toString().contains("nano=")
                        && HookUtils.LOGS.toString().contains("dim=1200x2608")
                        && HookUtils.LOGS.toString().contains("gates="));

        int afterClearCount = countTraceLogs();
        for (int i = 0; i < 10; i++) {
            root.getViewTreeObserver().dispatchOnPreDrawForTest();
        }
        check("steady-state PreDraw loop emits zero extra trace logs",
                countTraceLogs() == afterClearCount);

        // External black reset -> RESUPPRESS on PreDraw.
        Drawable reBlack = new ColorDrawable(0xff000000);
        host.setBackground(reBlack);
        root.getViewTreeObserver().dispatchOnPreDrawForTest();
        check("opt-in trace emits RESUPPRESS when native black bg resets externally",
                hasTraceLog("RESUPPRESS "));

        // Wake up -> RESTORE.
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        check("opt-in trace emits RESTORE on wake",
                hasTraceLog("RESTORE "));

        AodHostBackgroundTracker.traceRootReplaced(root, root);
        check("opt-in trace emits ROOT_REPLACE",
                hasTraceLog("ROOT_REPLACE "));
    }

    private static void testMutableSentinelLifecycle(Context ctx, Resources res) {
        AodHostBackgroundTracker.resetForTests();
        HookUtils.PROPS.clear();
        HookUtils.LOGS.clear();
        HookUtils.propSet(AodHostBackgroundTracker.PROP_TRACE, "1");
        SystemClock.uptimeMs = 110000L;
        StateCoordinator.sContext = ctx;
        StateCoordinator.invalidateWallpaperOwnedCache();
        StateCoordinator.setCachedWallpaperSlots(true, true);
        setSleepState(true, true, false);

        NotificationShadeWindowView root = new NotificationShadeWindowView(ctx);
        root.setResources(res);
        root.setId(ID_LEGACY_ROOT);
        root.setSize(1200, 2608);
        root.setAttachedForTest(true);

        AODView host = new AODView(ctx);
        host.setResources(res);
        host.setId(ID_AOD_LAYOUT);
        host.setSize(1200, 2608);
        Drawable origBg = new ColorDrawable(0xff000000);
        host.setBackground(origBg);
        root.addView(host);
        StateCoordinator.sAodRoot = root;

        // 1. Initial suppress installs transparent ColorDrawable(0) sentinel.
        ViewTreeSweeper.sweep(root);
        Drawable sentinel1 = host.getBackground();
        check("initial clear installs transparent ColorDrawable(0)",
                sentinel1 instanceof ColorDrawable && sentinel1 != origBg
                        && ((ColorDrawable) sentinel1).getColor() == 0);

        // 2. Native calls View.setBackgroundColor(0xff000000) -> mutates sentinel1 in place!
        HookUtils.LOGS.clear();
        host.setBackgroundColor(0xff000000);
        check("setBackgroundColor(black) mutates sentinel1 in place without swapping object identity",
                host.getBackground() == sentinel1 && ((ColorDrawable) sentinel1).getColor() == 0xff000000);

        // Next preDraw must inspect color, re-suppress cleanly with fresh ColorDrawable(0), and trace RESUPPRESS.
        int invBeforeResuppress = host.invalidateCount;
        root.getViewTreeObserver().dispatchOnPreDrawForTest();
        Drawable sentinel2 = host.getBackground();
        check("preDraw after in-place black mutation re-suppresses to fresh transparent sentinel",
                sentinel2 instanceof ColorDrawable
                        && sentinel2 != sentinel1
                        && ((ColorDrawable) sentinel2).getColor() == 0
                        && host.invalidateCount == invBeforeResuppress + 1
                        && hasTraceLog("RESUPPRESS ")
                        && HookUtils.LOGS.toString().contains("beforeBg=Color(0xff000000) afterBg=Color(0x0)"));

        // Repeat preDraw passes must remain idempotent (zero invalidations, zero extra logs).
        int invAfterResuppress = host.invalidateCount;
        int logsAfterResuppress = countTraceLogs();
        for (int i = 0; i < 5; i++) {
            root.getViewTreeObserver().dispatchOnPreDrawForTest();
        }
        check("repeat reconcile after in-place resuppress is idempotent",
                host.getBackground() == sentinel2
                        && ((ColorDrawable) host.getBackground()).getColor() == 0
                        && host.invalidateCount == invAfterResuppress
                        && countTraceLogs() == logsAfterResuppress);

        // 3. Even if an existing sentinel is mutated in place AND then cur switches to another black Drawable,
        // reconciling cur != sentinel must NEVER reinstall a black-contaminated sentinel.
        ((ColorDrawable) sentinel2).setColor(0xff000000);
        Drawable newBlackObj = new ColorDrawable(0xff000000);
        host.setBackground(newBlackObj);
        root.getViewTreeObserver().dispatchOnPreDrawForTest();
        Drawable sentinel3 = host.getBackground();
        check("contaminated sentinel is discarded rather than re-applying black on object swap",
                sentinel3 instanceof ColorDrawable
                        && sentinel3 != sentinel2
                        && sentinel3 != newBlackObj
                        && ((ColorDrawable) sentinel3).getColor() == 0);

        // 4. Wake restore after in-place-to-black resuppression restores opaque black cleanly.
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        check("wake after in-place resuppress restores opaque black background",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0xff000000
                        && AodHostBackgroundTracker.trackedCount() == 0);

        // 5. Immediate wake right after in-place black mutation (before preDraw ran) also yields latest native black!
        setSleepState(true, true, false);
        StateCoordinator.recompute(ctx);
        Drawable sentinel4 = host.getBackground();
        check("re-suppressed for immediate-wake black mutation test",
                sentinel4 instanceof ColorDrawable && ((ColorDrawable) sentinel4).getColor() == 0);
        host.setBackgroundColor(0xff020202);
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        check("immediate wake preserves in-place opaque black color without staying stuck transparent",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0xff020202
                        && AodHostBackgroundTracker.trackedCount() == 0);

        // 6. Native in-place mutation to a non-black color while sleeping: preDraw untracks and never clobbers,
        // and subsequent wake leaves the native non-black color intact.
        setSleepState(true, true, false);
        host.setBackgroundColor(0xff000000);
        StateCoordinator.recompute(ctx);
        check("re-suppressed for non-black in-place reconcile test",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0);
        HookUtils.LOGS.clear();
        host.setBackgroundColor(0xff102030);
        root.getViewTreeObserver().dispatchOnPreDrawForTest();
        check("in-place mutation to non-black untracks without stripping",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0xff102030
                        && AodHostBackgroundTracker.trackedCount() == 0
                        && hasTraceLog("UNTRACK_NATIVE_BG "));
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        check("wake after non-black in-place untrack preserves non-black background",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0xff102030);

        // 7. Native in-place mutation to non-black followed by IMMEDIATE wake (without intervening reconcile):
        // restoreView must skip overwriting with originalBg!
        setSleepState(true, true, false);
        host.setBackgroundColor(0xff000000);
        StateCoordinator.recompute(ctx);
        check("re-suppressed for immediate-wake non-black test",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0);
        HookUtils.LOGS.clear();
        host.setBackgroundColor(0x80123456);
        setSleepState(false, true, false);
        StateCoordinator.recompute(ctx);
        check("immediate wake after in-place non-black mutation does not clobber with originalBg",
                host.getBackground() instanceof ColorDrawable
                        && ((ColorDrawable) host.getBackground()).getColor() == 0x80123456
                        && AodHostBackgroundTracker.trackedCount() == 0
                        && hasTraceLog("RESTORE_SKIP_CHANGED "));
    }

    private static void awaitWakeQueue(boolean ownershipQueue) throws InterruptedException {
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        if (ownershipQueue) StateCoordinator.postAsync(drained::countDown);
        else StateCoordinator.postPowerAsync(drained::countDown);
        if (!drained.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("Wake test queue did not drain");
        }
    }

    private static void testPrimaryWakeRecovery() throws Exception {
        awaitWakeQueue(true);
        awaitWakeQueue(false);
        android.os.Handler.drainMainQueueForTest();
        final android.app.WallpaperManager wm = new android.app.WallpaperManager();
        final java.util.concurrent.atomic.AtomicInteger lockWakes = new java.util.concurrent.atomic.AtomicInteger();
        wm.mockSystemInfo = new android.app.WallpaperInfo("com.hyperalive.aod", "QihangWallpaper");
        StateCoordinator.sContext = new Context() {
            @Override public Object getSystemService(String name) {
                return WALLPAPER_SERVICE.equals(name) ? wm : null;
            }
            @Override public void sendBroadcast(android.content.Intent intent) {
                if (StateCoordinator.ACTION_MODE.equals(intent.getAction())
                        && "power_wake".equals(intent.getStringExtra(StateCoordinator.EXTRA_STATE))) {
                    lockWakes.incrementAndGet();
                }
            }
        };

        SystemClock.uptimeMs = 200_000L;
        StateCoordinator.setCachedWallpaperSlots(true, true);
        setSleepState(true, true, false);
        SystemClock.uptimeMs += 11_000L;
        StateCoordinator.onPrimaryDisplayAwake(false);
        awaitWakeQueue(true);
        awaitWakeQueue(false);
        check("expired ownership is rechecked and the confirmed primary wake is delivered",
                wm.queryCount > 0 && !StateCoordinator.isSleepingActive() && lockWakes.get() == 1);
        StateCoordinator.onPrimaryDisplayAwake(false);
        awaitWakeQueue(false);
        check("a wake already delivered is not sent again", lockWakes.get() == 1);

        setSleepState(true, true, false);
        SystemClock.uptimeMs += 11_000L;
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        StateCoordinator.postAsync(() -> {
            try {
                resume.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            StateCoordinator.onPrimaryDisplayAwake(false);
            StateCoordinator.cancelPrimaryWakeRecovery();
        } finally {
            resume.countDown();
        }
        awaitWakeQueue(true);
        awaitWakeQueue(false);
        check("a new primary sleep edge cancels the wake pending ownership verification",
                StateCoordinator.isSleepingActive() && lockWakes.get() == 1);

        wm.mockLockId = 42;
        wm.mockLockInfo = new android.app.WallpaperInfo("com.miui.home", "NativeWallpaper");
        SystemClock.uptimeMs += 11_000L;
        StateCoordinator.onPrimaryDisplayAwake(false);
        awaitWakeQueue(true);
        awaitWakeQueue(false);
        check("desktop ownership alone cannot recover a lock wallpaper wake",
                !StateCoordinator.isLockWallpaperActiveFast() && lockWakes.get() == 1);
    }

    public static void main(String[] args) {
        try {
            Resources res = buildRes();
            Context ctx = buildCtx(res);
            testClassifierAndExclusions();
            testRealSweeperEndToEndLifecycle(ctx, res);
            testNativeReplacementNotClobbered(ctx, res);
            testDetachRootReplacementAndMainThreadGuard(ctx, res);
            testWeakReferenceGc(ctx, res);
            testOwnershipChurnDecisions();
            testLateAttachAfterSweepAndMaskOff(ctx, res);
            testOptInTraceSwitchAndSteadyStateSilence(ctx, res);
            testMutableSentinelLifecycle(ctx, res);
            testPrimaryWakeRecovery();
            System.out.println("AOD host background lifecycle: " + assertions + " assertions passed");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
""",
}


def check_source_contracts():
    sweeper = (APP_JAVA / "com/hyperalive/aod/hook/ViewTreeSweeper.java").read_text(encoding="utf-8")
    aod_hook = (APP_JAVA / "com/hyperalive/aod/hook/AodHook.java").read_text(encoding="utf-8")
    coord = (APP_JAVA / "com/hyperalive/aod/hook/StateCoordinator.java").read_text(encoding="utf-8")
    tracker = (APP_JAVA / "com/hyperalive/aod/hook/AodHostBackgroundTracker.java").read_text(encoding="utf-8")

    assert "setBackground(null)" not in sweeper, "ViewTreeSweeper must not call untracked setBackground(null)"
    assert "isDarkBackground(" not in sweeper, "Legacy ViewTreeSweeper.isDarkBackground must remain removed"
    assert "setBackground(null)" not in aod_hook, "AodHook must not call untracked setBackground(null)"
    assert "installHostLifecycleHooks" in aod_hook, "AodHook must dynamically hook plugin AODView lifecycle"
    assert "PROP_TRACE = \"debug.qihang.aod_bg_trace\"" in tracker, "Opt-in trace property must stay named debug.qihang.aod_bg_trace"
    assert "isTraceEnabled()" in tracker, "Trace logging must stay gated behind isTraceEnabled()"
    assert "aodHostsCollected" not in sweeper, "Premature one-shot aodHostsCollected lockout must remain removed"
    assert "WALLPAPER_OWNED_CACHE_MS = 10000L" in coord, "Strict 10s ownership TTL must remain"
    assert "WALLPAPER_OWNED_PREREFRESH_MS = 7000L" in coord, "7s pre-refresh threshold must remain"
    assert '"1".equals(propVal)' not in coord, "No insecure prop=1 ownership fallback allowed"
    for name, src in (("ViewTreeSweeper", sweeper), ("AodHook", aod_hook), ("StateCoordinator", coord), ("AodHostBackgroundTracker", tracker)):
        assert "glReadPixels" not in src, f"{name} must not depend on glReadPixels"
        assert "black_gap" not in src, f"{name} must not depend on temporary black_gap debug props"


def main():
    check_source_contracts()
    build = ROOT / "build/offline_tests"
    build.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="aod_host_bg_", dir=build) as tmp:
        work = Path(tmp)
        shim_src = work / "shim_src"
        shim_cls = work / "shim_cls"
        prod_cls = work / "prod_cls"
        test_src = work / "test_src"
        test_cls = work / "test_cls"
        for d in (shim_src, shim_cls, prod_cls, test_src, test_cls):
            d.mkdir(parents=True, exist_ok=True)

        shim_files = []
        for rel, content in SHIM_SOURCES.items():
            p = shim_src / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            shim_files.append(str(p))

        javac = _resolve_jdk_tool("javac")
        java = _resolve_jdk_tool("java")

        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-d", str(shim_cls),
        ] + shim_files, check=True)

        prod_files = [
            str(APP_JAVA / "com/hyperalive/aod/hook/AodHostBackgroundTracker.java"),
            str(APP_JAVA / "com/hyperalive/aod/hook/ViewTreeSweeper.java"),
            str(APP_JAVA / "com/hyperalive/aod/hook/StateCoordinator.java"),
            str(APP_JAVA / "com/hyperalive/aod/TransitionPolicy.java"),
        ]
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", str(shim_cls), "-d", str(prod_cls),
        ] + prod_files, check=True)

        tfiles = []
        for rel, content in TEST_CLASSES.items():
            p = test_src / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            tfiles.append(str(p))

        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", os.pathsep.join([str(prod_cls), str(shim_cls)]), "-d", str(test_cls),
        ] + tfiles, check=True)

        subprocess.run([
            java, "-cp", os.pathsep.join([str(test_cls), str(prod_cls), str(shim_cls)]), "AodHostBgLifecycleTest",
        ], check=True)


if __name__ == "__main__":
    main()
