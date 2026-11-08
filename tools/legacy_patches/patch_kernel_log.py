# -*- coding: utf-8 -*-
"""GaussianBlur：每 3 秒打一行核参数，便于和文档数值核对。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\GaussianBlur.java'
s = io.open(P, encoding='utf-8').read()
a = "        float s = 1f / factor;"
b = """        float s = 1f / factor;
        if (System.nanoTime() - kernelLogNanos > 3_000_000_000L) {
            kernelLogNanos = System.nanoTime();
            Log.i(TAG, String.format(java.util.Locale.ROOT,
                    "高斯核 radius=%.1f factor=%.6f u_scale=%.6f taps=%d w0=%.8f w1=%.8f wLast=%.8f",
                    radius, factor, s, tapCount, packed[2], packed[3],
                    packed[2 + tapCount - 1]));
        }"""
print(s.count(a))
if s.count(a) != 1:
    raise SystemExit('匹配数不对')
s = s.replace(a, b)
s = s.replace("    private android.content.Context appContext;",
              "    private long kernelLogNanos;\n    private android.content.Context appContext;")
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')