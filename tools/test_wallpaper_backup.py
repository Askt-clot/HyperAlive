#!/usr/bin/env python3
"""Offline JVM regression suite for HyperOS 4 wallpaper backup, validation, and lifecycle.

Compiles and executes the real production Java classes
(HyperOsWallpaperHelper, Root, PreModuleWallpaperBackup) against:
1. Optional external HyperOS 4 fixture sets via --evidence-dir / HYPERALIVE_EVIDENCE_DIR;
2. Edge-case XML/JSON/path-traversal/corruption/concurrency scenarios;
3. Minimal JVM runtime shims placed ahead of android.jar so no Android device is needed.
   Never touches release APKs or existing build/cli_out outputs.
"""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP_JAVA = ROOT / "app/src/main/java"
RES_DIR = ROOT / "app/src/main/res"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
STUB_DIR = ROOT / "stub"


def _version_key(name: str) -> tuple[int, ...]:
    return tuple(int(tok) for tok in re.findall(r"\d+", name))


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


def _resolve_android_tools(sdk_override: Path | None = None) -> tuple[Path, Path]:
    sdk_raw = (
        str(sdk_override)
        if sdk_override
        else (os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"))
    )
    if not sdk_raw or not Path(sdk_raw).is_dir():
        raise SystemExit("Missing Android SDK: configure ANDROID_SDK_ROOT or ANDROID_HOME, or pass --sdk")
    sdk = Path(sdk_raw)
    bt_dirs = sorted(
        (d for d in (sdk / "build-tools").glob("*") if d.is_dir()),
        key=lambda p: _version_key(p.name),
        reverse=True,
    )
    aapt2_name = "aapt2.exe" if os.name == "nt" else "aapt2"
    aapt2 = bt_dirs[0] / aapt2_name if bt_dirs else Path()
    plat_jars = sorted(
        (p for p in (sdk / "platforms").glob("android-*/android.jar") if p.is_file()),
        key=lambda p: _version_key(p.parent.name),
        reverse=True,
    )
    if not aapt2.is_file() or not plat_jars:
        raise SystemExit(f"Android SDK missing build-tools aapt2 or platforms/android.jar under {sdk}")
    return aapt2, plat_jars[0]


DEFAULT_EVIDENCE_DIR = (
    Path(os.environ["HYPERALIVE_EVIDENCE_DIR"])
    if os.environ.get("HYPERALIVE_EVIDENCE_DIR")
    else None
)

SHIM_LOG = r"""
package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Log {
    public static final List<String> ENTRIES = Collections.synchronizedList(new ArrayList<String>());

    public static void clearEntries() {
        ENTRIES.clear();
    }

    public static int d(String tag, String msg) {
        ENTRIES.add("D/" + tag + ": " + msg);
        return 0;
    }

    public static int i(String tag, String msg) {
        ENTRIES.add("I/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        ENTRIES.add("W/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        ENTRIES.add("W/" + tag + ": " + msg + " [" + tr + "]");
        return 0;
    }

    public static int e(String tag, String msg) {
        ENTRIES.add("E/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        ENTRIES.add("E/" + tag + ": " + msg + " [" + tr + "]");
        return 0;
    }
}
"""

SHIM_PROCESS = r"""
package android.os;

public class Process {
    public static int sUid = 10123;

    public static int myUid() {
        return sUid;
    }
}
"""

SHIM_SHARED_PREFERENCES = r"""
package android.content;

import java.util.Map;
import java.util.Set;

public interface SharedPreferences {
    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key);
    }

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    Map<String, ?> getAll();
    String getString(String key, String defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean getBoolean(String key, boolean defValue);
    boolean contains(String key);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
}
"""

SHIM_ASSET_MANAGER = r"""
package android.content.res;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class AssetManager {
    public static final Map<String, byte[]> sAssets = new HashMap<String, byte[]>();

    public InputStream open(String fileName) throws IOException {
        byte[] b = sAssets.get(fileName);
        if (b == null) {
            throw new IOException("Missing asset: " + fileName);
        }
        return new ByteArrayInputStream(b);
    }
}
"""

SHIM_CONTEXT = r"""
package android.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String WALLPAPER_SERVICE = "wallpaper";
    public static final String USER_SERVICE = "user";
    public static volatile String sFailCommitForPrefPrefix = null;
    private final File filesDir;
    private final File externalFilesDir;
    private final File deFilesDir;
    private final Context deContext;
    private final Map<String, SharedPreferences> prefsMap;

    public Context(File filesDir) {
        this(filesDir, null, filesDir, new HashMap<String, SharedPreferences>());
    }

    public Context(File filesDir, File externalFilesDir, File deFilesDir) {
        this(filesDir, externalFilesDir, deFilesDir, new HashMap<String, SharedPreferences>());
    }

    private Context(
            File filesDir,
            File externalFilesDir,
            File deFilesDir,
            Map<String, SharedPreferences> prefsMap) {
        this.filesDir = filesDir;
        this.externalFilesDir = externalFilesDir;
        this.deFilesDir = deFilesDir;
        this.prefsMap = prefsMap;
        this.deContext = (deFilesDir != null && !deFilesDir.equals(filesDir))
                ? new Context(deFilesDir, externalFilesDir, deFilesDir, new HashMap<String, SharedPreferences>(), true)
                : this;
    }

    private Context(
            File deFilesDir,
            File externalFilesDir,
            File sameDeFilesDir,
            Map<String, SharedPreferences> prefsMap,
            boolean marker) {
        this.filesDir = deFilesDir;
        this.externalFilesDir = externalFilesDir;
        this.deFilesDir = sameDeFilesDir;
        this.prefsMap = prefsMap;
        this.deContext = this;
    }

    public Context getApplicationContext() {
        return this;
    }

    public Context createDeviceProtectedStorageContext() {
        return deContext;
    }

    public boolean isDeviceProtectedStorage() {
        return this == deContext && !filesDir.equals(externalFilesDir);
    }

    public File getFilesDir() {
        return filesDir;
    }

    public File getExternalFilesDir(String type) {
        return externalFilesDir;
    }

    public String getPackageName() {
        return "com.hyperalive.aod";
    }

    public ContentResolver getContentResolver() {
        return null;
    }

    public Object getSystemService(String name) {
        return null;
    }

    public android.content.res.AssetManager getAssets() {
        return new android.content.res.AssetManager();
    }

    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences sp = prefsMap.get(name);
        if (sp == null) {
            File storeDir = (filesDir != null) ? new File(filesDir, "_test_prefs") : null;
            File storeFile = (storeDir != null) ? new File(storeDir, name + ".props") : null;
            sp = new PersistentPrefs(name, storeFile);
            prefsMap.put(name, sp);
        }
        return sp;
    }

    public synchronized boolean deleteSharedPreferences(String name) {
        prefsMap.remove(name);
        if (filesDir != null) {
            File f = new File(new File(filesDir, "_test_prefs"), name + ".props");
            if (f.exists()) {
                return f.delete();
            }
        }
        return true;
    }

    private static final class PersistentPrefs implements SharedPreferences {
        private final String prefName;
        private final File storeFile;
        private final Map<String, Object> data = new HashMap<String, Object>();

        PersistentPrefs(String prefName, File storeFile) {
            this.prefName = prefName;
            this.storeFile = storeFile;
            loadFromDisk();
        }

        private void loadFromDisk() {
            if (storeFile == null || !storeFile.isFile()) return;
            Properties p = new Properties();
            try (FileInputStream fis = new FileInputStream(storeFile)) {
                p.load(fis);
                for (String k : p.stringPropertyNames()) {
                    String raw = p.getProperty(k);
                    if (raw == null) continue;
                    if (raw.startsWith("s:")) data.put(k, raw.substring(2));
                    else if (raw.startsWith("i:")) data.put(k, Integer.valueOf(Integer.parseInt(raw.substring(2))));
                    else if (raw.startsWith("l:")) data.put(k, Long.valueOf(Long.parseLong(raw.substring(2))));
                    else if (raw.startsWith("f:")) data.put(k, Float.valueOf(Float.parseFloat(raw.substring(2))));
                    else if (raw.startsWith("b:")) data.put(k, Boolean.valueOf(Boolean.parseBoolean(raw.substring(2))));
                }
            } catch (Throwable ignored) {
            }
        }

        private void flushToDisk() {
            if (storeFile == null) return;
            try {
                File parent = storeFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                Properties p = new Properties();
                for (Map.Entry<String, Object> e : data.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String) p.setProperty(e.getKey(), "s:" + v);
                    else if (v instanceof Integer) p.setProperty(e.getKey(), "i:" + v);
                    else if (v instanceof Long) p.setProperty(e.getKey(), "l:" + v);
                    else if (v instanceof Float) p.setProperty(e.getKey(), "f:" + v);
                    else if (v instanceof Boolean) p.setProperty(e.getKey(), "b:" + v);
                }
                try (FileOutputStream fos = new FileOutputStream(storeFile)) {
                    p.store(fos, null);
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public synchronized Map<String, ?> getAll() {
            return new HashMap<String, Object>(data);
        }

        @Override
        public synchronized String getString(String key, String defValue) {
            Object v = data.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = data.get(key);
            return v instanceof Set ? (Set<String>) v : defValues;
        }

        @Override
        public synchronized int getInt(String key, int defValue) {
            Object v = data.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public synchronized long getLong(String key, long defValue) {
            Object v = data.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public synchronized float getFloat(String key, float defValue) {
            Object v = data.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public synchronized boolean getBoolean(String key, boolean defValue) {
            Object v = data.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public synchronized boolean contains(String key) {
            return data.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> puts = new HashMap<String, Object>();
                private final java.util.List<String> removes = new java.util.ArrayList<String>();
                private boolean clearAll = false;

                @Override public Editor putString(String k, String v) { puts.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { puts.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { puts.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { puts.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { puts.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { puts.put(k, v); return this; }
                @Override public Editor remove(String k) { removes.add(k); puts.remove(k); return this; }
                @Override public Editor clear() { clearAll = true; puts.clear(); removes.clear(); return this; }
                @Override public boolean commit() {
                    String failPrefix = sFailCommitForPrefPrefix;
                    if (failPrefix != null && prefName != null && prefName.startsWith(failPrefix)) {
                        return false;
                    }
                    synchronized (PersistentPrefs.this) {
                        if (clearAll) data.clear();
                        for (String r : removes) data.remove(r);
                        data.putAll(puts);
                        flushToDisk();
                    }
                    return true;
                }
                @Override public void apply() { commit(); }
            };
        }

        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    }
}
"""

SHIM_VIEW = r"""
package android.view;

import android.content.Context;

public class View {
    private final Context ctx;

    public View() {
        this.ctx = null;
    }

    public View(Context ctx) {
        this.ctx = ctx;
    }

    public Context getContext() {
        return ctx;
    }
}
"""

SHIM_SURFACE = r"""
package android.view;

public class Surface {
}
"""

SHIM_SURFACE_HOLDER = r"""
package android.view;

public interface SurfaceHolder {
}
"""

SHIM_XPOSED_HOOK = r"""
package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;

public abstract class XC_MethodHook {
    public static final int PRIORITY_DEFAULT = 50;

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static void invokeBefore(XC_MethodHook hook, MethodHookParam param) throws Throwable {
        hook.beforeHookedMethod(param);
    }

    public static void invokeAfter(XC_MethodHook hook, MethodHookParam param) throws Throwable {
        hook.afterHookedMethod(param);
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;
        private Map<String, Object> extras;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        public Object getObjectExtra(String key) {
            return extras != null ? extras.get(key) : null;
        }

        public void setObjectExtra(String key, Object extra) {
            if (extras == null) extras = new HashMap<String, Object>();
            extras.put(key, extra);
        }
    }

    public final class Unhook {
        public Member getHookedMethod() {
            return null;
        }

        public void unhook() {
        }
    }
}
"""

SHIM_CONTENT_RESOLVER = r"""
package android.content;

public class ContentResolver {
}
"""

SHIM_BITMAP = r"""
package android.graphics;

import java.io.OutputStream;

public class Bitmap {
    public enum Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE
    }

    public enum CompressFormat {
        JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS
    }

    private final int width;
    private final int height;
    private boolean recycled;

    public Bitmap() {
        this(1, 1);
    }

    public Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isRecycled() {
        return recycled;
    }

    public void recycle() {
        recycled = true;
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (stream == null || recycled) return false;
        try {
            byte[] hdr = new byte[]{
                    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                    (byte) ((width >> 8) & 0xFF), (byte) (width & 0xFF),
                    (byte) ((height >> 8) & 0xFF), (byte) (height & 0xFF)
            };
            stream.write(hdr);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height) {
        return new Bitmap(Math.max(1, width), Math.max(1, height));
    }
}
"""

SHIM_BITMAP_FACTORY = r"""
package android.graphics;

import java.io.FileInputStream;
import java.io.InputStream;

public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds;
        public int outWidth;
        public int outHeight;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig;
    }

    public static Bitmap decodeFile(String pathName) {
        return decodeFile(pathName, null);
    }

    public static Bitmap decodeFile(String pathName, Options opts) {
        if (opts != null) {
            opts.outWidth = -1;
            opts.outHeight = -1;
        }
        if (pathName == null) return null;
        try (FileInputStream fis = new FileInputStream(pathName)) {
            return decodeStream(fis, null, opts);
        } catch (Throwable ignored) {}
        return null;
    }

    public static Bitmap decodeStream(InputStream is) {
        return decodeStream(is, null, null);
    }

    public static Bitmap decodeStream(InputStream is, Object outPadding, Options opts) {
        if (opts != null) {
            opts.outWidth = -1;
            opts.outHeight = -1;
        }
        if (is == null) return null;
        try {
            byte[] hdr = new byte[12];
            int n = is.read(hdr);
            if (n >= 3 && (hdr[0] & 0xFF) == 0xFF && (hdr[1] & 0xFF) == 0xD8 && (hdr[2] & 0xFF) == 0xFF) {
                if (opts != null) { opts.outWidth = 1080; opts.outHeight = 2400; }
                return new Bitmap(1080, 2400);
            }
            if (n >= 4 && hdr[0] == (byte) 0x89 && hdr[1] == 'P' && hdr[2] == 'N' && hdr[3] == 'G') {
                int w = (n >= 12) ? (((hdr[8] & 0xFF) << 8) | (hdr[9] & 0xFF)) : 1080;
                int h = (n >= 12) ? (((hdr[10] & 0xFF) << 8) | (hdr[11] & 0xFF)) : 2400;
                if (w <= 0) w = 1080;
                if (h <= 0) h = 2400;
                if (opts != null) { opts.outWidth = w; opts.outHeight = h; }
                return new Bitmap(w, h);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
"""

SHIM_JSON_EXCEPTION = r"""
package org.json;

public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }

    public JSONException(Throwable cause) {
        super(cause);
    }
}
"""

SHIM_JSON_ARRAY = r"""
package org.json;

import java.util.ArrayList;
import java.util.List;

public class JSONArray {
    final List<Object> list = new ArrayList<Object>();

    public JSONArray() {
    }

    public JSONArray(String json) throws JSONException {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof JSONArray)) {
            throw new JSONException("Not a JSONArray");
        }
        list.addAll(((JSONArray) parsed).list);
    }

    public int length() {
        return list.size();
    }

    public JSONArray put(Object value) {
        list.add(value == null ? JSONObject.NULL : value);
        return this;
    }

    public JSONArray put(int value) {
        list.add(Integer.valueOf(value));
        return this;
    }

    public JSONArray put(long value) {
        list.add(Long.valueOf(value));
        return this;
    }

    public JSONArray put(boolean value) {
        list.add(Boolean.valueOf(value));
        return this;
    }

    public Object opt(int index) {
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    public int optInt(int index) {
        return optInt(index, 0);
    }

    public int optInt(int index, int fallback) {
        Object v = opt(index);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null || v == JSONObject.NULL) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        if (v == null || v == JSONObject.NULL) return fallback;
        return String.valueOf(v);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        return (v instanceof JSONObject) ? (JSONObject) v : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(JsonCodec.stringify(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    public String toString(int indentFactor) {
        return JsonCodec.pretty(this, indentFactor, 0);
    }
}
"""

SHIM_JSON_OBJECT = r"""
package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject {
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    final Map<String, Object> map = new LinkedHashMap<String, Object>();

    public JSONObject() {
    }

    public JSONObject(String json) throws JSONException {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("Not a JSONObject");
        }
        map.putAll(((JSONObject) parsed).map);
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, value == null ? NULL : value);
        return this;
    }

    public JSONObject put(String name, int value) throws JSONException {
        return put(name, Integer.valueOf(value));
    }

    public JSONObject put(String name, long value) throws JSONException {
        return put(name, Long.valueOf(value));
    }

    public JSONObject put(String name, boolean value) throws JSONException {
        return put(name, Boolean.valueOf(value));
    }

    public JSONObject put(String name, double value) throws JSONException {
        return put(name, Double.valueOf(value));
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public Object opt(String name) {
        return map.get(name);
    }

    public String optString(String name) {
        return optString(name, "");
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        if (v == null || v == NULL) return fallback;
        return String.valueOf(v);
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return fallback;
    }

    public double optDouble(String name, double fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        return (v instanceof JSONObject) ? (JSONObject) v : null;
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        return (v instanceof JSONArray) ? (JSONArray) v : null;
    }

    public Iterator<String> keys() {
        return map.keySet().iterator();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(JsonCodec.quote(e.getKey())).append(":").append(JsonCodec.stringify(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    public String toString(int indentFactor) throws JSONException {
        return JsonCodec.pretty(this, indentFactor, 0);
    }
}
"""

SHIM_JSON_CODEC = r"""
package org.json;

import java.util.Map;

final class JsonCodec {
    private final String s;
    private int pos;

    private JsonCodec(String s) {
        this.s = s == null ? "" : s;
    }

    static Object parse(String raw) throws JSONException {
        if (raw == null) throw new JSONException("Null JSON");
        JsonCodec p = new JsonCodec(raw);
        p.skipWs();
        Object val = p.readValue();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw new JSONException("Trailing characters at " + p.pos);
        }
        return val;
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object readValue() throws JSONException {
        skipWs();
        if (pos >= s.length()) throw new JSONException("Unexpected EOF");
        char c = s.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' && s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (c == 'f' && s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (c == 'n' && s.startsWith("null", pos)) { pos += 4; return JSONObject.NULL; }
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw new JSONException("Unexpected char '" + c + "' at " + pos);
    }

    private JSONObject readObject() throws JSONException {
        pos++; // '{'
        JSONObject obj = new JSONObject();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw new JSONException("Expected key at " + pos);
            }
            String key = readString();
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw new JSONException("Expected ':' at " + pos);
            }
            pos++;
            Object val = readValue();
            obj.put(key, val);
            skipWs();
            if (pos >= s.length()) throw new JSONException("Unterminated object");
            char next = s.charAt(pos++);
            if (next == '}') return obj;
            if (next != ',') throw new JSONException("Expected ',' or '}' at " + (pos - 1));
        }
    }

    private JSONArray readArray() throws JSONException {
        pos++; // '['
        JSONArray arr = new JSONArray();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return arr;
        }
        while (true) {
            arr.put(readValue());
            skipWs();
            if (pos >= s.length()) throw new JSONException("Unterminated array");
            char next = s.charAt(pos++);
            if (next == ']') return arr;
            if (next != ',') throw new JSONException("Expected ',' or ']' at " + (pos - 1));
        }
    }

    private String readString() throws JSONException {
        pos++; // '"'
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= s.length()) throw new JSONException("Unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new JSONException("Invalid unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new JSONException("Invalid escape: " + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new JSONException("Unterminated string");
    }

    private Number readNumber() throws JSONException {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String raw = s.substring(start, pos);
        try {
            if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                return Double.valueOf(Double.parseDouble(raw));
            }
            long v = Long.parseLong(raw);
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) v);
            }
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            throw new JSONException(e);
        }
    }

    static String quote(String val) {
        if (val == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    static String stringify(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof JSONObject) return ((JSONObject) v).toString();
        if (v instanceof JSONArray) return ((JSONArray) v).toString();
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        return quote(String.valueOf(v));
    }

    static String pretty(Object v, int indent, int depth) {
        if (v instanceof JSONObject) {
            JSONObject obj = (JSONObject) v;
            if (obj.map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            int n = obj.map.size();
            for (Map.Entry<String, Object> e : obj.map.entrySet()) {
                indent(sb, indent * (depth + 1));
                sb.append(quote(e.getKey())).append(": ").append(pretty(e.getValue(), indent, depth + 1));
                if (++i < n) sb.append(",");
                sb.append("\n");
            }
            indent(sb, indent * depth).append("}");
            return sb.toString();
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            if (arr.list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            int n = arr.list.size();
            for (int i = 0; i < n; i++) {
                indent(sb, indent * (depth + 1));
                sb.append(pretty(arr.list.get(i), indent, depth + 1));
                if (i + 1 < n) sb.append(",");
                sb.append("\n");
            }
            indent(sb, indent * depth).append("]");
            return sb.toString();
        }
        return stringify(v);
    }

    private static StringBuilder indent(StringBuilder sb, int spaces) {
        for (int i = 0; i < spaces; i++) sb.append(' ');
        return sb;
    }
}
"""

HARNESS = r"""
import android.content.Context;
import android.util.Log;

import com.hyperalive.aod.HyperOsWallpaperHelper;
import com.hyperalive.aod.PreModuleWallpaperBackup;
import com.hyperalive.aod.Root;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class WallpaperBackupRegression {
    private static int assertions = 0;

    private static void check(String label, boolean cond) {
        assertions++;
        if (!cond) {
            throw new AssertionError("Assertion failed: " + label);
        }
    }

    private static void checkEq(String label, Object expected, Object actual) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static String readUtf8(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeUtf8(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static String extractKeyValSetting(String settingsText, String key) {
        String prefix = key + "=";
        for (String line : settingsText.split("\r?\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    private static final class MockGateway implements HyperOsWallpaperHelper.Gateway {
        final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        final Map<String, String> secureSettings = new LinkedHashMap<String, String>();
        final Map<String, String> systemSettings = new LinkedHashMap<String, String>();
        boolean forceXmlReadError = false;
        boolean forceSettingReadError = false;

        void putText(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
        }

        void putBytes(String path, byte[] data) {
            files.put(path, data);
        }

        @Override
        public int probeFile(String path) {
            byte[] b = files.get(path);
            if (b == null) return 0;
            return b.length > 0 ? 1 : -1;
        }

        @Override
        public HyperOsWallpaperHelper.TextReadResult readTextFile(String path) {
            if (forceXmlReadError && path.endsWith(".xml")) {
                return new HyperOsWallpaperHelper.TextReadResult(-1, null);
            }
            byte[] b = files.get(path);
            if (b == null) return new HyperOsWallpaperHelper.TextReadResult(0, null);
            return new HyperOsWallpaperHelper.TextReadResult(1, new String(b, StandardCharsets.UTF_8));
        }

        @Override
        public HyperOsWallpaperHelper.SettingEntry readSetting(String table, String key) {
            if (forceSettingReadError) {
                return new HyperOsWallpaperHelper.SettingEntry(false, false, "");
            }
            Map<String, String> src = "system".equals(table) ? systemSettings : secureSettings;
            if (!src.containsKey(key)) {
                return new HyperOsWallpaperHelper.SettingEntry(true, false, "");
            }
            String v = src.get(key);
            return new HyperOsWallpaperHelper.SettingEntry(true, true, v == null ? "" : v);
        }
    }

    public static void main(String[] args) throws Exception {
        File evidenceDir = new File(args[0]);
        File workRoot = new File(args[1]);

        String oemHomeXml = readUtf8(new File(evidenceDir, "oem_home.txt"));
        String oemLockXml = readUtf8(new File(evidenceDir, "oem_lock.txt"));
        String settingsTxt = readUtf8(new File(evidenceDir, "settings.txt"));
        String editorJsonRaw = extractKeyValSetting(settingsTxt, "constant_template_editor_info");
        String aodCategoryRaw = extractKeyValSetting(settingsTxt, "aod_category_name");
        String linkageRaw = extractKeyValSetting(settingsTxt, "Linkage");
        String imageBgRaw = extractKeyValSetting(settingsTxt, "image_bg_selector");
        check("captured constant_template_editor_info exists", editorJsonRaw != null && editorJsonRaw.contains("rhombus"));
        check("captured aod_category_name exists", "Linkage".equals(aodCategoryRaw));
        check("captured Linkage JSON exists", linkageRaw != null && linkageRaw.contains("\"name\":\"Linkage\""));
        check("captured image_bg_selector JSON exists", imageBgRaw != null && imageBgRaw.contains("\"name\":\"image_bg_selector\""));

        // 1. Real HyperOS 4 OEM fixture verification
        Log.clearEntries();
        MockGateway gw = new MockGateway();
        gw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml", oemHomeXml);
        gw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml", oemLockXml);
        byte[] fakeHomeJpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
        byte[] fakeLockJpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 5, 6, 7, 8};
        byte[] fakePng = new byte[]{(byte) 0x89, 'P', 'N', 'G', 10, 11, 12, 13};
        gw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", fakeHomeJpg);
        gw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", fakeLockJpg);
        gw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home", fakePng);
        gw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper", fakePng);
        gw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/subject_mask", fakePng);
        // Live HyperOS4 device puts aod_category_name, Linkage, image_bg_selector in secure (system returns null)
        gw.secureSettings.put("constant_template_editor_info", editorJsonRaw);
        gw.secureSettings.put("aod_category_name", aodCategoryRaw);
        gw.secureSettings.put("Linkage", linkageRaw);
        gw.secureSettings.put("image_bg_selector", imageBgRaw);

        HyperOsWallpaperHelper.OemProbeResult res = HyperOsWallpaperHelper.probeAndValidateOemState(0, gw);
        checkEq("real HyperOS4 fixture classified as OEM_CUSTOM_IMAGE",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, res.mode);
        checkEq("clock template extracted", "rhombus", res.clockTemplateId);
        check("captured home image + lock image + template files", res.filesToBackup.size() >= 5);
        HyperOsWallpaperHelper.SettingEntry secCat = res.secureSettings.get("aod_category_name");
        HyperOsWallpaperHelper.SettingEntry secLinkage = res.secureSettings.get("Linkage");
        HyperOsWallpaperHelper.SettingEntry secImgBg = res.secureSettings.get("image_bg_selector");
        check("secure aod_category_name captured from real fixture",
                secCat != null && secCat.ok && secCat.existed && "Linkage".equals(secCat.value));
        check("secure Linkage captured from real fixture",
                secLinkage != null && secLinkage.ok && secLinkage.existed && linkageRaw.equals(secLinkage.value));
        check("secure image_bg_selector captured from real fixture",
                secImgBg != null && secImgBg.ok && secImgBg.existed && imageBgRaw.equals(secImgBg.value));
        HyperOsWallpaperHelper.SettingEntry sysCat = res.systemSettings.get("aod_category_name");
        HyperOsWallpaperHelper.SettingEntry sysLinkage = res.systemSettings.get("Linkage");
        check("system compat read for aod_category_name tolerates null",
                sysCat != null && sysCat.ok && !sysCat.existed);
        check("system compat read for Linkage tolerates null",
                sysLinkage != null && sysLinkage.ok && !sysLinkage.existed);

        // Verify diagnostic logs never contain sensitive paths or JSON body
        for (String logLine : Log.ENTRIES) {
            check("no raw wallpaper path in log: " + logLine, !logLine.contains("home_wallpaper.jpg"));
            check("no raw editor JSON in log: " + logLine, !logLine.contains("matrixArr"));
        }

        // 2. Negative / Ambiguous probes
        // 2a. Missing home.xml with leftover JPG -> AMBIGUOUS_OR_UNVERIFIED
        MockGateway missingXml = new MockGateway();
        missingXml.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml", oemLockXml);
        missingXml.putBytes("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", fakeHomeJpg);
        missingXml.putBytes("/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", fakeLockJpg);
        checkEq("missing home.xml with leftover jpg must abort",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, missingXml).mode);

        // 2b. Path traversal in path0 -> AMBIGUOUS_OR_UNVERIFIED
        MockGateway traversalGw = new MockGateway();
        traversalGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml",
                oemHomeXml.replace("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg",
                        "/data/system/theme_magic/users/0/wallpaper/image/../../../etc/passwd"));
        traversalGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml", oemLockXml);
        traversalGw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", fakeLockJpg);
        traversalGw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home", fakePng);
        traversalGw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper", fakePng);
        traversalGw.secureSettings.put("constant_template_editor_info", editorJsonRaw);
        checkEq("path traversal in home.xml must abort",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, traversalGw).mode);

        // 2c. Root read error on XML -> AMBIGUOUS_OR_UNVERIFIED
        gw.forceXmlReadError = true;
        checkEq("XML read error must abort",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, gw).mode);
        gw.forceXmlReadError = false;

        // 2d. Contradictory flag_lock_wallpaper_type vs lock=image & unsupported video type -> AMBIGUOUS_OR_UNVERIFIED
        MockGateway mismatchGw = new MockGateway();
        mismatchGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml", oemHomeXml);
        mismatchGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml", oemLockXml);
        mismatchGw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", fakeHomeJpg);
        mismatchGw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", fakeLockJpg);
        mismatchGw.secureSettings.put("flag_lock_wallpaper_type", "video");
        checkEq("flag_lock_wallpaper_type vs lock=image contradiction must abort",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, mismatchGw).mode);

        MockGateway videoGw = new MockGateway();
        videoGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml",
                "<wallpaper_data which=\"1\" type=\"video\" component=\"" + HyperOsWallpaperHelper.MIUI_IMAGE_COMPONENT_FULL + "\" />");
        videoGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml",
                "<wallpaper_data which=\"2\" type=\"default\" component=\"" + HyperOsWallpaperHelper.MIUI_IMAGE_COMPONENT_FULL + "\" />");
        checkEq("unsupported OEM video type must abort",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, videoGw).mode);

        // Single-sided home=image + lock=default is valid OEM_CUSTOM_IMAGE
        MockGateway singleSideGw = new MockGateway();
        singleSideGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml", oemHomeXml);
        singleSideGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml",
                "<wallpaper_data which=\"2\" type=\"default\" component=\"" + HyperOsWallpaperHelper.MIUI_IMAGE_COMPONENT_FULL + "\" />");
        singleSideGw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", fakeHomeJpg);
        HyperOsWallpaperHelper.OemProbeResult singleRes = HyperOsWallpaperHelper.probeAndValidateOemState(0, singleSideGw);
        checkEq("single-side home=image + lock=default supported",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, singleRes.mode);
        checkEq("single-side homeType", "image", singleRes.homeType);
        checkEq("single-side lockType", "default", singleRes.lockType);

        // 2e. Genuine confirmed default XML (type="default" attribute on both, no editor image contradiction)
        MockGateway defaultGw = new MockGateway();
        defaultGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml",
                "<wallpaper_data which=\"1\" type=\"default\" component=\"" + HyperOsWallpaperHelper.MIUI_IMAGE_COMPONENT_FULL + "\" />");
        defaultGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml",
                "<wallpaper_data which=\"2\" type=\"default\" component=\"" + HyperOsWallpaperHelper.MIUI_IMAGE_COMPONENT_FULL + "\" />");
        checkEq("genuine default XML confirmed",
                HyperOsWallpaperHelper.Mode.CONFIRMED_DEFAULT,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, defaultGw).mode);

        // 2f. Malformed XML without type attribute -> AMBIGUOUS_OR_UNVERIFIED
        MockGateway malformedGw = new MockGateway();
        malformedGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml",
                "<wallpaper_data><type>default</type></wallpaper_data>");
        malformedGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml",
                "<wallpaper_data><type>default</type></wallpaper_data>");
        checkEq("missing type attribute rejected",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.probeAndValidateOemState(0, malformedGw).mode);

        // 3. Root.isSetWallSuccess verification
        check("accepts single SETWALL OK",
                Root.isSetWallSuccess("SETWALL OK which=3 cn=com.miui.aod/.Test"));
        check("accepts dual SETWALL OK",
                Root.isSetWallSuccess("SETWALL OK which=3 cn=a/.b ; OK which=1 cn=a/.c ; OK which=2 cn=a/.b"));
        check("accepts lowercase SETWALL ok",
                Root.isSetWallSuccess("SETWALL ok which=1 cn=a/.b"));
        check("rejects partial sub-segment ERR",
                !Root.isSetWallSuccess("SETWALL OK which=3 cn=a/.b ; ERR which=1 failed ; OK which=2 cn=a/.b"));
        check("rejects ERR prefix", !Root.isSetWallSuccess("ERR su failed"));
        check("rejects empty/null", !Root.isSetWallSuccess(null) && !Root.isSetWallSuccess("   "));

        // 4. Production PreModuleWallpaperBackup lifecycle, OEM integrity, corruption & locking
        File filesDir = new File(workRoot, "app_files");
        filesDir.mkdirs();
        Context ctx = new Context(filesDir);
        File userDir = new File(filesDir, "pre_module_backup/user_0");

        // 4a. Initially NONE; restoreBackupSync fails cleanly with zero writes
        checkEq("initial state NONE",
                PreModuleWallpaperBackup.BackupState.NONE, PreModuleWallpaperBackup.getBackupState(ctx));
        PreModuleWallpaperBackup.RestoreResult noBackupRes = PreModuleWallpaperBackup.restoreBackupSync(ctx);
        check("no-backup restore rejected", !noBackupRes.success);
        check("no-backup restore did not create backup dir", !userDir.exists());

        // 4b. Populated dir without meta.json (including nested oem/ subdir) -> CORRUPTED (not NONE!)
        File orphanFile = new File(userDir, "oem/tpl_wallpaper");
        writeUtf8(orphanFile, "partial");
        checkEq("non-empty leftover without meta is CORRUPTED",
                PreModuleWallpaperBackup.BackupState.CORRUPTED,
                PreModuleWallpaperBackup.getBackupState(ctx));
        PreModuleWallpaperBackup.RestoreResult corruptedRes = PreModuleWallpaperBackup.restoreBackupSync(ctx);
        check("corrupted restore refuses writes", !corruptedRes.success);
        check("corrupted files preserved on failure", orphanFile.exists());

        // 4c. clearBackup recursively cleans nested oem/ subdir -> NONE
        PreModuleWallpaperBackup.clearBackup(ctx);
        check("userDir removed cleanly", !userDir.exists());
        checkEq("state after clear is NONE",
                PreModuleWallpaperBackup.BackupState.NONE,
                PreModuleWallpaperBackup.getBackupState(ctx));

        // 4d. Commit valid v2 OEM_HYPEROS snapshot matching production stageOemCustomPayload schema
        File oemDir = new File(userDir, "oem");
        oemDir.mkdirs();
        JSONArray oemFilesArr = new JSONArray();
        for (HyperOsWallpaperHelper.OemFileSpec spec : res.filesToBackup) {
            File dest = new File(oemDir, spec.relName);
            writeBytes(dest, gw.files.get(spec.sourcePath));
            JSONObject fObj = new JSONObject();
            fObj.put("rel_name", spec.relName);
            fObj.put("source_path", spec.sourcePath);
            fObj.put("size", dest.length());
            fObj.put("sha256", HyperOsWallpaperHelper.sha256Hex(dest));
            fObj.put("is_image", spec.imageFile);
            oemFilesArr.put(fObj);
        }
        JSONObject oemObj = new JSONObject();
        oemObj.put("home_type", res.homeType);
        oemObj.put("lock_type", res.lockType);
        oemObj.put("clock_template_id", res.clockTemplateId);
        oemObj.put("files", oemFilesArr);
        JSONObject settingsObj = new JSONObject();
        settingsObj.put("secure", new JSONObject());
        settingsObj.put("system", new JSONObject());
        oemObj.put("settings", settingsObj);

        JSONObject metaJson = new JSONObject();
        metaJson.put("version", 2);
        metaJson.put("userId", 0);
        metaJson.put("backupTime", System.currentTimeMillis());
        metaJson.put("enter_ambient_mode_existed", false);
        metaJson.put("enter_ambient_mode_val", "");
        metaJson.put("alive_wallpaper_position_existed", true);
        metaJson.put("alive_wallpaper_position_val", "");
        metaJson.put("system_type", "OEM_HYPEROS");
        metaJson.put("system_pkg", "");
        metaJson.put("system_cls", "");
        metaJson.put("system_file", "");
        metaJson.put("lock_type", "OEM_HYPEROS");
        metaJson.put("lock_pkg", "");
        metaJson.put("lock_cls", "");
        metaJson.put("lock_file", "");
        metaJson.put("oem", oemObj);
        metaJson.put("valid", true);
        writeUtf8(new File(userDir, "meta.json"), metaJson.toString(2));

        PreModuleWallpaperBackup.BackupInspection insp = PreModuleWallpaperBackup.inspectBackupLocked(ctx);
        checkEq("OEM backup VALID via inspectBackupLocked",
                PreModuleWallpaperBackup.BackupState.VALID, insp.state);
        check("metadata parsed", insp.metadata != null);
        checkEq("metadata systemType is OEM_HYPEROS", "OEM_HYPEROS", insp.metadata.systemType);
        checkEq("metadata preserves rhombus template", "rhombus", insp.metadata.oemClockTemplateId);
        check("metadata distinguishes absent vs empty setting",
                !insp.metadata.enterAmbientExisted && insp.metadata.alivePosExisted && "".equals(insp.metadata.alivePosValue));

        // Pre-check aborts cleanly before any writes when ROOT is unavailable in offline env, retaining snapshot VALID
        PreModuleWallpaperBackup.RestoreResult oemPrecheck = PreModuleWallpaperBackup.restoreBackupSync(ctx);
        check("OEM_HYPEROS restore pre-check aborts safely without ROOT", !oemPrecheck.success && !oemPrecheck.partial);
        check("OEM_HYPEROS no-root pre-check explains ROOT requirement",
                oemPrecheck.message != null && oemPrecheck.message.contains("ROOT"));
        checkEq("OEM snapshot retained after pre-check abort",
                PreModuleWallpaperBackup.BackupState.VALID, PreModuleWallpaperBackup.getBackupState(ctx));

        // 4e. SHA-256 tamper detection -> CORRUPTED
        File firstOemFile = new File(oemDir, res.filesToBackup.get(0).relName);
        writeUtf8(firstOemFile, "TAMPERED");
        checkEq("tampered OEM payload detected as CORRUPTED",
                PreModuleWallpaperBackup.BackupState.CORRUPTED,
                PreModuleWallpaperBackup.getBackupState(ctx));

        // Cleanup resets cleanly to NONE for next apply cycle
        PreModuleWallpaperBackup.clearBackup(ctx);
        checkEq("ready for next apply lifecycle (NONE)",
                PreModuleWallpaperBackup.BackupState.NONE,
                PreModuleWallpaperBackup.getBackupState(ctx));

        // 5. TX_LOCK concurrency check: inspectBackupLocked must block while lockTransaction is held
        PreModuleWallpaperBackup.lockTransaction();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<PreModuleWallpaperBackup.BackupState> asyncState =
                new AtomicReference<PreModuleWallpaperBackup.BackupState>();
        Thread t = new Thread(() -> {
            started.countDown();
            PreModuleWallpaperBackup.BackupInspection bi = PreModuleWallpaperBackup.inspectBackupLocked(ctx);
            asyncState.set(bi.state);
            finished.countDown();
        });
        t.start();
        check("worker thread started", started.await(2, TimeUnit.SECONDS));
        check("inspectBackupLocked blocks while TX_LOCK is held", !finished.await(150, TimeUnit.MILLISECONDS));
        PreModuleWallpaperBackup.unlockTransaction();
        check("inspectBackupLocked completes after unlock", finished.await(2, TimeUnit.SECONDS));
        checkEq("post-unlock state matches", PreModuleWallpaperBackup.BackupState.NONE, asyncState.get());

        // 6. Phase 2 Objective C & D & A & R5 checks
        // 6a. Tightened Root.isSetWallSuccess must reject prior exceptions/warnings/partial errors
        check("accepts valid single SETWALL OK",
                Root.isSetWallSuccess("SETWALL OK which=3 cn=com.flyme.alivewallpaper/.Alive"));
        check("accepts valid dual SETWALL OK",
                Root.isSetWallSuccess("SETWALL OK which=3 cn=a/b ; OK which=1 cn=a/c ; OK which=2 cn=a/b"));
        check("accepts valid SETWALL OK clear",
                Root.isSetWallSuccess("SETWALL OK clear which=3"));
        check("rejects SecurityException followed by SETWALL OK",
                !Root.isSetWallSuccess("java.lang.SecurityException: denied\nSETWALL OK which=3 cn=a/b"));
        check("rejects stacktrace followed by SETWALL OK",
                !Root.isSetWallSuccess("at android.os.Binder.transact(Binder.java:1)\nSETWALL OK which=1 cn=a/b"));
        check("rejects suRaw failure followed by OK",
                !Root.isSetWallSuccess("suRaw \u5931\u8d25: exit=1\nOK which=1 cn=a/b"));
        check("rejects partial ERR in multi-segment dual output",
                !Root.isSetWallSuccess("SETWALL OK which=3 cn=a/b ; OK which=1 cn=a/c ; ERR SecurityException"));
        check("rejects stray warning line before SETWALL OK",
                !Root.isSetWallSuccess("unexpected warning\nSETWALL OK which=3 cn=a/b"));

        // 6b. Safe rollback: only delete newly created backup when zero state changed; null baseline keeps backup
        com.hyperalive.aod.Phase2RegressionBridge.runAll();
        PreModuleWallpaperBackup.RollbackResult nullBaseRb =
                PreModuleWallpaperBackup.rollbackCreatedBackupIfUnapplied(ctx);
        check("null baseline conservatively retains backup", !nullBaseRb.deleted);

        // 6c. HyperOsWallpaperHelper.parseBridgeProbeOutput (OK, permission-denied, timeout)
        String okProbeRaw = "PROBE_OK {\"ok\":true,\"userId\":0,\"uid\":0,\"bindMethod\":\"bindServiceAsUser(int)\","
                + "\"slots\":{\"home\":{\"which\":1,\"type\":{\"ok\":true,\"returnedNull\":false,\"value\":\"image\"},"
                + "\"lastType\":{\"ok\":true,\"returnedNull\":false,\"value\":\"default\"},"
                + "\"defaultType\":{\"ok\":true,\"returnedNull\":false,\"value\":\"image\"},"
                + "\"isDefault\":{\"ok\":true,\"returnedNull\":false,\"value\":\"false\"},"
                + "\"componentUsing\":{\"ok\":true,\"returnedNull\":false,\"value\":\"true\"},"
                + "\"effectId\":{\"ok\":true,\"returnedNull\":false,\"value\":\"0\"},"
                + "\"blurState\":{\"ok\":true,\"returnedNull\":false,\"value\":\"false\"},"
                + "\"pathCall\":{\"ok\":true,\"returnedNull\":false,\"value\":\"/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg\"},"
                + "\"resolvedFile\":{\"isFile\":true,\"bytes\":1868520,\"sha256\":\"abc123\"}},"
                + "\"lock\":{\"which\":2,\"type\":{\"ok\":true,\"returnedNull\":false,\"value\":\"image\"},"
                + "\"lastType\":{\"ok\":true,\"returnedNull\":false,\"value\":\"default\"},"
                + "\"defaultType\":{\"ok\":true,\"returnedNull\":false,\"value\":\"image\"},"
                + "\"isDefault\":{\"ok\":true,\"returnedNull\":false,\"value\":\"false\"},"
                + "\"componentUsing\":{\"ok\":true,\"returnedNull\":false,\"value\":\"true\"},"
                + "\"effectId\":{\"ok\":true,\"returnedNull\":false,\"value\":\"0\"},"
                + "\"blurState\":{\"ok\":true,\"returnedNull\":false,\"value\":\"false\"},"
                + "\"pathCall\":{\"ok\":true,\"returnedNull\":false,\"value\":\"/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg\"},"
                + "\"resolvedFile\":{\"isFile\":true,\"bytes\":1868520,\"sha256\":\"def456\"}}},"
                + "\"wms\":{\"sysId\":1266,\"lockId\":-1,\"sysComp\":\"ComponentInfo{com.miui.miwallpaper/com.miui.miwallpaper.wallpaperservice.ImageWallpaper}\",\"lockComp\":\"null\"}}";
        HyperOsWallpaperHelper.BridgeProbeSummary okProbe =
                HyperOsWallpaperHelper.parseBridgeProbeOutput(okProbeRaw);
        check("probe ok parsed", okProbe.ok && !okProbe.hasPermissionGap());
        checkEq("probe home type", "image", okProbe.home.authoritativeType);
        check("probe home not default", okProbe.home.isDefaultOk && !okProbe.home.isDefault);
        checkEq("probe home sha256", "abc123", okProbe.home.resolvedSha256);
        checkEq("probe wms sysId", 1266, okProbe.wmsSysId);

        String permGapRaw = "PROBE_OK {\"ok\":true,\"userId\":0,\"uid\":0,\"bindMethod\":\"bindService(Executor)\","
                + "\"slots\":{\"home\":{\"which\":1,\"type\":{\"ok\":true,\"returnedNull\":true,\"value\":\"\"},"
                + "\"pathCall\":{\"ok\":false,\"returnedNull\":true,\"error\":\"SECURITY_EXCEPTION\"}},"
                + "\"lock\":{\"which\":2,\"type\":{\"ok\":true,\"returnedNull\":true,\"value\":\"\"},"
                + "\"pathCall\":{\"ok\":true,\"returnedNull\":true,\"value\":\"\"}}},\"wms\":{\"sysId\":1266,\"lockId\":-1}}";
        HyperOsWallpaperHelper.BridgeProbeSummary permGapProbe =
                HyperOsWallpaperHelper.parseBridgeProbeOutput(permGapRaw);
        check("probe permission gap flagged explicitly", permGapProbe.hasPermissionGap());

        String timeoutRaw = "PROBE_ERR {\"ok\":false,\"error\":\"BIND_TIMEOUT\",\"detail\":\"No callback\",\"userId\":0,\"uid\":0}";
        HyperOsWallpaperHelper.BridgeProbeSummary timeoutProbe =
                HyperOsWallpaperHelper.parseBridgeProbeOutput(timeoutRaw);
        check("probe timeout parsed as failure", !timeoutProbe.ok);
        checkEq("probe timeout error code", "BIND_TIMEOUT", timeoutProbe.error);

        // 7. R5: crossValidateWithBridge, buildBridgeRestoreSpecJson, parseBridgeRestoreOutput, whitelist & sha256sum
        String r5HomeSha = "70fb25f1b6f7f858aab8a1e10de8126204328db1f27bd30f96aa63da444ddabd";
        String r5LockSha = "70fb25f1b6f7f858aab8a1e10de8126204328db1f27bd30f96aa63da444ddabd";
        String okProbeLiveHomeLock = "PROBE_OK {\"ok\":true,\"userId\":0,\"uid\":0,\"bindMethod\":\"IAM:peekService(Intent,String,String)\","
                + "\"slots\":{\"home\":{\"which\":1,\"type\":{\"ok\":true,\"returnedNull\":false,\"value\":\"image\"},"
                + "\"isDefault\":{\"ok\":true,\"returnedNull\":false,\"value\":\"false\"},"
                + "\"componentUsing\":{\"ok\":true,\"returnedNull\":false,\"value\":\"true\"},"
                + "\"pathCall\":{\"ok\":true,\"returnedNull\":false,\"value\":\"/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg\"},"
                + "\"resolvedFile\":{\"isFile\":true,\"bytes\":1868520,\"sha256\":\"" + r5HomeSha + "\"}},"
                + "\"lock\":{\"which\":2,\"type\":{\"ok\":true,\"returnedNull\":false,\"value\":\"image\"},"
                + "\"isDefault\":{\"ok\":true,\"returnedNull\":false,\"value\":\"false\"},"
                + "\"componentUsing\":{\"ok\":true,\"returnedNull\":false,\"value\":\"true\"},"
                + "\"pathCall\":{\"ok\":true,\"returnedNull\":false,\"value\":\"/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg\"},"
                + "\"resolvedFile\":{\"isFile\":true,\"bytes\":1868520,\"sha256\":\"" + r5LockSha + "\"}}},"
                + "\"wms\":{\"sysId\":1266,\"lockId\":-1,\"sysComp\":\"null\",\"lockComp\":\"null\"}}";
        HyperOsWallpaperHelper.OemProbeResult crossOk = HyperOsWallpaperHelper.crossValidateWithBridge(
                res, HyperOsWallpaperHelper.parseBridgeProbeOutput(okProbeLiveHomeLock), 0);
        checkEq("crossValidateWithBridge accepts real probe_r4-style AIDL + FS match",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, crossOk.mode);
        checkEq("crossValidate preserves expected home sha256", r5HomeSha, crossOk.expectedHomeSha256);
        checkEq("crossValidate preserves expected lock sha256", r5LockSha, crossOk.expectedLockSha256);

        // Residual files on disk MUST be rejected if AIDL reports default or timeout or HyperAlive active
        String residualDefaultProbe = okProbeLiveHomeLock.replace("\"value\":\"image\"", "\"value\":\"default\"")
                .replace("\"value\":\"false\"", "\"value\":\"true\"");
        checkEq("crossValidate rejects residual XML when AIDL reports default",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        res, HyperOsWallpaperHelper.parseBridgeProbeOutput(residualDefaultProbe), 0).mode);
        checkEq("crossValidate rejects null/timeout bridge probe",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.crossValidateWithBridge(res, timeoutProbe, 0).mode);
        String hyperAliveWmsProbe = okProbeLiveHomeLock.replace("\"sysComp\":\"null\"",
                "\"sysComp\":\"ComponentInfo{com.hyperalive.aod/com.hyperalive.aod.QihangWallpaper}\"");
        checkEq("crossValidate rejects when WMS already bound to HyperAlive",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        res, HyperOsWallpaperHelper.parseBridgeProbeOutput(hyperAliveWmsProbe), 0).mode);
        String splitLockProbe = okProbeLiveHomeLock.replace("\"lockId\":-1", "\"lockId\":1299");
        checkEq("crossValidate rejects unexpected independent WMS lockId for dual image OEM",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        res, HyperOsWallpaperHelper.parseBridgeProbeOutput(splitLockProbe), 0).mode);

        // CONFIRMED_DEFAULT cross-validation
        HyperOsWallpaperHelper.OemProbeResult defFs = HyperOsWallpaperHelper.probeAndValidateOemState(0, defaultGw);
        checkEq("clean FS detected as CONFIRMED_DEFAULT",
                HyperOsWallpaperHelper.Mode.CONFIRMED_DEFAULT, defFs.mode);
        String okDefaultProbe = "PROBE_OK {\"ok\":true,\"userId\":0,\"uid\":0,\"bindMethod\":\"IAM:peekService(Intent,String,String)\","
                + "\"slots\":{\"home\":{\"which\":1,\"type\":{\"ok\":true,\"returnedNull\":false,\"value\":\"default\"},"
                + "\"isDefault\":{\"ok\":true,\"returnedNull\":false,\"value\":\"true\"},\"pathCall\":{\"ok\":true,\"returnedNull\":false,\"value\":\"\"}},"
                + "\"lock\":{\"which\":2,\"type\":{\"ok\":true,\"returnedNull\":false,\"value\":\"default\"},"
                + "\"isDefault\":{\"ok\":true,\"returnedNull\":false,\"value\":\"true\"},\"pathCall\":{\"ok\":true,\"returnedNull\":false,\"value\":\"\"}}},"
                + "\"wms\":{\"sysId\":1266,\"lockId\":-1,\"sysComp\":\"null\",\"lockComp\":\"null\"}}";
        checkEq("crossValidate accepts matching AIDL default",
                HyperOsWallpaperHelper.Mode.CONFIRMED_DEFAULT,
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        defFs, HyperOsWallpaperHelper.parseBridgeProbeOutput(okDefaultProbe), 0).mode);
        checkEq("crossValidate rejects CONFIRMED_DEFAULT when AIDL still reports image",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        defFs, HyperOsWallpaperHelper.parseBridgeProbeOutput(okProbeLiveHomeLock), 0).mode);

        // buildBridgeRestoreSpecJson on real oem_home.txt + oem_lock.txt evidence
        HyperOsWallpaperHelper.WallpaperDataXml hXml = HyperOsWallpaperHelper.parseWallpaperDataXml(oemHomeXml);
        HyperOsWallpaperHelper.WallpaperDataXml lXml = HyperOsWallpaperHelper.parseWallpaperDataXml(oemLockXml);
        check("parsed home+lock XML from device evidence", hXml != null && lXml != null);
        checkEq("parsed lock clockStyleType", "rhombus", lXml.clockStyleType);
        check("parsed clockTypeInfo [4, 104]",
                lXml.clockTypeInfo != null && lXml.clockTypeInfo.length == 2
                        && lXml.clockTypeInfo[0] == 4 && lXml.clockTypeInfo[1] == 104);
        JSONObject specJson = HyperOsWallpaperHelper.buildBridgeRestoreSpecJson(
                0, hXml, lXml,
                "/tmp/home_wallpaper.jpg", r5HomeSha,
                "/tmp/lock_wallpaper.jpg", r5LockSha,
                "", "com.miui.aod", "com.miui.aod");
        checkEq("restore spec preserves clockStyleType", "rhombus", specJson.optString("clockStyleType", ""));
        checkEq("restore spec filters pendingPackage against PackageUtils whitelist (never com.hyperalive.aod)",
                "com.miui.aod", specJson.optString("pendingPackage", ""));
        checkEq("restore spec enableBlur=0", 0, specJson.optInt("enableBlur", -99));
        checkEq("restore spec effectId=0", 0, specJson.optInt("effectId", -99));
        check("restore spec supportMatting=true", specJson.optBoolean("supportMatting", false));
        checkEq("restore spec originBitmapWidth=1920", 1920, specJson.optInt("originBitmapWidth", 0));
        checkEq("restore spec originBitmapHeight=2806", 2806, specJson.optInt("originBitmapHeight", 0));

        // Unwhitelisted pendingPackage must fallback safely to com.miui.aod
        JSONObject specFallbackPkg = HyperOsWallpaperHelper.buildBridgeRestoreSpecJson(
                0, hXml, hXml,
                "/tmp/home_wallpaper.jpg", r5HomeSha,
                "/tmp/lock_wallpaper.jpg", r5LockSha,
                "", "com.hyperalive.aod", "");
        checkEq("fallback pendingPackage when input not whitelisted",
                "com.miui.aod", specFallbackPkg.optString("pendingPackage", ""));
        checkEq("literal null clockStyleType sanitized to empty string",
                "", specFallbackPkg.optString("clockStyleType", "X"));

        // parseBridgeRestoreOutput checks
        HyperOsWallpaperHelper.BridgeRestoreResult restOk =
                HyperOsWallpaperHelper.parseBridgeRestoreOutput(
                        "RESTORE_OK {\"ok\":true,\"targetWhich\":3,\"verification\":{\"converged\":true,\"reason\":\"ok\"}}");
        check("parseBridgeRestoreOutput accepts converged RESTORE_OK", restOk.ok && restOk.converged && restOk.targetWhich == 3);
        HyperOsWallpaperHelper.BridgeRestoreResult restUnconverged =
                HyperOsWallpaperHelper.parseBridgeRestoreOutput(
                        "RESTORE_OK {\"ok\":true,\"targetWhich\":3,\"verification\":{\"converged\":false,\"reason\":\"timeout\"}}");
        check("parseBridgeRestoreOutput rejects unconverged", !restUnconverged.ok);
        HyperOsWallpaperHelper.BridgeRestoreResult restErr =
                HyperOsWallpaperHelper.parseBridgeRestoreOutput(
                        "RESTORE_ERR {\"ok\":false,\"error\":\"RESTORE_SECURITY_EXCEPTION\",\"detail\":\"denied\"}");
        check("parseBridgeRestoreOutput parses RESTORE_ERR",
                !restErr.ok && "RESTORE_SECURITY_EXCEPTION".equals(restErr.error));

        // Template whitelist path validation
        check("accepts valid AOD template path",
                HyperOsWallpaperHelper.isWhitelistTemplatePath("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home", 0));
        check("rejects traversal template path",
                !HyperOsWallpaperHelper.isWhitelistTemplatePath("/data/user_de/0/com.miui.aod/files/templates/current/../secret", 0));
        check("rejects cross-user template path",
                !HyperOsWallpaperHelper.isWhitelistTemplatePath("/data/user_de/10/com.miui.aod/files/templates/current/wallpaper_home", 0));

        // Phase2RegressionBridge extra check for verifySha256SumOutput
        check("sha256sum verification accepts matching hash",
                com.hyperalive.aod.Phase2RegressionBridge.checkSha256SumHelper(
                        r5HomeSha + "  /data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home\n__EXIT=0\n",
                        r5HomeSha));
        check("sha256sum verification rejects nonzero exit",
                !com.hyperalive.aod.Phase2RegressionBridge.checkSha256SumHelper(
                        r5HomeSha + "  /tmp/x\n__EXIT=1\n", r5HomeSha));

        // 7. Legacy DEFAULT (v1 / missing OEM proof) conflict pre-check using real device_test_185102 fixture
        File dev1851Dir = new File(evidenceDir, "device_test_185102");
        String meta1851Raw = readUtf8(new File(dev1851Dir, "metadata.txt"));
        int xmlMarker = meta1851Raw.indexOf("<?xml");
        String legacyMetaJsonStr = (xmlMarker > 0 ? meta1851Raw.substring(0, xmlMarker) : meta1851Raw).trim();
        String probe1851Raw = readUtf8(new File(dev1851Dir, "probe.txt")).trim();
        String settings1851Txt = readUtf8(new File(dev1851Dir, "settings.txt"));

        File legacyAppFiles = new File(workRoot, "legacy_185102_files");
        Context legacyCtx = new Context(legacyAppFiles);
        File legacyMetaFile = new File(legacyAppFiles, "pre_module_backup/user_0/meta.json");
        writeUtf8(legacyMetaFile, legacyMetaJsonStr);
        String legacyMetaBeforeSha = HyperOsWallpaperHelper.sha256Hex(legacyMetaFile);

        checkEq("legacy v1 DEFAULT meta inspected as VALID (preserved)",
                PreModuleWallpaperBackup.BackupState.VALID,
                PreModuleWallpaperBackup.getBackupState(legacyCtx));
        PreModuleWallpaperBackup.BackupInspection legacyInsp = PreModuleWallpaperBackup.inspectBackupLocked(legacyCtx);
        check("legacy v1 DEFAULT meta parsed non-null", legacyInsp.metadata != null);
        checkEq("legacy v1 version == 1", 1, legacyInsp.metadata.version);
        checkEq("legacy v1 systemType == DEFAULT", "DEFAULT", legacyInsp.metadata.systemType);
        check("legacy v1 detected as isLegacyUnverifiedDefault", legacyInsp.metadata.isLegacyUnverifiedDefault());
        check("legacy v1 hasVerifiedDefaultProof == false", !legacyInsp.metadata.hasVerifiedDefaultProof());
        check("legacy v1 description marks unverified record",
                legacyInsp.metadata.getSystemDescription().contains("旧版未核验"));

        MockGateway gw1851 = new MockGateway();
        gw1851.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml", oemHomeXml);
        gw1851.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml", oemLockXml);
        gw1851.putBytes("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", fakeHomeJpg);
        gw1851.putBytes("/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", fakeLockJpg);
        gw1851.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home", fakePng);
        gw1851.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper", fakePng);
        gw1851.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/subject_mask", fakePng);
        gw1851.secureSettings.put("constant_template_editor_info",
                extractKeyValSetting(settings1851Txt, "constant_template_editor_info"));
        gw1851.secureSettings.put("aod_category_name",
                extractKeyValSetting(settings1851Txt, "aod_category_name"));
        gw1851.secureSettings.put("Linkage",
                extractKeyValSetting(settings1851Txt, "Linkage"));
        gw1851.secureSettings.put("image_bg_selector",
                extractKeyValSetting(settings1851Txt, "image_bg_selector"));

        HyperOsWallpaperHelper.LegacyDefaultSafetyResult conflict1851 =
                com.hyperalive.aod.Phase2RegressionBridge.checkLegacyDefaultHelper(
                        legacyInsp.metadata, gw1851, probe1851Raw);
        check("device_test_185102 legacy DEFAULT + live OEM image must BLOCK clear",
                !conflict1851.safeToProceedDefaultClear);
        checkEq("device_test_185102 conflict diagnostic code",
                "legacy_default_oem_custom_conflict", conflict1851.code);
        check("conflict message explicitly bans clear, retains snapshot, and asks for trusted baseline",
                conflict1851.userMessage.contains("clear")
                        && conflict1851.userMessage.contains("保留旧快照")
                        && conflict1851.userMessage.contains("基线"));

        // Even if FS looked clean, AIDL probe reporting image + isDefault=false STILL blocks clear
        HyperOsWallpaperHelper.LegacyDefaultSafetyResult aidlOnlyConflict =
                com.hyperalive.aod.Phase2RegressionBridge.checkLegacyDefaultHelper(
                        legacyInsp.metadata, defaultGw, probe1851Raw);
        check("AIDL image conflict blocks legacy clear even with empty theme_magic FS",
                !aidlOnlyConflict.safeToProceedDefaultClear);
        checkEq("AIDL-only conflict code", "legacy_default_oem_custom_conflict", aidlOnlyConflict.code);

        // Bridge probe failure / unverified also blocks legacy clear
        HyperOsWallpaperHelper.LegacyDefaultSafetyResult bridgeErrBlock =
                com.hyperalive.aod.Phase2RegressionBridge.checkLegacyDefaultHelper(
                        legacyInsp.metadata, defaultGw, "PROBE_ERR {\"ok\":false,\"error\":\"BINDER_PING_FAILED\"}");
        check("bridge error blocks legacy clear", !bridgeErrBlock.safeToProceedDefaultClear);
        checkEq("bridge error code", "legacy_default_bridge_unverified", bridgeErrBlock.code);

        // Genuine live OEM default + clean FS allows proceed
        HyperOsWallpaperHelper.LegacyDefaultSafetyResult liveDefAllowed =
                com.hyperalive.aod.Phase2RegressionBridge.checkLegacyDefaultHelper(
                        legacyInsp.metadata, defaultGw, okDefaultProbe);
        check("verified clean FS + AIDL default=true allows legacy clear", liveDefAllowed.safeToProceedDefaultClear);

        // restoreBackupSync on legacy 185102 snapshot must perform zero mutations and preserve meta.json intact
        PreModuleWallpaperBackup.RestoreResult legacyRestoreRes = PreModuleWallpaperBackup.restoreBackupSync(legacyCtx);
        check("restoreBackupSync on legacy snapshot fails cleanly without partial state",
                !legacyRestoreRes.success && !legacyRestoreRes.partial);
        checkEq("legacy meta.json preserved byte-for-byte after restore abort",
                legacyMetaBeforeSha, HyperOsWallpaperHelper.sha256Hex(legacyMetaFile));
        checkEq("legacy snapshot still VALID after restore abort",
                PreModuleWallpaperBackup.BackupState.VALID, PreModuleWallpaperBackup.getBackupState(legacyCtx));
        check("zero files added or removed in backup dir during blocked legacy restore",
                new File(legacyAppFiles, "pre_module_backup/user_0").listFiles().length == 1);

        // New v2 verified DEFAULT snapshots: Round 1 (oem_bridge_confirmed) & Round 2 (aosp_confirmed) + unsigned v2 rejection
        File v2Files = new File(workRoot, "verified_def_v2_files");
        Context v2Ctx = new Context(v2Files);
        File v2MetaFile = new File(v2Files, "pre_module_backup/user_0/meta.json");

        JSONObject v2Round1 = new JSONObject();
        v2Round1.put("version", 2);
        v2Round1.put("userId", 0);
        v2Round1.put("backupTime", 1794565000000L);
        v2Round1.put("enter_ambient_mode_existed", false);
        v2Round1.put("enter_ambient_mode_val", "");
        v2Round1.put("alive_wallpaper_position_existed", false);
        v2Round1.put("alive_wallpaper_position_val", "");
        v2Round1.put("system_type", "DEFAULT");
        v2Round1.put("system_pkg", "");
        v2Round1.put("system_cls", "");
        v2Round1.put("system_file", "");
        v2Round1.put("lock_type", "SAME_AS_SYSTEM");
        v2Round1.put("lock_pkg", "");
        v2Round1.put("lock_cls", "");
        v2Round1.put("lock_file", "");
        v2Round1.put("default_verified", true);
        v2Round1.put("default_source", "oem_bridge_confirmed");
        v2Round1.put("default_oem_home_type", "default");
        v2Round1.put("default_oem_lock_type", "default");
        v2Round1.put("valid", true);
        writeUtf8(v2MetaFile, v2Round1.toString(2));

        PreModuleWallpaperBackup.BackupInspection r1Insp = PreModuleWallpaperBackup.inspectBackupLocked(v2Ctx);
        checkEq("Round 1 verified DEFAULT is VALID", PreModuleWallpaperBackup.BackupState.VALID, r1Insp.state);
        check("Round 1 hasVerifiedDefaultProof == true",
                r1Insp.metadata != null && r1Insp.metadata.hasVerifiedDefaultProof());
        check("Round 1 isLegacyUnverifiedDefault == false", !r1Insp.metadata.isLegacyUnverifiedDefault());
        checkEq("Round 1 verified DEFAULT description normal",
                "系统内置默认壁纸", r1Insp.metadata.getSystemDescription());
        check("Round 1 passes checkLegacyDefaultHelper without conflict check",
                com.hyperalive.aod.Phase2RegressionBridge.checkLegacyDefaultHelper(
                        r1Insp.metadata, gw1851, probe1851Raw).safeToProceedDefaultClear);
        PreModuleWallpaperBackup.clearBackup(v2Ctx);
        checkEq("Round 1 cleared cleanly to NONE before Round 2",
                PreModuleWallpaperBackup.BackupState.NONE, PreModuleWallpaperBackup.getBackupState(v2Ctx));

        JSONObject v2Round2 = new JSONObject(v2Round1.toString());
        v2Round2.put("backupTime", 1794565100000L);
        v2Round2.put("default_source", "aosp_confirmed");
        writeUtf8(v2MetaFile, v2Round2.toString(2));
        PreModuleWallpaperBackup.BackupInspection r2Insp = PreModuleWallpaperBackup.inspectBackupLocked(v2Ctx);
        checkEq("Round 2 verified DEFAULT is VALID", PreModuleWallpaperBackup.BackupState.VALID, r2Insp.state);
        check("Round 2 hasVerifiedDefaultProof == true",
                r2Insp.metadata != null && r2Insp.metadata.hasVerifiedDefaultProof());
        PreModuleWallpaperBackup.clearBackup(v2Ctx);
        checkEq("Round 2 cleared cleanly to NONE",
                PreModuleWallpaperBackup.BackupState.NONE, PreModuleWallpaperBackup.getBackupState(v2Ctx));

        // Unsigned v2 DEFAULT (missing proof fields) is treated as unverified and blocked against OEM image
        JSONObject v2Unsigned = new JSONObject(v2Round1.toString());
        v2Unsigned.put("default_verified", false);
        v2Unsigned.put("default_source", "");
        writeUtf8(v2MetaFile, v2Unsigned.toString(2));
        PreModuleWallpaperBackup.BackupInspection unsignedInsp = PreModuleWallpaperBackup.inspectBackupLocked(v2Ctx);
        check("unsigned v2 DEFAULT is treated as legacy/unverified",
                unsignedInsp.metadata != null && unsignedInsp.metadata.isLegacyUnverifiedDefault());
        check("unsigned v2 DEFAULT blocked when OEM reports image",
                !com.hyperalive.aod.Phase2RegressionBridge.checkLegacyDefaultHelper(
                        unsignedInsp.metadata, gw1851, probe1851Raw).safeToProceedDefaultClear);

        // 8. Round-2 lifecycle regression: post-restore SET_STATIC + /data/system/users/0/wallpaper present must still choose OEM_HYPEROS
        String baselineRestoreRaw = readUtf8(new File(dev1851Dir, "baseline_restore.txt")).trim();
        check("baseline_restore.txt starts with RESTORE_OK", baselineRestoreRaw.startsWith("RESTORE_OK "));
        JSONObject baseRestoreJson = new JSONObject(baselineRestoreRaw.substring("RESTORE_OK ".length()).trim());
        JSONObject baseVerif = baseRestoreJson.optJSONObject("verification");
        check("baseline_restore verification converged", baseVerif != null && baseVerif.optBoolean("converged", false));
        JSONObject probeFromBaseline = new JSONObject();
        probeFromBaseline.put("ok", true);
        probeFromBaseline.put("userId", 0);
        probeFromBaseline.put("bindMethod", "IAM:peekService(Intent,String,String)");
        JSONObject slotsFromBaseline = new JSONObject();
        slotsFromBaseline.put("home", baseVerif.optJSONObject("home"));
        slotsFromBaseline.put("lock", baseVerif.optJSONObject("lock"));
        probeFromBaseline.put("slots", slotsFromBaseline);
        probeFromBaseline.put("wms", baseVerif.optJSONObject("wms"));

        HyperOsWallpaperHelper.BridgeProbeSummary postRestoreProbe =
                HyperOsWallpaperHelper.parseBridgeProbeOutput("PROBE_OK " + probeFromBaseline.toString());
        HyperOsWallpaperHelper.OemProbeResult round2Fs =
                HyperOsWallpaperHelper.probeAndValidateOemState(0, gw1851);
        HyperOsWallpaperHelper.OemProbeResult round2Cross =
                HyperOsWallpaperHelper.crossValidateWithBridge(round2Fs, postRestoreProbe, 0);
        checkEq("post-restore SET_STATIC probe + FS cross-validates as OEM_CUSTOM_IMAGE",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, round2Cross.mode);
        checkEq("post-restore cross-validation preserves home sha256",
                r5HomeSha, round2Cross.expectedHomeSha256);

        check("shouldRequireOemCrossValidation true for MIUI SET_STATIC even with sysInfo=null",
                com.hyperalive.aod.Phase2RegressionBridge.checkShouldRequireOemHelper(
                        1273, "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC"));
        check("shouldRequireOemCrossValidation false for non-MIUI AOSP ImageWallpaper",
                !com.hyperalive.aod.Phase2RegressionBridge.checkShouldRequireOemHelper(
                        1273, "com.android.systemui", ".ImageWallpaper", "SET_STATIC"));

        checkEq("Round-2 SET_STATIC + readable standard file + OEM_CUSTOM_IMAGE still classifies as OEM_HYPEROS",
                "OEM_HYPEROS",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiStaticHelper(
                        round2Cross, 1273, -1,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC",
                        false, false, false, true));

        // Ambiguous/residual OEM state + readable standard file MUST ABORT cleanly (never degrade to PLAIN_STATIC)
        HyperOsWallpaperHelper.OemProbeResult ambiguousCross =
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        defFs, postRestoreProbe, 0);
        checkEq("contradictory FS/AIDL on SET_STATIC must ABORT instead of PLAIN_STATIC",
                "ABORT",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiStaticHelper(
                        ambiguousCross, 1273, -1,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC",
                        false, false, true, true));

        // Clean OEM default + third-party SET_STATIC static image -> retains PLAIN_STATIC branch
        HyperOsWallpaperHelper.OemProbeResult cleanDefCross =
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        defFs, HyperOsWallpaperHelper.parseBridgeProbeOutput(okDefaultProbe), 0);
        checkEq("genuine non-OEM third-party SET_STATIC with confirmed default OEM uses PLAIN_STATIC",
                "PLAIN_STATIC",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiStaticHelper(
                        cleanDefCross, 1274, -1,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC",
                        false, false, true, true));

        // SET_LIVE_TO_CLEAR with lingering standard file rejects CONFIRMED_DEFAULT and ABORTs
        checkEq("SET_LIVE_TO_CLEAR with non-absent standard file aborts safely",
                "ABORT",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiStaticHelper(
                        cleanDefCross, 1266, -1,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_LIVE_TO_CLEAR",
                        false, false, true, false));

        // 9. Live cycle1_template_error.txt + cycle1_after_apply.state.json fixture (mount-ns isolated vs -M global)
        String tplErrTxt = readUtf8(new File(dev1851Dir, "cycle1_template_error.txt"));
        String cycle1EditorJson = "";
        for (String line : tplErrTxt.split("\r?\n")) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("{\"homeInfo\"")) {
                cycle1EditorJson = trimmedLine;
                break;
            }
        }
        check("cycle1_template_error.txt contains real constant_template_editor_info JSON",
                cycle1EditorJson.contains("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home"));

        JSONObject cycle1State = new JSONObject(readUtf8(new File(dev1851Dir, "cycle1_after_apply.state.json")));
        JSONObject cycle1Secure = cycle1State.optJSONObject("secure");
        check("cycle1_after_apply.state.json secure present", cycle1Secure != null);

        // 9a. Simulate app mount-namespace isolation where /data/system/theme_magic works but /data/user_de/0/com.miui.aod returns probe=0
        Log.clearEntries();
        MockGateway isolatedNsGw = new MockGateway();
        isolatedNsGw.putText("/data/system/theme_magic/users/0/wallpaper/data/home.xml", oemHomeXml);
        isolatedNsGw.putText("/data/system/theme_magic/users/0/wallpaper/data/lock.xml", oemLockXml);
        isolatedNsGw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", fakeHomeJpg);
        isolatedNsGw.putBytes("/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", fakeLockJpg);
        isolatedNsGw.secureSettings.put("constant_template_editor_info", cycle1EditorJson);
        for (String sk : HyperOsWallpaperHelper.OEM_SECURE_SETTING_KEYS) {
            if (!"constant_template_editor_info".equals(sk) && cycle1Secure.has(sk)) {
                isolatedNsGw.secureSettings.put(sk, cycle1Secure.optString(sk, ""));
            }
        }
        HyperOsWallpaperHelper.OemProbeResult isoRes = HyperOsWallpaperHelper.probeAndValidateOemState(0, isolatedNsGw);
        checkEq("isolated mount-ns fails at oem_tpl_home_missing", "oem_tpl_home_missing", isoRes.diagnosticCode);
        check("oem_tpl_home_missing surface reports safe path_match=true and probe=0",
                isoRes.userMessage.contains("path_match=true") && isoRes.userMessage.contains("probe=0"));
        boolean sawDiagLog = false;
        for (String logLine : Log.ENTRIES) {
            check("diagnostic log must not leak template path", !logLine.contains("wallpaper_home"));
            check("diagnostic log must not leak template editor JSON", !logLine.contains("matrixArr"));
            if (logLine.contains("code=oem_tpl_home_missing")
                    && logLine.contains("path_match=true")
                    && logLine.contains("probe=0")) {
                sawDiagLog = true;
            }
        }
        check("diagnostic log emitted safe path_match & probe status", sawDiagLog);

        // 9b. Simulate -M global mount namespace: templates visible (probe=1) -> full OEM_CUSTOM_IMAGE + 4 templates
        isolatedNsGw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper_home", fakePng);
        isolatedNsGw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/wallpaper", fakePng);
        isolatedNsGw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/subject", fakePng);
        isolatedNsGw.putBytes("/data/user_de/0/com.miui.aod/files/templates/current/subject_mask", fakePng);
        HyperOsWallpaperHelper.OemProbeResult globalFsRes = HyperOsWallpaperHelper.probeAndValidateOemState(0, isolatedNsGw);
        checkEq("global mount-ns (-M) resolves OEM_CUSTOM_IMAGE",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, globalFsRes.mode);
        check("global mount-ns captures all 4 templates + 2 xml + 2 jpg (8 files)",
                globalFsRes.filesToBackup.size() == 8);
        HyperOsWallpaperHelper.OemProbeResult globalCross =
                HyperOsWallpaperHelper.crossValidateWithBridge(globalFsRes, postRestoreProbe, 0);
        checkEq("global mount-ns cross-validates against SET_STATIC post-restore probe",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, globalCross.mode);

        // 10. Split native static / MiuiKeyguardPictorialWallpaper & StateCoordinator gating checks
        check("isMiuiStaticOrPictorialName accepts MiuiKeyguardPictorialWallpaper",
                PreModuleWallpaperBackup.isMiuiStaticOrPictorialName(
                        "com.miui.miwallpaper", ".wallpaperservice.MiuiKeyguardPictorialWallpaper")
                        && PreModuleWallpaperBackup.isMiuiStaticOrPictorialName(
                                "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper"));
        check("isMiuiStaticOrPictorialName rejects third-party live wallpaper",
                !PreModuleWallpaperBackup.isMiuiStaticOrPictorialName("com.third.party", ".LiveService"));
        check("isCleanActiveSplitLockComp accepts Pictorial/Image and rejects null or third-party",
                HyperOsWallpaperHelper.isCleanActiveSplitLockComp(HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL)
                        && HyperOsWallpaperHelper.isCleanActiveSplitLockComp(HyperOsWallpaperHelper.MIUI_IMAGE_COMPONENT_SHORT)
                        && !HyperOsWallpaperHelper.isCleanActiveSplitLockComp("null")
                        && !HyperOsWallpaperHelper.isCleanActiveSplitLockComp("ComponentInfo{com.third.party/.Live}"));

        String pictorialSplitProbe = okProbeLiveHomeLock.replace("\"lockId\":-1", "\"lockId\":1301");
        HyperOsWallpaperHelper.BridgeProbeSummary splitBridge =
                HyperOsWallpaperHelper.parseBridgeProbeOutput(pictorialSplitProbe);
        HyperOsWallpaperHelper.OemProbeResult splitOemCross =
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        res, splitBridge, 0, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL);
        checkEq("crossValidateWithBridge accepts verified MiuiKeyguardPictorialWallpaper split lock",
                HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE, splitOemCross.mode);
        check("verified split OEM probe preserves wmsSplitLock & wmsLockComp",
                splitOemCross.wmsSplitLock
                        && HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL.equals(splitOemCross.wmsLockComp));
        checkEq("crossValidateWithBridge rejects third-party lock component even with split lockId",
                HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED,
                HyperOsWallpaperHelper.crossValidateWithBridge(
                        res, splitBridge, 0, "com.third.party/com.third.party.LiveWallpaper").mode);

        // Diverged desktop A (5267ca...) + lock gradient (960737...) vs stale OEM B (r5HomeSha)
        String stdHomeASha = "5267ca90ef803dc013bd281fd5d669d2b33c4547ccdfee193e8886f69d9ebea9";
        String stdLockGradSha = "960737f3b60b231fc8e26caa1a1454c4ea99bdb9ff543cb93917a3fc3d02e669";
        check("detects diverged split desktop photo A vs stale OEM photo B",
                com.hyperalive.aod.Phase2RegressionBridge.checkSplitDivergedHelper(
                        splitOemCross, true, stdHomeASha, true, stdLockGradSha));
        check("matching OEM split hashes are not marked diverged",
                !com.hyperalive.aod.Phase2RegressionBridge.checkSplitDivergedHelper(
                        splitOemCross, true, r5HomeSha, true, r5LockSha));
        checkEq("diverged split desktop A + readable standard file ABORTs conservatively (no PLAIN_STATIC downgrade)",
                "ABORT",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiSplitHelper(
                        splitOemCross, 1300, 1301,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC",
                        true, false, false, true, true));
        checkEq("diverged split unreadable desktop file ABORTs conservatively",
                "ABORT",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiSplitHelper(
                        splitOemCross, 1300, 1301,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC",
                        true, false, false, false, true));
        checkEq("matching OEM split topology classifies as OEM_HYPEROS",
                "OEM_HYPEROS",
                com.hyperalive.aod.Phase2RegressionBridge.checkClassifyMiuiSplitHelper(
                        splitOemCross, 1300, 1301,
                        "com.miui.miwallpaper", ".wallpaperservice.ImageWallpaper", "SET_STATIC",
                        true, false, false, false, false));

        String splitLockSha = "aaaaaaaa11111111bbbbbbbb22222222cccccccc33333333dddddddd44444444";
        JSONObject splitSpecJson = HyperOsWallpaperHelper.buildBridgeRestoreSpecJson(
                0, hXml, lXml,
                "/tmp/home_wallpaper.jpg", r5HomeSha,
                "/tmp/lock_wallpaper.jpg", splitLockSha,
                "", "com.miui.aod", "com.miui.aod",
                true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL);
        check("split restore spec sets allowSplitRestore, expectedWmsLockComp, sameEffectAndBlur, and std hashes",
                splitSpecJson.optBoolean("allowSplitRestore", false)
                        && splitSpecJson.has("sameEffectAndBlur")
                        && splitSpecJson.optBoolean("sameEffectAndBlur", false)
                        && HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL.equals(
                                splitSpecJson.optString("expectedWmsLockComp", ""))
                        && r5HomeSha.equals(splitSpecJson.optString("stdHomeSha256", ""))
                        && splitLockSha.equals(splitSpecJson.optString("stdLockSha256", "")));
        check("distinct split images are NOT flagged unsafe sameLayer",
                !HyperOsWallpaperHelper.isSplitRestoreUnsafeSameLayer(splitSpecJson)
                        && !com.hyperalive.aod.root.HyperOsWallpaperBridge.isUnsafeSplitSameLayerSpec(splitSpecJson));

        JSONObject unsafeSameLayerSpec = HyperOsWallpaperHelper.buildBridgeRestoreSpecJson(
                0, hXml, lXml,
                "/tmp/home_wallpaper.jpg", r5HomeSha,
                "/tmp/lock_wallpaper.jpg", r5HomeSha,
                "", "com.miui.aod", "com.miui.aod",
                true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL);
        check("identical-image split restore with sameEffectAndBlur & trusted pendingPkg is rejected pre-clear",
                HyperOsWallpaperHelper.isSplitRestoreUnsafeSameLayer(unsafeSameLayerSpec)
                        && com.hyperalive.aod.root.HyperOsWallpaperBridge.isUnsafeSplitSameLayerSpec(unsafeSameLayerSpec));

        HyperOsWallpaperHelper.WallpaperDataXml diffEffectLockXml =
                HyperOsWallpaperHelper.parseWallpaperDataXml(
                        oemLockXml.replace("effect_id=\"0\"", "effect_id=\"5\""));
        JSONObject diffEffectSplitSpec = HyperOsWallpaperHelper.buildBridgeRestoreSpecJson(
                0, hXml, diffEffectLockXml,
                "/tmp/home_wallpaper.jpg", r5HomeSha,
                "/tmp/lock_wallpaper.jpg", r5HomeSha,
                "", "com.miui.aod", "com.miui.aod",
                true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL);
        check("identical-image split restore with distinct effectId sets sameEffectAndBlur=false",
                !diffEffectSplitSpec.optBoolean("sameEffectAndBlur", true)
                        && !HyperOsWallpaperHelper.isSplitRestoreUnsafeSameLayer(diffEffectSplitSpec)
                        && !com.hyperalive.aod.root.HyperOsWallpaperBridge.isUnsafeSplitSameLayerSpec(diffEffectSplitSpec));

        // prepareOemRestoreSpec must return null BEFORE allow_reset=1 on unsafe split sameLayer snapshot
        File unsafePrepDir = new File(workRoot, "unsafe_split_prep_user0");
        File unsafeOemDir = new File(unsafePrepDir, "oem_payload");
        unsafeOemDir.mkdirs();
        File unsafeHomeImg = new File(unsafeOemDir, "home_wallpaper.jpg");
        File unsafeLockImg = new File(unsafeOemDir, "lock_wallpaper.jpg");
        writeBytes(unsafeHomeImg, fakeHomeJpg);
        writeBytes(unsafeLockImg, fakeHomeJpg);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(fakeHomeJpg);
        StringBuilder shaSb = new StringBuilder();
        for (byte b : digest) shaSb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        String unsafeImgSha = shaSb.toString();
        JSONObject unsafeMetaJson = new JSONObject();
        unsafeMetaJson.put("version", 2);
        unsafeMetaJson.put("userId", 0);
        unsafeMetaJson.put("system_type", "OEM_HYPEROS");
        unsafeMetaJson.put("lock_type", "OEM_HYPEROS");
        JSONObject unsafeOemObj = new JSONObject();
        unsafeOemObj.put("allow_split_restore", true);
        unsafeOemObj.put("wms_lock_comp", HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL);
        unsafeOemObj.put("home_xml_text", oemHomeXml);
        unsafeOemObj.put("lock_xml_text", oemLockXml);
        JSONArray unsafeFilesArr = new JSONArray();
        unsafeFilesArr.put(new JSONObject().put("rel_name", "home_wallpaper.jpg")
                .put("source_path", "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg")
                .put("sha256", unsafeImgSha));
        unsafeFilesArr.put(new JSONObject().put("rel_name", "lock_wallpaper.jpg")
                .put("source_path", "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg")
                .put("sha256", unsafeImgSha));
        unsafeOemObj.put("files", unsafeFilesArr);
        unsafeMetaJson.put("oem", unsafeOemObj);
        check("prepareOemRestoreSpec rejects unsafe split sameLayer snapshot BEFORE allow_reset=1",
                com.hyperalive.aod.Phase2RegressionBridge.checkPrepareOemRestoreSpecHelper(
                        unsafePrepDir, unsafeMetaJson) == null);

        JSONObject evalHomeSlot = new JSONObject()
                .put("type", new JSONObject().put("ok", true).put("returnedNull", false).put("value", "image"))
                .put("isDefault", new JSONObject().put("ok", true).put("value", "false"))
                .put("componentUsing", new JSONObject().put("ok", true).put("value", "true"))
                .put("pathCall", new JSONObject().put("ok", true).put("returnedNull", false)
                        .put("value", "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg"))
                .put("resolvedFile", new JSONObject().put("isFile", true).put("bytes", 1868520L).put("sha256", r5HomeSha));
        JSONObject evalLockSlot = new JSONObject()
                .put("type", new JSONObject().put("ok", true).put("returnedNull", false).put("value", "image"))
                .put("isDefault", new JSONObject().put("ok", true).put("value", "false"))
                .put("componentUsing", new JSONObject().put("ok", true).put("value", "true"))
                .put("pathCall", new JSONObject().put("ok", true).put("returnedNull", false)
                        .put("value", "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg"))
                .put("resolvedFile", new JSONObject().put("isFile", true).put("bytes", 1868520L).put("sha256", splitLockSha));
        JSONObject splitPictorialWms = new JSONObject()
                .put("sysId", 1300).put("lockId", 1301)
                .put("sysComp", "null")
                .put("lockComp", "ComponentInfo{com.miui.miwallpaper/com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper}");
        JSONObject splitValidFs = new JSONObject()
                .put("stdSys", new JSONObject().put("exists", true).put("isFile", true).put("sha256", r5HomeSha))
                .put("stdLock", new JSONObject().put("exists", true).put("isFile", true).put("sha256", splitLockSha));
        check("merged restore contract still rejects split lockId",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, splitPictorialWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        false).startsWith("wms_unexpected_split_lockId"));
        JSONObject mergedDirtyFs = new JSONObject()
                .put("stdSys", new JSONObject().put("exists", true).put("isFile", true).put("sha256", r5HomeSha))
                .put("stdLock", new JSONObject().put("exists", true).put("isFile", true).put("sha256", splitLockSha));
        JSONObject mergedWms = new JSONObject()
                .put("sysId", 1300).put("lockId", -1).put("sysComp", "null").put("lockComp", "null");
        check("merged restore rejects leftover stdLock file",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, mergedWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        false, "", mergedDirtyFs, "", "").equals("merged_unexpected_std_lock_file"));
        checkEq("allowSplitRestore accepts clean MiuiKeyguardPictorialWallpaper split WMS state + matching distinct std hashes",
                "",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, splitPictorialWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        splitValidFs, r5HomeSha, splitLockSha));

        JSONObject evalLockSlotSameSha = new JSONObject(evalLockSlot.toString());
        evalLockSlotSameSha.optJSONObject("resolvedFile").put("sha256", r5HomeSha);
        JSONObject splitSameShaValidFs = new JSONObject()
                .put("stdSys", new JSONObject().put("exists", true).put("isFile", true).put("sha256", r5HomeSha))
                .put("stdLock", new JSONObject().put("exists", true).put("isFile", true).put("sha256", r5HomeSha));
        JSONObject splitUnsafeSameLayerWms = new JSONObject(splitPictorialWms.toString())
                .put("sameEffectAndBlur", true)
                .put("lockXmlLastPendingPkg", "com.miui.aod")
                .put("wallpaperChanged2", "com.miui.aod");
        checkEq("allowSplitRestore evaluateRestoreState rejects same-image sameEffectAndBlur trusted-pending split",
                "split_same_layer_alpha_zero_risk",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlotSameSha, splitUnsafeSameLayerWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", r5HomeSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        splitSameShaValidFs, r5HomeSha, r5HomeSha));
        JSONObject splitSafeNonTrustedPendingWms = new JSONObject(splitPictorialWms.toString())
                .put("sameEffectAndBlur", true)
                .put("lockXmlLastPendingPkg", "android")
                .put("wallpaperChanged2", "android");
        checkEq("allowSplitRestore evaluateRestoreState allows same-image split when non-PackageUtils.d pendingPkg=android breaks b()/y()",
                "",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlotSameSha, splitSafeNonTrustedPendingWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", r5HomeSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        splitSameShaValidFs, r5HomeSha, r5HomeSha));
        check("allowSplitRestore rejects lockId=-1 (must not silently accept merged)",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, mergedWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        splitValidFs, r5HomeSha, splitLockSha).startsWith("wms_split_lockId_missing"));
        JSONObject splitXmlFallbackWms = new JSONObject()
                .put("sysId", 1300).put("lockId", 1301)
                .put("sysComp", "null").put("lockComp", "null")
                .put("lockXmlComp", "com.miui.miwallpaper/.wallpaperservice.MiuiKeyguardPictorialWallpaper");
        checkEq("allowSplitRestore accepts SET_STATIC wallpaper_info.xml lockXmlComp when AIDL lockComp is null",
                "",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, splitXmlFallbackWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        splitValidFs, r5HomeSha, splitLockSha));
        check("allowSplitRestore rejects mismatched expectedWmsLockComp",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, splitXmlFallbackWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true, "com.miui.miwallpaper/com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        splitValidFs, r5HomeSha, splitLockSha).startsWith("wms_lockComp_mismatch"));
        JSONObject dirtyLockWms = new JSONObject()
                .put("sysId", 1300).put("lockId", 1301)
                .put("sysComp", "null")
                .put("lockComp", "ComponentInfo{com.third.party/.Live}");
        check("allowSplitRestore still rejects third-party lockComp",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, dirtyLockWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true).startsWith("wms_lockComp_unexpected"));
        JSONObject missingStdLockFs = new JSONObject()
                .put("stdSys", new JSONObject().put("exists", true).put("isFile", true).put("sha256", r5HomeSha))
                .put("stdLock", new JSONObject().put("exists", false).put("isFile", false));
        checkEq("allowSplitRestore rejects missing stdLock file",
                "std_lock_missing",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, splitPictorialWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        missingStdLockFs, r5HomeSha, splitLockSha));
        JSONObject wrongStdSysFs = new JSONObject()
                .put("stdSys", new JSONObject().put("exists", true).put("isFile", true).put("sha256", stdHomeASha))
                .put("stdLock", new JSONObject().put("exists", true).put("isFile", true).put("sha256", splitLockSha));
        checkEq("allowSplitRestore rejects mismatched stdSys sha256",
                "std_sys_sha256_mismatch",
                com.hyperalive.aod.root.HyperOsWallpaperBridge.evaluateRestoreState(
                        evalHomeSlot, evalLockSlot, splitPictorialWms,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/home_wallpaper.jpg", r5HomeSha,
                        true, "/data/system/theme_magic/users/0/wallpaper/image/lock_wallpaper.jpg", splitLockSha,
                        true, HyperOsWallpaperHelper.MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL,
                        wrongStdSysFs, r5HomeSha, splitLockSha));

        // StateCoordinator authoritative + fail-open snapshot & AOD slot distinction
        check("authoritative null WMS overrides stale prop=1",
                !com.hyperalive.aod.hook.StateCoordinator.evaluateWallpaperOwnership(true, null, -1, null, "1"));
        check("unavailable WMS fails open (must NOT trust prop=1)",
                !com.hyperalive.aod.hook.StateCoordinator.evaluateWallpaperOwnership(false, null, -1, null, "1"));
        check("unavailable WMS with prop=0 stays inactive",
                !com.hyperalive.aod.hook.StateCoordinator.evaluateWallpaperOwnership(false, null, -1, null, "0"));

        com.hyperalive.aod.hook.StateCoordinator.OwnershipDetail homeOnly =
                com.hyperalive.aod.hook.StateCoordinator.evaluateWallpaperOwnershipDetailByName(
                        true, "com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaper",
                        1301, "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper");
        check("home-only HyperAlive owns home slot but NOT lock/AOD slot",
                homeOnly.homeOwned && !homeOnly.lockOwned && homeOnly.anyOwned());
        com.hyperalive.aod.hook.StateCoordinator.OwnershipDetail mergedOwned =
                com.hyperalive.aod.hook.StateCoordinator.evaluateWallpaperOwnershipDetailByName(
                        true, "com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaper",
                        -1, null, null);
        check("merged HyperAlive owns both home and lock/AOD slots",
                mergedOwned.homeOwned && mergedOwned.lockOwned);

        com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot coldSnap =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot(false, true, true, 1L, 100L);
        check("cold-start invalid snapshot fails open on all slots",
                !com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(coldSnap, 200L, 10000L, 1L, false)
                        && !com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(coldSnap, 200L, 10000L, 1L, true));

        com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot homeOnlySnap =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot(true, true, false, 5L, 1000L);
        check("valid home-only snapshot allows ANY/HOME but rejects LOCK/AOD",
                com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(homeOnlySnap, 2000L, 10000L, 5L, false)
                        && !com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(homeOnlySnap, 2000L, 10000L, 5L, true));

        com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot validBothSnap =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot(true, true, true, 5L, 1000L);
        check("valid snapshot expires at TTL boundary (fail-open)",
                com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(validBothSnap, 10999L, 10000L, 5L, true)
                        && !com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(validBothSnap, 11000L, 10000L, 5L, true));
        check("pre-refresh window [7000ms, 9999ms] schedules refresh without dropping active true",
                !com.hyperalive.aod.hook.StateCoordinator.shouldScheduleOwnershipPreRefresh(validBothSnap, 7999L, 7000L, 10000L, 5L)
                        && com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(validBothSnap, 8000L, 10000L, 5L, true)
                        && com.hyperalive.aod.hook.StateCoordinator.shouldScheduleOwnershipPreRefresh(validBothSnap, 8000L, 7000L, 10000L, 5L)
                        && com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(validBothSnap, 10999L, 10000L, 5L, true)
                        && com.hyperalive.aod.hook.StateCoordinator.shouldScheduleOwnershipPreRefresh(validBothSnap, 10999L, 7000L, 10000L, 5L));
        check("epoch bump invalidates snapshot immediately (owned -> native exit)",
                !com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(validBothSnap, 1500L, 10000L, 6L, false)
                        && com.hyperalive.aod.hook.StateCoordinator.shouldScheduleOwnershipPreRefresh(validBothSnap, 1500L, 7000L, 10000L, 6L));
        check("clock regression fails open safely",
                !com.hyperalive.aod.hook.StateCoordinator.isSnapshotActive(validBothSnap, 999L, 10000L, 5L, false)
                        && com.hyperalive.aod.hook.StateCoordinator.shouldScheduleOwnershipPreRefresh(validBothSnap, 999L, 7000L, 10000L, 5L));

        // 11. Rewrite Phase 2 & 3 behavioral unit checks (Root timeout/interrupt, LspState, raw settings, batch probe, canonicalize)
        assertions += com.hyperalive.aod.Phase2RegressionBridge.runRewriteUnitChecks();

        System.out.println("Wallpaper backup regression: " + assertions + " assertions passed (+ Phase2 + R5 AIDL + split topology + Rewrite unit checks)");
    }
}
"""

HARNESS_PHASE2_BRIDGE = r"""
package com.hyperalive.aod;

public final class Phase2RegressionBridge {
    private static int unitAssertions = 0;

    private static void check(String label, boolean cond) {
        if (!cond) {
            throw new AssertionError("FAIL [Phase2Bridge]: " + label);
        }
        unitAssertions++;
    }

    private static PreModuleWallpaperBackup.WallpaperXmlState makeXml(
            int wpId, String wpPkg, String wpCls, String bindSource,
            boolean hasKwp, int kwpId, String kwpPkg, String kwpCls) {
        PreModuleWallpaperBackup.WallpaperXmlState x = new PreModuleWallpaperBackup.WallpaperXmlState();
        x.hasWp = true;
        x.wpId = wpId;
        x.wpPkg = wpPkg;
        x.wpCls = wpCls;
        x.wpBindSource = bindSource;
        x.hasKwp = hasKwp;
        x.kwpId = kwpId;
        x.kwpPkg = kwpPkg;
        x.kwpCls = kwpCls;
        return x;
    }

    public static void runAll() {
        PreModuleWallpaperBackup.PreApplyBaseline base =
                new PreModuleWallpaperBackup.PreApplyBaseline(
                        0, 1266, -1, false, "SET_LIVE_TO_CLEAR",
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        false, "",
                        true, "");

        // Case 1: identical state -> safe to delete newly created backup
        check("unchanged baseline allows cleanup",
                PreModuleWallpaperBackup.evaluateBaselineUnchanged(
                        base, 0, false,
                        1266, -1,
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        makeXml(1266, "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                                "SET_LIVE_TO_CLEAR", false, -1, "", ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, false, ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, true, "")).deleted);

        // Case 2: wallpaper ID changed externally even though NOT HyperAlive -> MUST retain backup!
        check("changed sysId non-HyperAlive retains backup",
                !PreModuleWallpaperBackup.evaluateBaselineUnchanged(
                        base, 0, false,
                        1270, -1,
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        makeXml(1270, "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                                "SET_LIVE_TO_CLEAR", false, -1, "", ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, false, ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, true, "")).deleted);

        // Case 3: partial bind left independent lockId != -1 -> MUST retain backup!
        check("partial lockId change retains backup",
                !PreModuleWallpaperBackup.evaluateBaselineUnchanged(
                        base, 0, false,
                        1266, 1271,
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        makeXml(1266, "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                                "SET_LIVE_TO_CLEAR", false, -1, "", ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, false, ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, true, "")).deleted);

        // Case 4: Root.setWallpaper wrote enter_ambient_mode before SetWall failed -> MUST retain backup!
        check("setting mutation retains backup",
                !PreModuleWallpaperBackup.evaluateBaselineUnchanged(
                        base, 0, false,
                        1266, -1,
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        makeXml(1266, "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                                "SET_LIVE_TO_CLEAR", false, -1, "", ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, true, "1"),
                        new PreModuleWallpaperBackup.SettingRecord(true, true, "")).deleted);

        // Case 5: occupied by HyperAlive -> MUST retain backup!
        check("HyperAlive occupied retains backup",
                !PreModuleWallpaperBackup.evaluateBaselineUnchanged(
                        base, 0, true,
                        1266, -1,
                        "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                        "", "",
                        makeXml(1266, "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                                "SET_LIVE_TO_CLEAR", false, -1, "", ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, false, ""),
                        new PreModuleWallpaperBackup.SettingRecord(true, true, "")).deleted);

        // Case 6: slot verification after SetWall
        PreModuleWallpaperBackup.SlotVerifyResult singleOk = PreModuleWallpaperBackup.evaluateAppliedSlots(
                false, "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive", null, null,
                35, 1300, -1,
                "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive",
                "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive",
                makeXml(1300, "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive",
                        "SET_WALLPAPER", false, -1, "", ""));
        check("single slot verification accepts matching unified slot", singleOk.ok);

        PreModuleWallpaperBackup.SlotVerifyResult singleResidualLock = PreModuleWallpaperBackup.evaluateAppliedSlots(
                false, "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive", null, null,
                35, 1300, 1301,
                "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive",
                "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                makeXml(1300, "com.flyme.alivewallpaper", "com.flyme.alivewallpaper.Alive",
                        "SET_WALLPAPER", true, 1301, "com.miui.miwallpaper",
                        "com.miui.miwallpaper.wallpaperservice.ImageWallpaper"));
        check("single slot verification rejects lingering lock slot", !singleResidualLock.ok);

        PreModuleWallpaperBackup.SlotVerifyResult dualPartial = PreModuleWallpaperBackup.evaluateAppliedSlots(
                true, "com.hyperalive.aod", "com.hyperalive.aod.QihangHomeWallpaper",
                "com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaper",
                35, 1301, 1302,
                "com.hyperalive.aod", "com.hyperalive.aod.QihangHomeWallpaper",
                "com.miui.miwallpaper", "com.miui.miwallpaper.wallpaperservice.ImageWallpaper",
                makeXml(1301, "com.hyperalive.aod", "com.hyperalive.aod.QihangHomeWallpaper",
                        "", true, 1302, "com.miui.miwallpaper",
                        "com.miui.miwallpaper.wallpaperservice.ImageWallpaper"));
        check("dual slot verification rejects mismatched lock slot", !dualPartial.ok);

        PreModuleWallpaperBackup.SlotVerifyResult dualOk = PreModuleWallpaperBackup.evaluateAppliedSlots(
                true, "com.hyperalive.aod", "com.hyperalive.aod.QihangHomeWallpaper",
                "com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaper",
                35, 1301, 1302,
                "com.hyperalive.aod", "com.hyperalive.aod.QihangHomeWallpaper",
                "com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaper",
                makeXml(1301, "com.hyperalive.aod", "com.hyperalive.aod.QihangHomeWallpaper",
                        "", true, 1302, "com.hyperalive.aod", "com.hyperalive.aod.QihangWallpaper"));
        check("dual slot verification accepts matching dual slots", dualOk.ok);

        runDraftAndRollbackAndDiagTests();
    }

    private static void writeBytesFile(java.io.File f, byte[] data) throws Exception {
        java.io.File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(data);
            out.flush();
        }
    }

    private static byte[] readBytesFile(java.io.File f) throws Exception {
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    private static void runDraftAndRollbackAndDiagTests() {
        java.io.File tmpRoot = null;
        try {
            tmpRoot = java.nio.file.Files.createTempDirectory("draft_diag_test_").toFile();
            java.io.File ceInternal = new java.io.File(tmpRoot, "ce_internal");
            java.io.File ceExternal = new java.io.File(tmpRoot, "ce_external");
            java.io.File deInternal = new java.io.File(tmpRoot, "de_internal");
            ceInternal.mkdirs();
            ceExternal.mkdirs();
            deInternal.mkdirs();
            DirectBootContext.sForceDeForTest = Boolean.TRUE;
            android.content.Context ctx = new android.content.Context(ceInternal, ceExternal, deInternal);

            // Seed committed CE + DE files and prefs before opening editor
            byte[] committedLockBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 10, 0, 20, 1, 2};
            byte[] committedHomeBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 30, 0, 40, 3, 4};
            byte[] committedLockRaw = new byte[]{'R', 'A', 'W', '_', 'L', 'O', 'C', 'K', '1'};
            writeBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK), committedLockBytes);
            writeBytesFile(PhotoStore.deFile(ctx, PhotoStore.LOCK), committedLockBytes);
            writeBytesFile(PhotoStore.file(ctx, PhotoStore.HOME), committedHomeBytes);
            writeBytesFile(PhotoStore.deFile(ctx, PhotoStore.HOME), committedHomeBytes);
            writeBytesFile(PhotoStore.rawFile(ctx, PhotoStore.LOCK), committedLockRaw);
            writeBytesFile(PhotoStore.deFile(ctx, "raw_" + PhotoStore.LOCK), committedLockRaw);
            ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("x_" + PhotoStore.LOCK, 0.25f)
                    .putFloat("y_" + PhotoStore.LOCK, 0.35f)
                    .putFloat("zoom_" + PhotoStore.LOCK, 1.5f)
                    .commit();
            ctx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, true)
                    .commit();

            // 1. Draft creation, crop save, frame sync, and preview vs committed isolation
            String sid = PhotoDraftStore.newSessionId();
            Object sidLease = new Object();
            PhotoDraftStore.acquireDraftLease(sid, sidLease);
            check("generated session id validates cleanly", PhotoDraftStore.isValidSessionId(sid));
            check("path-traversal session id rejected",
                    !PhotoDraftStore.isValidSessionId("../escape")
                            && !PhotoDraftStore.isValidSessionId("d123/../../x"));

            java.io.File stagedIn = new java.io.File(PhotoDraftStore.sessionDir(ctx, sid), "photo-picked1.jpg");
            writeBytesFile(stagedIn, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03});
            android.graphics.Bitmap draftLockBmp = new android.graphics.Bitmap(123, 456);
            PhotoDraftStore.saveCroppedToDraft(ctx, sid, PhotoStore.LOCK, stagedIn, draftLockBmp, 0.6f, 0.7f, 2.2f);
            android.graphics.Bitmap draftHomeBmp = new android.graphics.Bitmap(222, 333);
            PhotoDraftStore.saveCroppedToDraft(ctx, sid, PhotoStore.HOME, stagedIn, draftHomeBmp, 0.4f, 0.5f, 1.8f);
            check("syncFrameToLockDraftWithDefaultCrop creates draft frame",
                    PhotoDraftStore.syncFrameToLockDraftWithDefaultCrop(ctx, sid));

            check("committed lock/home CE+DE bytes stay identical while drafting",
                    java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.deFile(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedHomeBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.HOME)))
                            && java.util.Arrays.equals(committedHomeBytes, readBytesFile(PhotoStore.deFile(ctx, PhotoStore.HOME))));
            check("committed frame does not exist prematurely before Apply",
                    !PhotoStore.file(ctx, PhotoStore.FRAME).exists()
                            && !PhotoStore.deFile(ctx, PhotoStore.FRAME).exists());
            check("committed photo_crop_prefs and home_follow_lock remain untouched before Apply",
                    Math.abs(ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .getFloat("zoom_" + PhotoStore.LOCK, 0f) - 1.5f) < 0.001f
                            && !ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .contains("zoom_" + PhotoStore.HOME)
                            && ctx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, 0)
                            .getBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, false));

            // Preview isolation vs active wallpaper / WallpaperPreviewProvider isolation
            String effLock = PhotoDraftStore.effectiveSource(ctx, sid, PhotoStore.LOCK);
            String effFrame = PhotoDraftStore.effectiveSource(ctx, sid, PhotoStore.FRAME);
            check("preview effectiveSource points to draft while committed sessionId=null keeps committed names",
                    effLock.contains(PhotoDraftStore.DRAFT_ROOT_DIR)
                            && effFrame.contains(PhotoDraftStore.DRAFT_ROOT_DIR)
                            && PhotoStore.LOCK.equals(PhotoDraftStore.effectiveSource(ctx, null, PhotoStore.LOCK))
                            && PhotoStore.FRAME.equals(PhotoDraftStore.effectiveSource(ctx, null, PhotoStore.FRAME)));
            android.graphics.Bitmap loadedDraftLock = PhotoDraftStore.loadSource(ctx, effLock);
            check("loadSource decodes valid draft path and rejects traversal or outside files",
                    loadedDraftLock != null
                            && loadedDraftLock.getWidth() == 123
                            && PhotoDraftStore.loadSource(ctx, ceExternal.getAbsolutePath() + "/lock_photo.png") == null
                            && PhotoDraftStore.loadSource(ctx, effLock + "/../lock_photo.png") == null);

            // 2. Discard on cancel/back leaves committed state untouched
            String cancelSid = PhotoDraftStore.newSessionId();
            PhotoDraftStore.saveCroppedToDraft(ctx, cancelSid, PhotoStore.LOCK, stagedIn, draftLockBmp, 0.9f, 0.9f, 3.0f);
            PhotoDraftStore.discardSession(ctx, cancelSid);
            check("discardSession deletes draft files and leaves committed lock intact",
                    !PhotoDraftStore.hasDraftSlot(ctx, cancelSid, PhotoStore.LOCK)
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK))));

            // 3. Injected DE-mirror failure mid-commit triggers complete rollback
            PhotoDraftStore.CommitTargetWriter failOnHomeWriter = (c, src, ceDst, deDst, syncDe) -> {
                if (ceDst != null && PhotoStore.HOME.equals(ceDst.getName()) && syncDe) {
                    throw new java.io.IOException("injected DE mirror failure on HOME");
                }
                PhotoDraftStore.DEFAULT_COMMIT_WRITER.writeSlotFile(c, src, ceDst, deDst, syncDe);
            };
            PhotoDraftStore.CommitResult partialFail = PhotoDraftStore.commitDraft(ctx, sid, false, failOnHomeWriter);
            check("commitDraft fails loudly on injected DE failure and rolls back LOCK CE/DE, raw, crop prefs, and follow pref",
                    !partialFail.ok
                            && partialFail.detail.contains("回滚")
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.deFile(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockRaw, readBytesFile(PhotoStore.rawFile(ctx, PhotoStore.LOCK)))
                            && !PhotoStore.file(ctx, PhotoStore.FRAME).exists()
                            && Math.abs(ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .getFloat("zoom_" + PhotoStore.LOCK, 0f) - 1.5f) < 0.001f
                            && ctx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, 0)
                            .getBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, false)
                            && PhotoDraftStore.hasDraftSlot(ctx, sid, PhotoStore.LOCK));

            // 4. Post-commit wallpaper-bind failure rollback (including deleting newly created committed FRAME and DE raws!)
            PhotoDraftStore.CommitResult committed = PhotoDraftStore.commitDraft(ctx, sid, false, null);
            check("commitDraft succeeds cleanly with valid draft, writes newly committed CE+DE+DE_raw+prefs",
                    committed.ok
                            && PhotoStore.file(ctx, PhotoStore.FRAME).isFile()
                            && PhotoStore.deFile(ctx, PhotoStore.FRAME).isFile()
                            && PhotoStore.deFile(ctx, "raw_" + PhotoStore.LOCK).isFile()
                            && PhotoStore.deFile(ctx, "raw_" + PhotoStore.HOME).isFile()
                            && PhotoStore.deFile(ctx, "raw_" + PhotoStore.FRAME).isFile()
                            && Math.abs(ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .getFloat("zoom_" + PhotoStore.LOCK, 0f) - 2.2f) < 0.001f
                            && !ctx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, 0)
                            .getBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, true));
            PhotoDraftStore.RollbackResult postRb = PhotoDraftStore.rollbackTransactionSnapshot(ctx, committed.snapshot, false);
            check("post-commit rollback reverts modified LOCK, deletes newly created FRAME CE+DE+DE_raw, restores prefs & follow",
                    postRb.ok
                            && !committed.snapshot.snapshotDir.exists()
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.deFile(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockRaw, readBytesFile(PhotoStore.rawFile(ctx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockRaw, readBytesFile(PhotoStore.deFile(ctx, "raw_" + PhotoStore.LOCK)))
                            && !PhotoStore.file(ctx, PhotoStore.FRAME).exists()
                            && !PhotoStore.deFile(ctx, PhotoStore.FRAME).exists()
                            && !PhotoStore.deFile(ctx, "raw_" + PhotoStore.FRAME).exists()
                            && Math.abs(ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .getFloat("zoom_" + PhotoStore.LOCK, 0f) - 1.5f) < 0.001f
                            && ctx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, 0)
                            .getBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, false));

            // 5A. Full journal.json + cold-restart (new Context) round-trip across files, partial crop keys, follow, AodStyle, ClockSettings, LastSet
            AodStyleSettings.resetCacheForTest();
            AodStyleSettings.setStyleLocalStrict(ctx, 2);
            ClockSettings.Config initAod = new ClockSettings.Config();
            initAod.enabled = true;
            initAod.style = 3;
            initAod.color = 0xFF60A5FA;
            initAod.scale = 1.25f;
            initAod.weight = 700;
            initAod.offsetY = -12;
            initAod.showLunar = true;
            ClockSettings.Config initLock = new ClockSettings.Config();
            initLock.enabled = false;
            initLock.style = 4;
            initLock.color = 0xFFFBBF24;
            initLock.scale = 0.85f;
            initLock.weight = 350;
            initLock.offsetY = 18;
            initLock.showLunar = false;
            check("ClockSettings.saveLocalStrict & LastSet.saveStrict succeed on seed state",
                    ClockSettings.saveLocalStrict(ctx, initAod, initLock)
                            && LastSet.saveStrict(ctx, LastSet.KIND_PHOTO, "seed/comp"));

            // Ensure HOME has ONLY x_home (y/zoom absent) so presence bitmasks are tested verbatim
            ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0).edit()
                    .putFloat("x_" + PhotoStore.HOME, 0.33f)
                    .remove("y_" + PhotoStore.HOME)
                    .remove("zoom_" + PhotoStore.HOME)
                    .commit();
            byte[] seedDeLockRaw = readBytesFile(PhotoStore.deFile(ctx, "raw_" + PhotoStore.LOCK));

            PhotoDraftStore.CommitSnapshot coldSnap = PhotoDraftStore.beginTransactionSnapshot(ctx);
            String coldTxDirName = coldSnap.snapshotDir.getName();
            // Simulate partial commit mutations before a cold crash (after prefs/module state AND photo/raw writes)
            PhotoDraftStore.commitDraftPhotosIntoTransaction(ctx, coldSnap, sid, false, null);
            AodStyleSettings.setStyleLocalStrict(ctx, 0);
            ClockSettings.Config dirtyAod = new ClockSettings.Config();
            dirtyAod.enabled = false;
            dirtyAod.style = 0;
            ClockSettings.saveLocalStrict(ctx, dirtyAod, dirtyAod);
            LastSet.saveStrict(ctx, LastSet.KIND_DYNAMIC, "evil/Wall");
            ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0).edit()
                    .putFloat("y_" + PhotoStore.HOME, 0.88f)
                    .putFloat("zoom_" + PhotoStore.HOME, 2.5f)
                    .commit();
            // Simulate process restart without calling complete/rollback: clear active in-memory txId & caches, use brand-new Context
            PhotoDraftStore.CommitResult unusedClearCheck = null;
            java.lang.reflect.Field activeTxField = PhotoDraftStore.class.getDeclaredField("ACTIVE_TX_IDS");
            activeTxField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<String> activeTxSet = (java.util.Set<String>) activeTxField.get(null);
            activeTxSet.clear();
            AodStyleSettings.resetCacheForTest();
            android.content.Context coldCtx = new android.content.Context(ceInternal, ceExternal, deInternal);
            PhotoDraftStore.RecoveryResult coldRec = PhotoDraftStore.recoverIncompleteCommits(coldCtx);
            ClockSettings.Config recAod = ClockSettings.getAodDirect(coldCtx);
            ClockSettings.Config recLock = ClockSettings.getLockDirect(coldCtx);
            check("cold restart recovery restores CE+DE slots+raws, exact partial crop key presence, follow, AodStyle, ClockSettings, LastSet",
                    coldRec.ok
                            && coldRec.recoveredCount == 1
                            && !PhotoDraftStore.hasUnresolvedRecovery(coldCtx)
                            && !new java.io.File(new java.io.File(coldCtx.getFilesDir(), PhotoDraftStore.SNAPSHOT_ROOT_DIR), coldTxDirName).exists()
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(coldCtx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.deFile(coldCtx, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(seedDeLockRaw, readBytesFile(PhotoStore.deFile(coldCtx, "raw_" + PhotoStore.LOCK)))
                            && !PhotoStore.file(coldCtx, PhotoStore.FRAME).exists()
                            && !PhotoStore.deFile(coldCtx, "raw_" + PhotoStore.FRAME).exists()
                            && Math.abs(coldCtx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .getFloat("x_" + PhotoStore.HOME, 0f) - 0.33f) < 0.001f
                            && !coldCtx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .contains("y_" + PhotoStore.HOME)
                            && !coldCtx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                            .contains("zoom_" + PhotoStore.HOME)
                            && coldCtx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, 0)
                            .getBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, false)
                            && AodStyleSettings.getStyle(coldCtx) == 2
                            && recAod.isSameStyle(initAod)
                            && recLock.isSameStyle(initLock)
                            && LastSet.kind(coldCtx) == LastSet.KIND_PHOTO
                            && "seed/comp".equals(LastSet.component(coldCtx)));
            ctx = coldCtx;

            // 5B. Restart at 3 points: PENDING after prefs only, PENDING after photo/raw commit, COMMITTED before dir deletion
            // Point 1: PENDING after prefs only
            PhotoDraftStore.CommitSnapshot p1 = PhotoDraftStore.beginTransactionSnapshot(ctx);
            AodStyleSettings.setStyleLocalStrict(ctx, 1);
            LastSet.saveStrict(ctx, LastSet.KIND_FOLLOW, "");
            activeTxSet.clear();
            AodStyleSettings.resetCacheForTest();
            android.content.Context coldP1Ctx = new android.content.Context(ceInternal, ceExternal, deInternal);
            PhotoDraftStore.RecoveryResult rP1 = PhotoDraftStore.recoverIncompleteCommits(coldP1Ctx);
            check("restart at point 1 (PENDING after prefs only) rolls back module prefs & cleans snapshot",
                    rP1.ok && rP1.recoveredCount == 1
                            && AodStyleSettings.getStyle(coldP1Ctx) == 2
                            && LastSet.kind(coldP1Ctx) == LastSet.KIND_PHOTO);
            ctx = coldP1Ctx;
            // Point 3: COMMITTED marker written before snapshot dir delete
            PhotoDraftStore.CommitSnapshot p3 = PhotoDraftStore.beginTransactionSnapshot(ctx);
            PhotoDraftStore.writeTxStateAtomic(p3.snapshotDir, PhotoDraftStore.TX_STATE_COMMITTED);
            LastSet.saveStrict(ctx, LastSet.KIND_DYNAMIC, "committed/Wall");
            activeTxSet.clear();
            AodStyleSettings.resetCacheForTest();
            android.content.Context coldP3Ctx = new android.content.Context(ceInternal, ceExternal, deInternal);
            PhotoDraftStore.RecoveryResult rP3 = PhotoDraftStore.recoverIncompleteCommits(coldP3Ctx);
            check("restart at point 3 (COMMITTED before dir delete) cleans snapshot dir without rolling back committed state",
                    rP3.ok && rP3.cleanedCount == 1 && rP3.recoveredCount == 0
                            && !p3.snapshotDir.exists()
                            && LastSet.kind(coldP3Ctx) == LastSet.KIND_DYNAMIC);
            ctx = coldP3Ctx;
            LastSet.saveStrict(ctx, LastSet.KIND_PHOTO, "seed/comp");

            // 5C. Malformed/arbitrary-path journal, missing .bak, transient recovery failure -> preserved RECOVERY_FAILED + fail-closed -> retry succeeds
            java.io.File evilSnapDir = new java.io.File(
                    new java.io.File(ctx.getFilesDir(), PhotoDraftStore.SNAPSHOT_ROOT_DIR),
                    "tx_12345_evil1");
            evilSnapDir.mkdirs();
            PhotoDraftStore.writeTxStateAtomic(evilSnapDir, PhotoDraftStore.TX_STATE_PENDING);
            org.json.JSONObject evilJournal = new org.json.JSONObject();
            evilJournal.put("version", 1);
            evilJournal.put("txId", "tx_12345_evil1");
            evilJournal.put("createdAtMs", System.currentTimeMillis());
            org.json.JSONArray evilFiles = new org.json.JSONArray();
            org.json.JSONObject evilEntry = new org.json.JSONObject();
            evilEntry.put("key", "../outside_escape.png");
            evilEntry.put("existed", false);
            evilEntry.put("backup", "");
            evilFiles.put(evilEntry);
            evilJournal.put("files", evilFiles);
            writeBytesFile(new java.io.File(evilSnapDir, PhotoDraftStore.SNAPSHOT_JOURNAL_FILE),
                    evilJournal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            PhotoDraftStore.RecoveryResult evilRes = PhotoDraftStore.recoverIncompleteCommits(ctx);
            check("arbitrary/unlisted journal key fails closed, preserves snapshot dir, and blocks new commits",
                    !evilRes.ok && evilRes.failedCount == 1
                            && PhotoDraftStore.hasUnresolvedRecovery(ctx)
                            && evilSnapDir.exists()
                            && !PhotoDraftStore.commitDraft(ctx, sid, false, null).ok);
            PhotoDraftStore.deleteRecursive(evilSnapDir);

            // Missing .bak case
            PhotoDraftStore.CommitSnapshot missBakSnap = PhotoDraftStore.beginTransactionSnapshot(ctx);
            activeTxSet.clear();
            java.io.File[] baks = missBakSnap.snapshotDir.listFiles((d, n) -> n.endsWith(".bak"));
            check("snapshot creates .bak backups", baks != null && baks.length > 0);
            baks[0].delete();
            PhotoDraftStore.RecoveryResult missBakRes = PhotoDraftStore.recoverIncompleteCommits(ctx);
            check("missing .bak file fails closed, transitions tx_state=RECOVERY_FAILED, preserves evidence",
                    !missBakRes.ok && missBakRes.failedCount == 1
                            && PhotoDraftStore.hasUnresolvedRecovery(ctx)
                            && missBakSnap.snapshotDir.exists()
                            && PhotoDraftStore.TX_STATE_RECOVERY_FAILED.equals(PhotoDraftStore.readTxState(missBakSnap.snapshotDir)));
            PhotoDraftStore.deleteRecursive(missBakSnap.snapshotDir);

            // Transient fault injection during recovery -> RECOVERY_FAILED preserves evidence -> retry without fault succeeds
            PhotoDraftStore.CommitSnapshot transSnap = PhotoDraftStore.beginTransactionSnapshot(ctx);
            PhotoDraftStore.commitDraftPhotosIntoTransaction(ctx, transSnap, sid, false, null);
            activeTxSet.clear();
            PhotoDraftStore.setRecoveryFaultInjectorForTest(new PhotoDraftStore.RecoveryFaultInjector() {
                @Override
                public void beforeRestoreFile(String storageKey, java.io.File targetFile) throws java.io.IOException {
                    throw new java.io.IOException("transient EIO during restore");
                }
                @Override
                public void beforeDeleteTarget(String storageKey, java.io.File targetFile) {}
                @Override
                public boolean allowCropPrefsCommit() { return true; }
                @Override
                public boolean allowFollowPrefCommit() { return true; }
                @Override
                public boolean allowModuleStateRestore() { return true; }
            });
            try {
                PhotoDraftStore.RecoveryResult rFail = PhotoDraftStore.recoverIncompleteCommits(ctx);
                EditorActivity.ApplyTxEnv noOpEnv = new EditorActivity.ApplyTxEnv() {
                    @Override public boolean hasValidBackup(android.content.Context c) { return true; }
                    @Override public PreModuleWallpaperBackup.BackupPrepResult ensureBackupBeforeApply(android.content.Context c) {
                        return new PreModuleWallpaperBackup.BackupPrepResult(true, false, "ok");
                    }
                    @Override public PreModuleWallpaperBackup.RollbackResult rollbackCreatedBackupIfUnapplied(
                            android.content.Context c, PreModuleWallpaperBackup.PreApplyBaseline b) {
                        return new PreModuleWallpaperBackup.RollbackResult(true, "ok");
                    }
                    @Override public void syncAliveScope(android.content.Context c, String w) {}
                    @Override public String setDynamicWallpaper(android.content.Context c, String p, String cls) { return "OK dyn"; }
                    @Override public String setDualWallpaper(android.content.Context c, String p, String h, String l) { return "OK dual"; }
                    @Override public PreModuleWallpaperBackup.SlotVerifyResult verifyAppliedSlots(
                            android.content.Context c, boolean d, String p, String h, String du, String l) {
                        return new PreModuleWallpaperBackup.SlotVerifyResult(true, "ok");
                    }
                    @Override public PhotoDraftStore.CommitTargetWriter commitWriter() { return PhotoDraftStore.DEFAULT_COMMIT_WRITER; }
                    @Override public void onStageMessage(String m) {}
                    @Override public void onLog(String m) {}
                };
                EditorActivity.ApplyTxResult blockedApply = EditorActivity.executeApplyTransactionCore(
                        ctx,
                        new EditorActivity.ApplyTxRequest(sid, -1, null, null, LastSet.KIND_PHOTO, null, false),
                        noOpEnv);
                check("transient recovery failure marks RECOVERY_FAILED, preserves snapshot, and blocks new apply",
                        !rFail.ok && PhotoDraftStore.hasUnresolvedRecovery(ctx)
                                && transSnap.snapshotDir.exists()
                                && !blockedApply.ok && blockedApply.abortedBeforeBackupOrUnresolved);
            } finally {
                PhotoDraftStore.setRecoveryFaultInjectorForTest(null);
            }
            PhotoDraftStore.RecoveryResult rRetry = PhotoDraftStore.recoverIncompleteCommits(ctx);
            check("clearing transient fault allows RECOVERY_FAILED retry to succeed and clean up",
                    rRetry.ok && rRetry.recoveredCount == 1
                            && !PhotoDraftStore.hasUnresolvedRecovery(ctx)
                            && !transSnap.snapshotDir.exists()
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK))));

            // Ordinary rollback failure must NOT claim success or delete snapshot evidence
            PhotoDraftStore.CommitSnapshot ordSnap = PhotoDraftStore.beginTransactionSnapshot(ctx);
            PhotoDraftStore.setRecoveryFaultInjectorForTest(new PhotoDraftStore.RecoveryFaultInjector() {
                @Override public void beforeRestoreFile(String k, java.io.File t) {}
                @Override public void beforeDeleteTarget(String k, java.io.File t) {}
                @Override public boolean allowCropPrefsCommit() { return false; }
                @Override public boolean allowFollowPrefCommit() { return true; }
                @Override public boolean allowModuleStateRestore() { return true; }
            });
            try {
                PhotoDraftStore.RollbackResult ordRb = PhotoDraftStore.rollbackTransactionSnapshot(ctx, ordSnap, false);
                check("ordinary rollback failure returns !ok and preserves snapshot evidence",
                        !ordRb.ok && ordRb.snapshotPreserved && ordSnap.snapshotDir.exists());
            } finally {
                PhotoDraftStore.setRecoveryFaultInjectorForTest(null);
            }
            activeTxSet.clear();
            check("subsequent recovery cleans preserved failed rollback snapshot",
                    PhotoDraftStore.recoverIncompleteCommits(ctx).ok && !ordSnap.snapshotDir.exists());

            // 5D. Live session lease, active txId protection against concurrent recovery, and startup async recovery coordination
            String leasedSid = PhotoDraftStore.newSessionId();
            String staleSid = PhotoDraftStore.newSessionId();
            PhotoDraftStore.saveCroppedToDraft(ctx, leasedSid, PhotoStore.LOCK, stagedIn, draftLockBmp, 0.5f, 0.5f, 1.4f);
            PhotoDraftStore.saveCroppedToDraft(ctx, staleSid, PhotoStore.LOCK, stagedIn, draftLockBmp, 0.5f, 0.5f, 1.4f);
            Object leaseOwner = new Object();
            PhotoDraftStore.acquireDraftLease(leasedSid, leaseOwner);
            PhotoDraftStore.cleanupStaleSessions(ctx, null);
            check("cleanupStaleSessions preserves leased active draft session while pruning unleased stale session",
                    PhotoDraftStore.hasDraftSlot(ctx, leasedSid, PhotoStore.LOCK)
                            && !PhotoDraftStore.hasDraftSlot(ctx, staleSid, PhotoStore.LOCK));
            PhotoDraftStore.releaseDraftLeaseAndDiscardIfIdle(ctx, leasedSid, leaseOwner);
            check("releaseDraftLeaseAndDiscardIfIdle discards session once last lease is released",
                    !PhotoDraftStore.hasDraftSlot(ctx, leasedSid, PhotoStore.LOCK));

            PhotoDraftStore.CommitSnapshot activeLiveSnap = PhotoDraftStore.beginTransactionSnapshot(ctx);
            PhotoDraftStore.RecoveryResult concurrentRec = PhotoDraftStore.recoverIncompleteCommits(ctx);
            check("recoverIncompleteCommits does not touch live in-flight ACTIVE_TX_IDS snapshot",
                    concurrentRec.ok && concurrentRec.recoveredCount == 0 && activeLiveSnap.snapshotDir.exists());
            PhotoDraftStore.completeTransactionSnapshot(ctx, activeLiveSnap);
            check("completeTransactionSnapshot deletes finished snapshot dir", !activeLiveSnap.snapshotDir.exists());

            PhotoDraftStore.CommitSnapshot startupSnap = PhotoDraftStore.beginTransactionSnapshot(ctx);
            AodStyleSettings.setStyleLocalStrict(ctx, 4);
            activeTxSet.clear();
            AodStyleSettings.resetCacheForTest();
            PhotoDraftStore.resetStartupRecoveryForTest();
            PhotoDraftStore.triggerStartupRecoveryAsync(ctx);
            PhotoDraftStore.awaitStartupRecoveryIfNonMainThread(ctx);
            check("startup async recovery completes before gated worker proceeds and restores AodStyle",
                    AodStyleSettings.getStyle(ctx) == 2 && !startupSnap.snapshotDir.exists());

            // 5E. EditorActivity.executeApplyTransactionCore full failure/throw rollback + draft preservation + DE raw verification
            final int[] envMode = new int[]{0}; // 0=setDual throws, 1=verify throws, 2=verify returns false
            EditorActivity.ApplyTxEnv failingTxEnv = new EditorActivity.ApplyTxEnv() {
                @Override public boolean hasValidBackup(android.content.Context c) { return true; }
                @Override public PreModuleWallpaperBackup.BackupPrepResult ensureBackupBeforeApply(android.content.Context c) {
                    return new PreModuleWallpaperBackup.BackupPrepResult(true, false, "existing backup ok");
                }
                @Override public PreModuleWallpaperBackup.RollbackResult rollbackCreatedBackupIfUnapplied(
                        android.content.Context c, PreModuleWallpaperBackup.PreApplyBaseline b) {
                    return new PreModuleWallpaperBackup.RollbackResult(true, "ok");
                }
                @Override public void syncAliveScope(android.content.Context c, String w) {}
                @Override public String setDynamicWallpaper(android.content.Context c, String p, String cls) {
                    return "OK dyn";
                }
                @Override public String setDualWallpaper(android.content.Context c, String p, String h, String l) {
                    if (envMode[0] == 0) {
                        throw new RuntimeException("simulated setDualWallpaper binder crash");
                    }
                    return "OK dual bound";
                }
                @Override public PreModuleWallpaperBackup.SlotVerifyResult verifyAppliedSlots(
                        android.content.Context c, boolean d, String p, String h, String du, String l) {
                    if (envMode[0] == 1) {
                        throw new IllegalStateException("simulated wms dump crash");
                    }
                    return new PreModuleWallpaperBackup.SlotVerifyResult(false, "injected slot mismatch");
                }
                @Override public PhotoDraftStore.CommitTargetWriter commitWriter() { return PhotoDraftStore.DEFAULT_COMMIT_WRITER; }
                @Override public void onStageMessage(String m) {}
                @Override public void onLog(String m) {}
            };
            ClockSettings.Config pendingAodNew = initAod.copy();
            pendingAodNew.style = 1;
            EditorActivity.ApplyTxRequest txReq = new EditorActivity.ApplyTxRequest(
                    sid, 4, pendingAodNew, initLock.copy(), LastSet.KIND_PHOTO, null, false);
            for (int m = 0; m < 3; m++) {
                envMode[0] = m;
                EditorActivity.ApplyTxResult txRes = EditorActivity.executeApplyTransactionCore(ctx, txReq, failingTxEnv);
                check("executeApplyTransactionCore mode=" + m + " returns failed & rollbackSucceeded & !snapshotPreserved: " + txRes.fullFailureMessage,
                        !txRes.ok && txRes.rollbackSucceeded && !txRes.snapshotPreserved);
                check("executeApplyTransactionCore mode=" + m + " leaves no unresolved recovery & keeps draft",
                        !PhotoDraftStore.hasUnresolvedRecovery(ctx)
                                && PhotoDraftStore.hasDraftSlot(ctx, sid, PhotoStore.LOCK)
                                && PhotoDraftStore.hasDraftSlot(ctx, sid, PhotoStore.FRAME));
                check("executeApplyTransactionCore mode=" + m + " rolls back CE/DE photos and DE raws",
                        java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK)))
                                && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.deFile(ctx, PhotoStore.LOCK)))
                                && java.util.Arrays.equals(seedDeLockRaw, readBytesFile(PhotoStore.deFile(ctx, "raw_" + PhotoStore.LOCK)))
                                && !PhotoStore.file(ctx, PhotoStore.FRAME).exists()
                                && !PhotoStore.deFile(ctx, "raw_" + PhotoStore.FRAME).exists());
                check("executeApplyTransactionCore mode=" + m + " rolls back cropPrefs, follow, AodStyle, ClockSettings, LastSet",
                        Math.abs(ctx.getSharedPreferences(PhotoDraftStore.COMMITTED_CROP_PREFS, 0)
                                .getFloat("zoom_" + PhotoStore.LOCK, 0f) - 1.5f) < 0.001f
                                && ctx.getSharedPreferences(PhotoDraftStore.EDITOR_PREF_NAME, 0)
                                .getBoolean(PhotoDraftStore.KEY_HOME_FOLLOW_LOCK, false)
                                && AodStyleSettings.getStyle(ctx) == 2
                                && ClockSettings.getAodDirect(ctx).style == 3
                                && LastSet.kind(ctx) == LastSet.KIND_PHOTO
                                && "seed/comp".equals(LastSet.component(ctx)));
            }

            // 5F. Draft revision atomicity: commit()==false or hook failure during saveCroppedToDraft / loadDefaultExampleToDraft / syncFrameToLockDraftWithDefaultCrop + abandoned stageFromUri
            byte[] priorDraftLockBytes = readBytesFile(PhotoDraftStore.slotFile(ctx, sid, PhotoStore.LOCK));
            byte[] priorDraftLockRaw = readBytesFile(PhotoDraftStore.rawFile(ctx, sid, PhotoStore.LOCK));
            byte[] defaultJpgHeader = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03, 0x04};
            android.content.res.AssetManager.sAssets.put("photo/default.jpg", defaultJpgHeader);

            android.content.Context.sFailCommitForPrefPrefix = PhotoDraftStore.DRAFT_PREF_PREFIX;
            boolean cropFailed = false;
            try {
                PhotoDraftStore.saveCroppedToDraft(
                        ctx, sid, PhotoStore.LOCK, stagedIn, new android.graphics.Bitmap(77, 88), 0.1f, 0.2f, 3.3f);
            } catch (Throwable expected) {
                cropFailed = true;
            } finally {
                android.content.Context.sFailCommitForPrefPrefix = null;
            }
            check("saveCroppedToDraft commit()==false throws and preserves prior draft slot+raw+cropPrefs",
                    cropFailed
                            && java.util.Arrays.equals(priorDraftLockBytes, readBytesFile(PhotoDraftStore.slotFile(ctx, sid, PhotoStore.LOCK)))
                            && java.util.Arrays.equals(priorDraftLockRaw, readBytesFile(PhotoDraftStore.rawFile(ctx, sid, PhotoStore.LOCK)))
                            && Math.abs(PhotoDraftStore.cropPrefs(ctx, sid).getFloat("zoom_" + PhotoStore.LOCK, 0f) - 2.2f) < 0.001f);

            byte[] priorDraftFrameBytes = readBytesFile(PhotoDraftStore.slotFile(ctx, sid, PhotoStore.FRAME));
            PhotoDraftStore.setDraftRevisionFaultHookForTest((s, sl) -> false);
            try {
                boolean defOk = PhotoDraftStore.loadDefaultExampleToDraft(ctx, sid, PhotoStore.LOCK);
                boolean syncOk = PhotoDraftStore.syncFrameToLockDraftWithDefaultCrop(ctx, sid);
                check("loadDefaultExampleToDraft & syncFrameToLockDraftWithDefaultCrop return false on hook failure and keep prior draft intact",
                        !defOk && !syncOk
                                && java.util.Arrays.equals(priorDraftLockBytes, readBytesFile(PhotoDraftStore.slotFile(ctx, sid, PhotoStore.LOCK)))
                                && java.util.Arrays.equals(priorDraftFrameBytes, readBytesFile(PhotoDraftStore.slotFile(ctx, sid, PhotoStore.FRAME)))
                                && Math.abs(PhotoDraftStore.cropPrefs(ctx, sid).getFloat("zoom_" + PhotoStore.LOCK, 0f) - 2.2f) < 0.001f);
            } finally {
                PhotoDraftStore.setDraftRevisionFaultHookForTest(null);
                android.content.res.AssetManager.sAssets.clear();
            }

            boolean abandonedStageThrew = false;
            try {
                PhotoDraftStore.stageFromUri(ctx, sid, null, PhotoStore.LOCK);
            } catch (Throwable expected) {
                abandonedStageThrew = true;
            }
            check("failed/abandoned stageFromUri never clears prior draft cropPrefs or slot",
                    abandonedStageThrew
                            && java.util.Arrays.equals(priorDraftLockBytes, readBytesFile(PhotoDraftStore.slotFile(ctx, sid, PhotoStore.LOCK)))
                            && Math.abs(PhotoDraftStore.cropPrefs(ctx, sid).getFloat("zoom_" + PhotoStore.LOCK, 0f) - 2.2f) < 0.001f);

            // 5G. Draft revision rollback failure must preserve rev_prev recovery files, report actual failure, mark session unusable until repaired/discarded
            PhotoDraftStore.setDraftRevisionRollbackFaultHookForTest(new PhotoDraftStore.DraftRevisionRollbackFaultHook() {
                @Override public void beforeRollbackSlotFile(String s, String sl) {}
                @Override public boolean allowDeleteSlotOnRollback(String s, String sl) { return true; }
                @Override public void beforeRollbackRawFile(String s, String sl) {}
                @Override public boolean allowDeleteRawOnRollback(String s, String sl) { return true; }
                @Override public boolean allowRollbackPrefsCommit(String s, String sl) { return false; }
            });
            android.content.Context.sFailCommitForPrefPrefix = PhotoDraftStore.DRAFT_PREF_PREFIX;
            String revFailMsg = "";
            try {
                PhotoDraftStore.saveCroppedToDraft(
                        ctx, sid, PhotoStore.LOCK, stagedIn, new android.graphics.Bitmap(91, 92), 0.2f, 0.3f, 2.9f);
            } catch (Throwable expected) {
                revFailMsg = String.valueOf(expected.getMessage());
            } finally {
                android.content.Context.sFailCommitForPrefPrefix = null;
                PhotoDraftStore.setDraftRevisionRollbackFaultHookForTest(null);
            }
            java.io.File[] revPrevAfterFail = PhotoDraftStore.sessionDir(ctx, sid).listFiles(
                    (d, n) -> n.startsWith(PhotoStore.LOCK + ".rev_prev."));
            EditorActivity.ApplyTxResult blockedCorruptApply = EditorActivity.executeApplyTransactionCore(
                    ctx,
                    new EditorActivity.ApplyTxRequest(sid, -1, null, null, LastSet.KIND_PHOTO, null, false),
                    failingTxEnv);
            check("draft revision rollback failure reports rollback status, preserves rev_prev backup, marks session corrupt, and blocks commitDraft & executeApplyTransactionCore",
                    revFailMsg.contains("回滚")
                            && revPrevAfterFail != null && revPrevAfterFail.length >= 1
                            && PhotoDraftStore.hasCorruptRevision(ctx, sid)
                            && !PhotoDraftStore.commitDraft(ctx, sid, false, null).ok
                            && !blockedCorruptApply.ok
                            && blockedCorruptApply.abortedBeforeBackupOrUnresolved
                            && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(ctx, PhotoStore.LOCK))));
            // Repair the corrupted slot via clean saveCroppedToDraft -> clears corrupt marker & allows clean apply/usage again
            PhotoDraftStore.saveCroppedToDraft(ctx, sid, PhotoStore.LOCK, stagedIn, draftLockBmp, 0.6f, 0.7f, 2.2f);
            java.io.File[] revPrevAfterRepair = PhotoDraftStore.sessionDir(ctx, sid).listFiles(
                    (d, n) -> n.startsWith(PhotoStore.LOCK + ".rev_prev."));
            check("repairing corrupted draft slot clears corrupt marker and cleans old rev_prev backups",
                    !PhotoDraftStore.hasCorruptRevision(ctx, sid)
                            && (revPrevAfterRepair == null || revPrevAfterRepair.length == 0));

            // 5H. completeTransactionSnapshot & rollbackTransactionSnapshot COMMITTED persistence failure vs cleanup-only failure
            final int[] txHookMode = new int[]{0}; // 0=fail writeTxState(COMMITTED), 1=fail deleteSnapshotDir after COMMITTED succeeds
            PhotoDraftStore.setTxLifecycleFaultHookForTest(new PhotoDraftStore.TxLifecycleFaultHook() {
                @Override
                public void beforeWriteTxState(java.io.File snapDir, String targetState) throws java.io.IOException {
                    if (txHookMode[0] == 0 && PhotoDraftStore.TX_STATE_COMMITTED.equals(targetState)) {
                        throw new java.io.IOException("injected EIO on tx_state=COMMITTED");
                    }
                }
                @Override
                public boolean allowDeleteSnapshotDir(java.io.File snapDir) {
                    return txHookMode[0] != 1;
                }
            });
            final int[] backupRollbackCalls = new int[]{0};
            final java.io.File snapRootDir = new java.io.File(ctx.getFilesDir(), PhotoDraftStore.SNAPSHOT_ROOT_DIR);
            EditorActivity.ApplyTxEnv succeedWithCreatedBackupEnv = new EditorActivity.ApplyTxEnv() {
                @Override public boolean hasValidBackup(android.content.Context c) { return false; }
                @Override public PreModuleWallpaperBackup.BackupPrepResult ensureBackupBeforeApply(android.content.Context c) {
                    return new PreModuleWallpaperBackup.BackupPrepResult(true, true, "created backup");
                }
                @Override public PreModuleWallpaperBackup.RollbackResult rollbackCreatedBackupIfUnapplied(
                        android.content.Context c, PreModuleWallpaperBackup.PreApplyBaseline b) {
                    backupRollbackCalls[0]++;
                    return new PreModuleWallpaperBackup.RollbackResult(true, "cleaned");
                }
                @Override public void syncAliveScope(android.content.Context c, String w) {}
                @Override public String setDynamicWallpaper(android.content.Context c, String p, String cls) { return "OK dyn"; }
                @Override public String setDualWallpaper(android.content.Context c, String p, String h, String l) { return "OK dual"; }
                @Override public PreModuleWallpaperBackup.SlotVerifyResult verifyAppliedSlots(
                        android.content.Context c, boolean d, String p, String h, String du, String l) {
                    return new PreModuleWallpaperBackup.SlotVerifyResult(true, "ok");
                }
                @Override public PhotoDraftStore.CommitTargetWriter commitWriter() { return PhotoDraftStore.DEFAULT_COMMIT_WRITER; }
                @Override public void onStageMessage(String m) {}
                @Override public void onLog(String m) {}
            };

            try {
                // H1: writeTxStateAtomic(COMMITTED) throws -> must NOT delete journal, must NOT report success, must NOT delete backup
                txHookMode[0] = 0;
                EditorActivity.ApplyTxResult h1Res = EditorActivity.executeApplyTransactionCore(
                        ctx,
                        new EditorActivity.ApplyTxRequest(sid, AodStyleSettings.STYLE_LEAVE_LIGHT, pendingAodNew, initLock.copy(), LastSet.KIND_PHOTO, null, false),
                        succeedWithCreatedBackupEnv);
                java.io.File[] h1Dirs = snapRootDir.listFiles();
                check("completeTransactionSnapshot/rollback COMMITTED write failure does not claim success, preserves PENDING snapshot journal, and preserves backup",
                        !h1Res.ok
                                && !h1Res.rollbackSucceeded
                                && h1Res.snapshotPreserved
                                && backupRollbackCalls[0] == 0
                                && h1Dirs != null && h1Dirs.length == 1
                                && PhotoDraftStore.TX_STATE_PENDING.equals(PhotoDraftStore.readTxState(h1Dirs[0]))
                                && new java.io.File(h1Dirs[0], PhotoDraftStore.SNAPSHOT_JOURNAL_FILE).isFile());
                PhotoDraftStore.setTxLifecycleFaultHookForTest(null);
                activeTxSet.clear();
                AodStyleSettings.resetCacheForTest();
                android.content.Context afterH1Ctx = new android.content.Context(ceInternal, ceExternal, deInternal);
                PhotoDraftStore.RecoveryResult h1Rec = PhotoDraftStore.recoverIncompleteCommits(afterH1Ctx);
                check("restart after COMMITTED write failure recovers preserved journal back to prior state",
                        h1Rec.ok && h1Rec.recoveredCount == 1
                                && AodStyleSettings.getStyle(afterH1Ctx) == 2
                                && java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(afterH1Ctx, PhotoStore.LOCK))));
                ctx = afterH1Ctx;

                // H2: writeTxStateAtomic(COMMITTED) succeeds, deleteSnapshotDir fails -> apply succeeds durably, next restart cleans dir WITHOUT undoing applied state
                PhotoDraftStore.setTxLifecycleFaultHookForTest(new PhotoDraftStore.TxLifecycleFaultHook() {
                    @Override public void beforeWriteTxState(java.io.File snapDir, String targetState) {}
                    @Override public boolean allowDeleteSnapshotDir(java.io.File snapDir) { return false; }
                });
                EditorActivity.ApplyTxResult h2Res = EditorActivity.executeApplyTransactionCore(
                        ctx,
                        new EditorActivity.ApplyTxRequest(sid, AodStyleSettings.STYLE_LEAVE_LIGHT, pendingAodNew, initLock.copy(), LastSet.KIND_PHOTO, null, false),
                        succeedWithCreatedBackupEnv);
                java.io.File[] h2Dirs = snapRootDir.listFiles();
                check("cleanup failure after durable COMMITTED returns ok=true: " + h2Res.fullFailureMessage,
                        h2Res.ok);
                check("cleanup failure after durable COMMITTED does not trigger unresolved recovery",
                        !PhotoDraftStore.hasUnresolvedRecovery(ctx));
                check("cleanup failure after durable COMMITTED keeps 1 COMMITTED snapshot dir with AodStyle==1",
                        h2Dirs != null && h2Dirs.length == 1
                                && PhotoDraftStore.TX_STATE_COMMITTED.equals(PhotoDraftStore.readTxState(h2Dirs[0]))
                                && AodStyleSettings.getStyle(ctx) == AodStyleSettings.STYLE_LEAVE_LIGHT);
                PhotoDraftStore.setTxLifecycleFaultHookForTest(null);
                activeTxSet.clear();
                AodStyleSettings.resetCacheForTest();
                android.content.Context afterH2Ctx = new android.content.Context(ceInternal, ceExternal, deInternal);
                PhotoDraftStore.RecoveryResult h2Rec = PhotoDraftStore.recoverIncompleteCommits(afterH2Ctx);
                check("next restart cleans leftover COMMITTED snapshot without undoing reported successful apply",
                        h2Rec.ok && h2Rec.cleanedCount == 1 && h2Rec.recoveredCount == 0
                                && !h2Dirs[0].exists()
                                && AodStyleSettings.getStyle(afterH2Ctx) == AodStyleSettings.STYLE_LEAVE_LIGHT
                                && !java.util.Arrays.equals(committedLockBytes, readBytesFile(PhotoStore.file(afterH2Ctx, PhotoStore.LOCK))));
                ctx = afterH2Ctx;
            } finally {
                PhotoDraftStore.setTxLifecycleFaultHookForTest(null);
            }

            PhotoDraftStore.releaseDraftLeaseAndDiscardIfIdle(ctx, sid, sidLease);

            // 6. ModuleDiagLog bounded tail-after-filter command, exit-code preservation, redaction, spam filter, and status distinction
            String hookCmd = ModuleDiagLog.buildHookLogcatCommand(ModuleDiagLog.MAX_HOOK_LOGCAT_LINES);
            int sIdx = hookCmd.indexOf("-s QihangLSP:* QihangAod:* PreModuleBackup:*");
            int tailIdx = hookCmd.indexOf("| tail -n " + ModuleDiagLog.MAX_HOOK_LOGCAT_LINES);
            ModuleDiagLog.LogcatResult maskedErrParsed = ModuleDiagLog.parseHookLogcatSuOutput(
                    "logcat: unknown error\n__DIAG_LC_RC=1 __DIAG_TAIL_RC=0\n");
            ModuleDiagLog.LogcatResult okMatchedParsed = ModuleDiagLog.parseHookLogcatSuOutput(
                    "11-14 10:00:00.000 I/QihangLSP(123): card injected\n__DIAG_LC_RC=0 __DIAG_TAIL_RC=0\n");
            check("buildHookLogcatCommand filters before tail without -t and preserves logcat exit code against tail=0",
                    sIdx >= 0 && tailIdx > sIdx
                            && !hookCmd.contains("-t 250")
                            && !maskedErrParsed.ok
                            && maskedErrParsed.rootAvailable
                            && okMatchedParsed.ok
                            && okMatchedParsed.output.contains("card injected"));

            ModuleDiagLog.clearForTesting(ctx);
            for (int i = 0; i < 200; i++) {
                ModuleDiagLog.record(ctx, "操作事件 #" + i);
            }
            ModuleDiagLog.record(ctx, "AOD自转帧率=120.0fps（目标=120.0Hz）");
            ModuleDiagLog.record(ctx, "异常调试 password=mySecret99 pin: 123456");
            ModuleDiagLog.DiagSnapshot noRootSnap = ModuleDiagLog.collectDiagnostics(
                    ctx,
                    timeout -> new ModuleDiagLog.LogcatResult(false, false, "", "su denied"));
            ModuleDiagLog.DiagSnapshot rootFailSnap = ModuleDiagLog.collectDiagnostics(
                    ctx,
                    timeout -> new ModuleDiagLog.LogcatResult(true, false, "", "logcat failed rc=1"));
            ModuleDiagLog.DiagSnapshot rootEmptySnap = ModuleDiagLog.collectDiagnostics(
                    ctx,
                    timeout -> new ModuleDiagLog.LogcatResult(true, true, "", ""));
            ModuleDiagLog.DiagSnapshot rootSnap = ModuleDiagLog.collectDiagnostics(
                    ctx,
                    timeout -> new ModuleDiagLog.LogcatResult(true, true,
                            "11-14 10:00:00.000 I/QihangLSP(123): card injected\n", ""));
            check("ModuleDiagLog bounds lines, drops FPS spam, redacts secrets, distinguishes no-root / root-collection-fail / root-empty / root-success subtitles",
                    ModuleDiagLog.readAppLogs(ctx).size() == ModuleDiagLog.MAX_MEMORY_LINES
                            && !noRootSnap.rootAvailable
                            && !noRootSnap.hookCollectionOk
                            && noRootSnap.hasAppLogs
                            && noRootSnap.formattedText.contains("未获取 Root 权限")
                            && !noRootSnap.formattedText.contains("mySecret99")
                            && !noRootSnap.formattedText.contains("123456")
                            && !noRootSnap.formattedText.contains("AOD自转帧率")
                            && rootFailSnap.rootAvailable
                            && !rootFailSnap.hookCollectionOk
                            && !rootFailSnap.hasHookLogs
                            && rootFailSnap.formattedText.contains("logcat failed")
                            && MainActivity.resolveDiagSubtitleText(rootFailSnap).contains("读取失败")
                            && rootEmptySnap.rootAvailable
                            && rootEmptySnap.hookCollectionOk
                            && !rootEmptySnap.hasHookLogs
                            && MainActivity.resolveDiagSubtitleText(rootEmptySnap).contains("暂无匹配记录")
                            && rootSnap.rootAvailable
                            && rootSnap.hookCollectionOk
                            && rootSnap.hasHookLogs
                            && rootSnap.formattedText.contains("card injected")
                            && MainActivity.resolveDiagSubtitleText(rootSnap).contains("实时日志"));
        } catch (Throwable t) {
            throw new AssertionError("runDraftAndRollbackAndDiagTests threw: " + t, t);
        } finally {
            PhotoDraftStore.setTxLifecycleFaultHookForTest(null);
            PhotoDraftStore.setDraftRevisionRollbackFaultHookForTest(null);
            DirectBootContext.sForceDeForTest = null;
            if (tmpRoot != null) {
                PhotoDraftStore.deleteRecursive(tmpRoot);
            }
        }
    }

    public static boolean checkSha256SumHelper(String out, String expected) {
        return PreModuleWallpaperBackup.verifySha256SumOutput(out, expected);
    }

    public static HyperOsWallpaperHelper.LegacyDefaultSafetyResult checkLegacyDefaultHelper(
            PreModuleWallpaperBackup.BackupMetadata meta,
            HyperOsWallpaperHelper.Gateway gw,
            String bridgeRaw) {
        return PreModuleWallpaperBackup.checkLegacyDefaultWithSources(meta, gw, bridgeRaw);
    }

    public static boolean checkShouldRequireOemHelper(
            int wpId, String wpPkg, String wpCls, String bindSource) {
        return PreModuleWallpaperBackup.shouldRequireOemCrossValidation(
                null, makeXml(wpId, wpPkg, wpCls, bindSource, false, -1, "", ""));
    }

    public static String checkClassifyMiuiStaticHelper(
            HyperOsWallpaperHelper.OemProbeResult oemRes,
            int sysId, int lockId,
            String wpPkg, String wpCls, String bindSource,
            boolean hasIndependentLock,
            boolean stdSysAbsent, boolean stdLockAbsent, boolean capturedPlainStatic) {
        return checkClassifyMiuiSplitHelper(
                oemRes, sysId, lockId, wpPkg, wpCls, bindSource,
                hasIndependentLock, stdSysAbsent, stdLockAbsent, capturedPlainStatic, false);
    }

    public static String checkClassifyMiuiSplitHelper(
            HyperOsWallpaperHelper.OemProbeResult oemRes,
            int sysId, int lockId,
            String wpPkg, String wpCls, String bindSource,
            boolean hasIndependentLock,
            boolean stdSysAbsent, boolean stdLockAbsent, boolean capturedPlainStatic,
            boolean divergedSplitStatic) {
        PreModuleWallpaperBackup.WallpaperXmlState xml =
                makeXml(sysId, wpPkg, wpCls, bindSource, false, -1, "", "");
        return PreModuleWallpaperBackup.classifyMiuiStaticSystemSlot(
                oemRes, xml, sysId, lockId, null,
                hasIndependentLock, stdSysAbsent, stdLockAbsent, capturedPlainStatic, divergedSplitStatic).choice.name();
    }

    public static boolean checkSplitDivergedHelper(
            HyperOsWallpaperHelper.OemProbeResult oemRes,
            boolean sysStdPresent, String sysStdSha256,
            boolean lockStdPresent, String lockStdSha256) {
        return PreModuleWallpaperBackup.isSplitStandardDivergedFromOem(
                oemRes, sysStdPresent, sysStdSha256, lockStdPresent, lockStdSha256);
    }

    public static org.json.JSONObject checkPrepareOemRestoreSpecHelper(
            java.io.File userDir, org.json.JSONObject metaJson) throws Exception {
        PreModuleWallpaperBackup.BackupMetadata meta = new PreModuleWallpaperBackup.BackupMetadata();
        meta.version = metaJson.optInt("version", 2);
        meta.userId = metaJson.optInt("userId", 0);
        meta.systemType = metaJson.optString("system_type", "OEM_HYPEROS");
        meta.lockType = metaJson.optString("lock_type", "OEM_HYPEROS");
        meta.oemClockTemplateId = metaJson.optString("oem_clock_template_id", "");
        meta.oemJson = metaJson.optJSONObject("oem");
        meta.valid = true;
        return PreModuleWallpaperBackup.prepareOemRestoreSpec(meta, userDir);
    }

    public static int runRewriteUnitChecks() throws Exception {
        unitAssertions = 0;

        // 11a. LspState strict token matching & enabled=0 priority
        String fullScopes = "enabled=1\nscope=system,com.android.systemui,miui.systemui.plugin,com.miui.aod,com.android.thememanager\n";
        String disabledWithFullScopes = "enabled=0\nscope=system,com.android.systemui,miui.systemui.plugin,com.miui.aod,com.android.thememanager\n";
        String missingSystemScope = "enabled=1\nscope=com.android.systemui,miui.systemui.plugin,com.miui.aod,com.android.thememanager\n";
        check("LspState enabled=0 takes priority over scopes and running lspd",
                LspState.evaluateLspDbAndProcess(disabledWithFullScopes, "2") == LspState.State.DISABLED);
        check("LspState enabled=1 with all 5 core scopes and lspd>0 is ENABLED",
                LspState.evaluateLspDbAndProcess(fullScopes, "1") == LspState.State.ENABLED);
        check("LspState com.android.systemui never false-matches missing 'system' scope",
                !LspState.hasScopeToken(missingSystemScope, "system")
                        && !LspState.hasRequiredCoreScopes(missingSystemScope)
                        && LspState.evaluateLspDbAndProcess(missingSystemScope, "3") == LspState.State.UNKNOWN);
        check("LspState enabled=1 with lspd=0 or error is UNKNOWN",
                LspState.evaluateLspDbAndProcess(fullScopes, "0") == LspState.State.UNKNOWN
                        && LspState.evaluateLspDbAndProcess(fullScopes, "ERR timeout") == LspState.State.UNKNOWN);

        // 11b. PreModuleWallpaperBackup.parseRawSettingOutput (CRLF preservation, trailing LF, empty, absent vs literal "null", full trailer validation)
        String crlfVal = "  {\"line1\": \"a\",\r\n  \"line2\": \"b\"}\r\n";
        PreModuleWallpaperBackup.SettingRecord recMulti = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n" + crlfVal + "\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:0\n");
        check("parseRawSettingOutput preserves CRLF, trailing LF and outer spaces verbatim",
                recMulti.ok && recMulti.existed && crlfVal.equals(recMulti.value)
                        && recMulti.value.contains("\r\n") && recMulti.value.endsWith("\r\n"));

        PreModuleWallpaperBackup.SettingRecord recEmpty = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:0\n");
        check("parseRawSettingOutput preserves empty string as existed=true, value=''",
                recEmpty.ok && recEmpty.existed && "".equals(recEmpty.value));

        PreModuleWallpaperBackup.SettingRecord recAbsent = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\nnull\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:0\n");
        check("parseRawSettingOutput identifies absent key when settings list lacks key=null",
                recAbsent.ok && !recAbsent.existed && "".equals(recAbsent.value));

        PreModuleWallpaperBackup.SettingRecord recLiteralNull = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\nnull\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:1\n");
        check("parseRawSettingOutput preserves literal 'null' when settings list has key=null",
                recLiteralNull.ok && recLiteralNull.existed && "null".equals(recLiteralNull.value));

        PreModuleWallpaperBackup.SettingRecord recMissingTrailer = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n1\n__QH_END__:0\n");
        check("parseRawSettingOutput rejects non-null value missing trailer", !recMissingTrailer.ok);

        PreModuleWallpaperBackup.SettingRecord recBadListRcNonNull = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n1\n__QH_END__:0\n__QH_LIST_RC__:1\n");
        check("parseRawSettingOutput rejects non-null value with failed LIST_RC", !recBadListRcNonNull.ok);

        PreModuleWallpaperBackup.SettingRecord recConflictLiteralFlag = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\nnull\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:0\n__QH_LITERAL_NULL__:1\n");
        check("parseRawSettingOutput rejects conflicting duplicate __QH_LITERAL_NULL__ flags", !recConflictLiteralFlag.ok);

        PreModuleWallpaperBackup.SettingRecord recContradictNonNull = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n1\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:1\n");
        check("parseRawSettingOutput rejects non-null value contradicting literalNull=1", !recContradictNonNull.ok);

        PreModuleWallpaperBackup.SettingRecord recDupEnd = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n1\n__QH_END__:0\n__QH_END__:0\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:0\n");
        check("parseRawSettingOutput rejects duplicate __QH_END__ marker", !recDupEnd.ok);

        PreModuleWallpaperBackup.SettingRecord recErrExit = PreModuleWallpaperBackup.parseRawSettingOutput(
                "__QH_BEGIN__\n1\n__QH_END__:1\n__QH_LIST_RC__:0\n__QH_LITERAL_NULL__:0\n");
        check("parseRawSettingOutput rejects non-zero exit code", !recErrExit.ok);

        PreModuleWallpaperBackup.SettingRecord recTimeout = PreModuleWallpaperBackup.parseRawSettingOutput(
                "suRaw 失败: timeout after 5000ms");
        check("parseRawSettingOutput rejects suRaw failure output", !recTimeout.ok);

        // 11c. PreModuleWallpaperBackup.parseBatchProbeOutput (valid, symlink ERR, truncated, duplicate, out-of-range, non-zero exit)
        PreModuleWallpaperBackup.ProbeStatus[] batchOk = PreModuleWallpaperBackup.parseBatchProbeOutput(
                "__P:0:EXISTS\n__P:1:ABSENT\n__P:2:EXISTS\n__EXIT:0\n", 3);
        check("parseBatchProbeOutput parses valid 3-item batch",
                batchOk.length == 3
                        && batchOk[0] == PreModuleWallpaperBackup.ProbeStatus.EXISTS
                        && batchOk[1] == PreModuleWallpaperBackup.ProbeStatus.ABSENT
                        && batchOk[2] == PreModuleWallpaperBackup.ProbeStatus.EXISTS);

        PreModuleWallpaperBackup.ProbeStatus[] batchSymlink = PreModuleWallpaperBackup.parseBatchProbeOutput(
                "__P:0:EXISTS\n__P:1:ERR\n__EXIT:0\n", 2);
        check("parseBatchProbeOutput maps symlink/non-regular ERR to ProbeStatus.ERROR",
                batchSymlink[0] == PreModuleWallpaperBackup.ProbeStatus.EXISTS
                        && batchSymlink[1] == PreModuleWallpaperBackup.ProbeStatus.ERROR);

        PreModuleWallpaperBackup.ProbeStatus[] batchTruncated = PreModuleWallpaperBackup.parseBatchProbeOutput(
                "__P:0:EXISTS\n__EXIT:0\n", 2);
        check("parseBatchProbeOutput rejects truncated output missing index",
                batchTruncated[0] == PreModuleWallpaperBackup.ProbeStatus.ERROR
                        && batchTruncated[1] == PreModuleWallpaperBackup.ProbeStatus.ERROR);

        PreModuleWallpaperBackup.ProbeStatus[] batchDup = PreModuleWallpaperBackup.parseBatchProbeOutput(
                "__P:0:EXISTS\n__P:0:EXISTS\n__EXIT:0\n", 2);
        check("parseBatchProbeOutput rejects duplicate index",
                batchDup[0] == PreModuleWallpaperBackup.ProbeStatus.ERROR
                        && batchDup[1] == PreModuleWallpaperBackup.ProbeStatus.ERROR);

        PreModuleWallpaperBackup.ProbeStatus[] batchOob = PreModuleWallpaperBackup.parseBatchProbeOutput(
                "__P:0:EXISTS\n__P:5:EXISTS\n__EXIT:0\n", 2);
        check("parseBatchProbeOutput rejects out-of-range index",
                batchOob[0] == PreModuleWallpaperBackup.ProbeStatus.ERROR
                        && batchOob[1] == PreModuleWallpaperBackup.ProbeStatus.ERROR);

        PreModuleWallpaperBackup.ProbeStatus[] batchExit1 = PreModuleWallpaperBackup.parseBatchProbeOutput(
                "__P:0:EXISTS\n__P:1:EXISTS\n__EXIT:1\n", 2);
        check("parseBatchProbeOutput rejects non-zero __EXIT",
                batchExit1[0] == PreModuleWallpaperBackup.ProbeStatus.ERROR
                        && batchExit1[1] == PreModuleWallpaperBackup.ProbeStatus.ERROR);

        // 11d. HyperOsWallpaperHelper & HyperOsWallpaperBridge canonicalization consistency
        check("canonicalizeComponent matches between Helper and Bridge",
                "com.miui.miwallpaper/com.miui.miwallpaper.wallpaperservice.ImageWallpaper".equals(
                        HyperOsWallpaperHelper.canonicalizeComponent(
                                "ComponentInfo{com.miui.miwallpaper/.wallpaperservice.ImageWallpaper}")));

        // 11e. Root.runProcessWithDeadline timeout, InterruptedException, stream read error, and synchronized stream close non-blocking
        String javaExe = new java.io.File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        String cp = System.getProperty("java.class.path");
        Process pFast = new ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start();
        String fastOut = Root.runProcessWithDeadline(pFast, 5000L, false);
        check("runProcessWithDeadline completes normal process",
                fastOut != null && !fastOut.startsWith("ERR "));

        Process pHang = new ProcessBuilder(javaExe, "-cp", cp, "com.hyperalive.aod.Phase2RegressionBridge").redirectErrorStream(true).start();
        String hangOut = Root.runProcessWithDeadline(pHang, 150L, false);
        check("runProcessWithDeadline returns explicit ERR timeout on hung process",
                hangOut != null && hangOut.startsWith("ERR timeout after "));

        Process pHangRaw = new ProcessBuilder(javaExe, "-cp", cp, "com.hyperalive.aod.Phase2RegressionBridge").redirectErrorStream(true).start();
        String hangRawOut = Root.runProcessWithDeadline(pHangRaw, 150L, true);
        check("runProcessWithDeadline returns explicit suRaw timeout on hung process in rawMode",
                hangRawOut != null && hangRawOut.startsWith("suRaw 失败: timeout after "));

        // Simulated Process whose InputStream throws IOException after emitting partial bytes while process exits 0
        Process pBrokenStream = createSimulatedProcess(true, false);
        String brokenOut = Root.runProcessWithDeadline(pBrokenStream, 2000L, false);
        check("runProcessWithDeadline rejects truncated stream on IOException",
                brokenOut != null && brokenOut.startsWith("ERR stream read error:") && !brokenOut.contains("partial_payload"));

        Process pBrokenRaw = createSimulatedProcess(true, false);
        String brokenRawOut = Root.runProcessWithDeadline(pBrokenRaw, 2000L, true);
        check("runProcessWithDeadline rawMode rejects truncated stream on IOException",
                brokenRawOut != null && brokenRawOut.startsWith("suRaw 失败: stream read error:"));

        // Simulated Process whose InputStream holds synchronized monitor in read() forever and close() is also synchronized
        final boolean[] destroyCalledFirst = new boolean[]{false};
        Process pSyncMonitor = createSimulatedLockingStreamProcess(destroyCalledFirst);
        long t0 = System.nanoTime();
        String syncMonitorOut = Root.runProcessWithDeadline(pSyncMonitor, 80L, false);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        check("runProcessWithDeadline destroys process first and returns boundedly even if stream.close() blocks on read monitor",
                destroyCalledFirst[0] && syncMonitorOut != null && syncMonitorOut.startsWith("ERR timeout after ") && elapsedMs < 1500L);

        final boolean[] interruptRestored = new boolean[]{false};
        final boolean[] caughtInterrupt = new boolean[]{false};
        Thread waiter = new Thread(() -> {
            Process pInt = null;
            try {
                pInt = new ProcessBuilder(javaExe, "-cp", cp, "com.hyperalive.aod.Phase2RegressionBridge").redirectErrorStream(true).start();
                Thread.currentThread().interrupt();
                Root.runProcessWithDeadline(pInt, 5000L, false);
            } catch (InterruptedException ie) {
                caughtInterrupt[0] = true;
                interruptRestored[0] = Thread.currentThread().isInterrupted();
            } catch (Throwable ignored) {
            } finally {
                if (pInt != null) pInt.destroyForcibly();
            }
        });
        waiter.start();
        waiter.join(3000);
        check("runProcessWithDeadline rethrows InterruptedException and restores interrupt flag",
                caughtInterrupt[0] && interruptRestored[0]);

        // 11f. MaskFrames real production buffer pool: object identity tracking, content/position mutation, refCount, non-eviction of in-use, bounded allocation & concurrent close
        final int[] decodeCalls = new int[]{0};
        MaskFrames mf = new MaskFrames(61, 120, 483667, 64, new MaskFrames.FrameDecoder() {
            @Override
            public boolean decode(int index, java.nio.ByteBuffer out) {
                decodeCalls[0]++;
                out.clear();
                // Write identical bytes for frame 0 and frame 1 initially, then mutate position/content
                byte fill = (index <= 1) ? (byte) 0x42 : (byte) (index & 0x7F);
                for (int i = 0; i < 64; i++) {
                    out.put(i, fill);
                }
                out.position((index % 7) + 1);
                return true;
            }
        }, false);

        java.nio.ByteBuffer b0 = mf.getOrDecodeSync(0);
        java.nio.ByteBuffer b1 = mf.getOrDecodeSync(1);
        check("MaskFrames allocates two distinct buffers even when initial/decoded content is identical",
                b0 != null && b1 != null && b0 != b1
                        && mf.inUseDistinctCountForTest() == 2
                        && mf.inUseRefCountForTest(b0) == 1
                        && mf.inUseRefCountForTest(b1) == 1);

        // Mutate b0 content and position as GL upload or decoder would do, then verify identity tracking still finds & releases b0
        b0.put(0, (byte) 0x99);
        b0.position(13);
        java.nio.ByteBuffer b0Again = mf.getOrDecodeSync(0);
        check("MaskFrames reference-counts repeated acquisition of same cached buffer by identity",
                b0Again == b0 && mf.inUseRefCountForTest(b0) == 2);
        mf.releaseInUse(b0Again);
        check("MaskFrames first releaseInUse decrements refCount to 1 and keeps buffer in use",
                mf.inUseRefCountForTest(b0) == 1 && mf.isBufferInUseLocked(b0));
        mf.releaseInUse(b1);
        check("MaskFrames releaseInUse removes b1 after content/position change",
                mf.inUseRefCountForTest(b1) == 0 && mf.inUseDistinctCountForTest() == 1);

        // Hold b0 (frame 0) in-use while decoding 25 other frames (> CACHE_LIMIT=16 and > MAX_ALLOCATED=20)
        for (int idx = 2; idx < 27; idx++) {
            java.nio.ByteBuffer tmp = mf.getOrDecodeSync(idx);
            check("MaskFrames decode under pressure succeeds for frame " + idx, tmp != null && tmp != b0);
            check("MaskFrames allocatedCount never exceeds MAX_ALLOCATED (" + MaskFrames.MAX_ALLOCATED + ")",
                    mf.allocatedCountForTest() <= MaskFrames.MAX_ALLOCATED);
            mf.releaseInUse(tmp);
        }
        check("MaskFrames never evicts in-use buffer b0 from cache during heavy LRU churn",
                mf.isCachedIdentityForTest(0, b0) && b0.get(0) == (byte) 0x99 && mf.inUseRefCountForTest(b0) == 1);
        mf.releaseInUse(b0);
        check("MaskFrames releases b0 to refCount=0 when consumer finishes",
                mf.inUseRefCountForTest(b0) == 0 && mf.inUseDistinctCountForTest() == 0);
        mf.close();

        // Concurrent close() while decode is in flight
        final java.util.concurrent.CountDownLatch decodeEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowDecodeFinish = new java.util.concurrent.CountDownLatch(1);
        final MaskFrames mfCloseRace = new MaskFrames(61, 120, 483667, 64, new MaskFrames.FrameDecoder() {
            @Override
            public boolean decode(int index, java.nio.ByteBuffer out) {
                decodeEntered.countDown();
                try {
                    allowDecodeFinish.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                out.put(0, (byte) 0x55);
                return true;
            }
        }, false);
        final java.nio.ByteBuffer[] raceResult = new java.nio.ByteBuffer[1];
        Thread decThread = new Thread(() -> {
            raceResult[0] = mfCloseRace.getOrDecodeSync(5);
        });
        decThread.start();
        check("concurrent decode entered", decodeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        mfCloseRace.close();
        check("while decode is in flight during close(), inUse count is 1",
                mfCloseRace.inUseDistinctCountForTest() == 1 && mfCloseRace.allocatedCountForTest() == 1);
        allowDecodeFinish.countDown();
        decThread.join(2000);
        check("concurrent close() causes in-flight getOrDecodeSync to return null and drop buffer without pooling",
                raceResult[0] == null
                        && mfCloseRace.inUseDistinctCountForTest() == 0
                        && mfCloseRace.allocatedCountForTest() == 0
                        && mfCloseRace.poolSizeForTest() == 0
                        && mfCloseRace.cacheSizeForTest() == 0);

        // 11g. StateCoordinator TTL expiry -> fail-open -> same-owner refresh recovery & Power queue epoch validation
        com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot prevOwnedSnap =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot(true, true, true, 5L, 1000L);
        com.hyperalive.aod.hook.StateCoordinator.OwnershipDetail sameOwnerDetail =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipDetail(true, true);
        check("within TTL (t=8500ms < 11000ms), same-owner refresh does not trigger redundant lock recovery",
                !com.hyperalive.aod.hook.StateCoordinator.didSlotOwnershipRecover(
                        prevOwnedSnap, 8500L, 10000L, 5L,
                        com.hyperalive.aod.hook.StateCoordinator.SLOT_LOCK, sameOwnerDetail));
        check("after TTL expiry (t=11005ms >= 11000ms) where fast path failed open, same-owner refresh triggers lock recovery",
                com.hyperalive.aod.hook.StateCoordinator.didSlotOwnershipRecover(
                        prevOwnedSnap, 11005L, 10000L, 5L,
                        com.hyperalive.aod.hook.StateCoordinator.SLOT_LOCK, sameOwnerDetail));
        com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot refreshedSnap =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot(true, true, true, 5L, 11005L);
        check("main-thread recovery check succeeds when epoch matches and refreshed snapshot is active",
                com.hyperalive.aod.hook.StateCoordinator.shouldApplyRecoveredLockState(
                        refreshedSnap, 11020L, 10000L, 5L, 5L));
        check("main-thread recovery check aborts if ownership epoch bumped during refresh/post",
                !com.hyperalive.aod.hook.StateCoordinator.shouldApplyRecoveredLockState(
                        refreshedSnap, 11020L, 10000L, 6L, 5L));

        com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot prevHomeOnlySnap =
                new com.hyperalive.aod.hook.StateCoordinator.OwnershipSnapshot(true, true, false, 5L, 5000L);
        check("when homeOwned stays true and lockOwned transitions false->true within TTL, only lockRecovered is true",
                !com.hyperalive.aod.hook.StateCoordinator.didSlotOwnershipRecover(
                        prevHomeOnlySnap, 7000L, 10000L, 5L,
                        com.hyperalive.aod.hook.StateCoordinator.SLOT_HOME, sameOwnerDetail)
                        && com.hyperalive.aod.hook.StateCoordinator.didSlotOwnershipRecover(
                                prevHomeOnlySnap, 7000L, 10000L, 5L,
                                com.hyperalive.aod.hook.StateCoordinator.SLOT_LOCK, sameOwnerDetail));

        check("Power queue task is valid when generation, epoch, and lock ownership match",
                com.hyperalive.aod.hook.StateCoordinator.isPowerTaskStillValid(
                        12L, 12L, 5L, 5L, refreshedSnap, 11500L, 10000L));
        check("Power queue task is invalidated immediately when ownerEpoch changes or TTL expires",
                !com.hyperalive.aod.hook.StateCoordinator.isPowerTaskStillValid(
                        12L, 12L, 5L, 6L, refreshedSnap, 11500L, 10000L)
                        && !com.hyperalive.aod.hook.StateCoordinator.isPowerTaskStillValid(
                                12L, 12L, 5L, 5L, refreshedSnap, 22000L, 10000L));

        // 11h. MainActivity.StatusProbeCoordinator single-in-flight coalescing & lifecycle cancellation
        MainActivity.StatusProbeCoordinator probeCoord = new MainActivity.StatusProbeCoordinator();
        final java.util.concurrent.atomic.AtomicInteger activeProbes = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger maxConcurrentProbes = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger totalProbeRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch firstProbeStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseFirstProbe = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finalDelivered = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Long> deliveredSeqs = java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());

        MainActivity.StatusProbeCoordinator.ResultConsumer consumer =
                new MainActivity.StatusProbeCoordinator.ResultConsumer() {
                    @Override
                    public void onProbeCompleted(long seq, MainActivity.StatusProbeCoordinator.ProbeResult result) {
                        deliveredSeqs.add(seq);
                        finalDelivered.countDown();
                    }
                };
        MainActivity.StatusProbeCoordinator.ProbeAction coalescedAction =
                new MainActivity.StatusProbeCoordinator.ProbeAction() {
                    @Override
                    public MainActivity.StatusProbeCoordinator.ProbeResult execute() {
                        int cur = activeProbes.incrementAndGet();
                        synchronized (maxConcurrentProbes) {
                            if (cur > maxConcurrentProbes.get()) {
                                maxConcurrentProbes.set(cur);
                            }
                        }
                        int runNum = totalProbeRuns.incrementAndGet();
                        try {
                            if (runNum == 1) {
                                firstProbeStarted.countDown();
                                try {
                                    releaseFirstProbe.await(2, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return new MainActivity.StatusProbeCoordinator.ProbeResult(true, LspState.State.ENABLED);
                        } finally {
                            activeProbes.decrementAndGet();
                        }
                    }
                };

        long seq1 = probeCoord.requestProbe(consumer, coalescedAction, null);
        check("first probe started", firstProbeStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        // Simulate rapid pause -> resume -> resume while first probe is still blocked
        probeCoord.cancelConsumer(consumer);
        long seq2 = probeCoord.requestProbe(consumer, coalescedAction, null);
        long seq3 = probeCoord.requestProbe(consumer, coalescedAction, null);
        releaseFirstProbe.countDown();
        check("coalesced follow-up probe delivered", finalDelivered.await(2, java.util.concurrent.TimeUnit.SECONDS));

        // Verify fast/preempted completion before requestProbe returns + queued main-thread callback seq validation
        final java.util.concurrent.atomic.AtomicLong fastSeqSink = new java.util.concurrent.atomic.AtomicLong(0L);
        final java.util.concurrent.atomic.AtomicBoolean seenMatchingSeqOnWorker = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.List<Runnable> simulatedMainQueue = new java.util.ArrayList<>();
        final java.util.List<Long> appliedUiSeqs = new java.util.ArrayList<>();
        MainActivity.StatusProbeCoordinator.ResultConsumer fastConsumer =
                new MainActivity.StatusProbeCoordinator.ResultConsumer() {
                    @Override
                    public void onProbeCompleted(final long seq, final MainActivity.StatusProbeCoordinator.ProbeResult result) {
                        if (seq == fastSeqSink.get()) {
                            seenMatchingSeqOnWorker.set(true);
                        }
                        simulatedMainQueue.add(new Runnable() {
                            @Override
                            public void run() {
                                if (seq == fastSeqSink.get()) {
                                    appliedUiSeqs.add(seq);
                                }
                            }
                        });
                    }
                };
        MainActivity.StatusProbeCoordinator.ProbeAction instantAction =
                new MainActivity.StatusProbeCoordinator.ProbeAction() {
                    @Override
                    public MainActivity.StatusProbeCoordinator.ProbeResult execute() {
                        return new MainActivity.StatusProbeCoordinator.ProbeResult(false, LspState.State.UNKNOWN);
                    }
                };
        java.util.concurrent.Executor inlineExecutor = new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        long fastSeq1 = probeCoord.requestProbe(fastConsumer, fastSeqSink, instantAction, inlineExecutor);
        for (Runnable r : simulatedMainQueue) {
            r.run();
        }
        simulatedMainQueue.clear();
        long fastSeq2 = probeCoord.requestProbe(fastConsumer, fastSeqSink, instantAction, inlineExecutor);
        probeCoord.cancelConsumer(fastConsumer, fastSeqSink);
        for (Runnable r : simulatedMainQueue) {
            r.run();
        }
        simulatedMainQueue.clear();

        check("StatusProbeCoordinator runs at most 1 probe at a time, coalesces intermediate requests, publishes seq before worker completion, and invalidates queued UI callback on cancel",
                maxConcurrentProbes.get() == 1
                        && totalProbeRuns.get() == 2
                        && deliveredSeqs.size() == 1
                        && deliveredSeqs.get(0) == seq3
                        && seq3 > seq2 && seq2 > seq1
                        && seenMatchingSeqOnWorker.get()
                        && appliedUiSeqs.size() == 1
                        && appliedUiSeqs.get(0) == fastSeq1
                        && fastSeqSink.get() > fastSeq2);

        // 11i. LibraryActivity atomic VariantCacheSnapshot publication (rejects stale scan overwrite)
        LibraryActivity.resetVariantCacheForTest();
        java.util.Map<String, Integer> v1Map = new java.util.HashMap<>();
        v1Map.put("com.flyme.alivewallpaper/.A", 1);
        java.util.Map<String, Integer> v2Map = new java.util.HashMap<>();
        v2Map.put("com.flyme.alivewallpaper/.B", 201);
        LibraryActivity.tryPublishVariantCacheSnapshot(200L, 200L, v2Map);
        LibraryActivity.tryPublishVariantCacheSnapshot(100L, 200L, v1Map);
        LibraryActivity.tryPublishVariantCacheSnapshot(100L, 100L, v1Map);
        LibraryActivity.VariantCacheSnapshot curCache = LibraryActivity.getVariantCacheForTest();
        check("LibraryActivity atomic VariantCacheSnapshot rejects stale scan from overwriting newer package updateTime",
                curCache.updateTime == 200L
                        && Integer.valueOf(201).equals(curCache.map.get("com.flyme.alivewallpaper/.B"))
                        && !curCache.map.containsKey("com.flyme.alivewallpaper/.A"));

        // 11j. Bug 2 regression: LspState.queryLspState(nonNullCtx, false) & evaluateLspState tri-state checks
        String activeDbOut = "enabled=1 "
                + "scope=system,com.android.systemui,miui.systemui.plugin,com.miui.aod,com.android.thememanager,com.hyperalive.aod\n";
        android.content.Context bugCtx = new android.content.Context(null);
        check("LspState.queryLspState(nonNullCtx, false) returns UNKNOWN directly without relying on sticky markers",
                LspState.queryLspState(bugCtx, false) == LspState.State.UNKNOWN
                        && LspState.queryLspState(null, true) == LspState.State.UNKNOWN);
        check("LspState.evaluateLspState returns UNKNOWN when root is revoked even if DB & lspd look active",
                LspState.evaluateLspState(false, activeDbOut, "1") == LspState.State.UNKNOWN);
        check("LspState.evaluateLspState returns ENABLED when root is granted, DB enabled, core scopes & lspd present",
                LspState.evaluateLspState(true, activeDbOut, "1") == LspState.State.ENABLED);
        check("LspState.evaluateLspState returns DISABLED when root is granted and DB disabled",
                LspState.evaluateLspState(true, "enabled=0 scope=system,com.android.systemui,com.miui.aod,com.hyperalive.aod\n", "1")
                        == LspState.State.DISABLED);
        check("LspState.evaluateLspState returns UNKNOWN when core scope missing",
                LspState.evaluateLspState(true, "enabled=1 scope=system,com.hyperalive.aod\n", "1")
                        == LspState.State.UNKNOWN);
        check("MainActivity tri-state LSP label formatting & status OK flag match state",
                "LSP 已激活".equals(MainActivity.formatLspStatusLabel(LspState.State.ENABLED))
                        && "LSP 已停用".equals(MainActivity.formatLspStatusLabel(LspState.State.DISABLED))
                        && "LSP 状态未知".equals(MainActivity.formatLspStatusLabel(LspState.State.UNKNOWN))
                        && "LSP 状态未知".equals(MainActivity.formatLspStatusLabel(null))
                        && MainActivity.isLspStatusOk(LspState.State.ENABLED)
                        && !MainActivity.isLspStatusOk(LspState.State.DISABLED)
                        && !MainActivity.isLspStatusOk(LspState.State.UNKNOWN));

        // 11k. Bug 3 regression: Real LensRenderer instance preview switches must NOT corrupt committed AodStyleSettings
        AodStyleSettings.setStyle(bugCtx, AodStyleSettings.STYLE_LEAVE_LIGHT);
        LensRenderer leaveLightRenderer = new LensRenderer(bugCtx, (android.view.Surface) null);
        leaveLightRenderer.setAodStyle(AodStyleSettings.STYLE_SET_SAIL);
        int styleAfterStalePreview = AodStyleSettings.getStyle(bugCtx);
        leaveLightRenderer.setAodStyle(AodStyleSettings.STYLE_MOON);
        int styleAfterDraftMoon = AodStyleSettings.getStyle(bugCtx);

        AodStyleSettings.setStyle(bugCtx, AodStyleSettings.STYLE_MOON);
        LensRenderer moonRenderer = new LensRenderer(bugCtx, (android.view.Surface) null);
        moonRenderer.setAodStyle(AodStyleSettings.STYLE_SET_SAIL);
        int moonStyleAfterCancel = AodStyleSettings.getStyle(bugCtx);
        check("Real LensRenderer.setAodStyle preview/stale calls never overwrite committed AodStyleSettings (style 1 & 2)",
                styleAfterStalePreview == AodStyleSettings.STYLE_LEAVE_LIGHT
                        && styleAfterDraftMoon == AodStyleSettings.STYLE_LEAVE_LIGHT
                        && moonStyleAfterCancel == AodStyleSettings.STYLE_MOON
                        && LensRenderer.normalizeAodStyle(99) == AodStyleSettings.STYLE_SET_SAIL);

        // 11l. Bug 4 regression: EditorActivity apply transaction lock, neutral preparing copy & launch failure cleanup
        try {
            boolean firstAcquire = EditorActivity.tryBeginApplyTransaction();
            boolean secondAcquire = EditorActivity.tryBeginApplyTransaction();
            EditorActivity.endApplyTransaction();
            boolean reacquire = EditorActivity.tryBeginApplyTransaction();
            EditorActivity.endApplyTransaction();
            check("EditorActivity.tryBeginApplyTransaction prevents concurrent duplicate clicks and releases cleanly",
                    firstAcquire && !secondAcquire && reacquire);
        } finally {
            EditorActivity.endApplyTransaction();
        }
        check("EditorActivity apply progress messages distinguish preparing vs missing backup vs valid backup vs applying stage",
                "正在准备备份…".equals(EditorActivity.resolvePreparingApplyProgressMessage())
                        && EditorActivity.resolveInitialApplyProgressMessage(false).contains("正在备份原壁纸")
                        && "正在校验已有备份…".equals(EditorActivity.resolveInitialApplyProgressMessage(true))
                        && "正在应用壁纸与配置…".equals(EditorActivity.resolveApplyingStageProgressMessage()));

        final boolean[] cleanupCalledOnUiFail = {false};
        final boolean[] workerCalledOnUiFail = {false};
        boolean uiFailCaught = false;
        try {
            EditorActivity.executeApplyTransactionWithCleanup(
                    new Runnable() { @Override public void run() {} },
                    new Runnable() { @Override public void run() { throw new RuntimeException("ui boom"); } },
                    new Runnable() { @Override public void run() { workerCalledOnUiFail[0] = true; } },
                    new Runnable() { @Override public void run() { cleanupCalledOnUiFail[0] = true; } });
        } catch (RuntimeException expected) {
            uiFailCaught = true;
        }
        boolean lockReleasedAfterUiFail = EditorActivity.tryBeginApplyTransaction();
        EditorActivity.endApplyTransaction();
        check("EditorActivity releases APPLY_IN_FLIGHT & runs UI cleanup if showUi throws",
                uiFailCaught && cleanupCalledOnUiFail[0] && !workerCalledOnUiFail[0] && lockReleasedAfterUiFail);

        final boolean[] cleanupCalledOnLaunchFail = {false};
        boolean launchFailCaught = false;
        try {
            EditorActivity.executeApplyTransactionWithCleanup(
                    new Runnable() { @Override public void run() {} },
                    new Runnable() { @Override public void run() {} },
                    new Runnable() { @Override public void run() { throw new RuntimeException("thread start OOM"); } },
                    new Runnable() { @Override public void run() { cleanupCalledOnLaunchFail[0] = true; } });
        } catch (RuntimeException expected) {
            launchFailCaught = true;
        }
        boolean lockReleasedAfterLaunchFail = EditorActivity.tryBeginApplyTransaction();
        EditorActivity.endApplyTransaction();
        check("EditorActivity releases APPLY_IN_FLIGHT & runs UI cleanup if worker thread launch throws",
                launchFailCaught && cleanupCalledOnLaunchFail[0] && lockReleasedAfterLaunchFail);

        final boolean[] dupCalled = {false};
        final boolean[] cleanupOnSuccess = {false};
        boolean launchedOk = EditorActivity.executeApplyTransactionWithCleanup(
                new Runnable() { @Override public void run() {} },
                new Runnable() { @Override public void run() {} },
                new Runnable() { @Override public void run() {} },
                new Runnable() { @Override public void run() { cleanupOnSuccess[0] = true; } });
        boolean dupRejected = !EditorActivity.executeApplyTransactionWithCleanup(
                new Runnable() { @Override public void run() { dupCalled[0] = true; } },
                new Runnable() { @Override public void run() {} },
                new Runnable() { @Override public void run() {} },
                new Runnable() { @Override public void run() {} });
        EditorActivity.endApplyTransaction();
        check("EditorActivity holds APPLY_IN_FLIGHT when worker launches cleanly and rejects duplicate clicks",
                launchedOk && !cleanupOnSuccess[0] && dupRejected && dupCalled[0]);

        // 11m. Bug 1 regression: Real exact-signature hook installation & preview vs full-res apply isolation
        runBug1RealHookTests(bugCtx);

        return unitAssertions;
    }

    private static void runBug1RealHookTests(android.content.Context ctx) {
        final android.graphics.Bitmap previewLockBmp = new android.graphics.Bitmap(540, 1200);
        final android.graphics.Bitmap previewHomeBmp = new android.graphics.Bitmap(540, 1200);
        final android.graphics.Bitmap fullLockBmp = new android.graphics.Bitmap(1080, 2400);
        final android.graphics.Bitmap fullHomeBmp = new android.graphics.Bitmap(1080, 2400);

        final boolean[] lockOwned = {true};
        final boolean[] homeOwned = {true};
        final boolean[] allowLegacyVep5 = {false};

        final com.hyperalive.aod.hook.WallpaperPreviewHook.PreviewReader previewReader =
                new com.hyperalive.aod.hook.WallpaperPreviewHook.PreviewReader() {
                    @Override
                    public android.graphics.Bitmap read(android.content.Context c, String slot) {
                        if ("home".equals(slot)) return homeOwned[0] ? previewHomeBmp : null;
                        return lockOwned[0] ? previewLockBmp : null;
                    }
                };
        final com.hyperalive.aod.hook.WallpaperPreviewHook.PreviewReader fullResReader =
                new com.hyperalive.aod.hook.WallpaperPreviewHook.PreviewReader() {
                    @Override
                    public android.graphics.Bitmap read(android.content.Context c, String slot) {
                        if ("home".equals(slot)) return homeOwned[0] ? fullHomeBmp : null;
                        return lockOwned[0] ? fullLockBmp : null;
                    }
                };
        final com.hyperalive.aod.hook.WallpaperPreviewHook.OwnershipChecker ownershipChecker =
                new com.hyperalive.aod.hook.WallpaperPreviewHook.OwnershipChecker() {
                    @Override
                    public boolean isOwned(android.content.Context c, String slot) {
                        return "home".equals(slot) ? homeOwned[0] : lockOwned[0];
                    }
                };

        final java.util.Map<String, de.robv.android.xposed.XC_MethodHook> tmHooks =
                new java.util.HashMap<String, de.robv.android.xposed.XC_MethodHook>();
        final java.util.Map<String, de.robv.android.xposed.XC_MethodHook> aodHooks =
                new java.util.HashMap<String, de.robv.android.xposed.XC_MethodHook>();

        com.hyperalive.aod.hook.WallpaperPreviewHook.HookRegistrar tmRegistrar =
                new com.hyperalive.aod.hook.WallpaperPreviewHook.HookRegistrar() {
                    @Override
                    public boolean hookExact(
                            Class<?> clazz, String methodName, de.robv.android.xposed.XC_MethodHook hook, Class<?>... paramTypes) {
                        if ("vep5".equals(methodName) && !allowLegacyVep5[0]) {
                            return false;
                        }
                        try {
                            clazz.getDeclaredMethod(methodName, paramTypes);
                            tmHooks.put(clazz.getName() + "#" + methodName, hook);
                            return true;
                        } catch (NoSuchMethodException e) {
                            throw new AssertionError("Missing exact method " + clazz.getName() + "#" + methodName, e);
                        }
                    }
                };
        com.hyperalive.aod.hook.WallpaperPreviewHook.HookRegistrar aodRegistrar =
                new com.hyperalive.aod.hook.WallpaperPreviewHook.HookRegistrar() {
                    @Override
                    public boolean hookExact(
                            Class<?> clazz, String methodName, de.robv.android.xposed.XC_MethodHook hook, Class<?>... paramTypes) {
                        try {
                            clazz.getDeclaredMethod(methodName, paramTypes);
                            aodHooks.put(clazz.getName() + "#" + methodName, hook);
                            return true;
                        } catch (NoSuchMethodException e) {
                            throw new AssertionError("Missing exact method " + clazz.getName() + "#" + methodName, e);
                        }
                    }
                };

        ClassLoader cl = Phase2RegressionBridge.class.getClassLoader();
        // Notice BaseTemplateView declares optionalMiuiPlayer(com.optional.missing.BrokenPlayerDep) whose .class is deleted!
        // Any getDeclaredMethods()/getMethods() call inside WallpaperPreviewHook would throw NoClassDefFoundError here!
        com.hyperalive.aod.hook.WallpaperPreviewHook.installThemeManager(
                cl, tmRegistrar, previewReader, ownershipChecker);
        com.hyperalive.aod.hook.WallpaperPreviewHook.installAod(
                cl, aodRegistrar, previewReader, fullResReader, ownershipChecker);
        com.miui.keyguard.editor.data.template.TemplateApiImpl.ACTIVE_HOOKS = aodHooks;

        check("Exact-signature WallpaperPreviewHook installs ThemeManager & AOD hooks without BrokenPlayerDep LinkageError",
                tmHooks.containsKey("com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder#createOrLoadTemplateView")
                        && tmHooks.containsKey("com.miui.keyguard.editor.edit.base.BaseTemplateView#createWallpaperLayer")
                        && aodHooks.containsKey("com.miui.keyguard.editor.edit.base.BaseTemplateView#createWallpaperLayer")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#getCurrentWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#applyEditPageTemplate")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#needApplyAll")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#applyWallpaperWithConfig")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#applyImageWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#applyGalleryWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#applySensorWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#applySensorOuterVideo")
                        && aodHooks.containsKey("com.miui.keyguard.editor.edit.wallpaper.WallpaperController#applyVideoWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.edit.wallpaper.WallpaperController#applySuperWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.edit.wallpaper.WallpaperController#applyImageLockWallpaper")
                        && aodHooks.containsKey("com.miui.keyguard.editor.data.template.TemplateApiImpl#showCantApplyThirdPartyWallpaperToast")
                        && aodHooks.containsKey("com.miui.keyguard.editor.edit.base.TemplateApplier#applyTemplate"));

        // Scenario A: ThemeManager card binding uses preview thumbnail ONLY (isThirdPartyWallpaper stays true, positionInfo stays null)
        com.miui.keyguard.editor.data.bean.CommonConfig cardCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        true, null, null, null, "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder holder =
                new com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder(ctx);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam holderParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        holderParam.thisObject = holder;
        holderParam.args = new Object[]{"all_in_one", cardCfg};
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    tmHooks.get("com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder#createOrLoadTemplateView"),
                    holderParam);
        } catch (Throwable t) {
            throw new AssertionError("SettingsMyTemplateViewHolder hook threw", t);
        }
        check("ThemeManager card hook injects inSampleSize=2 preview ONLY, keeping isThirdPartyWallpaper=true & positionInfo=null",
                cardCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == previewLockBmp
                        && cardCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == previewHomeBmp
                        && cardCfg.getLockscreenInfo().isThirdPartyWallpaper()
                        && cardCfg.getHomeInfo().isThirdPartyWallpaper()
                        && cardCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() == null
                        && cardCfg.getHomeInfo().getWallpaperInfo().getPositionInfo() == null);

        // Scenario B: Formal apply (clock/template edit without changing wallpaper) upgrades cardCfg preview -> full-res (1080x2400) + matching PositionInfo,
        // preserves isThirdPartyWallpaper=true so live wallpaper is not replaced with static image, and suppresses misleading toast ONLY when Lock is owned
        com.miui.keyguard.editor.edit.base.BaseTemplateView editorView =
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cardCfg, false);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam applierParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        applierParam.args = new Object[]{editorView, Boolean.TRUE};
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    aodHooks.get("com.miui.keyguard.editor.edit.base.TemplateApplier#applyTemplate"),
                    applierParam);
        } catch (Throwable t) {
            throw new AssertionError("TemplateApplier.applyTemplate hook threw", t);
        }
        check("TemplateApplier.applyTemplate upgrades preview to full-res (1080x2400), builds matching PositionInfo, and preserves isThirdPartyWallpaper=true",
                cardCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == fullLockBmp
                        && cardCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == fullHomeBmp
                        && cardCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() != null
                        && cardCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo().getRight() == 1080f
                        && cardCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo().getBottom() == 2400f
                        && cardCfg.getHomeInfo().getWallpaperInfo().getPositionInfo().getRight() == 1080f
                        && cardCfg.getLockscreenInfo().isThirdPartyWallpaper()
                        && cardCfg.getHomeInfo().isThirdPartyWallpaper());

        com.miui.keyguard.editor.data.template.TemplateApiImpl apiImpl =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, true, true);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam toastParamOwned =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        toastParamOwned.thisObject = apiImpl;
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    aodHooks.get("com.miui.keyguard.editor.data.template.TemplateApiImpl#showCantApplyThirdPartyWallpaperToast"),
                    toastParamOwned);
        } catch (Throwable t) {
            throw new AssertionError("Toast hook threw", t);
        }
        check("showCantApplyThirdPartyWallpaperToast is suppressed only while owned Lock retains third-party live service",
                toastParamOwned.isReturnEarly());

        // Scenario C: User explicitly changed wallpaper in editor (getWallpaperHasChanged == true) -> hooks stay completely out of the way
        final android.graphics.Bitmap nativePickedBmp = new android.graphics.Bitmap(720, 1600);
        com.miui.keyguard.editor.data.bean.CommonConfig changedWallCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativePickedBmp, null, "image", "LOCK_WALLPAPER",
                        false, nativePickedBmp, null, "image", "HOME_WALLPAPER");
        com.miui.keyguard.editor.edit.base.BaseTemplateView changedView =
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, changedWallCfg, true);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam changedApplierParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        changedApplierParam.args = new Object[]{changedView, Boolean.TRUE};
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    aodHooks.get("com.miui.keyguard.editor.edit.base.TemplateApplier#applyTemplate"),
                    changedApplierParam);
        } catch (Throwable t) {
            throw new AssertionError("Changed wallpaper TemplateApplier hook threw", t);
        }
        check("TemplateApplier hook leaves newly picked editor wallpaper completely untouched when getWallpaperHasChanged()==true",
                changedWallCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == nativePickedBmp
                        && changedWallCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() == null
                        && !changedWallCfg.getLockscreenInfo().isThirdPartyWallpaper());

        // Scenario D: Dynamic ownership revocation between preview and apply -> apply hook & toast hook refuse to interfere
        com.miui.keyguard.editor.data.bean.CommonConfig revokedCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        true, previewLockBmp, null, null, "LOCK_WALLPAPER",
                        true, previewHomeBmp, null, null, "HOME_WALLPAPER");
        com.miui.keyguard.editor.edit.base.BaseTemplateView revokedView =
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, revokedCfg, false);
        lockOwned[0] = false;
        homeOwned[0] = false;
        de.robv.android.xposed.XC_MethodHook.MethodHookParam revokedParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        revokedParam.args = new Object[]{revokedView, Boolean.TRUE};
        de.robv.android.xposed.XC_MethodHook.MethodHookParam toastParamUnowned =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        toastParamUnowned.thisObject = apiImpl;
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    aodHooks.get("com.miui.keyguard.editor.edit.base.TemplateApplier#applyTemplate"),
                    revokedParam);
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    aodHooks.get("com.miui.keyguard.editor.data.template.TemplateApiImpl#showCantApplyThirdPartyWallpaperToast"),
                    toastParamUnowned);
        } catch (Throwable t) {
            throw new AssertionError("Revoked ownership hook threw", t);
        }
        check("Dynamic ownership revocation before Apply prevents full-res injection, leaves positionInfo=null, and does not suppress native toast",
                revokedCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == previewLockBmp
                        && revokedCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() == null
                        && !toastParamUnowned.isReturnEarly());

        // Scenario E: Mixed slots (Lock owned, Home unowned) -> only Lock prepared; Home untouched
        lockOwned[0] = true;
        homeOwned[0] = false;
        com.miui.keyguard.editor.data.bean.CommonConfig mixedCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        true, null, null, null, "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        int mixedPrepped = com.hyperalive.aod.hook.WallpaperPreviewHook.prepareCommonConfigForNativeApply(
                ctx, mixedCfg, false, fullResReader, ownershipChecker, cl);
        check("Mixed slot ownership prepares ONLY owned Lock full-res & PositionInfo and leaves unowned Home untouched",
                mixedPrepped == 1
                        && mixedCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == fullLockBmp
                        && mixedCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() != null
                        && mixedCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == null
                        && mixedCfg.getHomeInfo().getWallpaperInfo().getPositionInfo() == null);

        // Scenario F: Setter / readback failure triggers conservative rollback without clearing isThirdPartyWallpaper
        com.miui.keyguard.editor.data.bean.WallpaperInfo droppingPosInfo =
                new com.miui.keyguard.editor.data.bean.WallpaperInfo(
                        previewLockBmp, null, null, "LOCK_WALLPAPER", true);
        com.miui.keyguard.editor.data.bean.TemplateConfig lockSlot =
                new com.miui.keyguard.editor.data.bean.TemplateConfig(true, droppingPosInfo);
        boolean prepOkOnBrokenSetter = com.hyperalive.aod.hook.WallpaperPreviewHook.prepareSlotForNativeApply(
                ctx, lockSlot, droppingPosInfo, "lock", fullResReader, ownershipChecker, cl);
        check("Setter/readback failure aborts conservatively, rolls back currentWallpaper/resourceType, and never clears isThirdPartyWallpaper",
                !prepOkOnBrokenSetter
                        && droppingPosInfo.getCurrentWallpaper() == previewLockBmp
                        && droppingPosInfo.getResourceType() == null
                        && lockSlot.isThirdPartyWallpaper());

        // Scenario G: BaseTemplateView.createWallpaperLayer display-only hook (card vs wallpaperHasChanged) & TemplateApiImpl getCurrentWallpaper/applyEditPageTemplate
        lockOwned[0] = true;
        homeOwned[0] = true;
        com.miui.keyguard.editor.data.bean.CommonConfig layerCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        true, null, null, null, "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        com.miui.keyguard.editor.edit.base.BaseTemplateView cardLayerView =
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, layerCfg, false);
        cardLayerView.editable = false;
        de.robv.android.xposed.XC_MethodHook.MethodHookParam layerParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        layerParam.thisObject = cardLayerView;
        layerParam.args = new Object[]{ctx, layerCfg.getLockscreenInfo().getWallpaperInfo(), Boolean.FALSE, null, "card"};
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    tmHooks.get("com.miui.keyguard.editor.edit.base.BaseTemplateView#createWallpaperLayer"),
                    layerParam);
        } catch (Throwable t) {
            throw new AssertionError("BaseTemplateView.createWallpaperLayer hook threw", t);
        }
        check("ThemeManager BaseTemplateView.createWallpaperLayer injects display preview only, preserving isThirdPartyWallpaper & positionInfo=null",
                layerCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == previewLockBmp
                        && layerCfg.getLockscreenInfo().isThirdPartyWallpaper()
                        && layerCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() == null);

        de.robv.android.xposed.XC_MethodHook.MethodHookParam getWallThirdParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        getWallThirdParam.thisObject = apiImpl;
        getWallThirdParam.args = new Object[]{layerCfg.getLockscreenInfo().getWallpaperInfo()};
        com.miui.keyguard.editor.data.template.TemplateApiImpl nativeApiImpl =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam getWallNativeParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        getWallNativeParam.thisObject = nativeApiImpl;
        getWallNativeParam.args = new Object[]{layerCfg.getLockscreenInfo().getWallpaperInfo()};
        getWallNativeParam.setResult(nativePickedBmp);
        try {
            de.robv.android.xposed.XC_MethodHook.invokeAfter(
                    aodHooks.get("com.miui.keyguard.editor.data.template.TemplateApiImpl#getCurrentWallpaper"),
                    getWallThirdParam);
            de.robv.android.xposed.XC_MethodHook.invokeAfter(
                    aodHooks.get("com.miui.keyguard.editor.data.template.TemplateApiImpl#getCurrentWallpaper"),
                    getWallNativeParam);
        } catch (Throwable t) {
            throw new AssertionError("getCurrentWallpaper hook threw", t);
        }
        check("TemplateApiImpl.getCurrentWallpaper returns preview when third-party owned and preserves native result when non-third-party",
                getWallThirdParam.getResult() == previewLockBmp
                        && getWallNativeParam.getResult() == nativePickedBmp);

        de.robv.android.xposed.XC_MethodHook.MethodHookParam applyEditParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        applyEditParam.thisObject = apiImpl;
        applyEditParam.args = new Object[]{
                layerCfg, null, null, null, null,
                Long.valueOf(0L), Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, null
        };
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    aodHooks.get("com.miui.keyguard.editor.data.template.TemplateApiImpl#applyEditPageTemplate"),
                    applyEditParam);
        } catch (Throwable t) {
            throw new AssertionError("applyEditPageTemplate hook threw", t);
        }
        check("TemplateApiImpl.applyEditPageTemplate upgrades preview-only CommonConfig to full-res + valid PositionInfo without clearing isThirdPartyWallpaper",
                layerCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == fullLockBmp
                        && layerCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() != null
                        && layerCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo().getRight() == 1080f
                        && layerCfg.getLockscreenInfo().isThirdPartyWallpaper());

        // Scenario H: System Personalization gray card (isThirdPartyWallpaper=false + non-null stale static/gray Bitmap + fresh module ownership)
        // Re-install ThemeManager hook with allowLegacyVep5=true to exercise the obfuscated vep5 binding path as seen on device!
        allowLegacyVep5[0] = true;
        com.hyperalive.aod.hook.WallpaperPreviewHook.installThemeManager(
                ctx.getClass().getClassLoader(), tmRegistrar, previewReader, ownershipChecker);
        lockOwned[0] = true;
        homeOwned[0] = true;
        final android.graphics.Bitmap grayStaticBmp = new android.graphics.Bitmap(320, 640);
        com.miui.keyguard.editor.data.bean.CommonConfig grayCardCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, grayStaticBmp, null, null, "LOCK_WALLPAPER",
                        false, grayStaticBmp, null, null, "HOME_WALLPAPER");
        com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder tmHolder =
                new com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder(ctx);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam grayVep5Param =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        grayVep5Param.thisObject = tmHolder;
        grayVep5Param.args = new Object[]{"tpl_card_current", grayCardCfg};
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    tmHooks.get("com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder#vep5"),
                    grayVep5Param);
        } catch (Throwable t) {
            throw new AssertionError("Gray card vep5 hook threw", t);
        }
        check("Scenario H1: SettingsMyTemplateViewHolder.vep5 injects display preview even when isThirdPartyWallpaper=false and gray Bitmap is present, without setting positionInfo or flipping isThirdPartyWallpaper",
                grayCardCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == previewLockBmp
                        && grayCardCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == previewHomeBmp
                        && !grayCardCfg.getLockscreenInfo().isThirdPartyWallpaper()
                        && !grayCardCfg.getHomeInfo().isThirdPartyWallpaper()
                        && grayCardCfg.getLockscreenInfo().getWallpaperInfo().getPositionInfo() == null
                        && grayCardCfg.getHomeInfo().getWallpaperInfo().getPositionInfo() == null);

        // Scenario H2: BaseTemplateView.createWallpaperLayer injects over thirdParty=false + gray Bitmap when wallpaperHasChanged=false,
        // and refuses when user explicitly changed wallpaper in editor (getWallpaperHasChanged=true)
        com.miui.keyguard.editor.data.bean.CommonConfig grayLayerCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, grayStaticBmp, null, null, "LOCK_WALLPAPER",
                        false, grayStaticBmp, null, null, "HOME_WALLPAPER");
        com.miui.keyguard.editor.edit.base.BaseTemplateView grayLayerView =
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, grayLayerCfg, false);
        grayLayerView.editable = false;
        de.robv.android.xposed.XC_MethodHook.MethodHookParam grayLayerParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        grayLayerParam.thisObject = grayLayerView;
        grayLayerParam.args = new Object[]{ctx, grayLayerCfg.getLockscreenInfo().getWallpaperInfo(), Boolean.FALSE, null, "card"};
        final android.graphics.Bitmap userPickedGrayTestBmp = new android.graphics.Bitmap(720, 1600);
        com.miui.keyguard.editor.data.bean.CommonConfig userReplacedCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, userPickedGrayTestBmp, null, "image", "LOCK_WALLPAPER",
                        false, userPickedGrayTestBmp, null, "image", "HOME_WALLPAPER");
        com.miui.keyguard.editor.edit.base.BaseTemplateView userReplacedLayerView =
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, userReplacedCfg, true);
        de.robv.android.xposed.XC_MethodHook.MethodHookParam userReplacedParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        userReplacedParam.thisObject = userReplacedLayerView;
        userReplacedParam.args = new Object[]{ctx, userReplacedCfg.getLockscreenInfo().getWallpaperInfo(), Boolean.FALSE, null, "editor"};
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    tmHooks.get("com.miui.keyguard.editor.edit.base.BaseTemplateView#createWallpaperLayer"),
                    grayLayerParam);
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    tmHooks.get("com.miui.keyguard.editor.edit.base.BaseTemplateView#createWallpaperLayer"),
                    userReplacedParam);
        } catch (Throwable t) {
            throw new AssertionError("Scenario H2 createWallpaperLayer hook threw", t);
        }
        check("Scenario H2: BaseTemplateView.createWallpaperLayer injects over thirdParty=false+grayBmp on card, and protects user-replaced wallpaper when getWallpaperHasChanged=true",
                grayLayerCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == previewLockBmp
                        && !grayLayerCfg.getLockscreenInfo().isThirdPartyWallpaper()
                        && userReplacedCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == userPickedGrayTestBmp);

        // Scenario I: Mixed-slot & failing-ownership-check gray card safety -> only owned slot gets injected; unowned or error leaves grayBmp intact
        lockOwned[0] = true;
        homeOwned[0] = false;
        com.miui.keyguard.editor.data.bean.CommonConfig mixedGrayCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, grayStaticBmp, null, null, "LOCK_WALLPAPER",
                        false, grayStaticBmp, null, null, "HOME_WALLPAPER");
        de.robv.android.xposed.XC_MethodHook.MethodHookParam mixedGrayParam =
                new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
        mixedGrayParam.thisObject = tmHolder;
        mixedGrayParam.args = new Object[]{"tpl_mixed", mixedGrayCfg};
        com.miui.keyguard.editor.data.bean.CommonConfig errOwnershipCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, grayStaticBmp, null, null, "LOCK_WALLPAPER",
                        false, grayStaticBmp, null, null, "HOME_WALLPAPER");
        int errUpdated = com.hyperalive.aod.hook.WallpaperPreviewHook.applyDisplayPreviewToCommonConfig(
                ctx,
                errOwnershipCfg,
                (c, slot) -> "lock".equals(slot) ? previewLockBmp : previewHomeBmp,
                (c, slot) -> { throw new RuntimeException("simulated ownership probe failure"); });
        try {
            de.robv.android.xposed.XC_MethodHook.invokeBefore(
                    tmHooks.get("com.miui.keyguard.editor.settings.SettingsMyTemplateViewHolder#vep5"),
                    mixedGrayParam);
        } catch (Throwable t) {
            throw new AssertionError("Scenario I mixed gray card hook threw", t);
        }
        check("Scenario I: Mixed gray card injects ONLY owned lock slot while preserving unowned home grayBmp, and ownership error fails closed",
                mixedGrayCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == previewLockBmp
                        && mixedGrayCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == grayStaticBmp
                        && errUpdated == 0
                        && errOwnershipCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == grayStaticBmp
                        && errOwnershipCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == grayStaticBmp);

        runMixedSlotAndConcurrencyHookTests(ctx, lockOwned, homeOwned, fullLockBmp, fullHomeBmp);
    }

    private static void runMixedSlotAndConcurrencyHookTests(
            final android.content.Context ctx,
            final boolean[] lockOwned,
            final boolean[] homeOwned,
            final android.graphics.Bitmap fullLockBmp,
            final android.graphics.Bitmap fullHomeBmp) {
        final com.miui.keyguard.editor.edit.base.TemplateApplier applier =
                new com.miui.keyguard.editor.edit.base.TemplateApplier();
        final com.miui.keyguard.editor.data.bean.WallpaperPositionInfo nativePos =
                new com.miui.keyguard.editor.data.bean.WallpaperPositionInfo(
                        0f, 0f, 1080f, 2400f, new float[9], Boolean.FALSE, new Float[]{0f, 0f, 0f, 0f}, 0);
        final android.graphics.Bitmap nativeLockBmp = new android.graphics.Bitmap(1080, 2400);
        final android.graphics.Bitmap nativeHomeBmp = new android.graphics.Bitmap(1080, 2400);
        final android.graphics.Bitmap nativePickedBmp = new android.graphics.Bitmap(720, 1600);

        // (a) Dual-module (lockOwned=true, homeOwned=true, config-only):
        // Even if applyWallpaperWithConfig is entered (e.g., stale lockThird=false), both real slot writers stay silent
        // and template/history/notify still complete cleanly.
        lockOwned[0] = true;
        homeOwned[0] = true;
        com.miui.keyguard.editor.data.bean.CommonConfig dualModCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, null, null, null, "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        applier.applyTemplate(new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, dualModCfg, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl dualModApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        dualModApi.applyEditPageTemplate(
                dualModCfg, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(a) Dual-module config-only skips both slot writers, returns false from applyWallpaperWithConfig, and saves config/history/notify",
                dualModApi.controller.lockWrites.isEmpty()
                        && dualModApi.controller.homeWrites.isEmpty()
                        && Boolean.FALSE.equals(dualModApi.lastApplyWallpaperWithConfigResult)
                        && dualModApi.historyAddedCount == 1
                        && dualModApi.templateUpdatedCount == 1
                        && dualModApi.historyUpdatedCount == 1
                        && dualModApi.notifyApplyCount == 1
                        && dualModCfg.getHomeInfo().isThirdPartyWallpaper());

        // (b) Home=module + Lock=native (config-only): Image lock updates cleanly with applyAll=false, Home live untouched!
        lockOwned[0] = false;
        homeOwned[0] = true;
        com.miui.keyguard.editor.data.bean.CommonConfig homeModLockNativeCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, homeModLockNativeCfg, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl mixedHomeModApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        mixedHomeModApi.applyEditPageTemplate(
                homeModLockNativeCfg, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(b1) Home=module + Lock=native(image) config-only forces needApplyAll=false, updates Lock only (applyAll=false), skips Home writer, saves config/history",
                homeModLockNativeCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == fullHomeBmp
                        && !homeModLockNativeCfg.getLockscreenInfo().isThirdPartyWallpaper()
                        && mixedHomeModApi.controller.homeWrites.isEmpty()
                        && mixedHomeModApi.controller.lockWrites.size() == 1
                        && "image".equals(mixedHomeModApi.controller.lockWrites.get(0))
                        && mixedHomeModApi.controller.lockApplyAllFlags.size() == 1
                        && Boolean.FALSE.equals(mixedHomeModApi.controller.lockApplyAllFlags.get(0))
                        && Boolean.TRUE.equals(mixedHomeModApi.lastApplyWallpaperWithConfigResult)
                        && mixedHomeModApi.templateUpdatedCount == 1
                        && mixedHomeModApi.historyUpdatedCount == 1
                        && mixedHomeModApi.notifyApplyCount == 1);

        // (b2) Home=module + Lock=native(video): video lock writes with applyAll=false, Home video write does not run!
        com.miui.keyguard.editor.data.bean.CommonConfig homeModLockVideoCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "video", "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, homeModLockVideoCfg, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl mixedVideoApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        mixedVideoApi.applyEditPageTemplate(
                homeModLockVideoCfg, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(b2) Home=module + Lock=native(video) config-only executes video Lock with applyAll=false while blocking Home writer",
                mixedVideoApi.controller.homeWrites.isEmpty()
                        && mixedVideoApi.controller.lockWrites.size() == 1
                        && "video".equals(mixedVideoApi.controller.lockWrites.get(0))
                        && Boolean.FALSE.equals(mixedVideoApi.controller.lockApplyAllFlags.get(0)));

        // (b3) Home=module + Lock=native(super_wallpaper): applySuperWallpaper affects both slots, so it is blocked to protect owned Home
        com.miui.keyguard.editor.data.bean.CommonConfig homeModLockSuperCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "super_wallpaper", "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, homeModLockSuperCfg, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl mixedSuperApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        mixedSuperApi.applyEditPageTemplate(
                homeModLockSuperCfg, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(b3) Home=module + Lock=super_wallpaper config-only suppresses applySuperWallpaper while saving template/history",
                mixedSuperApi.controller.superWrites.isEmpty()
                        && mixedSuperApi.controller.homeWrites.isEmpty()
                        && mixedSuperApi.controller.lockWrites.isEmpty()
                        && Boolean.FALSE.equals(mixedSuperApi.lastApplyWallpaperWithConfigResult)
                        && mixedSuperApi.templateUpdatedCount == 1);

        // (c) Lock=module + Home=native (config-only):
        // Case c1: lockThird=true normal flow (no wallpaper write at all, config/history saved)
        // Case c2: stale lockThird=false forcing entry into applyWallpaperWithConfig -> Lock writer suppressed, Home writer allowed!
        lockOwned[0] = true;
        homeOwned[0] = false;
        com.miui.keyguard.editor.data.bean.CommonConfig lockModHomeNatNormal =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        true, null, null, null, "LOCK_WALLPAPER",
                        false, nativeHomeBmp, nativePos, "image", "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, lockModHomeNatNormal, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl lockModApi1 =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, true, false);
        lockModApi1.applyEditPageTemplate(
                lockModHomeNatNormal, null, null, null, null, 0L, false, false, false, false, false, null);

        com.miui.keyguard.editor.data.bean.CommonConfig lockModHomeNatStale =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, null, null, "gallery", "LOCK_WALLPAPER",
                        false, nativeHomeBmp, nativePos, "gallery", "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, lockModHomeNatStale, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl lockModApi2 =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
        lockModApi2.applyEditPageTemplate(
                lockModHomeNatStale, null, null, null, null, 0L, false, false, false, false, false, null);
        // Also test sensor writer suppression on Lock=module when stale flag enters applyWallpaperWithConfig
        com.miui.keyguard.editor.data.bean.CommonConfig lockModSensorStale =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, fullLockBmp, nativePos, "sensor", "LOCK_WALLPAPER",
                        false, nativeHomeBmp, nativePos, "image", "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, lockModSensorStale, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl lockModSensorApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
        lockModSensorApi.applyEditPageTemplate(
                lockModSensorStale, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(c) Lock=module + Home=native config-only protects owned Lock in both normal & stale-flag flows while allowing native Home + saving config",
                lockModApi1.controller.lockWrites.isEmpty()
                        && lockModApi1.templateUpdatedCount == 1
                        && lockModApi2.galleryWrites == 1
                        && lockModSensorApi.sensorLockWrites == 0
                        && lockModSensorApi.sensorOuterWrites == 0
                        && lockModSensorApi.controller.lockWrites.isEmpty()
                        && lockModSensorApi.controller.homeWrites.size() == 1
                        && Boolean.FALSE.equals(lockModSensorApi.controller.homeApplyAllFlags.get(0)));

        // (d) Dual-native (lockOwned=false, homeOwned=false, config-only): native writers execute unobstructed (needApplyAll stays true)
        lockOwned[0] = false;
        homeOwned[0] = false;
        com.miui.keyguard.editor.data.bean.CommonConfig dualNativeCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                        false, nativeHomeBmp, nativePos, "image", "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, dualNativeCfg, false), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl dualNativeApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
        dualNativeApi.applyEditPageTemplate(
                dualNativeCfg, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(d) Dual-native executes natively with unmodified needApplyAll=true",
                dualNativeApi.controller.lockWrites.size() == 1
                        && "image_all".equals(dualNativeApi.controller.lockWrites.get(0))
                        && dualNativeApi.controller.homeWrites.size() == 1
                        && Boolean.TRUE.equals(dualNativeApi.controller.lockApplyAllFlags.get(0)));

        // (e) Explicit user wallpaper change (wallpaperChangedInEditor=true, even while ownership temporarily still true):
        // Old module bitmaps never overwrite user selection, needApplyAll is not overridden, native writers apply!
        lockOwned[0] = true;
        homeOwned[0] = true;
        com.miui.keyguard.editor.data.bean.CommonConfig explicitChangeCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativePickedBmp, nativePos, "image", "LOCK_WALLPAPER",
                        false, nativePickedBmp, nativePos, "image", "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, explicitChangeCfg, true), true);
        com.miui.keyguard.editor.data.template.TemplateApiImpl explicitChangeApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
        explicitChangeApi.applyEditPageTemplate(
                explicitChangeCfg, null, nativePickedBmp, nativePickedBmp, null, 0L, false, false, false, false, false, null);
        check("(e) Explicit user wallpaper change preserves picked bitmaps & runs native writers even before provider clears ownership",
                explicitChangeCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper() == nativePickedBmp
                        && explicitChangeCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper() == nativePickedBmp
                        && explicitChangeApi.controller.lockWrites.size() == 1
                        && "image_all".equals(explicitChangeApi.controller.lockWrites.get(0))
                        && explicitChangeApi.controller.homeWrites.size() == 1);

        // (f) Ownership revoked while queued: applyTemplate ran while Home was module-owned (config-only),
        // then BEFORE applyEditPageTemplate/applyWallpaperWithConfig runs, Home ownership is revoked -> native applies cleanly!
        lockOwned[0] = false;
        homeOwned[0] = true;
        com.miui.keyguard.editor.data.bean.CommonConfig revokedInQueueCfg =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        applier.applyTemplate(
                new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, revokedInQueueCfg, false), true);
        // Revoke ownership while request sits queued before execution
        homeOwned[0] = false;
        com.miui.keyguard.editor.data.template.TemplateApiImpl revokedQueueApi =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        revokedQueueApi.applyEditPageTemplate(
                revokedInQueueCfg, null, null, null, null, 0L, false, false, false, false, false, null);
        check("(f) Ownership revoked while queued is re-verified at write time and allows native writers to proceed",
                revokedQueueApi.controller.lockWrites.size() == 1
                        && revokedQueueApi.controller.homeWrites.size() == 1);

        // (g) Concurrent applies across threads + cross-thread coroutine suspend/resume + exception cleanup
        lockOwned[0] = false;
        homeOwned[0] = true;
        final com.miui.keyguard.editor.data.bean.CommonConfig cfgA =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");
        final com.miui.keyguard.editor.data.bean.CommonConfig cfgB =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativePickedBmp, nativePos, "image", "LOCK_WALLPAPER",
                        false, nativePickedBmp, nativePos, "image", "HOME_WALLPAPER");
        final com.miui.keyguard.editor.data.bean.CommonConfig cfgErr =
                new com.miui.keyguard.editor.data.bean.CommonConfig(
                        false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                        true, null, null, null, "HOME_WALLPAPER");

        // Request A prepares on main thread before suspending at mutex.lock()
        applier.applyTemplate(new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cfgA, false), true);
        // Error test config also prepares on main thread
        applier.applyTemplate(new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cfgErr, false), true);

        final java.util.concurrent.CountDownLatch workerFinishedB = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService coroutinePool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        final com.miui.keyguard.editor.data.template.TemplateApiImpl apiA =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        final com.miui.keyguard.editor.data.template.TemplateApiImpl apiB =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
        final com.miui.keyguard.editor.data.template.TemplateApiImpl apiErr =
                new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
        apiErr.throwInApplyWithConfig = true;
        final boolean[] errCaught = {false};

        // Run Request B (explicit user wallpaper change) on another thread while Request A is suspended
        Thread threadB = new Thread(() -> {
            applier.applyTemplate(new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cfgB, true), true);
            apiB.applyEditPageTemplate(
                    cfgB, null, nativePickedBmp, nativePickedBmp, null, 0L, false, false, false, false, false, null);
            workerFinishedB.countDown();
        });
        threadB.start();

        try {
            if (!workerFinishedB.await(5L, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent thread B timed out");
            }
            threadB.join(5000L);
            // Simulate exception during config-only applyWallpaperWithConfig, then reuse SAME thread for resumed A and native B
            java.util.concurrent.Future<Boolean> resumedFuture = coroutinePool.submit(() -> {
                try {
                    apiErr.applyEditPageTemplate(
                            cfgErr, null, null, null, null, 0L, false, false, false, false, false, null);
                } catch (IllegalStateException expected) {
                    errCaught[0] = true;
                }
                // Resume Request A across thread boundary after Request B finished on threadB
                apiA.applyEditPageTemplate(
                        cfgA, null, null, null, null, 0L, false, false, false, false, false, null);
                // Run another explicit change on this same pooled thread after A + Err to confirm zero ThreadLocal leakage
                com.miui.keyguard.editor.data.bean.CommonConfig cfgAfterErr =
                        new com.miui.keyguard.editor.data.bean.CommonConfig(
                                false, nativePickedBmp, nativePos, "image", "LOCK_WALLPAPER",
                                false, nativePickedBmp, nativePos, "image", "HOME_WALLPAPER");
                applier.applyTemplate(
                        new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cfgAfterErr, true), true);
                com.miui.keyguard.editor.data.template.TemplateApiImpl apiAfterErr =
                        new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, false);
                apiAfterErr.applyEditPageTemplate(
                        cfgAfterErr, null, nativePickedBmp, nativePickedBmp, null, 0L, false, false, false, false, false, null);
                return Boolean.valueOf(apiAfterErr.controller.lockWrites.size() == 1
                        && apiAfterErr.controller.homeWrites.size() == 1);
            });
            boolean afterErrNativeOk = Boolean.TRUE.equals(resumedFuture.get(5L, java.util.concurrent.TimeUnit.SECONDS));
            check("(g) Concurrent/suspended coroutine intents stay isolated per CommonConfig, survive cross-thread resume, and pop ThreadLocal cleanly on exception",
                    apiB.controller.lockWrites.size() == 1
                            && apiB.controller.homeWrites.size() == 1
                            && errCaught[0]
                            && apiA.controller.homeWrites.isEmpty()
                            && apiA.controller.lockWrites.size() == 1
                            && Boolean.FALSE.equals(apiA.controller.lockApplyAllFlags.get(0))
                            && afterErrNativeOk);

            // (h) Defense-in-depth: direct applyImageWallpaper(..., isLock=true, applyAll=true) inside config-only Home=module downgrades applyAll -> false,
            // and superseded generation on same CommonConfig suppresses stale apply writes
            lockOwned[0] = false;
            homeOwned[0] = true;
            final com.miui.keyguard.editor.data.bean.CommonConfig cfgDowngrade =
                    new com.miui.keyguard.editor.data.bean.CommonConfig(
                            false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                            true, null, null, null, "HOME_WALLPAPER");
            applier.applyTemplate(
                    new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cfgDowngrade, false), true);
            final com.miui.keyguard.editor.data.template.TemplateApiImpl apiDowngrade =
                    new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
            final de.robv.android.xposed.XC_MethodHook withCfgHook =
                    com.miui.keyguard.editor.data.template.TemplateApiImpl.ACTIVE_HOOKS.get(
                            "com.miui.keyguard.editor.data.template.TemplateApiImpl#applyWallpaperWithConfig");
            final de.robv.android.xposed.XC_MethodHook.MethodHookParam manualScope =
                    new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
            manualScope.thisObject = apiDowngrade;
            manualScope.args = new Object[]{cfgDowngrade};
            try {
                de.robv.android.xposed.XC_MethodHook.invokeBefore(withCfgHook, manualScope);
                apiDowngrade.applyImageWallpaper(
                        nativeLockBmp, true, "lock_path", cfgDowngrade.getLockscreenInfo().getWallpaperInfo(),
                        false, true, false, null, new kotlin.Pair<Integer, Integer>(1080, 2400),
                        new com.miui.keyguard.editor.data.template.WallpaperApplyParam(), true);
                de.robv.android.xposed.XC_MethodHook.invokeAfter(withCfgHook, manualScope);
            } catch (Throwable t) {
                throw new AssertionError("Direct applyAll downgrade test threw", t);
            }

            final com.miui.keyguard.editor.data.bean.CommonConfig cfgSuperseded =
                    new com.miui.keyguard.editor.data.bean.CommonConfig(
                            false, nativeLockBmp, nativePos, "image", "LOCK_WALLPAPER",
                            true, null, null, null, "HOME_WALLPAPER");
            applier.applyTemplate(
                    new com.miui.keyguard.editor.edit.base.BaseTemplateView(ctx, cfgSuperseded, false), true);
            final com.miui.keyguard.editor.data.template.TemplateApiImpl apiSuperseded =
                    new com.miui.keyguard.editor.data.template.TemplateApiImpl(ctx, false, true);
            final de.robv.android.xposed.XC_MethodHook.MethodHookParam staleScope =
                    new de.robv.android.xposed.XC_MethodHook.MethodHookParam();
            staleScope.thisObject = apiSuperseded;
            staleScope.args = new Object[]{cfgSuperseded};
            try {
                de.robv.android.xposed.XC_MethodHook.invokeBefore(withCfgHook, staleScope);
                // Newer generation supersedes cfgSuperseded while old request is in-flight
                com.hyperalive.aod.hook.WallpaperPreviewHook.recordApplyIntent(cfgSuperseded, false);
                apiSuperseded.applyImageWallpaper(
                        nativeLockBmp, true, "lock_path", cfgSuperseded.getLockscreenInfo().getWallpaperInfo(),
                        false, true, false, null, new kotlin.Pair<Integer, Integer>(1080, 2400),
                        new com.miui.keyguard.editor.data.template.WallpaperApplyParam(), false);
                de.robv.android.xposed.XC_MethodHook.invokeAfter(withCfgHook, staleScope);
            } catch (Throwable t) {
                throw new AssertionError("Superseded generation test threw", t);
            }
            check("(h) Direct applyAll=true downgrades to single-slot when Home is owned, and superseded generation skips stale writes",
                    apiDowngrade.controller.homeWrites.isEmpty()
                            && apiDowngrade.controller.lockWrites.size() == 1
                            && Boolean.FALSE.equals(apiDowngrade.controller.lockApplyAllFlags.get(0))
                            && apiSuperseded.controller.lockWrites.isEmpty()
                            && apiSuperseded.controller.homeWrites.isEmpty());
        } catch (Exception e) {
            throw new AssertionError("Concurrent coroutine test failed", e);
        } finally {
            coroutinePool.shutdownNow();
            lockOwned[0] = true;
            homeOwned[0] = true;
        }
    }

    private static Process createSimulatedProcess(final boolean throwAfterPartial, final boolean hangProcess) {
        return new Process() {
            private volatile boolean destroyed = false;
            private final java.io.InputStream in = new java.io.InputStream() {
                private int step = 0;
                private final byte[] payload = "partial_payload\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

                @Override
                public int read(byte[] b, int off, int len) throws java.io.IOException {
                    if (step == 0) {
                        step = 1;
                        int n = Math.min(len, payload.length);
                        System.arraycopy(payload, 0, b, off, n);
                        return n;
                    }
                    if (throwAfterPartial && !destroyed) {
                        throw new java.io.IOException("simulated read failure");
                    }
                    return -1;
                }

                @Override
                public int read() throws java.io.IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n <= 0 ? -1 : (one[0] & 0xFF);
                }
            };

            @Override
            public java.io.OutputStream getOutputStream() {
                return new java.io.ByteArrayOutputStream();
            }

            @Override
            public java.io.InputStream getInputStream() {
                return in;
            }

            @Override
            public java.io.InputStream getErrorStream() {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }

            @Override
            public int waitFor() throws InterruptedException {
                if (hangProcess && !destroyed) {
                    Thread.sleep(10000L);
                }
                return 0;
            }

            @Override
            public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                if (!hangProcess || destroyed) {
                    return true;
                }
                Thread.sleep(Math.min(200L, unit.toMillis(timeout)));
                return destroyed;
            }

            @Override
            public int exitValue() {
                return 0;
            }

            @Override
            public void destroy() {
                destroyed = true;
            }

            @Override
            public Process destroyForcibly() {
                destroyed = true;
                return this;
            }
        };
    }

    private static Process createSimulatedLockingStreamProcess(final boolean[] destroyCalledFirst) {
        return new Process() {
            private volatile boolean destroyed = false;
            private volatile boolean closeCalled = false;
            private final Object streamMonitor = new Object();
            private final java.io.InputStream in = new java.io.InputStream() {
                @Override
                public int read(byte[] b, int off, int len) throws java.io.IOException {
                    synchronized (streamMonitor) {
                        while (!destroyed) {
                            try {
                                streamMonitor.wait(5000L);
                            } catch (InterruptedException ie) {
                                // Intentionally ignore interrupt unless process was destroyed first,
                                // simulating a stream whose monitor stays locked if close() is called before destroy()
                            }
                        }
                        return -1;
                    }
                }

                @Override
                public int read() throws java.io.IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1);
                }

                @Override
                public void close() throws java.io.IOException {
                    closeCalled = true;
                    synchronized (streamMonitor) {
                        streamMonitor.notifyAll();
                    }
                }
            };

            @Override
            public java.io.OutputStream getOutputStream() {
                return new java.io.ByteArrayOutputStream();
            }

            @Override
            public java.io.InputStream getInputStream() {
                return in;
            }

            @Override
            public java.io.InputStream getErrorStream() {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }

            @Override
            public int waitFor() throws InterruptedException {
                while (!destroyed) {
                    Thread.sleep(50L);
                }
                return 1;
            }

            @Override
            public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                long ms = unit.toMillis(timeout);
                if (!destroyed && ms > 0) {
                    Thread.sleep(Math.min(120L, ms));
                }
                return destroyed;
            }

            @Override
            public int exitValue() {
                return 1;
            }

            @Override
            public void destroy() {
                if (!closeCalled) {
                    destroyCalledFirst[0] = true;
                }
                destroyed = true;
                synchronized (streamMonitor) {
                    streamMonitor.notifyAll();
                }
            }

            @Override
            public Process destroyForcibly() {
                destroy();
                return this;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        Thread.sleep(10000L);
    }
}
"""


HARNESS_MIUI_HOSTS = {
    "com/optional/missing/BrokenPlayerDep.java": r"""
package com.optional.missing;
public final class BrokenPlayerDep {}
""",
    "kotlin/coroutines/Continuation.java": r"""
package kotlin.coroutines;
public interface Continuation<T> {}
""",
    "com/miui/keyguard/editor/data/bean/WallpaperPositionInfo.java": r"""
package com.miui.keyguard.editor.data.bean;

public final class WallpaperPositionInfo {
    private final float left, top, right, bottom;
    private final float[] matrixArr;
    private final Boolean useSquareArea;
    private final Float[] offset;
    private final Integer orientation;

    public WallpaperPositionInfo(
            float left, float top, float right, float bottom,
            float[] matrixArr, Boolean useSquareArea, Float[] offset, Integer orientation) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.matrixArr = matrixArr;
        this.useSquareArea = useSquareArea;
        this.offset = offset;
        this.orientation = orientation;
    }

    public float getLeft() { return left; }
    public float getTop() { return top; }
    public float getRight() { return right; }
    public float getBottom() { return bottom; }
    public float[] getMatrixArr() { return matrixArr; }
    public Boolean getUseSquareArea() { return useSquareArea; }
    public Float[] getOffset() { return offset; }
    public Integer getOrientation() { return orientation; }
}
""",
    "com/miui/keyguard/editor/data/bean/WallpaperInfo.java": r"""
package com.miui.keyguard.editor.data.bean;

import android.graphics.Bitmap;

public final class WallpaperInfo {
    private Bitmap currentWallpaper;
    private WallpaperPositionInfo positionInfo;
    private String resourceType;
    private final String infoType;
    private final boolean dropPositionSetter;

    public WallpaperInfo(
            Bitmap bmp, WallpaperPositionInfo pos, String resType, String infoType) {
        this(bmp, pos, resType, infoType, false);
    }

    public WallpaperInfo(
            Bitmap bmp, WallpaperPositionInfo pos, String resType, String infoType, boolean dropPositionSetter) {
        this.currentWallpaper = bmp;
        this.positionInfo = pos;
        this.resourceType = resType;
        this.infoType = infoType;
        this.dropPositionSetter = dropPositionSetter;
    }

    public Bitmap getCurrentWallpaper() { return currentWallpaper; }
    public void setCurrentWallpaper(Bitmap bmp) { this.currentWallpaper = bmp; }
    public WallpaperPositionInfo getPositionInfo() { return positionInfo; }
    public void setPositionInfo(WallpaperPositionInfo pos) {
        if (!dropPositionSetter) {
            this.positionInfo = pos;
        }
    }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resType) { this.resourceType = resType; }
    public String getInfoType() { return infoType; }
}
""",
    "com/miui/keyguard/editor/data/bean/TemplateConfig.java": r"""
package com.miui.keyguard.editor.data.bean;

public final class TemplateConfig {
    private boolean thirdPartyWallpaper;
    private final WallpaperInfo wallpaperInfo;

    public TemplateConfig(boolean thirdParty, WallpaperInfo wallpaperInfo) {
        this.thirdPartyWallpaper = thirdParty;
        this.wallpaperInfo = wallpaperInfo;
    }

    public boolean isThirdPartyWallpaper() { return thirdPartyWallpaper; }
    public void setThirdPartyWallpaper(boolean thirdParty) { this.thirdPartyWallpaper = thirdParty; }
    public WallpaperInfo getWallpaperInfo() { return wallpaperInfo; }
}
""",
    "com/miui/keyguard/editor/data/bean/HomeConfig.java": r"""
package com.miui.keyguard.editor.data.bean;

public final class HomeConfig {
    private boolean thirdPartyWallpaper;
    private final WallpaperInfo wallpaperInfo;

    public HomeConfig(boolean thirdParty, WallpaperInfo wallpaperInfo) {
        this.thirdPartyWallpaper = thirdParty;
        this.wallpaperInfo = wallpaperInfo;
    }

    public boolean isThirdPartyWallpaper() { return thirdPartyWallpaper; }
    public void setThirdPartyWallpaper(boolean thirdParty) { this.thirdPartyWallpaper = thirdParty; }
    public WallpaperInfo getWallpaperInfo() { return wallpaperInfo; }
}
""",
    "com/miui/keyguard/editor/data/bean/CommonConfig.java": r"""
package com.miui.keyguard.editor.data.bean;

import android.graphics.Bitmap;

public final class CommonConfig {
    private final TemplateConfig lockscreenInfo;
    private final HomeConfig homeInfo;

    public CommonConfig(
            boolean lockThird, Bitmap lockBmp, WallpaperPositionInfo lockPos, String lockRes, String lockType,
            boolean homeThird, Bitmap homeBmp, WallpaperPositionInfo homePos, String homeRes, String homeType) {
        this.lockscreenInfo = new TemplateConfig(lockThird, new WallpaperInfo(lockBmp, lockPos, lockRes, lockType));
        this.homeInfo = new HomeConfig(homeThird, new WallpaperInfo(homeBmp, homePos, homeRes, homeType));
    }

    public TemplateConfig getLockscreenInfo() { return lockscreenInfo; }
    public HomeConfig getHomeInfo() { return homeInfo; }
}
""",
    "com/miui/keyguard/editor/data/bean/ScreenshotSource.java": r"""
package com.miui.keyguard.editor.data.bean;
public final class ScreenshotSource {}
""",
    "com/miui/keyguard/editor/data/bean/WallpaperWhich.java": r"""
package com.miui.keyguard.editor.data.bean;

public abstract class WallpaperWhich {
    public static final class LockWhich extends WallpaperWhich {
        public static final LockWhich INSTANCE = new LockWhich();
    }
    public static final class HomeWhich extends WallpaperWhich {
        public static final HomeWhich INSTANCE = new HomeWhich();
    }
}
""",
    "com/miui/keyguard/editor/edit/EditFragment.java": r"""
package com.miui.keyguard.editor.edit;
public final class EditFragment {}
""",
    "com/miui/keyguard/editor/edit/base/BaseTemplateView.java": r"""
package com.miui.keyguard.editor.edit.base;

import android.content.Context;
import android.view.View;
import com.miui.keyguard.editor.data.bean.CommonConfig;
import com.miui.keyguard.editor.data.bean.WallpaperInfo;
import com.miui.keyguard.editor.edit.EditFragment;

public class BaseTemplateView extends View {
    public CommonConfig templateConfig;
    public boolean editable = true;
    private final boolean wallpaperHasChanged;

    public BaseTemplateView(Context context, CommonConfig templateConfig, boolean wallpaperHasChanged) {
        super(context);
        this.templateConfig = templateConfig;
        this.wallpaperHasChanged = wallpaperHasChanged;
    }

    public CommonConfig templateConfig() {
        return templateConfig;
    }

    public boolean getWallpaperHasChanged() {
        return wallpaperHasChanged;
    }

    public boolean isInThirdPartyWallpaperService() {
        return templateConfig != null
                && templateConfig.getLockscreenInfo().isThirdPartyWallpaper()
                && !wallpaperHasChanged;
    }

    public View createWallpaperLayer(
            Context context, WallpaperInfo info, boolean z, EditFragment fragment, String tag) {
        return null;
    }

    public void optionalMiuiPlayer() {
        new com.optional.missing.BrokenPlayerDep();
    }
}
""",
    "com/miui/keyguard/editor/edit/base/HomeTemplateView.java": r"""
package com.miui.keyguard.editor.edit.base;

import android.content.Context;
import com.miui.keyguard.editor.data.bean.CommonConfig;

public final class HomeTemplateView extends BaseTemplateView {
    public HomeTemplateView(Context context, CommonConfig cfg, boolean changed) {
        super(context, cfg, changed);
    }
}
""",
    "kotlin/Pair.java": r"""
package kotlin;
public final class Pair<A, B> {
    public final A first;
    public final B second;
    public Pair(A first, B second) {
        this.first = first;
        this.second = second;
    }
}
""",
    "com/miui/keyguard/editor/utils/PickWallpaperColorInfo.java": r"""
package com.miui.keyguard.editor.utils;
public final class PickWallpaperColorInfo {}
""",
    "com/miui/keyguard/editor/data/template/WallpaperApplyParam.java": r"""
package com.miui.keyguard.editor.data.template;
public final class WallpaperApplyParam {}
""",
    "com/miui/keyguard/editor/edit/wallpaper/WallpaperController.java": r"""
package com.miui.keyguard.editor.edit.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import com.miui.keyguard.editor.data.template.TemplateApiImpl;
import com.miui.keyguard.editor.data.template.WallpaperApplyParam;
import java.util.ArrayList;
import java.util.List;
import kotlin.Pair;

public final class WallpaperController {
    public final List<String> lockWrites = new ArrayList<String>();
    public final List<String> homeWrites = new ArrayList<String>();
    public final List<String> superWrites = new ArrayList<String>();
    public final List<Boolean> lockApplyAllFlags = new ArrayList<Boolean>();
    public final List<Boolean> homeApplyAllFlags = new ArrayList<Boolean>();

    public synchronized void clearWrites() {
        lockWrites.clear();
        homeWrites.clear();
        superWrites.clear();
        lockApplyAllFlags.clear();
        homeApplyAllFlags.clear();
    }

    public void applyImageLockWallpaper(
            Bitmap bmp, int magicType, boolean isLock, boolean fileChanged,
            boolean needDark, boolean blur, boolean supportSubject, int enableDoodle,
            Pair pair, WallpaperApplyParam applyParam, boolean applyAll) {
        final Object[] args = new Object[]{bmp, Integer.valueOf(magicType), Boolean.valueOf(isLock),
                Boolean.valueOf(fileChanged), Boolean.valueOf(needDark), Boolean.valueOf(blur),
                Boolean.valueOf(supportSubject), Integer.valueOf(enableDoodle), pair, applyParam, Boolean.valueOf(applyAll)};
        TemplateApiImpl.dispatchVoid(
                "com.miui.keyguard.editor.edit.wallpaper.WallpaperController#applyImageLockWallpaper",
                this,
                args,
                () -> {
                    boolean effIsLock = Boolean.TRUE.equals(args[2]);
                    boolean effApplyAll = Boolean.TRUE.equals(args[10]);
                    synchronized (this) {
                        if (effApplyAll) {
                            lockWrites.add("image_all");
                            homeWrites.add("image_all");
                            lockApplyAllFlags.add(Boolean.TRUE);
                            homeApplyAllFlags.add(Boolean.TRUE);
                        } else if (effIsLock) {
                            lockWrites.add("image");
                            lockApplyAllFlags.add(Boolean.FALSE);
                        } else {
                            homeWrites.add("image");
                            homeApplyAllFlags.add(Boolean.FALSE);
                        }
                    }
                    return null;
                });
    }

    public void applyVideoWallpaper(
            String path, Object bmpOrDrawable, int magicType, boolean supportLoop,
            boolean isLock, boolean fileChanged, boolean blur, boolean applyAll,
            int doodle, Boolean fullScreenAod) {
        final Object[] args = new Object[]{path, bmpOrDrawable, Integer.valueOf(magicType), Boolean.valueOf(supportLoop),
                Boolean.valueOf(isLock), Boolean.valueOf(fileChanged), Boolean.valueOf(blur),
                Boolean.valueOf(applyAll), Integer.valueOf(doodle), fullScreenAod};
        TemplateApiImpl.dispatchVoid(
                "com.miui.keyguard.editor.edit.wallpaper.WallpaperController#applyVideoWallpaper",
                this,
                args,
                () -> {
                    boolean effIsLock = Boolean.TRUE.equals(args[4]);
                    boolean effApplyAll = Boolean.TRUE.equals(args[7]);
                    synchronized (this) {
                        if (effApplyAll) {
                            lockWrites.add("video_all");
                            homeWrites.add("video_all");
                            lockApplyAllFlags.add(Boolean.TRUE);
                            homeApplyAllFlags.add(Boolean.TRUE);
                        } else if (effIsLock) {
                            lockWrites.add("video");
                            lockApplyAllFlags.add(Boolean.FALSE);
                        } else {
                            homeWrites.add("video");
                            homeApplyAllFlags.add(Boolean.FALSE);
                        }
                    }
                    return null;
                });
    }

    public void applySuperWallpaper(String path, Context ctx, boolean blur) {
        TemplateApiImpl.dispatchVoid(
                "com.miui.keyguard.editor.edit.wallpaper.WallpaperController#applySuperWallpaper",
                this,
                new Object[]{path, ctx, Boolean.valueOf(blur)},
                () -> {
                    synchronized (this) {
                        superWrites.add(path == null ? "super" : path);
                        lockWrites.add("super");
                        homeWrites.add("super");
                    }
                    return null;
                });
    }
}
""",
    "com/miui/keyguard/editor/edit/base/TemplateApplier.java": r"""
package com.miui.keyguard.editor.edit.base;

import com.miui.keyguard.editor.data.template.TemplateApiImpl;

public final class TemplateApplier {
    public final void applyTemplate(BaseTemplateView templateView, boolean z) {
        TemplateApiImpl.dispatchVoid(
                "com.miui.keyguard.editor.edit.base.TemplateApplier#applyTemplate",
                this,
                new Object[]{templateView, Boolean.valueOf(z)},
                () -> null);
    }
}
""",
    "com/miui/keyguard/editor/data/template/TemplateApiImpl.java": r"""
package com.miui.keyguard.editor.data.template;

import android.content.Context;
import android.graphics.Bitmap;
import com.miui.keyguard.editor.data.bean.CommonConfig;
import com.miui.keyguard.editor.data.bean.ScreenshotSource;
import com.miui.keyguard.editor.data.bean.WallpaperInfo;
import com.miui.keyguard.editor.data.bean.WallpaperWhich;
import com.miui.keyguard.editor.edit.wallpaper.WallpaperController;
import com.miui.keyguard.editor.utils.PickWallpaperColorInfo;
import de.robv.android.xposed.XC_MethodHook;
import java.util.Map;
import java.util.concurrent.Callable;
import kotlin.Pair;
import kotlin.coroutines.Continuation;

public final class TemplateApiImpl {
    public static volatile Map<String, XC_MethodHook> ACTIVE_HOOKS;

    public final Context context;
    public final WallpaperController controller = new WallpaperController();
    private final boolean lockThird;
    private final boolean homeThird;

    public int historyAddedCount = 0;
    public int templateUpdatedCount = 0;
    public int historyUpdatedCount = 0;
    public int notifyApplyCount = 0;
    public int galleryWrites = 0;
    public int sensorLockWrites = 0;
    public int sensorHomeWrites = 0;
    public int sensorOuterWrites = 0;
    public Boolean lastApplyWallpaperWithConfigResult = null;
    public boolean throwInApplyWithConfig = false;

    public TemplateApiImpl(Context context, boolean lockThird, boolean homeThird) {
        this.context = context;
        this.lockThird = lockThird;
        this.homeThird = homeThird;
    }

    public static Object dispatch(
            String key, Object thisObj, Object[] args, Callable<Object> body) {
        Map<String, XC_MethodHook> hooks = ACTIVE_HOOKS;
        XC_MethodHook hook = hooks == null ? null : hooks.get(key);
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = thisObj;
        param.args = args;
        try {
            if (hook != null) {
                XC_MethodHook.invokeBefore(hook, param);
            }
            if (!param.isReturnEarly()) {
                try {
                    param.setResult(body.call());
                } catch (Throwable err) {
                    param.setThrowable(err);
                }
            }
            if (hook != null) {
                XC_MethodHook.invokeAfter(hook, param);
            }
            if (param.hasThrowable()) {
                Throwable t = param.getThrowable();
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new RuntimeException(t);
            }
            return param.getResult();
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void dispatchVoid(
            String key, Object thisObj, Object[] args, Callable<Object> body) {
        dispatch(key, thisObj, args, body);
    }

    public boolean isThirdPartyWallpaper(WallpaperWhich which) {
        if (which instanceof WallpaperWhich.HomeWhich) return homeThird;
        return lockThird;
    }

    public Bitmap getCurrentWallpaper(WallpaperInfo info) {
        return null;
    }

    public boolean needApplyAll(Bitmap subjectBmp, CommonConfig cfg) {
        Object res = dispatch(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#needApplyAll",
                this,
                new Object[]{subjectBmp, cfg},
                () -> Boolean.TRUE);
        return Boolean.TRUE.equals(res);
    }

    public Object applyEditPageTemplate(
            CommonConfig cfg, ScreenshotSource src,
            Bitmap lockBmp, Bitmap homeBmp, Bitmap subjectBmp,
            long templateSource, boolean z1, boolean z2, boolean z3, boolean z4, boolean z5,
            Continuation<?> cont) {
        return dispatch(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#applyEditPageTemplate",
                this,
                new Object[]{cfg, src, lockBmp, homeBmp, subjectBmp,
                        Long.valueOf(templateSource), Boolean.valueOf(z1), Boolean.valueOf(z2),
                        Boolean.valueOf(z3), Boolean.valueOf(z4), Boolean.valueOf(z5), cont},
                () -> {
                    CommonConfig effCfg = cfg;
                    if (effCfg != null && !effCfg.getLockscreenInfo().isThirdPartyWallpaper()) {
                        needApplyAll(subjectBmp, effCfg);
                        lastApplyWallpaperWithConfigResult = Boolean.valueOf(applyWallpaperWithConfig(
                                effCfg,
                                lockBmp != null ? lockBmp : effCfg.getLockscreenInfo().getWallpaperInfo().getCurrentWallpaper(),
                                homeBmp != null ? homeBmp : effCfg.getHomeInfo().getWallpaperInfo().getCurrentWallpaper(),
                                false,
                                "lock_path",
                                "home_path",
                                subjectBmp,
                                false,
                                new PickWallpaperColorInfo(),
                                new Pair<Integer, Integer>(Integer.valueOf(1080), Integer.valueOf(2400)),
                                new Pair<Integer, Integer>(Integer.valueOf(1080), Integer.valueOf(2400)),
                                new WallpaperApplyParam(),
                                false));
                    } else {
                        historyAddedCount++;
                    }
                    templateUpdatedCount++;
                    historyUpdatedCount++;
                    notifyApplyCount++;
                    return Boolean.TRUE;
                });
    }

    public final boolean applyWallpaperWithConfig(
            CommonConfig commonConfig, Bitmap lockBmp, Bitmap homeBmp,
            boolean z, String lockPath, String homePath, Bitmap subjectBmp, boolean blur,
            PickWallpaperColorInfo pickColorInfo, Pair pair, Pair pair2,
            WallpaperApplyParam applyParam, boolean homeNotThird) {
        Object res = dispatch(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#applyWallpaperWithConfig",
                this,
                new Object[]{commonConfig, lockBmp, homeBmp, Boolean.valueOf(z), lockPath, homePath,
                        subjectBmp, Boolean.valueOf(blur), pickColorInfo, pair, pair2, applyParam, Boolean.valueOf(homeNotThird)},
                () -> {
                    if (throwInApplyWithConfig) {
                        throw new IllegalStateException("simulated native apply failure");
                    }
                    historyAddedCount++;
                    WallpaperInfo lockInfo = commonConfig.getLockscreenInfo().getWallpaperInfo();
                    WallpaperInfo homeInfo = commonConfig.getHomeInfo().getWallpaperInfo();
                    boolean applyAll = needApplyAll(subjectBmp, commonConfig);
                    if (applyAll) {
                        dispatchLambda66(lockInfo, lockBmp, lockPath, true, subjectBmp, blur, pair, pair2, applyParam);
                        if ("video".equals(lockInfo.getResourceType())) {
                            dispatchLambda66(homeInfo, homeBmp, homePath, true, subjectBmp, blur, pair, pair2, applyParam);
                        }
                    } else {
                        dispatchLambda66(homeInfo, homeBmp, homePath, false, subjectBmp, blur, pair, pair2, applyParam);
                        dispatchLambda66(lockInfo, lockBmp, lockPath, false, subjectBmp, blur, pair, pair2, applyParam);
                    }
                    applySensorOuterVideo(lockInfo, homeInfo, null);
                    return Boolean.TRUE;
                });
        return Boolean.TRUE.equals(res);
    }

    private void dispatchLambda66(
            WallpaperInfo info, Bitmap bmp, String path, boolean applyAll,
            Bitmap subjectBmp, boolean blur, Pair pair, Pair pair2, WallpaperApplyParam applyParam) {
        if (info == null) return;
        boolean isLock = !"HOME_WALLPAPER".equals(info.getInfoType());
        String rType = info.getResourceType();
        if ("video".equals(rType)) {
            if (!applyAll || isLock) {
                controller.applyVideoWallpaper(path, bmp, 0, true, isLock, true, blur, applyAll, 0, Boolean.FALSE);
            }
        } else if ("super_wallpaper".equals(rType)) {
            controller.applySuperWallpaper(path, context, blur);
        } else if ("gallery".equals(rType)) {
            applyGalleryWallpaper(bmp, true, info, path, isLock, blur, false, applyAll);
        } else if ("sensor".equals(rType)) {
            applySensorWallpaper(info, true, blur, applyAll, isLock);
        } else {
            applyImageWallpaper(bmp, true, path, info, false, isLock, blur, subjectBmp,
                    isLock ? pair : pair2, applyParam, applyAll);
        }
    }

    public final void applyImageWallpaper(
            Bitmap bmp, boolean fileChanged, String path, WallpaperInfo info,
            boolean isLoop, boolean isLock, boolean blur, Bitmap subjectBmp,
            Pair pair, WallpaperApplyParam applyParam, boolean applyAll) {
        final Object[] args = new Object[]{bmp, Boolean.valueOf(fileChanged), path, info, Boolean.valueOf(isLoop),
                Boolean.valueOf(isLock), Boolean.valueOf(blur), subjectBmp, pair, applyParam, Boolean.valueOf(applyAll)};
        dispatchVoid(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#applyImageWallpaper",
                this,
                args,
                () -> {
                    controller.applyImageLockWallpaper(
                            (Bitmap) args[0], 0, Boolean.TRUE.equals(args[5]), Boolean.TRUE.equals(args[1]),
                            false, Boolean.TRUE.equals(args[6]), false, 0, (Pair) args[8],
                            (WallpaperApplyParam) args[9], Boolean.TRUE.equals(args[10]));
                    return null;
                });
    }

    public final void applyGalleryWallpaper(
            Bitmap bmp, boolean fileChanged, WallpaperInfo info, String path,
            boolean isLock, boolean blur, boolean addToGallery, boolean applyAll) {
        final Object[] args = new Object[]{bmp, Boolean.valueOf(fileChanged), info, path,
                Boolean.valueOf(isLock), Boolean.valueOf(blur), Boolean.valueOf(addToGallery), Boolean.valueOf(applyAll)};
        dispatchVoid(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#applyGalleryWallpaper",
                this,
                args,
                () -> {
                    galleryWrites++;
                    return null;
                });
    }

    public final void applySensorWallpaper(
            WallpaperInfo info, boolean fileChanged, boolean blur, boolean applyAll, boolean isLock) {
        final Object[] args = new Object[]{info, Boolean.valueOf(fileChanged), Boolean.valueOf(blur),
                Boolean.valueOf(applyAll), Boolean.valueOf(isLock)};
        dispatchVoid(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#applySensorWallpaper",
                this,
                args,
                () -> {
                    boolean effApplyAll = Boolean.TRUE.equals(args[3]);
                    boolean effIsLock = Boolean.TRUE.equals(args[4]);
                    if (effApplyAll) {
                        sensorLockWrites++;
                        sensorHomeWrites++;
                    } else if (effIsLock) {
                        sensorLockWrites++;
                    } else {
                        sensorHomeWrites++;
                    }
                    return null;
                });
    }

    public final void applySensorOuterVideo(
            WallpaperInfo lockInfo, WallpaperInfo homeInfo, String outerVideoPath) {
        final Object[] args = new Object[]{lockInfo, homeInfo, outerVideoPath};
        dispatchVoid(
                "com.miui.keyguard.editor.data.template.TemplateApiImpl#applySensorOuterVideo",
                this,
                args,
                () -> {
                    WallpaperInfo effLock = (WallpaperInfo) args[0];
                    WallpaperInfo effHome = (WallpaperInfo) args[1];
                    if ((effLock != null && "sensor".equals(effLock.getResourceType()))
                            || (effHome != null && "sensor".equals(effHome.getResourceType()))) {
                        sensorOuterWrites++;
                    }
                    return null;
                });
    }

    public final void showCantApplyThirdPartyWallpaperToast() {
    }
}
""",
    "com/miui/keyguard/editor/settings/SettingsMyTemplateViewHolder.java": r"""
package com.miui.keyguard.editor.settings;

import android.content.Context;
import android.view.View;
import com.miui.keyguard.editor.data.bean.CommonConfig;

public final class SettingsMyTemplateViewHolder {
    public final View itemView;

    public SettingsMyTemplateViewHolder(Context context) {
        this.itemView = new View(context);
    }

    private final void createOrLoadTemplateView(String templateId, CommonConfig config) {
    }

    private final void vep5(String templateId, CommonConfig config) {
    }
}
""",
}


def write_sources(base: Path, mapping: dict[str, str]) -> list[Path]:
    out = []
    for rel, content in mapping.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        out.append(p)
    return out


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Offline JVM regression suite for HyperAlive wallpaper backup")
    parser.add_argument(
        "--evidence-dir",
        type=Path,
        default=DEFAULT_EVIDENCE_DIR,
        help="Optional path to external HyperOS 4 evidence fixture directory",
    )
    parser.add_argument(
        "--sdk",
        type=Path,
        default=None,
        help="Optional Android SDK directory (defaults to ANDROID_SDK_ROOT / ANDROID_HOME)",
    )
    return parser.parse_args()


def compile_and_run_tests() -> None:
    args = parse_args()
    if args.evidence_dir is None or not args.evidence_dir.is_dir():
        print("[INFO] Skipping external device-evidence fixture sub-suite (pass --evidence-dir or set HYPERALIVE_EVIDENCE_DIR to run); running source contract assertions...")
        verify_bridge_probe_contracts()
        verify_mount_master_contracts()
        verify_lsp_fallback_removed()
        verify_hook_ownership_gates()
        verify_split_restore_contracts()
        verify_rewrite_cleanup_contracts()
        verify_rename_and_lsp_log_contracts()
        return

    evidence_dir = args.evidence_dir.resolve()
    aapt2, android_jar = _resolve_android_tools(args.sdk)
    build = ROOT / "build/offline_tests"
    build.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="wp_backup_", dir=build) as tmp:
        work = Path(tmp)
        shim_src = work / "shim_src"
        shim_cls = work / "shim_cls"
        stub_cls = work / "stub_cls"
        r_dir = work / "r"
        app_cls = work / "app_cls"
        harness_src = work / "harness_src"
        harness_cls = work / "harness_cls"
        test_data = work / "test_data"
        for d in (shim_src, shim_cls, stub_cls, r_dir, app_cls, harness_src, harness_cls, test_data):
            d.mkdir(parents=True, exist_ok=True)

        shim_files = write_sources(shim_src, {
            "android/util/Log.java": SHIM_LOG,
            "android/os/Process.java": SHIM_PROCESS,
            "android/content/SharedPreferences.java": SHIM_SHARED_PREFERENCES,
            "android/content/res/AssetManager.java": SHIM_ASSET_MANAGER,
            "android/content/Context.java": SHIM_CONTEXT,
            "android/content/ContentResolver.java": SHIM_CONTENT_RESOLVER,
            "android/view/View.java": SHIM_VIEW,
            "android/view/Surface.java": SHIM_SURFACE,
            "android/view/SurfaceHolder.java": SHIM_SURFACE_HOLDER,
            "de/robv/android/xposed/XC_MethodHook.java": SHIM_XPOSED_HOOK,
            "android/graphics/Bitmap.java": SHIM_BITMAP,
            "android/graphics/BitmapFactory.java": SHIM_BITMAP_FACTORY,
            "org/json/JSONException.java": SHIM_JSON_EXCEPTION,
            "org/json/JSONObject.java": SHIM_JSON_OBJECT,
            "org/json/JSONArray.java": SHIM_JSON_ARRAY,
            "org/json/JsonCodec.java": SHIM_JSON_CODEC,
        })

        javac = _resolve_jdk_tool("javac")
        java = _resolve_jdk_tool("java")

        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-d", str(shim_cls),
        ] + [str(f) for f in shim_files], check=True)

        stubs = sorted(str(p) for p in STUB_DIR.rglob("*.java"))
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-cp", str(android_jar),
            "-d", str(stub_cls),
        ] + stubs, check=True)

        res_zip = work / "res.zip"
        subprocess.run([
            str(aapt2), "compile", "--dir", str(RES_DIR), "-o", str(res_zip),
        ], check=True)
        subprocess.run([
            str(aapt2), "link", "-o", str(work / "base.apk"), "-I", str(android_jar),
            "--min-sdk-version", "29", "--target-sdk-version", "35",
            "--java", str(r_dir), "--manifest", str(MANIFEST), str(res_zip),
        ], check=True)

        app_srcs = sorted(str(p) for p in APP_JAVA.rglob("*.java")) + sorted(str(p) for p in r_dir.rglob("*.java"))
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", os.pathsep.join([str(stub_cls), str(android_jar)]),
            "-d", str(app_cls),
        ] + app_srcs, check=True)

        harness_map = {
            "WallpaperBackupRegression.java": HARNESS,
            "com/hyperalive/aod/Phase2RegressionBridge.java": HARNESS_PHASE2_BRIDGE,
        }
        harness_map.update(HARNESS_MIUI_HOSTS)
        harness_files = write_sources(harness_src, harness_map)
        cp_compile = os.pathsep.join([str(shim_cls), str(app_cls), str(android_jar)])
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", cp_compile,
            "-d", str(harness_cls),
        ] + [str(f) for f in harness_files], check=True)

        # Delete optional player dependency bytecode so any getDeclaredMethods()/getMethods() enumeration triggers NoClassDefFoundError
        broken_dep_cls = harness_cls / "com/optional/missing/BrokenPlayerDep.class"
        if broken_dep_cls.exists():
            broken_dep_cls.unlink()

        cp_run = os.pathsep.join([str(harness_cls), str(app_cls), str(shim_cls), str(stub_cls), str(android_jar)])
        subprocess.run([
            java, "-cp", cp_run, "WallpaperBackupRegression",
            str(evidence_dir), str(test_data),
        ], check=True)

        # Injected LSPosed logging bridge regression (recording XposedBridge + XC_LoadPackage shim before stub_cls)
        lsp_shim_src = work / "lsp_shim_src"
        lsp_shim_cls = work / "lsp_shim_cls"
        lsp_harness_src = work / "lsp_harness_src"
        lsp_harness_cls = work / "lsp_harness_cls"
        lsp_data = work / "lsp_data"
        for d in (lsp_shim_src, lsp_shim_cls, lsp_harness_src, lsp_harness_cls, lsp_data):
            d.mkdir(parents=True, exist_ok=True)

        lsp_shim_files = write_sources(lsp_shim_src, {
            "de/robv/android/xposed/XposedBridge.java": r"""
package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class XposedBridge {
    public static final List<String> LOG_LINES = Collections.synchronizedList(new ArrayList<String>());
    public static final Map<Member, List<XC_MethodHook>> HOOKS = new ConcurrentHashMap<>();
    public static volatile Runnable sOnLogHook = null;

    private XposedBridge() {}

    public static void clear() {
        LOG_LINES.clear();
        HOOKS.clear();
        sOnLogHook = null;
    }

    public static void log(String text) {
        LOG_LINES.add(String.valueOf(text));
        Runnable hook = sOnLogHook;
        if (hook != null) {
            hook.run();
        }
    }

    public static void log(Throwable t) {
        LOG_LINES.add("THROWABLE:" + t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (hookMethod != null && callback != null) {
            HOOKS.computeIfAbsent(hookMethod, k -> Collections.synchronizedList(new ArrayList<>())).add(callback);
        }
        return new XC_MethodHook() {}.new Unhook();
    }
}
""",
            "de/robv/android/xposed/callbacks/XC_LoadPackage.java": r"""
package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

public final class XC_LoadPackage {
    private XC_LoadPackage() {}

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
""",
            "de/robv/android/xposed/IXposedHookLoadPackage.java": r"""
package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
""",
        })
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", os.pathsep.join([str(shim_cls), str(android_jar)]),
            "-d", str(lsp_shim_cls),
        ] + [str(f) for f in lsp_shim_files], check=True)

        lsp_harness_files = write_sources(lsp_harness_src, {
            "com/hyperalive/aod/LspLogBridgeRegression.java": r"""
package com.hyperalive.aod;

import android.content.Context;
import android.util.Log;

import com.hyperalive.aod.hook.HookUtils;

import java.io.File;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LspLogBridgeRegression {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    public static void main(String[] args) throws Throwable {
        File dataDir = new File(args[0]);
        Context ctx = new Context(dataDir);
        ModuleDiagLog.clearForTesting(ctx);
        Log.clearEntries();
        XposedBridge.clear();

        XposedInit init = new XposedInit();

        // 1. SystemUI hook event logs to both Android Log and XposedBridge.log
        XC_LoadPackage.LoadPackageParam lpSysUi = new XC_LoadPackage.LoadPackageParam();
        lpSysUi.packageName = "com.android.systemui";
        lpSysUi.processName = "com.android.systemui";
        lpSysUi.classLoader = LspLogBridgeRegression.class.getClassLoader();
        init.handleLoadPackage(lpSysUi);

        check(XposedBridge.LOG_LINES.stream().anyMatch(s -> s.contains("QihangLSP: handleLoadPackage: pkg=com.android.systemui")),
                "handleLoadPackage systemui event must appear in XposedBridge logs");
        check(Log.ENTRIES.stream().anyMatch(s -> s.contains("I/QihangLSP: handleLoadPackage: pkg=com.android.systemui")),
                "handleLoadPackage systemui event must also appear in Android Log");

        // 2. Self package hook installs isLsposedHooked + forwardToLsposedBridge
        Log.clearEntries();
        XposedBridge.clear();
        XC_LoadPackage.LoadPackageParam lpSelf = new XC_LoadPackage.LoadPackageParam();
        lpSelf.packageName = HookUtils.PKG_SELF;
        lpSelf.processName = HookUtils.PKG_SELF;
        lpSelf.classLoader = LspLogBridgeRegression.class.getClassLoader();
        init.handleLoadPackage(lpSelf);

        Method mHooked = LspState.class.getDeclaredMethod("isLsposedHooked");
        Method mBridge = ModuleDiagLog.class.getDeclaredMethod("forwardToLsposedBridge", String.class);
        List<XC_MethodHook> hookedCbs = XposedBridge.HOOKS.get(mHooked);
        List<XC_MethodHook> bridgeCbs = XposedBridge.HOOKS.get(mBridge);
        check(hookedCbs != null && hookedCbs.size() == 1, "isLsposedHooked must be hooked once");
        check(bridgeCbs != null && bridgeCbs.size() == 1, "forwardToLsposedBridge must be hooked once");
        check(XposedBridge.LOG_LINES.stream().anyMatch(s -> s.contains("isLsposedHooked=true, diagBridge=true")),
                "hookSelf registration event must log success flags to XposedBridge");

        XC_MethodHook.MethodHookParam hp = new XC_MethodHook.MethodHookParam();
        hp.method = mHooked;
        XC_MethodHook.invokeBefore(hookedCbs.get(0), hp);
        check(hp.isReturnEarly() && Boolean.TRUE.equals(hp.getResult()),
                "LspState.isLsposedHooked hook must set early result TRUE");

        // Wire ModuleDiagLog observer to dispatch captured XposedInit bridge hook
        final XC_MethodHook diagHook = bridgeCbs.get(0);
        ModuleDiagLog.sBridgeObserverForTest = cleaned -> {
            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.method = mBridge;
            p.args = new Object[]{cleaned};
            try {
                XC_MethodHook.invokeBefore(diagHook, p);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            check(p.isReturnEarly(), "diagBridge hook must returnEarly(null)");
        };

        // 3. Operation log sanitization + no duplicate Android Log lines
        Log.clearEntries();
        XposedBridge.LOG_LINES.clear();
        ModuleDiagLog.record(ctx, "恢复壁纸成功 password=mySecret123 pin:9876");

        List<String> appLogs = ModuleDiagLog.readAppLogs(ctx);
        check(appLogs.size() == 1 && appLogs.get(0).contains("password=***") && appLogs.get(0).contains("pin:***"),
                "ModuleDiagLog must persist redacted op log");
        check(!appLogs.get(0).contains("mySecret123") && !appLogs.get(0).contains("9876"),
                "Secret must not remain in ModuleDiagLog");
        check(XposedBridge.LOG_LINES.size() == 1, "Expected exactly 1 XposedBridge log line, got " + XposedBridge.LOG_LINES);
        String forwarded = XposedBridge.LOG_LINES.get(0);
        check("QihangLSP: [App] 恢复壁纸成功 password=*** pin:***".equals(forwarded),
                "Forwarded line mismatch: " + forwarded);
        check(Log.ENTRIES.isEmpty(), "ModuleDiagLog forward must not emit duplicate Android Log entries: " + Log.ENTRIES);

        // 4. High-frequency frame spam suppression in both app log and LSPosed bridge
        XposedBridge.LOG_LINES.clear();
        ModuleDiagLog.record(ctx, "AOD自转帧率=60");
        ModuleDiagLog.record(ctx, "drawFrame dt=16");
        ModuleDiagLog.record(ctx, "eglSwapBuffers ok");
        ModuleDiagLog.record(ctx, "预览 angle=1.5");
        ModuleDiagLog.record(ctx, "   ");
        check(ModuleDiagLog.readAppLogs(ctx).size() == 1, "Spam lines must not enter persistent app log");
        check(XposedBridge.LOG_LINES.isEmpty(), "Spam lines must not be forwarded to XposedBridge");

        // 5. Bounds enforcement (320 chars in ModuleDiagLog, 1024 chars in HookUtils.logXposedOnly)
        StringBuilder sb400 = new StringBuilder();
        for (int i = 0; i < 400; i++) sb400.append('A');
        ModuleDiagLog.record(ctx, sb400.toString());
        check(XposedBridge.LOG_LINES.size() == 1 && XposedBridge.LOG_LINES.get(0).endsWith("..."),
                "ModuleDiagLog >320 chars must truncate before forwarding");
        check(XposedBridge.LOG_LINES.get(0).length() == ("QihangLSP: [App] ".length() + ModuleDiagLog.MAX_LINE_CHARS),
                "Bounded length mismatch: " + XposedBridge.LOG_LINES.get(0).length());

        XposedBridge.LOG_LINES.clear();
        StringBuilder sb1400 = new StringBuilder();
        for (int i = 0; i < 1400; i++) sb1400.append('B');
        HookUtils.logXposedOnly(sb1400.toString());
        check(XposedBridge.LOG_LINES.size() == 1 && XposedBridge.LOG_LINES.get(0).length() == 1024
                && XposedBridge.LOG_LINES.get(0).endsWith("..."),
                "HookUtils.logXposedOnly must bound lines to 1024 chars");

        // 6. Re-entrancy / recursion protection across HookUtils + ModuleDiagLog
        XposedBridge.LOG_LINES.clear();
        Log.clearEntries();
        final boolean[] reentered = new boolean[]{false};
        XposedBridge.sOnLogHook = () -> {
            if (!reentered[0]) {
                reentered[0] = true;
                HookUtils.log("reentrant-hook-log");
                ModuleDiagLog.record(ctx, "reentrant-diag-log");
            }
        };
        ModuleDiagLog.record(ctx, "outer-op-event");
        XposedBridge.sOnLogHook = null;
        check(reentered[0], "Re-entry hook must have fired");
        check(XposedBridge.LOG_LINES.size() == 1 && XposedBridge.LOG_LINES.get(0).contains("outer-op-event"),
                "Re-entrant calls inside XposedBridge.log must not duplicate or recurse: " + XposedBridge.LOG_LINES);
        check(ModuleDiagLog.readAppLogs(ctx).stream().anyMatch(s -> s.contains("reentrant-diag-log")),
                "Re-entrant ModuleDiagLog.record must still persist safely without re-entering bridge");

        ModuleDiagLog.sBridgeObserverForTest = null;
        ModuleDiagLog.clearForTesting(ctx);
        System.out.println("LSPosed log bridge regression: 6 injected test groups passed");
    }
}
""",
        })
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", os.pathsep.join([str(lsp_shim_cls), str(shim_cls), str(app_cls), str(android_jar)]),
            "-d", str(lsp_harness_cls),
        ] + [str(f) for f in lsp_harness_files], check=True)
        subprocess.run([
            java, "-cp", os.pathsep.join([str(lsp_harness_cls), str(lsp_shim_cls), str(shim_cls), str(app_cls), str(android_jar)]),
            "com.hyperalive.aod.LspLogBridgeRegression", str(lsp_data),
        ], check=True)

        # No-framework fallback regression: strip all de.robv.android.xposed.* classes from runtime classpath
        no_fw_shim_cls = work / "no_fw_shim_cls"
        no_fw_harness_src = work / "no_fw_harness_src"
        no_fw_harness_cls = work / "no_fw_harness_cls"
        no_fw_data = work / "no_fw_data"
        for d in (no_fw_shim_cls, no_fw_harness_src, no_fw_harness_cls, no_fw_data):
            d.mkdir(parents=True, exist_ok=True)
        for cls_file in shim_cls.rglob("*.class"):
            rel_cls = cls_file.relative_to(shim_cls)
            if rel_cls.parts[0] == "de":
                continue
            dst_cls = no_fw_shim_cls / rel_cls
            dst_cls.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(cls_file, dst_cls)

        no_fw_harness_files = write_sources(no_fw_harness_src, {
            "com/hyperalive/aod/NoFrameworkFallbackRegression.java": r"""
package com.hyperalive.aod;

import android.content.Context;
import android.util.Log;

import com.hyperalive.aod.hook.HookUtils;

import java.io.File;
import java.util.List;

public final class NoFrameworkFallbackRegression {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    public static void main(String[] args) {
        // Confirm de.robv.android.xposed.XposedBridge is truly absent at runtime
        boolean xposedPresent;
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            xposedPresent = true;
        } catch (ClassNotFoundException | LinkageError e) {
            xposedPresent = false;
        }
        check(!xposedPresent, "No-framework classpath must omit XposedBridge");

        Context ctx = new Context(new File(args[0]));
        ModuleDiagLog.clearForTesting(ctx);
        Log.clearEntries();

        check(!LspState.isLsposedHooked(), "isLsposedHooked must be false without framework");
        check(LspState.queryLspState(ctx, false) == LspState.State.UNKNOWN,
                "queryLspState without root must return UNKNOWN");

        ModuleDiagLog.record(ctx, "无框架模式操作日志 password=secret");
        ModuleDiagLog.record(ctx, "AOD自转帧率=120");
        List<String> appLogs = ModuleDiagLog.readAppLogs(ctx);
        check(appLogs.size() == 1 && appLogs.get(0).contains("无框架模式操作日志 password=***"),
                "ModuleDiagLog must work safely without Xposed framework");

        // Repeat HookUtils calls to exercise sXposedBridgeMissing latch and Android Log fallback
        HookUtils.log("no-fw-info-1");
        HookUtils.log("no-fw-info-2");
        HookUtils.log("no-fw-err", new RuntimeException("expected-fallback"));
        HookUtils.logXposedOnly("no-fw-xposed-only");
        check(Log.ENTRIES.stream().anyMatch(s -> s.contains("I/QihangLSP: no-fw-info-1")),
                "HookUtils.log fallback must still emit Android Log");
        check(Log.ENTRIES.stream().anyMatch(s -> s.contains("E/QihangLSP: no-fw-err")),
                "HookUtils.log(msg, t) fallback must still emit Android Log.e");
        check(Log.ENTRIES.stream().noneMatch(s -> s.contains("no-fw-xposed-only")),
                "logXposedOnly must silently no-op without XposedBridge or Android Log duplication");

        ModuleDiagLog.clearForTesting(ctx);
        System.out.println("No-framework fallback regression: passed cleanly with Xposed absent");
    }
}
""",
        })
        subprocess.run([
            javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-cp", os.pathsep.join([str(no_fw_shim_cls), str(app_cls), str(android_jar)]),
            "-d", str(no_fw_harness_cls),
        ] + [str(f) for f in no_fw_harness_files], check=True)
        subprocess.run([
            java, "-cp", os.pathsep.join([str(no_fw_harness_cls), str(no_fw_shim_cls), str(app_cls), str(android_jar)]),
            "com.hyperalive.aod.NoFrameworkFallbackRegression", str(no_fw_data),
        ], check=True)

    verify_bridge_probe_contracts()
    verify_mount_master_contracts()
    verify_lsp_fallback_removed()
    verify_hook_ownership_gates()
    verify_split_restore_contracts()
    verify_rewrite_cleanup_contracts()
    verify_rename_and_lsp_log_contracts()


