# -*- coding: utf-8 -*-
"""圆内模糊状态变化时打一行日志（精确核对门控时间点，不依赖截图时机）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LensRenderer.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:46].replace('\n', '\\n')))
    if c != 1:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    /** 真·三级高斯模糊（OpenAlive 同款管线）；壁纸与预览共用。 */""",
    """    /** 上一帧是否真的做了圆内模糊（用于状态变化时打一行日志）。 */
    private boolean blurAppliedLast;
    /** 真·三级高斯模糊（OpenAlive 同款管线）；壁纸与预览共用。 */""")

rep("""        if (blurRadius >= 2f && aodLayerVisible()) {""",
    """        boolean blurNow = blurRadius >= 2f && aodLayerVisible();
        if (blurNow != blurAppliedLast) {
            blurAppliedLast = blurNow;
            Log.i(TAG, "圆内模糊 " + (blurNow ? "on" : "off") + " blur=" + blurRadius
                    + " tr=" + motion.lastTransition() + " 过渡已进行="
                    + motion.transitionElapsedMs(System.nanoTime()) + "ms mode=" + pendingMode);
        }
        if (blurNow) {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')