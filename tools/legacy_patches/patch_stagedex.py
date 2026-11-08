# -*- coding: utf-8 -*-
"""stageDex：App 无权限写 /data/local/tmp，改为「解到自己的 cache → su 拷过去」。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\Root.java'
s = io.open(P, encoding='utf-8').read()

a = '''            try (ZipFile zip = new ZipFile(apk);
                 InputStream in = zip.getInputStream(zip.getEntry("classes.dex"));
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            Log.i(TAG, "staged dex " + out.length() + "B (apk " + apkLen + "B)");
            return out.getAbsolutePath();'''
b = '''            // App 进程对 /data/local/tmp 没有写权限（EACCES），所以先解到自己的 cache，
            // 再让 root 拷过去；最后无论如何都回退到"文件存在就用"。
            File cache = new File(ctx.getCacheDir(), "qihang_aod.dex");
            try (ZipFile zip = new ZipFile(apk);
                 InputStream in = zip.getInputStream(zip.getEntry("classes.dex"));
                 OutputStream os = new FileOutputStream(cache)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            String r = su("cp -f " + cache.getAbsolutePath() + " " + out.getAbsolutePath()
                    + " && chmod 644 " + out.getAbsolutePath() + " && echo staged");
            if (r.contains("staged")) {
                Log.i(TAG, "staged dex via su " + out.length() + "B (apk " + apkLen + "B)");
                return out.getAbsolutePath();
            }
            Log.w(TAG, "stageDex via su 失败：" + r + "（改用已存在的旧 dex）");
            return out.exists() && out.length() > 1024 ? out.getAbsolutePath() : null;'''
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
s = s.replace(a, b)
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')