def verify_bridge_probe_contracts() -> None:
    bridge = (APP_JAVA / "com/hyperalive/aod/root/HyperOsWallpaperBridge.java").read_text(encoding="utf-8")
    path_block = bridge[bridge.index('aidlCallJson(service, "getMiuiWallpaperPath"'):]
    path_block = path_block[:path_block.index(";")]
    assert '"", which, false, false' in path_block, "getMiuiWallpaperPath must pass empty type string"
    assert '"android"' not in path_block, "getMiuiWallpaperPath must not pass caller package android"
    for method_name in ("getSubjectPath", "getAllKeyFrame", "getDefaultEffectId", "getVideoLoop"):
        assert f'"{method_name}"' in bridge, f"Missing WALLPAPER_INFO_METHODS entry: {method_name}"
    print("Bridge probe AIDL contract: 6 assertions passed")


def verify_mount_master_contracts() -> None:
    root_java = (APP_JAVA / "com/hyperalive/aod/Root.java").read_text(encoding="utf-8")
    for tok in ("supportsMountMaster()", "suMountMaster(", "suRawMountMaster(", '"-M"'):
        assert tok in root_java, f"Missing mount-master support token in Root.java: {tok}"
    backup_java = (APP_JAVA / "com/hyperalive/aod/PreModuleWallpaperBackup.java").read_text(encoding="utf-8")
    for tok in ("Root.suMountMaster(", "Root.suRawMountMaster("):
        assert tok in backup_java, f"Missing mount-master usage in PreModuleWallpaperBackup.java: {tok}"
    gateway_block = backup_java[backup_java.index("private static HyperOsWallpaperHelper.Gateway createRootGateway("):]
    gateway_block = gateway_block[:gateway_block.index("private static JSONObject stageOemCustomPayload(")]
    assert "probeFilesReadOnlyBatch(" in backup_java and "Root.suMountMaster(" in backup_java, \
        "PreModuleWallpaperBackup must use Root.suMountMaster for batch probing"
    assert "Root.suRawMountMaster(" in gateway_block, "createRootGateway.readTextFile must use Root.suRawMountMaster"
    tpl_restore_block = backup_java[backup_java.index("private static boolean restoreAndVerifyOemTemplates("):]
    tpl_restore_block = tpl_restore_block[:tpl_restore_block.index("static boolean verifySha256SumOutput(")]
    assert "Root.suMountMaster(" in tpl_restore_block, "restoreAndVerifyOemTemplates must use Root.suMountMaster"
    print("Mount-master contract: 10 assertions passed")


