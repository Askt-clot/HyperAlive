package com.hyperalive.aod;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * root 特权通道：模块所有需要 root 的操作都从这里走。
 *
 * <p>本模块有两类特权动作：
 * <ul>
 *   <li>省电白名单（{@code dumpsys deviceidle whitelist}）—— 防 MIUI 冻结壁纸进程；</li>
 *   <li>app_process 辅助类（{@link com.hyperalive.aod.root.SetWall} / {@link com.hyperalive.aod.root.LspDb}
 *       / {@link com.hyperalive.aod.root.HyperOsWallpaperBridge}）——
 *       以 uid=0 设壁纸、探测/恢复 HyperOS 壁纸、查询与同步 LSPosed 作用域。</li>
 * </ul>
 */
public final class Root {

    public static final String TAG = "QihangAod";

    private Root() {
    }

    private static volatile String suBin;

    /** 找一个能用的 su（App 进程里直接 exec "su" 可能报 error=13，用绝对路径最稳）。 */
    public static String suBin() {
        String cached = suBin;
        if (cached != null) {
            return cached;
        }
        String[] candidates = {"/system/bin/su", "/debug_ramdisk/su", "/sbin/su",
                "/system/xbin/su", "/product/bin/su", "su"};
        for (String c : candidates) {
            try {
                if (c.equals("su") || (new File(c).exists() && new File(c).canExecute())) {
                    suBin = c;
                    Log.i(TAG, "su = " + c);
                    return c;
                }
                Log.i(TAG, "跳过不可用的 su 路径 " + c);
            } catch (Throwable ignored) {
            }
        }
        suBin = "su";
        return "su";
    }

    /** 执行 su 命令，返回 stdout+stderr。 */
    public static String su(String command) {
        return su(command, 15000);
    }

    private static volatile Boolean sMountMasterSupported;

    /** 直接 exec su（参数原样传，不套 -c，不 trim），如 suRaw(8000, "-M", "-c", "cat /x")。 */
    public static String suRaw(long timeoutMs, String... args) {
        return executeSuWithDeadline(true, timeoutMs, args);
    }

    /** 检测当前 su 是否支持 -M（全局 mount namespace），结果进程内缓存。 */
    public static boolean supportsMountMaster() {
        Boolean cached = sMountMasterSupported;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean supported = false;
        try {
            String out = suExecArgs(2500, "-M", "-c", "echo __MOUNT_MASTER_OK");
            supported = (out != null && !hasOutputFailureMarker(out) && out.contains("__MOUNT_MASTER_OK"));
        } catch (Throwable ignored) {
            supported = false;
        }
        sMountMasterSupported = Boolean.valueOf(supported);
        return supported;
    }

    /** 优先以 su -M 在全局 mount namespace 读取（解决隔离命名空间下 /data/user_de/0/<pkg> 不可见）。 */
    public static String suMountMaster(String command, long timeoutMs) {
        if (supportsMountMaster()) {
            return suExecArgs(timeoutMs, "-M", "-c", command);
        }
        return suExecArgs(timeoutMs, "-c", command);
    }

    /** 优先以 su -M 在全局 mount namespace 原始输出执行（不套额外换行/trim）。 */
    public static String suRawMountMaster(long timeoutMs, String... args) {
        if (supportsMountMaster() && (args.length == 0 || !"-M".equals(args[0]))) {
            String[] withM = new String[args.length + 1];
            withM[0] = "-M";
            System.arraycopy(args, 0, withM, 1, args.length);
            return suRaw(timeoutMs, withM);
        }
        return suRaw(timeoutMs, args);
    }

    public static String su(String command, long timeoutMs) {
        return suExecArgs(timeoutMs, "-c", command);
    }

    private static String suExecArgs(long timeoutMs, String... args) {
        return executeSuWithDeadline(false, timeoutMs, args);
    }

