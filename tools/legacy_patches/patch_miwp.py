# -*- coding: utf-8 -*-
import io
import os

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\XposedInit.java'
s = io.open(P, encoding='utf-8').read()
a = '    static final String[] TARGET_PKGS = {PKG_SELF, PKG_ALIVE, "com.poc.alive"};'
b = ('    /**\n'
     '     * 需要放行壁纸门禁的包。{@code com.miui.miwallpaper} 是系统「图片壁纸」服务，\n'
     '     * 放行它才能把桌面槽位切到图片壁纸（用于验证「圆圈内容跟随桌面自定义壁纸」）。\n'
     '     */\n'
     '    static final String[] TARGET_PKGS = {PKG_SELF, PKG_ALIVE, "com.poc.alive",\n'
     '            "com.miui.miwallpaper"};')
n = s.count(a)
print(n)
if n == 1:
    io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')