def verify_lsp_fallback_removed() -> None:
    lsp_java = (APP_JAVA / "com/hyperalive/aod/LspState.java").read_text(encoding="utf-8")
    for forbidden in (
        "restoreLockDefaultWallpaper",
        "checkFallbackIfInactive",
        "doCheckFallbackIfInactive",
        "isLockWallpaperOccupiedByHyperAlive(",
        "setBitmap(",
        ".clear(",
    ):
        assert forbidden not in lsp_java, f"LspState must not contain legacy fallback/unused token: {forbidden}"
    for rel in ("com/hyperalive/aod/MainActivity.java", "com/hyperalive/aod/QihangWallpaper.java"):
        src = (APP_JAVA / rel).read_text(encoding="utf-8")
        assert "checkFallbackIfInactive" not in src, f"{rel} must not call checkFallbackIfInactive"
    root_java = (APP_JAVA / "com/hyperalive/aod/Root.java").read_text(encoding="utf-8")
    assert "restoreDefaultWallpaper(" not in root_java, "Dead Root.restoreDefaultWallpaper API must be removed"
    print("LspState & Root dead fallback removal contract: 9 assertions passed")


def verify_hook_ownership_gates() -> None:
    hook_dir = APP_JAVA / "com/hyperalive/aod/hook"
    sc = (hook_dir / "StateCoordinator.java").read_text(encoding="utf-8")
    for tok in (
        "isHyperAliveWallpaperActiveFast(",
        "isLockWallpaperActiveFast(",
        "isHomeWallpaperActiveFast(",
        "isAodPolicyActiveFast(",
        "isSlotOwnedFast(",
        "OwnershipSnapshot",
        "evaluateWallpaperOwnershipDetail(",
        "refreshWallpaperOwnershipSync(",
        "Intent.ACTION_WALLPAPER_CHANGED",
        "PROP_WP_ACTIVE",
    ):
        assert tok in sc, f"Missing StateCoordinator ownership token: {tok}"
    assert '"1".equals(propVal)' not in sc, "StateCoordinator must NOT fall back to prop=1 when unknown"
    assert '"1".equals(HookUtils.propGet(PROP_WP_ACTIVE' not in sc, "NoWait must NOT fall back to prop=1"
    for rel, gate in (
        ("SystemServerHook.java", "isHyperAliveWallpaperActiveFast()"),
        ("SystemServerHook.java", "isLockWallpaperActiveFast()"),
        ("SystemServerHook.java", "isAodPolicyActiveFast()"),
        ("SystemUiHook.java", "isLockWallpaperActiveFast()"),
        ("SystemUiHook.java", "isAodPolicyActiveFast()"),
        ("AodHook.java", "isLockWallpaperActiveFast()"),
        ("AodHook.java", "isAodPolicyActiveFast()"),
        ("ViewTreeSweeper.java", "isLockWallpaperActiveFast()"),
        ("ViewTreeSweeper.java", "clearWhenNotOwned("),
    ):
        src = (hook_dir / rel).read_text(encoding="utf-8")
        assert gate in src, f"Missing fast ownership gate {gate} in {rel}"
        assert "isHyperAliveWallpaperActive(" not in src, f"Synchronous active check must not appear in {rel}"
    preview_hook = (hook_dir / "WallpaperPreviewHook.java").read_text(encoding="utf-8")
    assert "isSlotOwnedFast(" not in preview_hook, "WallpaperPreviewHook must rely synchronously on WallpaperPreviewProvider, not stale isSlotOwnedFast"
    for forbidden in ("hookAllMethods(", "hookAll(", "getDeclaredMethods(", ".getMethods("):
        assert forbidden not in preview_hook, f"WallpaperPreviewHook must NOT enumerate class methods: {forbidden}"
    for tok in (
        "HookUtils::hookMethod",
        "PROVIDER_PREVIEW_READER",
        "PROVIDER_FULLRES_READER",
        "vep5",
        "createOrLoadTemplateView",
        "createWallpaperLayer",
        "getCurrentWallpaper",
        "applyEditPageTemplate",
        "needApplyAll",
        "applyWallpaperWithConfig",
        "applyImageWallpaper",
        "applyGalleryWallpaper",
        "applySensorWallpaper",
        "applySensorOuterVideo",
        "applyVideoWallpaper",
        "applySuperWallpaper",
        "applyImageLockWallpaper",
        "recordApplyIntent",
        "findApplyIntent",
        "showCantApplyThirdPartyWallpaperToast",
        "applyTemplate",
        "applyDisplayPreviewToCommonConfig",
        "prepareCommonConfigForNativeApply",
        "createValidPositionInfo(",
    ):
        assert tok in preview_hook, f"Missing WallpaperPreviewHook contract token: {tok}"
    sys_srv = (hook_dir / "SystemServerHook.java").read_text(encoding="utf-8")
    assert "refreshWallpaperOwnershipSync(" in sys_srv, "SystemServerHook must refresh ownership off-lock via postAsync"
    print("Hook ownership gate contract: 36 assertions passed")


