package com.qihang.aod;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * root 特权通道：模块所有需要 root 的操作都从这里走。
 *
 * <p>本模块有两类特权动作：
 * <ul>
 *   <li>省电白名单（{@code dumpsys deviceidle whitelist}）—— 防 MIUI 冻结壁纸进程；</li>
 *   <li>app_process 辅助类（{@link com.qihang.aod.root.SetWall} / {@link com.qihang.aod.root.LspDb}）——
 *       以 uid=0 设壁纸、自动在 LSPosed 里启用本模块并写入作用域。</li>
 * </ul>
 */
public final class Root {

    public static final String TAG = "QihangAod";

    private Root() {
    }

    private static String suBin;

    /** 找一个能用的 su（App 进程里直接 exec "su" 可能报 error=13，用绝对路径最稳）。 */
    public static String suBin() {
        if (suBin != null) {
            return suBin;
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
        return suBin;
    }

    /** 执行 su 命令，返回 stdout+stderr。 */
    public static String su(String command) {
        return su(command, 15000);
    }

    /** 直接 exec su（参数原样传，不套 -c），如 suRaw(8000, "-M", "-c", "cat /x")。 */
    public static String suRaw(long timeoutMs, String... args) {
        Process p = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(suBin());
            for (String a : args) {
                cmd.add(a);
            }
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try (java.io.InputStream in = p.getInputStream()) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (System.currentTimeMillis() > deadline) {
                        break;
                    }
                }
            }
            p.waitFor();
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "suRaw 失败: " + t;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    public static String su(String command, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder(suBin(), "-c", command).redirectErrorStream(true).start();
            final Process fp = p;
            final StringBuilder out = sb;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(fp.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            out.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);
            // ★ 关键：waitFor() 无界会永久卡住（实测 app_process 跑完 SetWall 后进程不退出，
            //   导致整个应用线程卡死、状态日志连结果行都出不来）。带超时 + 超时强制结束。
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "su 超时 " + timeoutMs + "ms，强制结束");
                p.destroyForcibly();
            }
        } catch (Throwable t) {
            return "ERR " + t;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return sb.toString().trim();
    }

    public static boolean hasRoot() {
        return su("id").contains("uid=0");
    }

    /** 把本模块 APK 里的 classes.dex 释放到 /data/local/tmp，供 app_process 使用。 */
    public static String stageDex(Context ctx) {
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            File dir = new File("/data/local/tmp");
            File out = new File(dir, "qihang_aod.dex");
            long apkLen = new File(apk).length();
            if (out.exists() && out.length() > 1024 && out.lastModified() >= new File(apk).lastModified()) {
                return out.getAbsolutePath();
            }
            // App 进程对 /data/local/tmp 没有写权限（EACCES），所以先解到自己的 cache，
            // 再让 root 拷过去；最后无论如何都回退到"文件存在就用"。
            File cache = new File(ctx.getCacheDir(), "qihang_aod.dex");
            try (ZipFile zip = new ZipFile(apk);
                 InputStream in = zip.getInputStream(zip.getEntry("classes.dex"));
                 OutputStream os = new FileOutputStream(cache)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            String r = su("cp -f " + cache.getAbsolutePath() + " " + out.getAbsolutePath()
                    + " && chmod 644 " + out.getAbsolutePath() + " && echo staged");
            if (r.contains("staged")) {
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

    /** 以 root 身份用 app_process 跑本模块里的静态 main。 */
    public static String appProcess(Context ctx, String mainClass, String... args) {
        String dex = stageDex(ctx);
        if (dex == null) {
            return "ERR stage dex failed";
        }
        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(dex)
                .append(" app_process /system/bin ").append(mainClass);
        for (String a : args) {
            cmd.append(' ').append(a);
        }
        return su(cmd.toString(), 20000);
    }

    // ---- 具体特权动作 ----


    public static String lsposedSync(Context ctx, String[] scope) {
        List<String> args = new ArrayList<>();
        args.add(ctx.getApplicationInfo().sourceDir);
        args.add(ctx.getPackageName());
        args.addAll(java.util.Arrays.asList(scope));
        return appProcess(ctx, "com.qihang.aod.root.LspDb", args.toArray(new String[0]));
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
                    su("settings put system alive_wallpaper_position '" + pos
                            + "'; settings put system enter_ambient_mode 1");
                }
            } catch (Throwable ignored) {
            }
        }
        // 优先用 app_process（以 root 身份调用 hidden API，不受 hidden-api 限制）
        String out = appProcess(ctx, "com.qihang.aod.root.SetWall", pkg, cls, String.valueOf(which));
        if (out.contains("SETWALL ok")) {
            return out;
        }
        Log.w(TAG, "root SetWall failed, fallback in-process: " + out);
        return com.qihang.aod.root.SetWall.set(pkg, cls, which);
    }

    /** 原子同时设置桌面槽位(which=1)与锁屏/息屏槽位(which=2)，防止单步中途打断导致一侧残留动态壁纸。 */
    public static String setDualWallpaper(Context ctx, String pkg, String homeCls, String lockCls) {
        String out = appProcess(ctx, "com.qihang.aod.root.SetWall", pkg, homeCls, lockCls, "dual");
        if (out.contains("SETWALL ok")) {
            return out;
        }
        Log.w(TAG, "root dual SetWall failed, fallback in-process: " + out);
        String r1 = com.qihang.aod.root.SetWall.set(pkg, homeCls, 1);
        String r2 = com.qihang.aod.root.SetWall.set(pkg, lockCls, 2);
        return r1 + " ; " + r2;
    }

}