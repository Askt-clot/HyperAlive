# -*- coding: utf-8 -*-
"""Root 增加 suRaw；WallpaperSource 打印 su stderr。"""
import io
import os

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(name, pairs, required=True):
    p = os.path.join(BASE, name)
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = s.count(a)
        print('%s: %d <- %r' % (name, n, a[:60].replace('\n', '\\n')))
        if n != 1:
            if required:
                raise SystemExit('匹配数不对：%s %r' % (name, a[:90]))
            continue
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('Root.java', [
    ("""    public static String su(String command, long timeoutMs) {""",
     """    /** 直接 exec su（参数原样传，不套 -c），如 suRaw(8000, "-M", "-c", "cat /x")。 */
    public static String suRaw(long timeoutMs, String... args) {
        Process p = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("su");
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

    public static String su(String command, long timeoutMs) {"""),
], required=False)

patch('WallpaperSource.java', [
    ("""            String out = Root.su("-M -c \\"stat -c %s:%Y " + SYSTEM_WALLPAPER + "\\"", 8000);""",
     """            String out = Root.suRaw(8000, "-M", "-c", "stat -c %s:%Y " + SYSTEM_WALLPAPER);"""),
    ("""            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2 << 20);""",
     """            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            final Process fp = p;
            final String[] err = new String[1];
            Thread errReader = new Thread(() -> {
                try (InputStream es = fp.getErrorStream()) {
                    ByteArrayOutputStream eb = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int k;
                    while ((k = es.read(b)) > 0) {
                        eb.write(b, 0, k);
                    }
                    err[0] = eb.toString("UTF-8").trim();
                } catch (Throwable ignored) {
                }
            }, "wp-stderr");
            errReader.setDaemon(true);
            errReader.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2 << 20);"""),
    ("""                Log.w(TAG, "系统壁纸读取过短（mountMaster=" + mountMaster + "）：exit=" + p.exitValue()
                        + " bytes=" + data.length);
                return null;""",
     """                errReader.join(800);
                Log.w(TAG, "系统壁纸读取过短（mountMaster=" + mountMaster + "）：exit=" + p.exitValue()
                        + " bytes=" + data.length + " stderr=" + err[0]);
                return null;"""),
])
print('ok')