def verify_split_restore_contracts() -> None:
    bridge = (APP_JAVA / "com/hyperalive/aod/root/HyperOsWallpaperBridge.java").read_text(encoding="utf-8")
    for tok in (
        "INVALID_SPLIT_LOCK_COMP",
        "UNSAFE_SPLIT_SAME_LAYER_BLACK_SCREEN",
        "split_same_layer_alpha_zero_risk",
        "populateOemSameLayerRenderState(",
        "setWallpaper3.invoke(\n                        service, 2,",
        "setWallpaper3.invoke(\n                        service, 1,",
        "populateWmsXmlComponents(",
        "checkStandardSlotHash(",
    ):
        assert tok in bridge, f"Missing split-restore bridge contract token: {tok}"
    assert bridge.index("UNSAFE_SPLIT_SAME_LAYER_BLACK_SCREEN") < bridge.index("clearLiveWallpaperViaWms("), \
        "Bridge must reject unsafe split sameLayer spec BEFORE clearLiveWallpaperViaWms"

    sc = (APP_JAVA / "com/hyperalive/aod/hook/StateCoordinator.java").read_text(encoding="utf-8")
    for tok in (
        "WALLPAPER_OWNED_PREREFRESH_MS = 7000L",
        "shouldScheduleOwnershipPreRefresh(",
        "if ((now - snap.updatedAtMs) >= WALLPAPER_OWNED_PREREFRESH_MS)",
    ):
        assert tok in sc, f"Missing StateCoordinator pre-refresh token: {tok}"

    backup = (APP_JAVA / "com/hyperalive/aod/PreModuleWallpaperBackup.java").read_text(encoding="utf-8")
    oem_cls_block = backup[backup.index("if (oemRes.mode == HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE)"):]
    oem_cls_block = oem_cls_block[:oem_cls_block.index("if (isConfirmedDefaultState(")]
    assert "PLAIN_STATIC" not in oem_cls_block, "OEM_CUSTOM_IMAGE diverged split must ABORT, never downgrade to PLAIN_STATIC"
    assert "isSplitRestoreUnsafeSameLayer(" in backup, "PreModuleWallpaperBackup must reject unsafe split sameLayer BEFORE allow_reset=1"
    print("Split restore & pre-refresh contract: 14 assertions passed")


