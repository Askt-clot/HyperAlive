# -*- coding: utf-8 -*-
"""App 侧：接收 system_server 的分块快照广播（含 IntentFilter 扩展）。"""
import io
import os

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\QihangWallpaper.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    n = s.count(a)
    print('%d <- %r' % (n, a[:60].replace('\n', '\\n')))
    if n != 1:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""                    if (intent.getBooleanExtra("reload_photo", false)) {""",
    """                    if ("com.qihang.aod.SNAP_DATA".equals(intent.getAction())) {
                        onSnapChunk(intent);
                        return;
                    }
                    if ("com.qihang.aod.SNAP_DONE".equals(intent.getAction())) {
                        Log.i(TAG, "system_server 快照完成，重载圆圈内容");
                        renderer.requestPhotoReload();
                        return;
                    }
                    if (intent.getBooleanExtra("reload_photo", false)) {""")

rep("""            IntentFilter filter = new IntentFilter(XposedInit.ACTION_MODE);""",
    """            IntentFilter filter = new IntentFilter(XposedInit.ACTION_MODE);
            filter.addAction("com.qihang.aod.SNAP_DATA");
            filter.addAction("com.qihang.aod.SNAP_DONE");""")

rep("""            heartbeat = new Handler(Looper.getMainLooper());""",
    """            heartbeat = new Handler(Looper.getMainLooper());
            // 首帧就找 system_server 要一份当前壁纸（圆圈内容跟随桌面壁纸）
            try {
                Intent snap = new Intent("com.qihang.aod.SNAP");
                snap.setPackage("com.qihang.aod");
                sendBroadcast(snap);
            } catch (Throwable ignored) {
            }""")

rep("""    private byte[][] snapChunks;""", """    private byte[][] snapChunks;""") if 'snapChunks' in s else None

if 'private void onSnapChunk' not in s:
    rep("""    private void onSurfaceDestroyed(SurfaceHolder holder) {""",
        """    private byte[][] snapChunks;
    private int snapGot;
    private int snapTotal;
    private int snapLen;

    /** system_server 分块广播过来的壁纸快照，凑齐后落盘并重载纹理。 */
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
            snapLen = 0;
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

    private void onSurfaceDestroyed(SurfaceHolder holder) {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')