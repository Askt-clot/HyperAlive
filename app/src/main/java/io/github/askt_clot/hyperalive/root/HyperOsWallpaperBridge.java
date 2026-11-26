package io.github.askt_clot.hyperalive.root;

import io.github.askt_clot.hyperalive.HyperOsWallpaperHelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.system.StructStat;
import android.util.Xml;

import dalvik.system.PathClassLoader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HyperOS 4 (MiWallpaper.apk) Binder/AIDL 特权桥接工具（通过 app_process 运行）。
 *
 * <p>设计约束：
 * 1. 仅从系统已安装的 {@code com.miui.miwallpaper} APK 动态加载 AIDL Stub（{@code IMiuiWallpaperManagerService$Stub}），
 *    严禁调用 {@code MiuiWallpaperManager} SDK 单例初始化，避免触发客户端副作用或静态状态污染；
 * 2. 提供只读 {@code -probe [userId]}（探测权威类型/路径/组件/WMS/白名单文件）、{@code -diagnose [userId]}
 *    与显式恢复 {@code -restore <userId> <specJsonPath>}；
 * 3. 所有跨用户路径仅允许固定白名单目录，缺权限或超时显式报错，绝不猜测或吞掉异常，不修改任何系统策略。
 */
public final class HyperOsWallpaperBridge {