def verify_rewrite_cleanup_contracts() -> None:
    assert not (APP_JAVA / "com/hyperalive/aod/NotificationActivity.java").exists(), "NotificationActivity.java must be removed"
    assert not (RES_DIR / "drawable/ic_texture.xml").exists(), "ic_texture.xml must be removed"
    assert not (RES_DIR / "drawable/ic_effect.xml").exists(), "ic_effect.xml must be removed"
    manifest_txt = MANIFEST.read_text(encoding="utf-8")
    assert "NotificationActivity" not in manifest_txt, "AndroidManifest.xml must not register NotificationActivity"
    assert (APP_JAVA / "com/hyperalive/aod/DirectBootContext.java").is_file(), "DirectBootContext.java must exist"
    for rel in ("PhotoStore.java", "LastSet.java", "ClockSettings.java", "AodStyleSettings.java"):
        src = (APP_JAVA / "com/hyperalive/aod" / rel).read_text(encoding="utf-8")
        assert "DirectBootContext.get(" in src, f"{rel} must delegate to DirectBootContext.get"
    sc = (APP_JAVA / "com/hyperalive/aod/hook/StateCoordinator.java").read_text(encoding="utf-8")
    for tok in ("sPowerAsync", "sOwnerAsync", "homeRecovered", "lockRecovered"):
        assert tok in sc, f"StateCoordinator must contain rewrite improvement token: {tok}"

    lr = (APP_JAVA / "com/hyperalive/aod/LensRenderer.java").read_text(encoding="utf-8")
    lr_set_block = lr[lr.index("void setAodStyle(int style)"):lr.index("private void syncRequestedAodStyleOnGlThread()")]
    assert "updateCachedStyle(" not in lr_set_block, "LensRenderer.setAodStyle must NOT mutate AodStyleSettings cache"
    assert "syncRequestedAodStyleOnGlThread(" in lr, "LensRenderer must sync requestedAodStyle on GL thread"
    ma = (APP_JAVA / "com/hyperalive/aod/MainActivity.java").read_text(encoding="utf-8")
    on_resume = ma[ma.index("protected void onResume()"):ma.index("protected void onPause()")]
    assert on_resume.index("syncClockPreview()") < on_resume.index("startScene()"), "MainActivity.onResume must syncClockPreview before startScene"
    assert "setCommittedAodStyle(" in ma, "MainActivity.syncClockPreview must call setCommittedAodStyle"
    lpv = (APP_JAVA / "com/hyperalive/aod/LivePreviewView.java").read_text(encoding="utf-8")
    assert "void setCommittedAodStyle(" in lpv, "LivePreviewView must expose setCommittedAodStyle"
    assert "void setDraftSessionId(" in lpv, "LivePreviewView must support draft session isolation"
    assert "previewFramePhoto" in lr and "PhotoDraftStore.loadSource(" in lr, "LensRenderer preview must load 3-slot draft/fallback sources"
    ed = (APP_JAVA / "com/hyperalive/aod/EditorActivity.java").read_text(encoding="utf-8")
    for forbidden in (
        "PhotoStore.stageFromUri(",
        "PhotoStore.stageExistingOrFallback(",
        "PhotoStore.loadDefaultExample(",
        "PhotoStore.syncFrameToLockWithDefaultCrop(",
        "PhotoStore.syncToDe(",
        '.putBoolean("home_follow_lock"',
    ):
        assert forbidden not in ed, f"EditorActivity must not mutate committed state prematurely: {forbidden}"
    for required in (
        "PhotoDraftStore.beginTransactionSnapshot(",
        "PhotoDraftStore.commitDraftPhotosIntoTransaction(",
        "PhotoDraftStore.completeTransactionSnapshot(",
        "PhotoDraftStore.rollbackTransactionSnapshot(",
        "PhotoDraftStore.releaseDraftLeaseAndDiscardIfIdle(",
        "PhotoDraftStore.syncFrameToLockDraftWithDefaultCrop(",
        "static ApplyTxResult executeApplyTransactionCore(",
    ):
        assert required in ed, f"EditorActivity must route edits & applies through transactional PhotoDraftStore: {required}"
    ed_on_create = ed[ed.index("protected void onCreate("):ed.index("protected void onSaveInstanceState(")]
    assert "PhotoDraftStore.recoverIncompleteCommits(" not in ed_on_create, \
        "EditorActivity.onCreate must not run blocking recovery synchronously on UI thread"
    manifest_txt = MANIFEST.read_text(encoding="utf-8")
    assert 'android:name=".QihangApp"' in manifest_txt, "AndroidManifest must register QihangApp"
    qihang_app = (APP_JAVA / "com/hyperalive/aod/QihangApp.java").read_text(encoding="utf-8")
    assert "PhotoDraftStore.triggerStartupRecoveryAsync(" in qihang_app, \
        "QihangApp must trigger asynchronous startup recovery"
    for entry_rel in (
        "com/hyperalive/aod/MainActivity.java",
        "com/hyperalive/aod/WallpaperPreviewProvider.java",
        "com/hyperalive/aod/ClockConfigProvider.java",
        "com/hyperalive/aod/QihangWallpaper.java",
    ):
        entry_src = (APP_JAVA / entry_rel).read_text(encoding="utf-8")
        assert "PhotoDraftStore.triggerStartupRecoveryAsync(" in entry_src, \
            f"{entry_rel} must trigger startup recovery at process/component entry"
    assert '"日志"' in ma and "showDiagLogDialog()" in ma and "resolveDiagSubtitleText(" in ma, \
        "MainActivity menu must include diagnostic log viewer with collection-status subtitle"
    mdl = (APP_JAVA / "com/hyperalive/aod/ModuleDiagLog.java").read_text(encoding="utf-8")
    assert "-t 250" not in mdl and "buildHookLogcatCommand(" in mdl and "hookCollectionOk" in mdl, \
        "ModuleDiagLog must tail after tag filter with pipeline RC & expose hookCollectionOk"
    pds = (APP_JAVA / "com/hyperalive/aod/PhotoDraftStore.java").read_text(encoding="utf-8")
    assert "hasCorruptRevision(" in pds and "CORRUPT_REVISION_PREFIX" in pds and "completeTransactionSnapshot(Context ctx, CommitSnapshot snapshot) throws IOException" in pds, \
        "PhotoDraftStore must enforce durable COMMITTED writes and corrupt revision locking"

    import importlib.util
    spec = importlib.util.spec_from_file_location("hyperalive_build", str(ROOT / "build.py"))
    assert spec is not None and spec.loader is not None, "Failed to load build.py spec"
    build_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build_mod)
    assert hasattr(build_mod, "KS") and hasattr(build_mod, "OUT") and hasattr(build_mod, "FINAL"), \
        "build.py must preserve KS, OUT, FINAL module attributes for import compatibility"
    valid_out, valid_final = build_mod.validate_build_paths(
        str(ROOT / "build/cli_out"),
        str(ROOT / "build/HyperAlive-v1.0.4.apk"),
    )
    assert valid_final.endswith("HyperAlive-v1.0.4.apk"), "Valid build path must resolve to HyperAlive-v1.0.4.apk"

    def expect_build_path_error(out_p: str, final_p: str, desc: str) -> None:
        try:
            build_mod.validate_build_paths(out_p, final_p)
        except ValueError:
            return
        raise AssertionError(f"build.validate_build_paths should have rejected {desc}: out={out_p}, final={final_p}")

    expect_build_path_error(str(ROOT / "build/cli_out"), str(ROOT.parent / "external_dir/HyperAlive.apk"), "external APK write")
    expect_build_path_error(str(ROOT / "build/cli_out"), str(ROOT / "app/evil.apk"), "source tree APK write")
    expect_build_path_error(str(ROOT / "build"), str(ROOT / "build/HyperAlive-v1.0.4.apk"), "OUT equal to build root")
    expect_build_path_error(str(ROOT), str(ROOT / "build/HyperAlive-v1.0.4.apk"), "OUT equal to project root")
    expect_build_path_error(str(ROOT / "build/cli_out"), str(ROOT / "build/cli_out/nested.apk"), "final_apk inside OUT temp dir")
    expect_build_path_error(str(ROOT / "build/cli_out"), str(ROOT / "build/not_an_apk.txt"), "non-apk extension")

    print("Rewrite cleanup, build path safety & architecture contract: 27 assertions passed")


