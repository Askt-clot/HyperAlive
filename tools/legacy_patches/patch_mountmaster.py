# -*- coding: utf-8 -*-
"""用 su -M（全局 mount namespace）读系统壁纸文件：App 自己的 namespace 里 /data/system 不可见。"""
import io
import os

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\WallpaperSource.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    n = s.count(a)
    print('%d <- %r' % (n, a[:60].replace('\n', '\\n')))
    if n != 1:
        raise SystemExit('匹配数不对: %r' % a[:80])
    s = s.replace(a, b)


rep("""    /** 壁纸文件指纹（root）。 */
    String stamp() {
        try {
            String out = Root.su("stat -c %s:%Y " + SYSTEM_WALLPAPER, 5000);
            if (out != null && out.contains(":")) {
                return out.trim() + ":" + out.hashCode();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Bitmap readFromSystemFile() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "cat " + SYSTEM_WALLPAPER)
                    .redirectErrorStream(false).start();""",
    """    /** 壁纸文件指纹（root，全局 mount namespace）。 */
    String stamp() {
        try {
            String out = Root.su("-M -c \\"stat -c %s:%Y " + SYSTEM_WALLPAPER + "\\"", 8000);
            if (out != null && out.contains(":")) {
                return out.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Bitmap readFromSystemFile() {
        // App 的 mount namespace 里 /data/system 根本不存在（No such file or directory），
        // 必须 su -M 进全局 namespace；普通 su 只作兜底。
        Bitmap b = readViaRoot(true);
        if (b == null) {
            b = readViaRoot(false);
        }
        return b;
    }

    private Bitmap readViaRoot(boolean mountMaster) {
        Process p = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("su");
            if (mountMaster) {
                cmd.add("-M");
            }
            cmd.add("-c");
            cmd.add("cat " + SYSTEM_WALLPAPER);
            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();""")

rep("""            p.waitFor();
            byte[] data = bos.toByteArray();
            if (data.length < 1024) {
                Log.w(TAG, "系统壁纸文件读取过短：exit=" + p.exitValue() + " bytes=" + data.length
                        + "（多半是模块尚未拿到 root 授权）");
                return null;
            }""",
    """            p.waitFor();
            byte[] data = bos.toByteArray();
            if (data.length < 1024) {
                Log.w(TAG, "系统壁纸读取过短（mountMaster=" + mountMaster + "）：exit=" + p.exitValue()
                        + " bytes=" + data.length);
                return null;
            }""")

rep("""        } catch (Throwable t) {
            Log.w(TAG, "读系统壁纸文件失败: " + t);
            return null;
        } finally {""",
    """        } catch (Throwable t) {
            Log.w(TAG, "读系统壁纸文件失败（mountMaster=" + mountMaster + "）: " + t);
            return null;
        } finally {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')