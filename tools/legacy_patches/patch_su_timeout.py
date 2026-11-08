# -*- coding: utf-8 -*-
"""修 Root.su(command, timeoutMs)：原来 reader.join 超时后仍然无界 p.waitFor()，会永久卡住。
改成带超时的 waitFor + 超时后 destroyForcibly。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\Root.java'
s = io.open(P, encoding='utf-8').read()
a = """            reader.join(timeoutMs);
            p.waitFor();"""
b = """            reader.join(timeoutMs);
            // ★ 关键：waitFor() 无界会永久卡住（实测 app_process 跑完 SetWall 后进程不退出，
            //   导致整个应用线程卡死、状态日志连结果行都出不来）。带超时 + 超时强制结束。
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "su 超时 " + timeoutMs + "ms，强制结束");
                p.destroyForcibly();
            }"""
print(s.count(a))
if s.count(a) != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')