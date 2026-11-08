# -*- coding: utf-8 -*-
"""su 改用绝对路径候选（App 进程里 "su" 可能不在 PATH，或只认绝对路径）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\Root.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    /** 执行 su 命令，返回 stdout+stderr。 */""",
    """    private static String suBin;

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

    /** 执行 su 命令，返回 stdout+stderr。 */""")

rep("""            cmd.add("su");
            for (String a : args) {""",
    """            cmd.add(suBin());
            for (String a : args) {""")

rep("""            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();""",
    """            p = new ProcessBuilder(suBin(), "-c", command).redirectErrorStream(true).start();""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')