    private static String executeSuWithDeadline(boolean rawMode, long timeoutMs, String... args) {
        Process p = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(suBin());
            if (args != null) {
                for (String a : args) {
                    cmd.add(a);
                }
            }
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return runProcessWithDeadline(p, timeoutMs, rawMode);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return rawMode ? "suRaw 失败: interrupted" : "ERR interrupted";
        } catch (Throwable t) {
            return rawMode ? ("suRaw 失败: " + t) : ("ERR " + t);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 以单一 monotonic deadline 同时覆盖子进程生命周期与流读取线程：
     * 1. 守护线程并发拉取输出并由 bufLock 同步保护缓冲区，非主动终止下的流读取异常显式记录并拒绝返回截断快照；
     * 2. 主线程按剩余时间等待进程退出及读取线程收敛，绝不发生 waitFor + join 双倍超时累加；
     * 3. 任何超时、流异常或流未收敛均优先强制终止子进程并有界清理，绝不因 synchronized Stream close 阻塞调用线程；
     * 4. 捕获 InterruptedException 时立即恢复线程 interrupt 标记。
     */
    static String runProcessWithDeadline(final Process p, long timeoutMs, boolean rawMode)
            throws InterruptedException {
        if (p == null) {
            return rawMode ? "suRaw 失败: null process" : "ERR null process";
        }
        final long effectiveTimeoutMs = Math.max(50L, timeoutMs);
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMs);
        final Object bufLock = new Object();
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final AtomicBoolean readerTimedOut = new AtomicBoolean(false);
        final AtomicBoolean forcedTerminate = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<Throwable> readerError =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        try {
            OutputStream stdin = p.getOutputStream();
            if (stdin != null) {
                stdin.close();
            }
        } catch (Throwable ignored) {
        }

        final InputStream stdout;
        try {
            stdout = p.getInputStream();
        } catch (Throwable t) {
            destroyProcessQuietly(p);
            return rawMode
                    ? ("suRaw 失败: stream open error: " + t.getMessage())
                    : ("ERR stream open error: " + t.getMessage());
        }
        if (stdout == null) {
            destroyProcessQuietly(p);
            return rawMode ? "suRaw 失败: null stdout" : "ERR null stdout";
        }

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = stdout.read(buf)) >= 0) {
                        if (n > 0) {
                            synchronized (bufLock) {
                                bos.write(buf, 0, n);
                            }
                        }
                        if (System.nanoTime() - deadlineNanos >= 0L) {
                            readerTimedOut.set(true);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    if (!forcedTerminate.get()) {
                        readerError.compareAndSet(null, t);
                    }
                } finally {
                    if (!forcedTerminate.get()) {
                        closeStreamQuietly(stdout);
                    }
                }
            }
        }, "qihang-su-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            long remNanos = deadlineNanos - System.nanoTime();
            boolean procExited = remNanos > 0L && p.waitFor(remNanos, TimeUnit.NANOSECONDS);
            if (!procExited) {
                Log.w(TAG, (rawMode ? "suRaw" : "su") + " 进程超时 " + effectiveTimeoutMs + "ms，强制结束");
                terminateProcessAndStreamBounded(p, stdout, reader, forcedTerminate);
                return rawMode
                        ? ("suRaw 失败: timeout after " + effectiveTimeoutMs + "ms")
                        : ("ERR timeout after " + effectiveTimeoutMs + "ms");
            }

            long remAfterExitNanos = deadlineNanos - System.nanoTime();
            if (remAfterExitNanos > 0L) {
                long joinMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remAfterExitNanos));
                long joinRestNanos = remAfterExitNanos - TimeUnit.MILLISECONDS.toNanos(joinMs);
                reader.join(joinMs, (int) Math.max(0L, Math.min(999_999L, joinRestNanos)));
            }
            if (reader.isAlive() || readerTimedOut.get()) {
                Log.w(TAG, (rawMode ? "suRaw" : "su") + " 输出流超时未收敛 " + effectiveTimeoutMs + "ms，强制结束");
                terminateProcessAndStreamBounded(p, stdout, reader, forcedTerminate);
                return rawMode
                        ? ("suRaw 失败: output stream timeout after " + effectiveTimeoutMs + "ms")
                        : ("ERR output stream timeout after " + effectiveTimeoutMs + "ms");
            }

            Throwable rErr = readerError.get();
            if (rErr != null) {
                Log.w(TAG, (rawMode ? "suRaw" : "su") + " 输出流读取异常", rErr);
                destroyProcessQuietly(p);
                return rawMode
                        ? ("suRaw 失败: stream read error: " + rErr.getMessage())
                        : ("ERR stream read error: " + rErr.getMessage());
            }

            final String snapshot;
            synchronized (bufLock) {
                snapshot = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return rawMode ? snapshot : snapshot.trim();
        } catch (InterruptedException ie) {
            terminateProcessAndStreamBounded(p, stdout, reader, forcedTerminate);
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    private static void terminateProcessAndStreamBounded(
            final Process p,
            final InputStream stdout,
            final Thread reader,
            final AtomicBoolean forcedTerminate) {
        forcedTerminate.set(true);
        // 终止优先：先强杀进程以打断底层管道，避免直接调用 synchronized InputStream.close() 与阻塞 read 互锁
        destroyProcessQuietly(p);
        if (reader != null) {
            try {
                reader.interrupt();
            } catch (Throwable ignored) {
            }
            joinReaderBounded(reader, 20L);
        }
        if (stdout != null) {
            if (reader != null && reader.isAlive()) {
                Thread closer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        closeStreamQuietly(stdout);
                    }
                }, "qihang-su-closer");
                closer.setDaemon(true);
                closer.start();
                joinReaderBounded(reader, 15L);
            } else {
                closeStreamQuietly(stdout);
            }
        }
    }

    private static void closeStreamQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void destroyProcessQuietly(Process p) {
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
                try {
                    p.destroy();
                } catch (Throwable ignored2) {
                }
            }
        }
    }

    private static void joinReaderBounded(Thread reader, long maxWaitMs) {
        if (reader == null) return;
        try {
            reader.join(Math.max(1L, maxWaitMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean hasRoot() {
        String out = su("id", 5000);
        return out != null && !hasOutputFailureMarker(out) && out.contains("uid=0");
    }

    /** 把本模块 APK 里的 classes.dex 释放到 /data/local/tmp，供 app_process 使用。 */
    public static String stageDex(Context ctx) {
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            File dir = new File("/data/local/tmp");
            File out = new File(dir, "hyperalive_aod.dex");
            long apkLen = new File(apk).length();
            if (out.exists() && out.length() > 1024 && out.lastModified() >= new File(apk).lastModified()) {
                return out.getAbsolutePath();
            }
            // App 进程对 /data/local/tmp 没有写权限（EACCES），所以先解到自己的 cache，
            // 再让 root 拷过去；最后无论如何都回退到"文件存在就用"。
            // 使用 Device Protected 存储确保开机 Direct Boot 阶段同样可用。
            Context storageCtx = DirectBootContext.get(ctx);
            File cache = new File(storageCtx.getCacheDir(), "hyperalive_aod.dex");
            try (ZipFile zip = new ZipFile(apk);
                 InputStream in = zip.getInputStream(zip.getEntry("classes.dex"));
                 OutputStream os = new FileOutputStream(cache)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            String r = su("cp -f " + shellQuote(cache.getAbsolutePath()) + " " + shellQuote(out.getAbsolutePath())
                    + " && chmod 644 " + shellQuote(out.getAbsolutePath()) + " && echo staged");
            if (r.contains("staged") && !hasOutputFailureMarker(r)) {
                Log.i(TAG, "staged dex via su " + out.length() + "B (apk " + apkLen + "B)");
                return out.getAbsolutePath();
            }
            Log.w(TAG, "stageDex via su 失败：" + r + "（改用已存在的旧 dex）");
            return out.exists() && out.length() > 1024 ? out.getAbsolutePath() : null;
        } catch (Throwable t) {
            Log.w(TAG, "stageDex failed", t);
            return null;
        }
    }

    /** POSIX 单引号安全转义。 */
    public static String shellQuote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /** 以 root 身份用 app_process 跑本模块里的静态 main。 */
    public static String appProcess(Context ctx, String mainClass, String... args) {
        String dex = stageDex(ctx);
        if (dex == null) {
            return "ERR stage dex failed";
        }
        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(shellQuote(dex))
                .append(" /system/bin/app_process /system/bin ").append(shellQuote(mainClass));
        for (String a : args) {
            cmd.append(' ').append(shellQuote(a));
        }
        return su(cmd.toString(), 20000);
    }

    // ---- 具体特权动作 ----

    public static String lsposedSync(Context ctx, String[] scope) {
        List<String> args = new ArrayList<>();
        args.add(ctx.getApplicationInfo().sourceDir);
        args.add(ctx.getPackageName());
        args.addAll(java.util.Arrays.asList(scope));
        return appProcess(ctx, "com.hyperalive.aod.root.LspDb", args.toArray(new String[0]));
    }

    /** which: 1=桌面 2=锁屏 3=两者 */
    public static String setWallpaper(Context ctx, String pkg, String cls, int which) {
        if ("com.flyme.alivewallpaper".equals(pkg) && ctx != null) {
            try {
                int rw = 0;
                int rh = 0;
                android.view.WindowManager wm = (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    android.util.DisplayMetrics rdm = new android.util.DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(rdm);
                    rw = Math.min(rdm.widthPixels, rdm.heightPixels);
                    rh = Math.max(rdm.widthPixels, rdm.heightPixels);
                }
                if (rw <= 0 || rh <= 0) {
                    android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    rw = Math.min(dm.widthPixels, dm.heightPixels);
                    rh = Math.max(dm.widthPixels, dm.heightPixels);
                }
                if (rw > 0 && rh > 0) {
                    int cx = rw / 2;
                    int cy = Math.round(rh * 0.335f);
                    int half = Math.round(rw * 0.2f);
                    String pos = (cx - half) + "|" + (cy - half) + "|" + (cx + half) + "|" + (cy + half);
                    int userId = android.os.Process.myUid() / 100000;
                    su("/system/bin/settings put --user " + userId + " system alive_wallpaper_position " + shellQuote(pos)
                            + "; /system/bin/settings put --user " + userId + " system enter_ambient_mode 1");
                }
            } catch (Throwable ignored) {
            }
        }
        return setWallpaperComponentRaw(ctx, pkg, cls, which);
    }

    /**
     * 严格校验 SetWall 返回文本：
     * 禁止仅抽取最后一行 success 而忽略先前异常或非 ERR 报错；所有非空行均须为合法 OK 结果行，
     * 不得包含 Exception/Error/Throwable/FAIL/denied/suRaw/堆栈或任意 ERR 段。
     */
    public static boolean isSetWallSuccess(String out) {
        if (out == null) return false;
        String t = out.trim();
        if (t.isEmpty()) {
            return false;
        }
        String[] lines = t.split("\\r?\\n");
        boolean sawOk = false;
        for (String rawLine : lines) {
            String ln = rawLine.trim();
            if (ln.isEmpty()) {
                continue;
            }
            if (hasOutputFailureMarker(ln)) {
                return false;
            }
            if (!(ln.startsWith("SETWALL ") || ln.startsWith("OK ") || ln.startsWith("ok "))) {
                return false;
            }
            if (ln.startsWith("SETWALL usage:")) {
                return false;
            }
            String[] parts = ln.split("\\s*;\\s*");
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (hasOutputFailureMarker(p)) {
                    return false;
                }
                if (p.startsWith("SETWALL OK ") || p.startsWith("SETWALL ok ")
                        || p.startsWith("OK ") || p.startsWith("ok ")) {
                    sawOk = true;
                } else {
                    return false;
                }
            }
        }
        return sawOk;
    }

    private static boolean hasOutputFailureMarker(String s) {
        if (s == null || s.isEmpty()) return false;
        String lower = s.toLowerCase(java.util.Locale.US);
        if (s.equals("ERR") || s.startsWith("ERR ") || s.contains(" ERR")
                || s.contains("ERR\n") || s.contains("suRaw")) {
            return true;
        }
        return lower.contains("exception")
                || lower.contains("error:")
                || lower.contains("throwable")
                || lower.contains("caused by:")
                || lower.contains("permission denied")
                || lower.contains("denied")
                || lower.contains("fail")
                || s.startsWith("at ");
    }

    /** 只读调用 HyperOsWallpaperBridge -probe 获取当前用户 HyperOS 权威壁纸/WMS/文件状态。 */
    public static String probeHyperOsWallpaper(Context ctx, int userId) {
        if (ctx == null) {
            return "PROBE_ERR {\"ok\":false,\"error\":\"NULL_CONTEXT\"}";
        }
        return appProcess(ctx, "com.hyperalive.aod.root.HyperOsWallpaperBridge",
                "-probe", String.valueOf(userId));
    }

    /** 调用 HyperOsWallpaperBridge -restore 通过 MiWallPaperControllerService AIDL 执行 HyperOS OEM 恢复与收敛校验。 */
    public static String restoreHyperOsWallpaper(Context ctx, int userId, String specJsonPath) {
        if (ctx == null || specJsonPath == null || specJsonPath.trim().isEmpty()) {
            return "RESTORE_ERR {\"ok\":false,\"error\":\"INVALID_ARGS\"}";
        }
        return appProcess(ctx, "com.hyperalive.aod.root.HyperOsWallpaperBridge",
                "-restore", String.valueOf(userId), specJsonPath);
    }

    /** 仅调用底层 SetWall 绑定壁纸组件，不写入 alive_wallpaper_position / enter_ambient_mode 附加配置。 */
    public static String setWallpaperComponentRaw(Context ctx, String pkg, String cls, int which) {
        // 优先用 app_process（以 root 身份调用 hidden API，不受 hidden-api 限制）
        String out = appProcess(ctx, "com.hyperalive.aod.root.SetWall", pkg, cls, String.valueOf(which));
        if (isSetWallSuccess(out)) {
            return out;
        }
        Log.w(TAG, "root SetWall failed, fallback in-process: " + out);
        return com.hyperalive.aod.root.SetWall.set(pkg, cls, which);
    }

    /** 原子同时设置桌面槽位(which=1)与锁屏/息屏槽位(which=2)，防止单步中途打断导致一侧残留动态壁纸。 */
    public static String setDualWallpaper(Context ctx, String pkg, String homeCls, String lockCls) {
        String out = appProcess(ctx, "com.hyperalive.aod.root.SetWall", pkg, homeCls, lockCls, "dual");
        if (isSetWallSuccess(out)) {
            return out;
        }
        Log.w(TAG, "root dual SetWall failed, fallback in-process: " + out);
        String r3 = com.hyperalive.aod.root.SetWall.set(pkg, lockCls, 3);
        String r1 = com.hyperalive.aod.root.SetWall.set(pkg, homeCls, 1);
        String r2 = com.hyperalive.aod.root.SetWall.set(pkg, lockCls, 2);
        return r3 + " ; " + r1 + " ; " + r2;
    }
}