    public static final String MIWALLPAPER_PKG = "com.miui.miwallpaper";
    public static final String MIWALLPAPER_IMAGE_CLS = "com.miui.miwallpaper.wallpaperservice.ImageWallpaper";
    public static final String CONTROLLER_ACTION = "android.service.wallpaper.WallPaperControllerService";
    private static final String STUB_CLASS = "com.miui.miwallpaper.IMiuiWallpaperManagerService$Stub";
    private static final Set<String> ALLOWED_PENDING_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.thememanager",
            "com.miui.aod",
            "com.miui.miwallpaper",
            "none",
            "com.android.wallpaper.livepicker"
    ));
    private static final String[] WALLPAPER_INFO_METHODS = {
            "getSubjectPath",
            "getAllKeyFrame",
            "getDefaultEffectId",
            "getVideoLoop"
    };
    private static final long RESTORE_CONVERGE_TIMEOUT_MS = 6500L;
    private static final long RESTORE_POLL_INTERVAL_MS = 150L;

    private HyperOsWallpaperBridge() {}

    public static void main(String[] args) {
        try {
            String mode = (args == null || args.length == 0) ? "-probe" : args[0];
            boolean isProbe = "-probe".equals(mode);
            boolean isDiagnose = "-diagnose".equals(mode);
            boolean isRestore = "-restore".equals(mode);
            if (!isProbe && !isDiagnose && !isRestore) {
                System.out.println("PROBE_ERR " + errJson("UNSUPPORTED_MODE",
                        "usage: -probe [userId] | -diagnose [userId] | -restore <userId> <specJsonPath>"));
                return;
            }
            String errPrefix = isRestore ? "RESTORE_ERR " : (isDiagnose ? "DIAG_ERR " : "PROBE_ERR ");
            String okPrefix = isRestore ? "RESTORE_OK " : (isDiagnose ? "DIAG_OK " : "PROBE_OK ");

            int userId = 0;
            if (args != null && args.length >= 2) {
                try {
                    userId = Integer.parseInt(args[1].trim());
                } catch (Throwable t) {
                    System.out.println(errPrefix + errJson("INVALID_USER_ID", t.getMessage()));
                    return;
                }
            }
            if (userId < 0) {
                System.out.println(errPrefix + errJson("INVALID_USER_ID", "userId < 0"));
                return;
            }
            Context sysCtx = obtainSystemContext();
            if (sysCtx == null) {
                System.out.println(errPrefix + errJson("NO_SYSTEM_CONTEXT",
                        "ActivityThread.systemMain().getSystemContext() returned null"));
                return;
            }
            final JSONObject report;
            if (isDiagnose) {
                report = runReadOnlyDiagnose(sysCtx, userId);
            } else if (isRestore) {
                String specPath = (args != null && args.length >= 3) ? args[2] : "";
                report = runRestore(sysCtx, userId, specPath);
            } else {
                report = runReadOnlyProbe(sysCtx, userId);
            }
            if (report.optBoolean("ok", false)) {
                System.out.println(okPrefix + report.toString());
            } else {
                System.out.println(errPrefix + report.toString());
            }
        } catch (Throwable t) {
            System.out.println("PROBE_ERR " + errJson("UNCAUGHT_THROWABLE", formatThrowable(t)));
        }
    }

    private static Context obtainSystemContext() {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method sysMain = atClass.getMethod("systemMain");
            Object at = sysMain.invoke(null);
            if (at == null) return null;
            Method getSysCtx = atClass.getMethod("getSystemContext");
            Object ctx = getSysCtx.invoke(at);
            return (ctx instanceof Context) ? (Context) ctx : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只读探测指定用户的 MiWallPaperControllerService AIDL 状态、WMS 槽位与真实文件系统校验。 */
    public static JSONObject runReadOnlyProbe(Context ctx, int userId) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("uid", Process.myUid());
            out.put("pid", Process.myPid());
            out.put("userId", userId);
            out.put("callerPackage", ctx.getPackageName());

            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(MIWALLPAPER_PKG, 0);
            } catch (Throwable t) {
                out.put("error", "MIWALLPAPER_PACKAGE_NOT_FOUND");
                out.put("detail", formatThrowable(t));
                return out;
            }
            boolean isSysApp = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            String apkPath = ai.sourceDir;
            out.put("miWallpaperApk", apkPath != null ? apkPath : "");
            out.put("miWallpaperSystemApp", isSysApp);
            if (!isSysApp || apkPath == null || apkPath.isEmpty() || !new File(apkPath).isFile()) {
                out.put("error", "MIWALLPAPER_NOT_VALID_SYSTEM_APK");
                out.put("detail", "systemApp=" + isSysApp + ", apkExists=" + (apkPath != null && new File(apkPath).isFile()));
                return out;
            }

            Class<?> stubClass;
            try {
                PathClassLoader apkLoader = new PathClassLoader(apkPath, ClassLoader.getSystemClassLoader());
                stubClass = apkLoader.loadClass(STUB_CLASS);
            } catch (Throwable t) {
                out.put("error", "LOAD_AIDL_STUB_FAILED");
                out.put("detail", formatThrowable(t));
                return out;
            }

            Intent bindIntent = new Intent(CONTROLLER_ACTION);
            bindIntent.setPackage(MIWALLPAPER_PKG);

            IBinder binder = peekControllerBinder(bindIntent, ctx.getPackageName(), userId, out);
            if (binder == null || out.has("error")) {
                return out;
            }
            boolean pingOk = false;
            try {
                pingOk = binder.pingBinder();
            } catch (Throwable ignored) {}
            out.put("binderPing", pingOk);
            if (!pingOk) {
                out.put("error", "BINDER_PING_FAILED");
                return out;
            }

            Object service;
            try {
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                service = asInterface.invoke(null, binder);
            } catch (Throwable t) {
                out.put("error", "STUB_AS_INTERFACE_FAILED");
                out.put("detail", formatThrowable(t));
                return out;
            }
            if (service == null) {
                out.put("error", "AS_INTERFACE_RETURNED_NULL");
                return out;
            }

            out.put("sdkVersionCode", aidlCallJson(service, "getMiuiWallpaperSdkVersionCode"));
            out.put("sdkVersion", aidlCallJson(service, "getMiuiWallpaperSdkVersion"));

            JSONObject slots = new JSONObject();
            slots.put("home", probeSingleSlot(service, ctx, userId, 1));
            slots.put("lock", probeSingleSlot(service, ctx, userId, 2));
            out.put("slots", slots);

            out.put("wms", probeWmsSummary(ctx, userId));
            out.put("fs", probeWhitelistFiles(userId));

            // 整体 ok 为 true 表示服务绑定成功且至少核心 AIDL 未遇到跨进程死锁/致命错误；
            // 具体权限缺口保留在各个字段的 error / returnedNull 中供上层决策。
            out.put("ok", true);
            return out;
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", "PROBE_EXCEPTION");
                out.put("detail", formatThrowable(t));
            } catch (Throwable ignored) {}
            return out;
        }
    }

    private static final class ServiceDispatcherInfo {
        Object loadedApk;
        Object serviceDispatcher;
        String methodUsed = "";
        String error = "";
    }

    /** 只读反射诊断：打印当前设备 IAM / LoadedApk / IServiceConnection / Context 签名并只读试探 IAM.peekService。 */
    public static JSONObject runReadOnlyDiagnose(Context ctx, int userId) {
        JSONObject out = new JSONObject();
        HandlerThread ipcThread = null;
        ExecutorService executor = null;
        try {
            out.put("ok", false);
            out.put("uid", Process.myUid());
            out.put("pid", Process.myPid());
            out.put("userId", userId);
            out.put("callerPackage", ctx.getPackageName());
            Context baseCtx = unwrapBaseContext(ctx);
            out.put("contextClass", ctx.getClass().getName());
            out.put("baseContextClass", baseCtx != null ? baseCtx.getClass().getName() : "");

            // 1. LoadedApk & ServiceDispatcher 元数据 + 纯本地构造校验
            JSONObject loadedApkJson = new JSONObject();
            Object loadedApk = findLoadedApk(baseCtx);
            loadedApkJson.put("found", loadedApk != null);
            loadedApkJson.put("className", loadedApk != null ? loadedApk.getClass().getName() : "");
            loadedApkJson.put("serviceDispatcherMethods",
                    loadedApk != null ? listMatchingMethods(loadedApk.getClass(), "ServiceDispatcher", false) : new JSONArray());

            ipcThread = new HandlerThread("hyperos-diag-ipc");
            ipcThread.start();
            Handler ipcHandler = new Handler(ipcThread.getLooper());
            executor = Executors.newSingleThreadExecutor();

            ServiceConnection dummyConn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {}
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            ServiceDispatcherInfo testSd = createServiceDispatcher(ctx, dummyConn, ipcHandler, executor);
            JSONObject sdTestJson = new JSONObject();
            sdTestJson.put("ok", testSd.serviceDispatcher != null);
            sdTestJson.put("methodUsed", testSd.methodUsed);
            sdTestJson.put("dispatcherClass", testSd.serviceDispatcher != null ? testSd.serviceDispatcher.getClass().getName() : "");
            sdTestJson.put("isIBinder", testSd.serviceDispatcher instanceof IBinder);
            if (!testSd.error.isEmpty()) sdTestJson.put("error", testSd.error);
            boolean forgetOk = forgetServiceDispatcher(testSd, ctx, dummyConn);
            sdTestJson.put("forgetOk", forgetOk);
            loadedApkJson.put("dispatcherTest", sdTestJson);
            out.put("loadedApk", loadedApkJson);

            // 2. IServiceConnection & Stub 签名
            JSONObject iscJson = new JSONObject();
            try {
                Class<?> iscClass = Class.forName("android.app.IServiceConnection");
                iscJson.put("methods", listMatchingMethods(iscClass, "", false));
                Class<?> iscStubClass = Class.forName("android.app.IServiceConnection$Stub");
                iscJson.put("stubDeclaredMethods", listMatchingMethods(iscStubClass, "connected", true));
            } catch (Throwable t) {
                iscJson.put("error", formatThrowable(t));
            }
            out.put("iServiceConnection", iscJson);

            // 3. IActivityManager 绑定相关签名
            Object iam = getIActivityManager();
            JSONObject iamJson = new JSONObject();
            iamJson.put("found", iam != null);
            iamJson.put("className", iam != null ? iam.getClass().getName() : "");
            if (iam != null) {
                iamJson.put("bindMethods", listMatchingMethods(iam.getClass(), "bind", false));
                iamJson.put("serviceMethods", listMatchingMethods(iam.getClass(), "Service", false));
            }
            out.put("iActivityManager", iamJson);

            // 4. Context 绑定相关签名
            JSONObject ctxJson = new JSONObject();
            ctxJson.put("contextBindMethods", listMatchingMethods(Context.class, "bind", false));
            if (baseCtx != null) {
                ctxJson.put("implServiceDispatcherMethods", listMatchingMethods(baseCtx.getClass(), "ServiceDispatcher", true));
            }
            out.put("contextMeta", ctxJson);

            // 5. 只读试探 IAM.peekService（获取已运行服务的现存 binder，不启动服务、不触发已知被拒的 caller=null 绑定）
            JSONObject peekProbe = new JSONObject();
            Intent bindIntent = new Intent(CONTROLLER_ACTION);
            bindIntent.setPackage(MIWALLPAPER_PKG);
            IBinder peeked = peekControllerBinder(bindIntent, ctx.getPackageName(), userId, peekProbe);
            peekProbe.put("binderNonNull", peeked != null);
            if (peeked != null) {
                boolean ping = false;
                try { ping = peeked.pingBinder(); } catch (Throwable ignored) {}
                peekProbe.put("binderPing", ping);
                if (ping) {
                    try {
                        ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(MIWALLPAPER_PKG, 0);
                        PathClassLoader apkLoader = new PathClassLoader(ai.sourceDir, ClassLoader.getSystemClassLoader());
                        Class<?> stubClass = apkLoader.loadClass(STUB_CLASS);
                        Object service = stubClass.getMethod("asInterface", IBinder.class).invoke(null, peeked);
                        if (service != null) {
                            peekProbe.put("sdkVersion", aidlCallJson(service, "getMiuiWallpaperSdkVersion"));
                        }
                    } catch (Throwable t) {
                        peekProbe.put("aidlCheckError", formatThrowable(t));
                    }
                }
            }
            out.put("peekServiceProbe", peekProbe);

            JSONObject skippedIamBind = new JSONObject();
            skippedIamBind.put("skipped", "R3_CONFIRMED_SECURITY_EXCEPTION_UNABLE_TO_FIND_APP_FOR_CALLER_NULL");
            out.put("iamNullCallerProbe", skippedIamBind);

            out.put("ok", iam != null);
            return out;
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", "DIAGNOSE_EXCEPTION");
                out.put("detail", formatThrowable(t));
            } catch (Throwable ignored) {}
            return out;
        } finally {
            if (ipcThread != null) {
                try { ipcThread.quitSafely(); } catch (Throwable ignored) {}
            }
            if (executor != null) {
                try { executor.shutdownNow(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 通过隐藏 {@code IActivityManager.peekService(Intent, String, String)} 只读获取已运行控制器服务的现存 Binder。
     * 不触发服务启动，也不伪造/注册任何 caller；若现存服务不可用则返回 null 并写入显式错误。
     */
    private static IBinder peekControllerBinder(
            Intent intent,
            String callingPkg,
            int userId,
            JSONObject out) throws Exception {
        out.put("bindMethod", "IAM:peekService(Intent,String,String)");
        int callingUserId = Process.myUid() / 100000;
        if (userId != callingUserId) {
            out.put("error", "PEEK_USER_MISMATCH");
            out.put("detail", "IActivityManager.peekService uses callingUserId=" + callingUserId
                    + ", cannot target userId=" + userId);
            return null;
        }

        Object iam = getIActivityManager();
        if (iam == null) {
            out.put("error", "IAM_UNAVAILABLE");
            out.put("detail", "ActivityManager.getService() returned null");
            return null;
        }

        Method peekMethod;
        try {
            peekMethod = iam.getClass().getMethod("peekService", Intent.class, String.class, String.class);
        } catch (NoSuchMethodException nsme) {
            out.put("error", "PEEK_METHOD_NOT_FOUND");
            out.put("detail", formatThrowable(nsme));
            return null;
        }

        try {
            Object ret = peekMethod.invoke(iam, intent, null, callingPkg);
            if (ret == null) {
                out.put("error", "EXISTING_SERVICE_UNAVAILABLE");
                out.put("detail", "IActivityManager.peekService returned null for " + CONTROLLER_ACTION
                        + " pkg=" + MIWALLPAPER_PKG + " (service not currently bound/running; no service started)");
                return null;
            }
            if (!(ret instanceof IBinder)) {
                out.put("error", "PEEK_UNEXPECTED_RESULT");
                out.put("detail", "peekService returned " + ret.getClass().getName());
                return null;
            }
            return (IBinder) ret;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            out.put("error", (cause instanceof SecurityException) ? "PEEK_PERMISSION_DENIED" : "PEEK_EXCEPTION");
            out.put("detail", formatThrowable(cause));
            return null;
        } catch (Throwable t) {
            out.put("error", "PEEK_EXCEPTION");
            out.put("detail", formatThrowable(t));
            return null;
        }
    }

    private static Context unwrapBaseContext(Context ctx) {
        Context cur = ctx;
        int guard = 0;
        while (cur instanceof ContextWrapper && guard++ < 8) {
            Context next = ((ContextWrapper) cur).getBaseContext();
            if (next == null || next == cur) break;
            cur = next;
        }
        return cur;
    }

    private static Object findLoadedApk(Context baseCtx) {
        if (baseCtx == null) return null;
        try {
            Field f = findField(baseCtx.getClass(), "mPackageInfo");
            if (f != null) {
                f.setAccessible(true);
                return f.get(baseCtx);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static ServiceDispatcherInfo createServiceDispatcher(
            Context ctx, ServiceConnection conn, Handler handler, Executor executor) {
        ServiceDispatcherInfo info = new ServiceDispatcherInfo();
        Context baseCtx = unwrapBaseContext(ctx);
        Object loadedApk = findLoadedApk(baseCtx);
        info.loadedApk = loadedApk;
        if (loadedApk == null) {
            info.error = "LOADED_APK_NULL";
            return info;
        }
        try {
            // 优先匹配 LoadedApk.getServiceDispatcher(ServiceConnection, Context, Handler, long|int)
            Method chosen = null;
            Object[] invokeArgs = null;
            for (Method m : loadedApk.getClass().getMethods()) {
                if (!"getServiceDispatcher".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 4) continue;
                if (!ServiceConnection.class.isAssignableFrom(p[0]) || !Context.class.isAssignableFrom(p[1])) continue;
                if (p[3] != long.class && p[3] != int.class) continue;
                Object flagArg = (p[3] == long.class)
                        ? Long.valueOf(Context.BIND_AUTO_CREATE) : Integer.valueOf(Context.BIND_AUTO_CREATE);
                if (Handler.class.equals(p[2]) && handler != null) {
                    chosen = m;
                    invokeArgs = new Object[]{conn, ctx, handler, flagArg};
                    break;
                }
                if (Executor.class.equals(p[2]) && executor != null && chosen == null) {
                    chosen = m;
                    invokeArgs = new Object[]{conn, ctx, executor, flagArg};
                }
            }
            if (chosen == null) {
                info.error = "GET_SERVICE_DISPATCHER_NOT_FOUND";
                return info;
            }
            info.methodUsed = formatMethodSignature(chosen);
            Object sd = chosen.invoke(loadedApk, invokeArgs);
            if (sd == null) {
                info.error = "GET_SERVICE_DISPATCHER_RETURNED_NULL";
                return info;
            }
            info.serviceDispatcher = sd;
            return info;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            info.error = formatThrowable(cause);
            return info;
        } catch (Throwable t) {
            info.error = formatThrowable(t);
            return info;
        }
    }

    private static boolean forgetServiceDispatcher(ServiceDispatcherInfo info, Context ctx, ServiceConnection conn) {
        if (info == null || info.loadedApk == null || conn == null) return false;
        try {
            Method m = info.loadedApk.getClass().getMethod(
                    "forgetServiceDispatcher", Context.class, ServiceConnection.class);
            m.invoke(info.loadedApk, ctx, conn);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getIActivityManager() {
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Method getService = amClass.getMethod("getService");
            return getService.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONArray listMatchingMethods(Class<?> cls, String needle, boolean declaredOnly) {
        JSONArray arr = new JSONArray();
        List<String> sigs = new ArrayList<>();
        Method[] methods = declaredOnly ? cls.getDeclaredMethods() : cls.getMethods();
        for (Method m : methods) {
            if (needle == null || needle.isEmpty() || m.getName().contains(needle)) {
                String sig = formatMethodSignature(m);
                if (!sigs.contains(sig)) sigs.add(sig);
            }
        }
        Collections.sort(sigs);
        for (String s : sigs) arr.put(s);
        return arr;
    }

    private static String formatMethodSignature(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static JSONObject probeSingleSlot(Object service, Context ctx, int userId, int which) throws Exception {
        JSONObject slot = new JSONObject();
        slot.put("which", which);

        JSONObject typeRes = aidlCallJson(service, "getMiuiWallpaperType", new Class<?>[]{int.class}, which);
        slot.put("type", typeRes);
        slot.put("lastType", aidlCallJson(service, "getLastMiuiWallpaperType", new Class<?>[]{int.class}, which));
        slot.put("defaultType", aidlCallJson(service, "getMiuiDefaultWallpaperType", new Class<?>[]{int.class}, which));
        slot.put("isDefault", aidlCallJson(service, "isMiuiDefaultWallpaper", new Class<?>[]{int.class}, which));
        slot.put("componentUsing", aidlCallJson(service, "isMiuiWallpaperComponentUsing", new Class<?>[]{int.class}, which));
        slot.put("effectId", aidlCallJson(service, "getEffectId", new Class<?>[]{int.class}, which));
        slot.put("blurState", aidlCallJson(service, "blurState", new Class<?>[]{int.class}, which));

        // getMiuiWallpaperPath(type, which, isDark, isThumbnail): 首参为 type，传空串 "" 以查询服务当前生效槽位路径
        JSONObject pathRes = aidlCallJson(service, "getMiuiWallpaperPath",
                new Class<?>[]{String.class, int.class, boolean.class, boolean.class},
                "", which, false, false);
        slot.put("pathCall", pathRes);

        String resolvedPath = pathRes.optString("value", "");
        if (!resolvedPath.isEmpty() && isAllowedProbePath(resolvedPath, userId)) {
            slot.put("resolvedFile", inspectImageFile(resolvedPath));
        } else if (!resolvedPath.isEmpty()) {
            JSONObject rej = new JSONObject();
            rej.put("rejectedOutsideWhitelist", true);
            slot.put("resolvedFile", rej);
        }

        // getWallpaperInfo(which, methodName, extras): 第 2 参为具体查询方法名而非包名
        slot.put("wallpaperInfo", queryWallpaperInfoMethods(service, which));
        return slot;
    }

    private static JSONObject queryWallpaperInfoMethods(Object service, int which) throws Exception {
        JSONObject infoSummary = new JSONObject();
        JSONObject methodsJson = new JSONObject();
        boolean anyOk = false;
        boolean allNull = true;
        String firstErr = "";
        try {
            Method m = service.getClass().getMethod("getWallpaperInfo", int.class, String.class, Bundle.class);
            for (String queryName : WALLPAPER_INFO_METHODS) {
                JSONObject item = new JSONObject();
                try {
                    Object ret = m.invoke(service, which, queryName, new Bundle());
                    item.put("ok", true);
                    item.put("returnedNull", ret == null);
                    anyOk = true;
                    if (ret instanceof Bundle) {
                        allNull = false;
                        Bundle b = (Bundle) ret;
                        for (String k : b.keySet()) {
                            Object val = b.get(k);
                            item.put(k, val != null ? String.valueOf(val) : "");
                        }
                    }
                } catch (InvocationTargetException ite) {
                    Throwable c = ite.getCause() != null ? ite.getCause() : ite;
                    String err = (c instanceof SecurityException) ? "SECURITY_EXCEPTION" : formatThrowable(c);
                    item.put("ok", false);
                    item.put("error", err);
                    if (firstErr.isEmpty()) firstErr = err;
                } catch (Throwable t) {
                    String err = formatThrowable(t);
                    item.put("ok", false);
                    item.put("error", err);
                    if (firstErr.isEmpty()) firstErr = err;
                }
                methodsJson.put(queryName, item);
            }
            infoSummary.put("ok", anyOk || firstErr.isEmpty());
            infoSummary.put("returnedNull", allNull);
            infoSummary.put("methods", methodsJson);
            if (!anyOk && !firstErr.isEmpty()) {
                infoSummary.put("error", firstErr);
            }
        } catch (NoSuchMethodException nsme) {
            infoSummary.put("ok", false);
            infoSummary.put("error", "NO_SUCH_METHOD:getWallpaperInfo");
        }
        return infoSummary;
    }

    /**
     * 通过 MiWallPaperControllerService AIDL (setWallpaper3 -> Map 目标路径 -> 原子写入/chmod 0777/fsync/MOVED_TO
     * 触发 MiuiWallpaperFileObserver -> K -> P -> U/d/S) 执行有服务端依据的 HyperOS 自定义壁纸恢复并验证收敛。
     */
    public static JSONObject runRestore(Context ctx, int userId, String specPath) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("uid", Process.myUid());
            out.put("pid", Process.myPid());
            out.put("userId", userId);

            if (specPath == null || specPath.trim().isEmpty()) {
                out.put("error", "MISSING_SPEC_PATH");
                out.put("detail", "restore requires specJsonPath argument");
                return out;
            }
            File specFile = new File(specPath.trim());
            if (!specFile.isFile() || !specFile.canRead()) {
                out.put("error", "SPEC_FILE_UNREADABLE");
                out.put("detail", "cannot read restore spec file");
                return out;
            }
            String specRaw = readSmallUtf8File(specFile, 256 * 1024);
            if (specRaw == null || specRaw.trim().isEmpty()) {
                out.put("error", "SPEC_FILE_EMPTY");
                return out;
            }
            JSONObject spec = new JSONObject(specRaw);
            if (spec.optInt("userId", -1) != userId) {
                out.put("error", "SPEC_USER_MISMATCH");
                out.put("detail", "spec userId=" + spec.optInt("userId", -1) + " != " + userId);
                return out;
            }

            boolean homeIsImage = spec.optBoolean("homeIsImage", false);
            boolean lockIsImage = spec.optBoolean("lockIsImage", false);
            boolean allowSplitRestore = spec.optBoolean("allowSplitRestore", false);
            String expectedWmsLockComp = spec.optString("expectedWmsLockComp", "");
            if (!homeIsImage && !lockIsImage) {
                out.put("error", "NO_IMAGE_SLOT_TO_RESTORE");
                return out;
            }
            if (allowSplitRestore) {
                if (!homeIsImage || !lockIsImage) {
                    out.put("error", "SPLIT_RESTORE_REQUIRES_BOTH_IMAGES");
                    return out;
                }
                if (!isCleanActiveSplitLockComp(expectedWmsLockComp)) {
                    out.put("error", "INVALID_SPLIT_LOCK_COMP");
                    out.put("detail", expectedWmsLockComp);
                    return out;
                }
                if (isUnsafeSplitSameLayerSpec(spec)) {
                    out.put("error", "UNSAFE_SPLIT_SAME_LAYER_BLACK_SCREEN");
                    out.put("detail", "split restore with identical home/lock sha256, sameEffectAndBlur, and trusted pendingPackage triggers MiWallpaper sameLayer/y() lock black screen");
                    return out;
                }
            }

            File homeSrc = null;
            String homeExpectedSha = spec.optString("homeSha256", "");
            if (homeIsImage) {
                String p = spec.optString("homeImagePath", "");
                homeSrc = validateBackupImageFile(p, homeExpectedSha);
                if (homeSrc == null) {
                    out.put("error", "HOME_BACKUP_IMAGE_INVALID");
                    out.put("detail", "missing, unreadable, invalid bitmap, or sha256 mismatch");
                    return out;
                }
            }

            File lockSrc = null;
            String lockExpectedSha = spec.optString("lockSha256", "");
            if (lockIsImage) {
                String p = spec.optString("lockImagePath", "");
                lockSrc = validateBackupImageFile(p, lockExpectedSha);
                if (lockSrc == null) {
                    out.put("error", "LOCK_BACKUP_IMAGE_INVALID");
                    out.put("detail", "missing, unreadable, invalid bitmap, or sha256 mismatch");
                    return out;
                }
            }
            String stdHomeExpectedSha = spec.optString("stdHomeSha256", homeExpectedSha);
            String stdLockExpectedSha = spec.optString("stdLockSha256", lockExpectedSha);

            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(MIWALLPAPER_PKG, 0);
            boolean isSysApp = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            String apkPath = ai.sourceDir;
            if (!isSysApp || apkPath == null || apkPath.isEmpty() || !new File(apkPath).isFile()) {
                out.put("error", "MIWALLPAPER_NOT_VALID_SYSTEM_APK");
                return out;
            }

            PathClassLoader apkLoader = new PathClassLoader(apkPath, ClassLoader.getSystemClassLoader());
            Class<?> stubClass = apkLoader.loadClass(STUB_CLASS);

            Intent bindIntent = new Intent(CONTROLLER_ACTION);
            bindIntent.setPackage(MIWALLPAPER_PKG);
            IBinder binder = peekControllerBinder(bindIntent, ctx.getPackageName(), userId, out);
            if (binder == null || out.has("error")) {
                return out;
            }
            if (!binder.pingBinder()) {
                out.put("error", "BINDER_PING_FAILED");
                return out;
            }
            Object service = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (service == null) {
                out.put("error", "AS_INTERFACE_RETURNED_NULL");
                return out;
            }

            ComponentName defaultComp = new ComponentName(MIWALLPAPER_PKG, MIWALLPAPER_IMAGE_CLS);
            Bundle extras = buildSetWallpaperExtras(spec);
            Method setWallpaper3 = service.getClass().getMethod(
                    "setWallpaper3", int.class, String.class, ComponentName.class, List.class, Bundle.class);

            String expectedHomeTarget = null;
            String expectedLockTarget = null;

            if (allowSplitRestore) {
                // 双槽独立恢复：禁止使用 targetWhich=3（会合并为单槽 lockId=-1 或同图覆盖），
                // 必须先清 HyperAlive，再显式分槽 which=2、which=1 恢复并核实标准槽独立文件。
                out.put("targetWhich", 2);
                out.put("splitWhich", "2,1");
                if (!clearLiveWallpaperViaWms(ctx, userId)) {
                    out.put("error", "WMS_CLEAR_BEFORE_SPLIT_RESTORE_FAILED");
                    return out;
                }
                ComponentName lockCompName = ComponentName.unflattenFromString(canonicalizeMiuiComp(expectedWmsLockComp));
                if (lockCompName == null) {
                    lockCompName = defaultComp;
                }
                Object lockMapObj = setWallpaper3.invoke(
                        service, 2, "image", lockCompName, new ArrayList<String>(), extras);
                if (!(lockMapObj instanceof Map<?, ?>)) {
                    out.put("error", "SET_WALLPAPER3_LOCK_RETURNED_NULL_OR_INVALID");
                    return out;
                }
                List<String> lockTargets = extractTargetPaths((Map<?, ?>) lockMapObj, 2, userId);
                if (lockTargets.isEmpty()) {
                    out.put("error", "SET_WALLPAPER3_MISSING_LOCK_TARGET");
                    return out;
                }
                expectedLockTarget = lockTargets.get(0);
                for (String dst : lockTargets) {
                    if (!writeWallpaperFileAtomic(lockSrc, dst)) {
                        out.put("error", "WRITE_LOCK_WALLPAPER_FAILED");
                        out.put("detail", dst);
                        return out;
                    }
                }

                Object homeMapObj = setWallpaper3.invoke(
                        service, 1, "image", defaultComp, new ArrayList<String>(), extras);
                if (!(homeMapObj instanceof Map<?, ?>)) {
                    out.put("error", "SET_WALLPAPER3_HOME_RETURNED_NULL_OR_INVALID");
                    return out;
                }
                List<String> homeTargets = extractTargetPaths((Map<?, ?>) homeMapObj, 1, userId);
                if (homeTargets.isEmpty()) {
                    out.put("error", "SET_WALLPAPER3_MISSING_HOME_TARGET");
                    return out;
                }
                expectedHomeTarget = homeTargets.get(0);
                for (String dst : homeTargets) {
                    if (!writeWallpaperFileAtomic(homeSrc, dst)) {
                        out.put("error", "WRITE_HOME_WALLPAPER_FAILED");
                        out.put("detail", dst);
                        return out;
                    }
                }
                ensureSplitWmsSlotsIfStillMerged(ctx, userId, homeSrc, lockSrc);
            } else {
                int targetWhich;
                if (homeIsImage && lockIsImage) {
                    targetWhich = 3;
                } else {
                    // 单侧自定义 + 另一侧 default：先通过 WMS 清理两侧的动态组件，防止残留 HyperAlive 或黑屏
                    if (!clearLiveWallpaperViaWms(ctx, userId)) {
                        out.put("error", "WMS_CLEAR_BEFORE_SINGLE_RESTORE_FAILED");
                        return out;
                    }
                    targetWhich = homeIsImage ? 1 : 2;
                }
                out.put("targetWhich", targetWhich);

                Object mapObj = setWallpaper3.invoke(
                        service, targetWhich, "image", defaultComp, new ArrayList<String>(), extras);
                if (!(mapObj instanceof Map<?, ?>)) {
                    out.put("error", "SET_WALLPAPER3_RETURNED_NULL_OR_INVALID");
                    out.put("detail", mapObj != null ? mapObj.getClass().getName() : "null");
                    return out;
                }
                Map<?, ?> targetMap = (Map<?, ?>) mapObj;

                // 先写 lock (which=2)，再写 home (which=1)，保证 MiuiWallpaperManagerService.K(1) 在 lock 已就绪后触发 S(..., 3)
                if (lockIsImage) {
                    List<String> lockTargets = extractTargetPaths(targetMap, 2, userId);
                    if (lockTargets.isEmpty()) {
                        out.put("error", "SET_WALLPAPER3_MISSING_LOCK_TARGET");
                        return out;
                    }
                    expectedLockTarget = lockTargets.get(0);
                    for (String dst : lockTargets) {
                        if (!writeWallpaperFileAtomic(lockSrc, dst)) {
                            out.put("error", "WRITE_LOCK_WALLPAPER_FAILED");
                            out.put("detail", dst);
                            return out;
                        }
                    }
                }

                if (homeIsImage) {
                    List<String> homeTargets = extractTargetPaths(targetMap, 1, userId);
                    if (homeTargets.isEmpty()) {
                        out.put("error", "SET_WALLPAPER3_MISSING_HOME_TARGET");
                        return out;
                    }
                    expectedHomeTarget = homeTargets.get(0);
                    for (String dst : homeTargets) {
                        if (!writeWallpaperFileAtomic(homeSrc, dst)) {
                            out.put("error", "WRITE_HOME_WALLPAPER_FAILED");
                            out.put("detail", dst);
                            return out;
                        }
                    }
                }
            }

            JSONObject converged = awaitAndVerifyRestoreConvergence(
                    service, ctx, userId,
                    homeIsImage, expectedHomeTarget, homeExpectedSha,
                    lockIsImage, expectedLockTarget, lockExpectedSha,
                    allowSplitRestore, expectedWmsLockComp,
                    stdHomeExpectedSha, stdLockExpectedSha);
            out.put("verification", converged);
            if (!converged.optBoolean("converged", false)) {
                out.put("error", "RESTORE_CONVERGENCE_TIMEOUT");
                out.put("detail", converged.optString("reason", "timeout"));
                return out;
            }

            out.put("ok", true);
            return out;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            try {
                out.put("ok", false);
                out.put("error", (cause instanceof SecurityException) ? "RESTORE_SECURITY_EXCEPTION" : "RESTORE_INVOCATION_ERROR");
                out.put("detail", formatThrowable(cause));
            } catch (Throwable ignored) {}
            return out;
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", "RESTORE_EXCEPTION");
                out.put("detail", formatThrowable(t));
            } catch (Throwable ignored) {}
            return out;
        }
    }

    static Bundle buildSetWallpaperExtras(JSONObject spec) {
        Bundle b = new Bundle();
        b.putString("content", spec.optString("content", ""));
        b.putBoolean("loop_video", spec.optBoolean("loopVideo", false));
        b.putBoolean("carousel", spec.optBoolean("carousel", true));

        String rawPkg = spec.optString("pendingPackage", "");
        String safePkg = ALLOWED_PENDING_PACKAGES.contains(rawPkg) ? rawPkg : "com.miui.aod";
        b.putString("pending_package", safePkg);

        int effectId = spec.optInt("effectId", 0);
        b.putInt("effect_id", effectId);
        b.putBoolean("support_dark", spec.optBoolean("supportDark", false));
        b.putBoolean("support_matting", spec.optBoolean("supportMatting", false));

        if (spec.has("doodleEnable") && !spec.isNull("doodleEnable")) {
            String d = spec.optString("doodleEnable", "").trim();
            if ("1".equals(d) || "true".equalsIgnoreCase(d)) {
                b.putBoolean("doodle_enable", true);
            } else if ("0".equals(d) || "false".equalsIgnoreCase(d)) {
                b.putBoolean("doodle_enable", false);
            }
        }

        int enableBlur = spec.optInt("enableBlur", -1);
        b.putInt("enable_blur", enableBlur);

        String clockStyle = spec.optString("clockStyleType", "").trim();
        if (!clockStyle.isEmpty() && !"null".equalsIgnoreCase(clockStyle)) {
            b.putString("clock_style_type", clockStyle);
        }

        JSONArray clockArr = spec.optJSONArray("clockTypeInfo");
        if (clockArr != null && clockArr.length() > 0) {
            int[] arr = new int[clockArr.length()];
            for (int i = 0; i < clockArr.length(); i++) {
                arr[i] = clockArr.optInt(i, -1);
            }
            b.putIntArray("clock_type_info", arr);
        }

        int origW = spec.optInt("originBitmapWidth", 0);
        int origH = spec.optInt("originBitmapHeight", 0);
        if (origW > 0) b.putInt("origin_bitmap_width", origW);
        if (origH > 0) b.putInt("origin_bitmap_height", origH);
        b.putInt("all_key_frame", spec.optInt("allKeyFrame", 0));
        return b;
    }

    private static List<String> extractTargetPaths(Map<?, ?> targetMap, int which, int userId) {
        Object val = targetMap.get(Integer.valueOf(which));
        if (!(val instanceof List<?>)) {
            val = targetMap.get(String.valueOf(which));
        }
        if (!(val instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<String> res = new ArrayList<>();
        for (Object item : (List<?>) val) {
            if (item instanceof String) {
                String p = ((String) item).trim();
                if (isAllowedProbePath(p, userId)) {
                    res.add(p);
                }
            }
        }
        return res;
    }

    private static boolean writeWallpaperFileAtomic(File srcFile, String targetPath) {
        File dst = new File(targetPath);
        File parent = dst.getParentFile();
        if (parent == null) return false;
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            return false;
        }
        File tmp = new File(parent, dst.getName() + ".restore_tmp." + Process.myPid());
        try {
            copyStreamWithSyncAndChmod(srcFile, tmp);
            try {
                StructStat pst = Os.stat(parent.getAbsolutePath());
                Os.chown(tmp.getAbsolutePath(), pst.st_uid, pst.st_gid);
            } catch (Throwable ignored) {}
            if (dst.exists() && !dst.delete()) {
                // ignore, renameTo will attempt atomic replace or fallback below
            }
            boolean renamed = tmp.renameTo(dst);
            if (!renamed) {
                copyStreamWithSyncAndChmod(srcFile, dst);
                try {
                    StructStat pst = Os.stat(parent.getAbsolutePath());
                    Os.chown(dst.getAbsolutePath(), pst.st_uid, pst.st_gid);
                } catch (Throwable ignored) {}
            }
            try {
                Os.chmod(dst.getAbsolutePath(), 511); // 0777，匹配 MiuiWallpaperFileUtils.WALLPAPER_PERMISSION_RWX
            } catch (Throwable ignored) {}
            try {
                Class<?> seClass = Class.forName("android.os.SELinux");
                seClass.getMethod("restorecon", File.class).invoke(null, dst);
            } catch (Throwable ignored) {}
            return dst.isFile() && dst.length() == srcFile.length();
        } catch (Throwable t) {
            return false;
        } finally {
            if (tmp.exists()) {
                try { tmp.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void copyStreamWithSyncAndChmod(File src, File dst) throws Exception {
        byte[] buf = new byte[16384];
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.getFD().sync();
        }
        try {
            Os.chmod(dst.getAbsolutePath(), 511);
        } catch (Throwable ignored) {}
    }

    private static boolean clearLiveWallpaperViaWms(Context ctx, int userId) {
        boolean cleared = false;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, "wallpaper");
            Class<?> stub = Class.forName("android.app.IWallpaperManager$Stub");
            Object iwm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            if (iwm != null) {
                for (Method m : iwm.getClass().getMethods()) {
                    if ("clearWallpaper".equals(m.getName()) && m.getParameterCount() == 3) {
                        m.invoke(iwm, "android", 3, userId);
                        cleared = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!cleared) {
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
                if (wm != null) {
                    wm.clear(3);
                    cleared = true;
                }
            } catch (Throwable ignored) {}
        }
        if (!cleared) return false;
        for (int i = 0; i < 8; i++) {
            try {
                JSONObject wms = probeWmsSummary(ctx, userId);
                String sysComp = wms.optString("sysComp", "");
                String lockComp = wms.optString("lockComp", "");
                if (!sysComp.contains("io.github.askt_clot.hyperalive") && !lockComp.contains("io.github.askt_clot.hyperalive")
                        && !sysComp.contains("com.hyperalive.aod") && !lockComp.contains("com.hyperalive.aod")
                        && !sysComp.contains("com.qihang.aod") && !lockComp.contains("com.qihang.aod")) {
                    return true;
                }
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void ensureSplitWmsSlotsIfStillMerged(Context ctx, int userId, File homeSrc, File lockSrc) {
        try {
            for (int i = 0; i < 4; i++) {
                JSONObject wms = probeWmsSummary(ctx, userId);
                JSONObject fs = probeWhitelistFiles(userId);
                int lockId = wms.optInt("lockId", -1);
                JSONObject stdSys = fs.optJSONObject("stdSys");
                JSONObject stdLock = fs.optJSONObject("stdLock");
                if (lockId > 0 && stdSys != null && stdSys.optBoolean("isFile", false)
                        && stdLock != null && stdLock.optBoolean("isFile", false)) {
                    return;
                }
                Thread.sleep(150L);
            }
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
            if (wm != null) {
                try (FileInputStream hfis = new FileInputStream(homeSrc)) {
                    wm.setStream(hfis, null, false, android.app.WallpaperManager.FLAG_SYSTEM);
                }
                try (FileInputStream lfis = new FileInputStream(lockSrc)) {
                    wm.setStream(lfis, null, false, android.app.WallpaperManager.FLAG_LOCK);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {}
    }

    private static JSONObject awaitAndVerifyRestoreConvergence(
            Object service, Context ctx, int userId,
            boolean homeIsImage, String homeTargetPath, String homeSha,
            boolean lockIsImage, String lockTargetPath, String lockSha,
            boolean allowSplitRestore, String expectedWmsLockComp,
            String stdHomeSha, String stdLockSha) throws Exception {
        long deadline = System.currentTimeMillis() + RESTORE_CONVERGE_TIMEOUT_MS;
        String lastReason = "pending";
        JSONObject snapshot = new JSONObject();

        while (true) {
            JSONObject homeSlot = probeSingleSlot(service, ctx, userId, 1);
            JSONObject lockSlot = probeSingleSlot(service, ctx, userId, 2);
            JSONObject wms = probeWmsSummary(ctx, userId);
            JSONObject fs = probeWhitelistFiles(userId);

            snapshot = new JSONObject();
            snapshot.put("home", homeSlot);
            snapshot.put("lock", lockSlot);
            snapshot.put("wms", wms);
            snapshot.put("fs", fs);

            String checkReason = evaluateRestoreState(
                    homeSlot, lockSlot, wms,
                    homeIsImage, homeTargetPath, homeSha,
                    lockIsImage, lockTargetPath, lockSha,
                    allowSplitRestore, expectedWmsLockComp,
                    fs, stdHomeSha, stdLockSha);
            if (checkReason.isEmpty()) {
                snapshot.put("converged", true);
                snapshot.put("reason", "ok");
                return snapshot;
            }
            lastReason = checkReason;
            if (System.currentTimeMillis() >= deadline) {
                snapshot.put("converged", false);
                snapshot.put("reason", lastReason);
                return snapshot;
            }
            try {
                Thread.sleep(RESTORE_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                snapshot.put("converged", false);
                snapshot.put("reason", "interrupted:" + lastReason);
                return snapshot;
            }
        }
    }

    public static String evaluateRestoreState(
            JSONObject homeSlot, JSONObject lockSlot, JSONObject wms,
            boolean homeIsImage, String homeTargetPath, String homeSha,
            boolean lockIsImage, String lockTargetPath, String lockSha) {
        return evaluateRestoreState(
                homeSlot, lockSlot, wms,
                homeIsImage, homeTargetPath, homeSha,
                lockIsImage, lockTargetPath, lockSha, false, "", null, "", "");
    }

    public static String evaluateRestoreState(
            JSONObject homeSlot, JSONObject lockSlot, JSONObject wms,
            boolean homeIsImage, String homeTargetPath, String homeSha,
            boolean lockIsImage, String lockTargetPath, String lockSha,
            boolean allowSplitRestore) {
        return evaluateRestoreState(
                homeSlot, lockSlot, wms,
                homeIsImage, homeTargetPath, homeSha,
                lockIsImage, lockTargetPath, lockSha, allowSplitRestore, "", null, "", "");
    }

    public static String evaluateRestoreState(
            JSONObject homeSlot, JSONObject lockSlot, JSONObject wms,
            boolean homeIsImage, String homeTargetPath, String homeSha,
            boolean lockIsImage, String lockTargetPath, String lockSha,
            boolean allowSplitRestore, String expectedWmsLockComp,
            JSONObject fs, String stdHomeSha, String stdLockSha) {
        String homeErr = evaluateSingleSlotRestore(homeSlot, homeIsImage, homeTargetPath, homeSha, "home");
        if (!homeErr.isEmpty()) return homeErr;

        String lockErr = evaluateSingleSlotRestore(lockSlot, lockIsImage, lockTargetPath, lockSha, "lock");
        if (!lockErr.isEmpty()) return lockErr;

        int sysId = wms.optInt("sysId", -999);
        int lockId = wms.optInt("lockId", -999);
        String sysComp = wms.optString("sysComp", "");
        String lockComp = wms.optString("lockComp", "");
        if (sysId <= 0) {
            return "wms_sysId_invalid:" + sysId;
        }
        if (sysComp.contains("io.github.askt_clot.hyperalive") || lockComp.contains("io.github.askt_clot.hyperalive")
                || sysComp.contains("com.hyperalive.aod") || lockComp.contains("com.hyperalive.aod")
                || sysComp.contains("com.qihang.aod") || lockComp.contains("com.qihang.aod")) {
            return "wms_still_bound_to_hyperalive";
        }
        String normSysComp = normalizeComponentName(sysComp);
        String imageCompShort = MIWALLPAPER_PKG + "/" + MIWALLPAPER_IMAGE_CLS;
        String imageCompDot = MIWALLPAPER_PKG + "/.wallpaperservice.ImageWallpaper";
        boolean sysCompClean = "null".equals(normSysComp)
                || imageCompShort.equals(normSysComp)
                || imageCompDot.equals(normSysComp);
        if (!sysCompClean) {
            return "wms_sysComp_unexpected:" + sysComp;
        }
        if (!allowSplitRestore) {
            if (homeIsImage && lockIsImage && lockId != -1) {
                return "wms_unexpected_split_lockId:" + lockId;
            }
            if (fs != null && homeIsImage && lockIsImage) {
                JSONObject stdLock = fs.optJSONObject("stdLock");
                if (stdLock != null && stdLock.optBoolean("exists", false)) {
                    return "merged_unexpected_std_lock_file";
                }
            }
        } else {
            if (lockId <= 0) {
                return "wms_split_lockId_missing:" + lockId;
            }
            String normLockComp = normalizeComponentName(lockComp);
            if (normLockComp.isEmpty() || "null".equals(normLockComp)) {
                normLockComp = normalizeComponentName(wms.optString("lockXmlComp", ""));
            }
            if (normLockComp.isEmpty() || "null".equals(normLockComp)) {
                return "wms_lockComp_missing";
            }
            if (!isCleanActiveSplitLockComp(normLockComp)) {
                return "wms_lockComp_unexpected:" + normLockComp;
            }
            if (expectedWmsLockComp != null && !expectedWmsLockComp.trim().isEmpty()) {
                if (!canonicalizeMiuiComp(expectedWmsLockComp).equals(canonicalizeMiuiComp(normLockComp))) {
                    return "wms_lockComp_mismatch:" + normLockComp;
                }
            }
            if (fs != null) {
                String expSysSha = (stdHomeSha != null && !stdHomeSha.isEmpty()) ? stdHomeSha : homeSha;
                String expLockSha = (stdLockSha != null && !stdLockSha.isEmpty()) ? stdLockSha : lockSha;
                String sysStdErr = checkStandardSlotHash(
                        fs.optJSONObject("stdSys"), fs.optJSONObject("stdSysOrig"), expSysSha, "std_sys");
                if (!sysStdErr.isEmpty()) return sysStdErr;
                String lockStdErr = checkStandardSlotHash(
                        fs.optJSONObject("stdLock"), fs.optJSONObject("stdLockOrig"), expLockSha, "std_lock");
                if (!lockStdErr.isEmpty()) return lockStdErr;
            }
            if (homeIsImage && lockIsImage && homeSha != null && !homeSha.isEmpty()
                    && homeSha.equalsIgnoreCase(lockSha)) {
                boolean sameEffectAndBlur = !wms.has("sameEffectAndBlur") || wms.optBoolean("sameEffectAndBlur", true);
                String lockPending = wms.optString("lockXmlLastPendingPkg", "").trim();
                String ch2 = wms.optString("wallpaperChanged2", "").trim();
                boolean hasPendingSignal = wms.has("lockXmlLastPendingPkg") || wms.has("wallpaperChanged2");
                boolean trustedPending = ALLOWED_PENDING_PACKAGES.contains(lockPending)
                        || ALLOWED_PENDING_PACKAGES.contains(ch2);
                if (sameEffectAndBlur && (trustedPending || !hasPendingSignal)) {
                    return "split_same_layer_alpha_zero_risk";
                }
            }
        }
        return "";
    }

    public static boolean isUnsafeSplitSameLayerSpec(JSONObject spec) {
        return HyperOsWallpaperHelper.isSplitRestoreUnsafeSameLayer(spec);
    }

    private static String checkStandardSlotHash(
            JSONObject primary, JSONObject orig, String expectedSha, String tag) {
        if (primary == null || !primary.optBoolean("isFile", false)) {
            return tag + "_missing";
        }
        if (expectedSha != null && !expectedSha.isEmpty()) {
            String pSha = primary.optString("sha256", "");
            boolean pMatch = expectedSha.equalsIgnoreCase(pSha);
            boolean origMatch = orig != null && orig.optBoolean("isFile", false)
                    && expectedSha.equalsIgnoreCase(orig.optString("sha256", ""));
            if (!pMatch && !origMatch) {
                return tag + "_sha256_mismatch";
            }
        }
        return "";
    }

    static boolean isCleanActiveSplitLockComp(String comp) {
        String c = canonicalizeMiuiComp(comp);
        return (MIWALLPAPER_PKG + "/" + MIWALLPAPER_IMAGE_CLS).equals(c)
                || (MIWALLPAPER_PKG + "/com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper").equals(c);
    }

    static String canonicalizeMiuiComp(String comp) {
        return HyperOsWallpaperHelper.canonicalizeComponent(comp);
    }

    private static String normalizeComponentName(String comp) {
        return HyperOsWallpaperHelper.unwrapComponentInfo(comp);
    }

    private static String evaluateSingleSlotRestore(
            JSONObject slot, boolean expectImage, String expectedPath, String expectedSha, String tag) {
        if (slot == null) return tag + "_slot_null";
        JSONObject typeObj = slot.optJSONObject("type");
        if (typeObj == null || !typeObj.optBoolean("ok", false) || typeObj.optBoolean("returnedNull", true)) {
            return tag + "_type_unreadable";
        }
        String actualType = typeObj.optString("value", "");
        String expectedType = expectImage ? "image" : "default";
        if (!expectedType.equals(actualType)) {
            return tag + "_type_mismatch:" + actualType;
        }
        JSONObject defObj = slot.optJSONObject("isDefault");
        if (defObj == null || !defObj.optBoolean("ok", false)) {
            return tag + "_isDefault_unreadable";
        }
        boolean actualDefault = "true".equalsIgnoreCase(defObj.optString("value", ""));
        if (actualDefault != !expectImage) {
            return tag + "_isDefault_mismatch:" + actualDefault;
        }
        if (expectImage) {
            JSONObject usingObj = slot.optJSONObject("componentUsing");
            if (usingObj == null || !"true".equalsIgnoreCase(usingObj.optString("value", ""))) {
                return tag + "_componentUsing_false";
            }
            JSONObject pathObj = slot.optJSONObject("pathCall");
            if (pathObj == null || !pathObj.optBoolean("ok", false) || pathObj.optBoolean("returnedNull", true)) {
                return tag + "_pathCall_null";
            }
            String actualPath = pathObj.optString("value", "");
            if (expectedPath != null && !expectedPath.isEmpty() && !expectedPath.equals(actualPath)) {
                return tag + "_path_mismatch:" + actualPath;
            }
            JSONObject resFile = slot.optJSONObject("resolvedFile");
            if (resFile == null || !resFile.optBoolean("isFile", false)) {
                return tag + "_resolvedFile_missing";
            }
            String actualSha = resFile.optString("sha256", "");
            if (expectedSha != null && !expectedSha.isEmpty() && !expectedSha.equalsIgnoreCase(actualSha)) {
                return tag + "_sha256_mismatch";
            }
        }
        return "";
    }

    private static File validateBackupImageFile(String path, String expectedSha) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.isFile() || !f.canRead() || f.length() <= 0L) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        if (expectedSha == null || expectedSha.isEmpty()) return null;
        return expectedSha.equalsIgnoreCase(sha256File(f)) ? f : null;
    }

    private static String readSmallUtf8File(File f, int maxBytes) {
        if (f == null || !f.isFile() || f.length() <= 0L || f.length() > maxBytes) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = fis.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) return null;
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject probeWmsSummary(int userId) throws Exception {
        return probeWmsSummary(null, userId);
    }

    private static JSONObject probeWmsSummary(Context ctx, int userId) throws Exception {
        JSONObject wms = new JSONObject();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, "wallpaper");
            Class<?> stub = Class.forName("android.app.IWallpaperManager$Stub");
            Object iwm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);

            wms.put("sysId", invokeIwmWallpaperId(iwm, 1, userId));
            wms.put("lockId", invokeIwmWallpaperId(iwm, 2, userId));
            wms.put("sysComp", invokeIwmWallpaperComponent(iwm, 1, userId));
            wms.put("lockComp", invokeIwmWallpaperComponent(iwm, 2, userId));
            populateWmsXmlComponents(wms, userId);
            populateOemSameLayerRenderState(wms, ctx, userId);
        } catch (Throwable t) {
            wms.put("error", formatThrowable(t));
        }
        return wms;
    }

    private static void populateOemSameLayerRenderState(JSONObject wms, Context ctx, int userId) {
        String base = "/data/system/theme_magic/users/" + userId + "/wallpaper/data/";
        String[] hAttr = readThemeMagicXmlAttrs(new File(base + "home.xml"));
        String[] lAttr = readThemeMagicXmlAttrs(new File(base + "lock.xml"));
        try {
            if (hAttr != null) {
                wms.put("homeXmlLastPendingPkg", hAttr[0]);
                wms.put("homeXmlEffectId", hAttr[1]);
                wms.put("homeXmlEnableBlur", hAttr[2]);
            }
            if (lAttr != null) {
                wms.put("lockXmlLastPendingPkg", lAttr[0]);
                wms.put("lockXmlEffectId", lAttr[1]);
                wms.put("lockXmlEnableBlur", lAttr[2]);
            }
            if (hAttr != null && lAttr != null) {
                boolean same = hAttr[1].equals(lAttr[1]) && hAttr[2].equals(lAttr[2]);
                wms.put("sameEffectAndBlur", same);
            }
            if (ctx != null) {
                wms.put("wallpaperChanged1", readSecureSettingString(ctx, "wallpaper_changed_1", userId));
                wms.put("wallpaperChanged2", readSecureSettingString(ctx, "wallpaper_changed_2", userId));
            }
        } catch (Throwable ignored) {}
    }

    private static String[] readThemeMagicXmlAttrs(File xmlFile) {
        if (xmlFile == null || !xmlFile.isFile() || !xmlFile.canRead()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(xmlFile)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, "UTF-8");
            int ev = parser.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wallpaper_data".equals(tag) || "wallpaper".equals(tag)
                            || "wp".equals(tag) || "kwp".equals(tag)) {
                        String pkg = safeTrim(parser.getAttributeValue(null, "lastPendingPkg"));
                        if (pkg.isEmpty()) {
                            pkg = safeTrim(parser.getAttributeValue(null, "lastPendingPackageName"));
                        }
                        String eff = safeTrim(parser.getAttributeValue(null, "effect_id"));
                        if (eff.isEmpty()) {
                            eff = safeTrim(parser.getAttributeValue(null, "effectId"));
                        }
                        String rawBlur = parser.getAttributeValue(null, "enable_blur");
                        if (rawBlur == null) {
                            rawBlur = parser.getAttributeValue(null, "enableBlur");
                        }
                        String blur = normalizeBlurString(rawBlur);
                        return new String[]{pkg, eff.isEmpty() ? "0" : eff, blur};
                    }
                }
                ev = parser.next();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String normalizeBlurString(String raw) {
        String t = safeTrim(raw);
        if ("true".equalsIgnoreCase(t) || "1".equals(t)) return "1";
        if ("false".equalsIgnoreCase(t) || "0".equals(t)) return "0";
        return "-1";
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String readSecureSettingString(Context ctx, String key, int userId) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            if (cr == null) return "";
            try {
                Method m = android.provider.Settings.Secure.class.getMethod(
                        "getStringForUser", android.content.ContentResolver.class, String.class, int.class);
                Object v = m.invoke(null, cr, key, userId);
                if (v instanceof String) return ((String) v).trim();
            } catch (NoSuchMethodException ignored) {}
            String v = android.provider.Settings.Secure.getString(cr, key);
            return v != null ? v.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void populateWmsXmlComponents(JSONObject wms, int userId) {
        File xmlFile = new File("/data/system/users/" + userId + "/wallpaper_info.xml");
        if (!xmlFile.isFile() || !xmlFile.canRead()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(xmlFile)) {
            XmlPullParser parser;
            try {
                Method resolveMethod = Xml.class.getMethod("resolvePullParser", java.io.InputStream.class);
                parser = (XmlPullParser) resolveMethod.invoke(null, fis);
            } catch (NoSuchMethodException nsme) {
                parser = Xml.newPullParser();
                parser.setInput(fis, "UTF-8");
            }
            int ev = parser.getEventType();
            String slotKey = null;
            int slotDepth = -1;
            java.util.Map<String, String> components = new java.util.HashMap<>();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wp".equals(tag) || "kwp".equals(tag)) {
                        boolean home = "wp".equals(tag);
                        int xmlId = Integer.parseInt(parser.getAttributeValue(null, "id"));
                        if (xmlId != wms.optInt(home ? "sysId" : "lockId", -999)) {
                            throw new IllegalStateException("WMS_XML_ID_MISMATCH");
                        }
                        slotKey = home ? "sysXmlComp" : "lockXmlComp";
                        slotDepth = parser.getDepth();
                        String comp = parser.getAttributeValue(null, "component");
                        if (comp != null && !comp.isEmpty()) components.put(slotKey, comp);
                    } else if ("description".equals(tag) && slotKey != null
                            && parser.getDepth() == slotDepth + 1) {
                        String comp = parser.getAttributeValue(null, "component");
                        if (comp != null && !comp.isEmpty()) {
                            String previous = components.get(slotKey);
                            if (previous != null && !canonicalizeMiuiComp(previous).equals(canonicalizeMiuiComp(comp))) {
                                throw new IllegalStateException("WMS_XML_COMPONENT_CONFLICT");
                            }
                            components.put(slotKey, comp);
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && parser.getDepth() == slotDepth) {
                    slotKey = null;
                    slotDepth = -1;
                }
                ev = parser.next();
            }
            for (java.util.Map.Entry<String, String> entry : components.entrySet()) {
                wms.put(entry.getKey(), entry.getValue());
            }
        } catch (Throwable ignored) {}
    }

    private static int invokeIwmWallpaperId(Object iwm, int which, int userId) {
        try {
            for (Method m : iwm.getClass().getMethods()) {
                if ("getWallpaperIdForUser".equals(m.getName()) && m.getParameterCount() == 2) {
                    Object v = m.invoke(iwm, which, userId);
                    if (v instanceof Integer) return ((Integer) v).intValue();
                }
            }
        } catch (Throwable ignored) {}
        return -999;
    }

    private static String invokeIwmWallpaperComponent(Object iwm, int which, int userId) {
        try {
            for (Method m : iwm.getClass().getMethods()) {
                if ("getWallpaperInfoWithFlags".equals(m.getName()) && m.getParameterCount() == 2) {
                    Object info = m.invoke(iwm, which, userId);
                    return formatWallpaperInfo(info);
                }
            }
            for (Method m : iwm.getClass().getMethods()) {
                if ("getWallpaperInfoForUser".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object info = m.invoke(iwm, userId);
                    return formatWallpaperInfo(info);
                }
            }
            for (Method m : iwm.getClass().getMethods()) {
                if ("getWallpaperInfo".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object info = m.invoke(iwm, userId);
                    return formatWallpaperInfo(info);
                }
            }
        } catch (Throwable t) {
            return "ERR:" + formatThrowable(t);
        }
        return "";
    }

    private static String formatWallpaperInfo(Object info) {
        if (info == null) return "null";
        try {
            Method getComponent = info.getClass().getMethod("getComponent");
            Object cn = getComponent.invoke(info);
            return cn != null ? cn.toString() : "null";
        } catch (Throwable t) {
            return String.valueOf(info);
        }
    }

    private static JSONObject probeWhitelistFiles(int userId) throws Exception {
        JSONObject fs = new JSONObject();
        fs.put("themeMagicHomeImg", inspectImageFile("/data/system/theme_magic/users/" + userId + "/wallpaper/image/home_wallpaper.jpg"));
        fs.put("themeMagicLockImg", inspectImageFile("/data/system/theme_magic/users/" + userId + "/wallpaper/image/lock_wallpaper.jpg"));
        fs.put("themeMagicHomeXml", inspectPlainFile("/data/system/theme_magic/users/" + userId + "/wallpaper/data/home.xml"));
        fs.put("themeMagicLockXml", inspectPlainFile("/data/system/theme_magic/users/" + userId + "/wallpaper/data/lock.xml"));
        fs.put("stdSys", inspectPlainFile("/data/system/users/" + userId + "/wallpaper"));
        fs.put("stdSysOrig", inspectPlainFile("/data/system/users/" + userId + "/wallpaper_orig"));
        fs.put("stdLock", inspectPlainFile("/data/system/users/" + userId + "/wallpaper_lock"));
        fs.put("stdLockOrig", inspectPlainFile("/data/system/users/" + userId + "/wallpaper_lock_orig"));
        return fs;
    }

    private static JSONObject inspectPlainFile(String path) throws Exception {
        JSONObject o = new JSONObject();
        o.put("path", path);
        File f = new File(path);
        boolean isFile = f.isFile();
        o.put("exists", f.exists());
        o.put("isFile", isFile);
        if (isFile && f.canRead()) {
            o.put("bytes", f.length());
            o.put("sha256", sha256File(f));
        }
        return o;
    }

    private static JSONObject inspectImageFile(String path) throws Exception {
        JSONObject o = inspectPlainFile(path);
        if (o.optBoolean("isFile", false)) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, opts);
                o.put("width", opts.outWidth);
                o.put("height", opts.outHeight);
            } catch (Throwable ignored) {}
        }
        return o;
    }

    static boolean isAllowedProbePath(String path, int userId) {
        if (path == null || path.isEmpty() || !path.startsWith("/") || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String seg : path.split("/")) {
            if ("..".equals(seg) || ".".equals(seg)) {
                return false;
            }
        }
        String tmPrefix = "/data/system/theme_magic/users/" + userId + "/wallpaper/";
        String stdPrefix = "/data/system/users/" + userId + "/";
        return path.startsWith(tmPrefix) || path.startsWith(stdPrefix);
    }

    private static JSONObject aidlCallJson(Object service, String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        JSONObject res = new JSONObject();
        try {
            Method m = service.getClass().getMethod(methodName, paramTypes);
            Object v = m.invoke(service, args);
            res.put("ok", true);
            res.put("returnedNull", v == null);
            res.put("value", v != null ? String.valueOf(v) : "");
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            res.put("ok", false);
            res.put("error", (cause instanceof SecurityException) ? "SECURITY_EXCEPTION" : formatThrowable(cause));
        } catch (NoSuchMethodException nsme) {
            res.put("ok", false);
            res.put("error", "NO_SUCH_METHOD:" + methodName);
        } catch (Throwable t) {
            res.put("ok", false);
            res.put("error", formatThrowable(t));
        }
        return res;
    }

    private static JSONObject aidlCallJson(Object service, String methodName) throws Exception {
        return aidlCallJson(service, methodName, new Class<?>[0]);
    }

    private static String sha256File(File f) {
        return HyperOsWallpaperHelper.sha256Hex(f);
    }

    private static String formatThrowable(Throwable t) {
        if (t == null) return "";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null && !msg.isEmpty() ? ": " + msg : "");
    }

    private static String errJson(String code, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", code != null ? code : "ERR");
            o.put("detail", detail != null ? detail : "");
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + code + "\"}";
        }
    }
}
