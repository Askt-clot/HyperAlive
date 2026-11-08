# -*- coding: utf-8 -*-
"""补齐两处：XposedInit 的 system_server 快照块；QihangWallpaper 的 onSnapChunk 方法。"""
import io
import os

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def rep_file(name, pairs):
    p = os.path.join(BASE, name)
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = s.count(a)
        print('%s: %d <- %r' % (name, n, a[:60].replace('\n', '\\n')))
        if n != 1:
            raise SystemExit('匹配数不对：%s %r' % (name, a[:90]))
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
    return s


SYSSNAP = open(os.path.join(os.path.dirname(__file__), 'syssnap_block.java'),
               encoding='utf-8').read()

rep_file('XposedInit.java', [
    ("""    private static int sNoop;""", SYSSNAP + """
    private static int sNoop;"""),
])

rep_file('QihangWallpaper.java', [
    ("""    private void onReceivedCommand(Intent intent) {""",
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

    private void onReceivedCommand(Intent intent) {"""),
])
print('ok')