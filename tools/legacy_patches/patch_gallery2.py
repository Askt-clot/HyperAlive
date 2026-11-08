# -*- coding: utf-8 -*-
"""① manifest 补 queries(PICK/GET_CONTENT image/*) 让包可见；② pick() 优先直达 com.miui.gallery，失败回退选择器。"""
import io

MAN = r'D:\DSH\default-workspace-2\qihang_lsp\module\AndroidManifest.xml'
s = io.open(MAN, encoding='utf-8').read()
a = """        <package android:name="com.miui.miwallpaper" />
    </queries>"""
b = """        <package android:name="com.miui.miwallpaper" />
        <!-- Android 11+ 包可见性：要能查到/直达系统相册 -->
        <intent>
            <action android:name="android.intent.action.PICK" />
            <data android:mimeType="image/*" />
        </intent>
        <intent>
            <action android:name="android.intent.action.GET_CONTENT" />
            <data android:mimeType="image/*" />
        </intent>
        <package android:name="com.miui.gallery" />
    </queries>"""
n = s.count(a)
print('manifest anchor', n)
if n != 1:
    raise SystemExit('manifest 匹配数不对')
io.open(MAN, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()
a2 = """            Intent pick = PhotoStore.pickerIntent();
            String gallery = galleryPackage(pick);
            if (gallery != null) {
                pick.setPackage(gallery);
                append("相册应用=" + gallery);
            }
            startActivityForResult(pick, req);
            return;"""
b2 = """            Intent pick = PhotoStore.pickerIntent();
            String gallery = galleryPackage(pick);
            if (gallery == null) {
                gallery = "com.miui.gallery";   // 本机已知系统相册（HyperOS）
            }
            try {
                Intent targeted = new Intent(pick);
                targeted.setPackage(gallery);
                append("相册应用=" + gallery);
                startActivityForResult(targeted, req);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "直达 " + gallery + " 失败，退回选择器", t);
            }
            startActivityForResult(pick, req);
            return;"""
n2 = s.count(a2)
print('editor anchor', n2)
if n2 != 1:
    raise SystemExit('editor 匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a2, b2))
print('ok')