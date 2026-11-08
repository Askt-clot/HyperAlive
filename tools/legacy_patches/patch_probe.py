# -*- coding: utf-8 -*-
"""probeStatus 打印中间结果，方便定位 LSP 判定为什么失败。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\MainActivity.java'
s = io.open(P, encoding='utf-8').read()

a = """            boolean lsp = lspd && inScope;
            runOnUiThread(() -> {
                setDot(lspDot, "LSP " + (lsp ? "已激活" : "未激活"), lsp);
                setDot(rootDot, "ROOT " + (root ? "已授权" : "未授权"), root);
            });"""
b = """            boolean lsp = lspd && inScope;
            Log.i(TAG, "状态探测 root=" + root + " lspd=" + lspd + " inScope=" + inScope + " db=" + db.trim());
            runOnUiThread(() -> {
                setDot(lspDot, "LSP " + (lsp ? "已激活" : "未激活"), lsp);
                setDot(rootDot, "ROOT " + (root ? "已授权" : "未授权"), root);
            });"""
n = s.count(a)
print('anchor', n)
if n == 1:
    io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')