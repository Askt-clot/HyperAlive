# -*- coding: utf-8 -*-
"""Motion.setMode 打一行过渡决策日志（便于核对 tr），并把 draw 日志里的 lod 改成实际用的 0。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:46].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:90])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('Motion.java', [
    ("""        boolean interrupted = animating;
        int tr = transition(prev, next);
        lastTransition = tr;""",
     """        boolean interrupted = animating;
        int tr = transition(prev, next);
        android.util.Log.i("QihangAod", "过渡 prev=" + prev + " next=" + next + " tr=" + tr
                + " interrupted=" + interrupted + (prev != mode ? "（并掉了中间锁屏态）" : ""));
        lastTransition = tr;"""),
])

patch('LensRenderer.java', [
    ("""                    + "lod=%.2f ratios=""",
     """                    + "lod=%.2f(实用0) ratios="""),
])
print('ok')