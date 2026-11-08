# -*- coding: utf-8 -*-
"""system_server 侧快照壁纸 → 写文件或分块广播给模块壁纸引擎。"""
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


SYSSNAP = '''
    // ───────────── system_server 侧壁纸快照（只有它能看见 /data/system） ─────────────
    private static boolean sSysSnapInstalled;

    private static Context sysContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(cur);
        } catch (Throwable t) {
            log("拿 system context 失败: " + t);
            return null;
        }
    }

    private static void installSystemSnap() {
        if (sSysSnapInstalled) {
            return;
        }
        Context ctx = sysContext();
        if (ctx == null) {
            return;
        }
        sSysSnapInstalled = true;
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        snapAsSystem(c);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }, new IntentFilter("com.qihang.aod.SNAP"), Context.RECEIVER_EXPORTED);
            log("system_server 壁纸快照接收器已注册");
        } catch (Throwable t) {
            log("注册 system_server 快照接收器失败: " + t);
        }
    }

    private static void snapAsSystem(Context ctx) {
        byte[] data = null;
        try (java.io.InputStream in = new java.io.FileInputStream(SNAP_SRC)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            data = bos.toByteArray();
        } catch (Throwable t) {
            log("system_server 读壁纸失败: " + t);
        }
        if (data == null || data.length < 1024) {
            log("system_server 读壁纸过短: " + (data == null ? -1 : data.length));
            return;
        }
        log("system_server 读到壁纸 " + data.length + "B");
        String[] targets = {
                "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/local/tmp/qihang_wp.bin",
                "/data/data/com.qihang.aod/files/wp_snapshot.bin",
        };
        boolean wrote = false;
        for (String t : targets) {
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(t)) {
                os.write(data);
                log("壁纸快照已写 " + t);
                wrote = true;
                break;
            } catch (Throwable e) {
                log("壁纸快照写失败 " + t + " : " + e);
            }
        }
        if (!wrote) {
            // 兜底：分块广播（Binder 上限 1MB，每块 640KB）
            int chunk = 640 * 1024;
            int total = (data.length + chunk - 1) / chunk;
            for (int i = 0; i < total; i++) {
                int from = i * chunk;
                int to = Math.min(data.length, from + chunk);
                Intent it = new Intent("com.qihang.aod.SNAP_DATA");
                it.setPackage("com.qihang.aod");
                it.putExtra("idx", i);
                it.putExtra("total", total);
                it.putExtra("len", data.length);
                it.putExtra("chunk", java.util.Arrays.copyOfRange(data, from, to));
                ctx.sendBroadcast(it);
            }
            log("壁纸快照改走分块广播: " + total + " 块");
        }
    }
'''

patch('XposedInit.java', [
    ("""    private static int sNoop;""", SYSSNAP + """
    private static int sNoop;"""),
    # 在 system_server 的 checkPermission hook 里懒安装
    ("""        if (PKG_AMBIENT.equals(permission)) {""",
     """        installSystemSnap();
        if (PKG_AMBIENT.equals(permission)) {"""),
])

# App 侧：接收分块广播 → 落盘 → 重载纹理
patch('QihangWallpaper.java', [
    ("""                    if (intent.getBooleanExtra("dump_frame", false)) {""",
     """                    if ("com.qihang.aod.SNAP_DATA".equals(intent.getAction())) {
                        onSnapChunk(intent);
                        return;
                    }
                    if ("com.qihang.aod.SNAP_DONE".equals(intent.getAction())) {
                        renderer.requestPhotoReload();
                        return;
                    }
                    if (intent.getBooleanExtra("dump_frame", false)) {"""),
    ("""    /** 抓帧 GL 输出 */""",
     """    private byte[][] snapChunks;
    private int snapGot;
    private int snapTotal;
    private int snapLen;

    /** system_server 分块广播过来的壁纸快照，凑齐后落盘并重载。 */
    private void onSnapChunk(Intent intent) {
        int idx = intent.getIntExtra("idx", -1);
        int total = intent.getIntExtra("total", 0);
        int len = intent.getIntExtra("len", 0);
        byte[] chunk = intent.getByteArrayExtra("chunk");
        if (idx < 0 || total <= 0 || chunk == null) {
            return;
        }
        if (snapChunks == null || snapTotal != total) {
            snapChunks = new byte[total][];
            snapGot = 0;
            snapTotal = total;
        }
        if (snapChunks[idx] == null) {
            snapChunks[idx] = chunk;
            snapGot++;
            snapLen += chunk.length;
        }
        Log.i(TAG, "快照分块 " + snapGot + "/" + snapTotal + " (" + snapLen + "/" + len + ")");
        if (snapGot < snapTotal) {
            return;
        }
        try {
            java.io.File out = new java.io.File(getExternalFilesDir(null), "wp_snapshot.bin");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                for (byte[] c : snapChunks) {
                    os.write(c);
                }
            }
            Log.i(TAG, "快照落盘 " + out.getAbsolutePath() + " (" + out.length() + "B)");
            snapChunks = null;
            snapGot = 0;
            renderer.requestPhotoReload();
        } catch (Throwable t) {
            Log.e(TAG, "快照落盘失败", t);
        }
    }

    /** 抓帧 GL 输出 */"""),
])
print('ok')