def verify_rename_and_lsp_log_contracts() -> None:
    assert not (APP_JAVA / "com/qihang").exists(), "Legacy com/qihang source tree must be removed"
    manifest = MANIFEST.read_text(encoding="utf-8")
    assert 'package="com.hyperalive.aod"' in manifest, "Manifest package must be com.hyperalive.aod"
    assert "com.qihang.aod" not in manifest, "Manifest must not contain com.qihang.aod"
    assert 'android:name=".QihangApp"' in manifest
    assert 'android:name=".QihangWallpaper"' in manifest
    assert 'android:name=".QihangHomeWallpaper"' in manifest
    assert 'android:authorities="com.hyperalive.aod.clockconfig"' in manifest
    assert 'android:authorities="com.hyperalive.aod.wallpaperpreview"' in manifest

    arrays = (RES_DIR / "values/arrays.xml").read_text(encoding="utf-8")
    assert "<item>com.hyperalive.aod</item>" in arrays and "com.qihang.aod" not in arrays, \
        "xposedscope in arrays.xml must list com.hyperalive.aod instead of com.qihang.aod"

    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    assert "namespace 'com.hyperalive.aod'" in gradle and 'applicationId "com.hyperalive.aod"' in gradle, \
        "app/build.gradle namespace and applicationId must be com.hyperalive.aod"

    proguard = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8")
    assert "-keep class com.hyperalive.aod.XposedInit { *; }" in proguard \
        and "-keep class com.hyperalive.aod.** { *; }" in proguard \
        and "com.qihang.aod" not in proguard, "proguard-rules.pro must reference com.hyperalive.aod"

    build_py = (ROOT / "build.py").read_text(encoding="utf-8")
    assert "INIT_CLASS = 'com.hyperalive.aod.XposedInit'" in build_py and "com.qihang.aod" not in build_py, \
        "build.py INIT_CLASS must emit com.hyperalive.aod.XposedInit"

    hook_utils = (APP_JAVA / "com/hyperalive/aod/hook/HookUtils.java").read_text(encoding="utf-8")
    assert 'PKG_SELF = "com.hyperalive.aod"' in hook_utils
    assert "XposedLogSink" in hook_utils and "logXposedOnly(" in hook_utils and "sInXposedLog" in hook_utils, \
        "HookUtils must isolate XposedBridge calls and guard recursion"

    xposed_init = (APP_JAVA / "com/hyperalive/aod/XposedInit.java").read_text(encoding="utf-8")
    assert "forwardToLsposedBridge" in xposed_init and "HookUtils.logXposedOnly(" in xposed_init, \
        "XposedInit.hookSelf must bridge ModuleDiagLog via HookUtils.logXposedOnly"

    mdl = (APP_JAVA / "com/hyperalive/aod/ModuleDiagLog.java").read_text(encoding="utf-8")
    assert "IN_BRIDGE_EMIT" in mdl and "static void forwardToLsposedBridge(String cleanedEntry)" in mdl, \
        "ModuleDiagLog must expose guarded forwardToLsposedBridge outside lock"

    cs = (APP_JAVA / "com/hyperalive/aod/ClockSettings.java").read_text(encoding="utf-8")
    assert 'PROVIDER_URI = "content://com.hyperalive.aod.clockconfig"' in cs
    assert 'ACTION_STYLE_CHANGED = "com.hyperalive.aod.CLOCK_STYLE_CHANGED"' in cs
    wpp = (APP_JAVA / "com/hyperalive/aod/WallpaperPreviewProvider.java").read_text(encoding="utf-8")
    assert 'AUTHORITY = "com.hyperalive.aod.wallpaperpreview"' in wpp
    aod_style = (APP_JAVA / "com/hyperalive/aod/AodStyleSettings.java").read_text(encoding="utf-8")
    assert 'ACTION_STYLE_CHANGED = "com.hyperalive.aod.AOD_STYLE_CHANGED"' in aod_style
    qw = (APP_JAVA / "com/hyperalive/aod/QihangWallpaper.java").read_text(encoding="utf-8")
    assert 'ACTION_MODE = "com.hyperalive.aod.CMD"' in qw
    sc = (APP_JAVA / "com/hyperalive/aod/hook/StateCoordinator.java").read_text(encoding="utf-8")
    assert 'ACTION_MODE = "com.hyperalive.aod.CMD"' in sc
    root_java = (APP_JAVA / "com/hyperalive/aod/Root.java").read_text(encoding="utf-8")
    assert '"hyperalive_aod.dex"' in root_java
    print("Package rename & LSPosed logging contract: 23 assertions passed")


if __name__ == "__main__":
    compile_and_run_tests()
