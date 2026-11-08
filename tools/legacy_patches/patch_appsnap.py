# -*- coding: utf-8 -*-
"""App 侧接收 system_server 的分块快照广播。"""
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


rep("""                    if (intent.getBooleanExtra("dump_frame", false)) {""",
    """                    if ("com.qihang.aod.SNAP_DATA".equals(intent.getAction())) {
                        onSnapChunk(intent);
                        return;
                    }
                    if ("com.qihang.aod.SNAP_DONE".equals(intent.getAction())) {
                        renderer.requestPhotoReload();
                        return;
                    }
                    if (intent.getBooleanExtra("dump_frame", false)) {""")

rep("""    /** 抓帧 GL 输出 */""",
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

    /** 抓帧 GL 输出 */""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')