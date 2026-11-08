# -*- coding: utf-8 -*-
"""SystemUI 侧快照系统壁纸到模块可读位置；App 侧按候选路径读取。"""
import io
import os

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(name, pairs):
    p = os.path.join(BASE, name)
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = s.count(a)
        print('%s: %d <- %r' % (name, n, a[:60].replace('\n', '\\n')))
        if n != 1:
            raise SystemExit('匹配数不对：%s %r' % (name, a[:90]))
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


SNAP = '''
    // ───────────────────────── 壁纸快照（圆圈内容跟随桌面壁纸） ─────────────────────────
    /**
     * App 自己的 mount namespace 里 /data/system 不可见（su -M 也救不回来），
     * 所以由 SystemUI 侧（uid=system、namespace 正确）读系统壁纸文件，
     * 写进模块可读的目录，再通知壁纸引擎重载。
     */
    static final String SNAP_SRC = "/data/system/users/0/wallpaper";

    private static void snapWallpaper(Context ctx) {
        byte[] data = readFileAsSystem(SNAP_SRC);
        if (data == null || data.length < 1024) {
            log("壁纸快照失败：读不到 " + SNAP_SRC + " (" + (data == null ? -1 : data.length) + ")");
            return;
        }
        String[] targets = {
                "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/storage/emulated/0/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/local/tmp/qihang_wp.bin",
        };
        for (String t : targets) {
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(t);
                os.write(data);
                os.close();
                log("壁纸快照已写 " + t + " (" + data.length + "B)");
                break;
            } catch (Throwable e) {
                log("壁纸快照写失败 " + t + " : " + e);
            }
        }
        if (ctx != null) {
            Intent back = new Intent("com.qihang.aod.SNAP_DONE");
            back.setPackage("com.qihang.aod");
            ctx.sendBroadcast(back);
        }
    }

    /** 先直接读（uid=system 有权限），失败再走 su -M。 */
    private static byte[] readFileAsSystem(String path) {
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            log("直接读 " + path + " 失败: " + t);
        }
        try {
            Process p = new ProcessBuilder("su", "-M", "-c", "cat " + path).start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            try (java.io.InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            p.waitFor();
            return bos.toByteArray();
        } catch (Throwable t) {
            log("su 读 " + path + " 失败: " + t);
            return null;
        }
    }

    private static boolean sSnapInstalled;

    private static void installSnapReceiver(Context ctx) {
        if (sSnapInstalled || ctx == null) {
            return;
        }
        sSnapInstalled = true;
        try {
            IntentFilter f = new IntentFilter("com.qihang.aod.SNAP");
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        snapWallpaper(c);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }, f, Context.RECEIVER_EXPORTED);
            log("壁纸快照接收器已注册");
        } catch (Throwable t) {
            log("注册壁纸快照接收器失败: " + t);
        }
    }

    private static int sNoop;
'''

patch('XposedInit.java', [
    ("""    private static int sSweepCount;""", SNAP + """
    private static int sSweepCount;"""),
    ("""        if (ctx != null) {
            sContext = ctx;
            ensureDisplayListener(ctx);
            startReconcile();
        }""",
     """        if (ctx != null) {
            sContext = ctx;
            ensureDisplayListener(ctx);
            startReconcile();
            installSnapReceiver(ctx);
        }"""),
])

WSSRC = '''
    static final String[] CANDIDATES = {
            "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
            "/storage/emulated/0/Android/data/com.qihang.aod/files/wp_snapshot.bin",
            "/data/local/tmp/qihang_wp.bin",
    };

    /** 优先用 SystemUI 快照（App 的 namespace 看不到 /data/system）。 */
    private Bitmap readFromSnapshot(Context ctx) {
        for (String path : CANDIDATES) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists() || f.length() < 1024) {
                    continue;
                }
                byte[] data = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0;
                    while (off < data.length) {
                        int n = in.read(data, off, data.length - off);
                        if (n <= 0) {
                            break;
                        }
                        off += n;
                    }
                }
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b != null) {
                    Log.i(TAG, "圆圈内容来源=SystemUI 快照 " + path + " " + b.getWidth() + "x" + b.getHeight());
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
'''
patch('WallpaperSource.java', [
    ("""    private Bitmap readFromSystemFile() {""", WSSRC + """
    private Bitmap readFromSystemFile() {"""),
    ("""        Bitmap bmp = readFromSystemFile();
        String from = "系统壁纸文件";
        if (bmp == null) {""",
     """        Bitmap bmp = readFromSnapshot(ctx);
        String from = "SystemUI 快照";
        if (bmp == null) {
            bmp = readFromSystemFile();
            from = "系统壁纸文件";
        }
        if (bmp == null) {"""),
])
print('ok')