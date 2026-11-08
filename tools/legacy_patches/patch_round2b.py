# -*- coding: utf-8 -*-
"""① App 启动即探一次 root（触发 KernelSU/Magisk 授权弹窗）；② 系统壁纸读失败时打出诊断。"""
import io
import os

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(name, pairs):
    p = os.path.join(BASE, name)
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = s.count(a)
        print('%s: %d <- %r' % (name, n, a[:70].replace('\n', '\\n')))
        if n != 1:
            raise SystemExit('匹配数不对：%s %r' % (name, a[:80]))
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('WallpaperSource.java', [
    ("""            p.waitFor();
            byte[] data = bos.toByteArray();
            if (data.length < 1024) {
                return null;
            }""",
     """            p.waitFor();
            byte[] data = bos.toByteArray();
            if (data.length < 1024) {
                Log.w(TAG, "系统壁纸文件读取过短：exit=" + p.exitValue() + " bytes=" + data.length
                        + "（多半是模块尚未拿到 root 授权）");
                return null;
            }"""),
])

patch('Root.java', [
    ("""    public static boolean hasRoot() {
        String out = su("id");
        return out.contains("uid=0");
    }""",
     """    public static boolean hasRoot() {
        return su("id").contains("uid=0");
    }

    /** 主动申请一次 root：KernelSU / Magisk 会弹授权框，用户点「允许」后本模块才有特权。 */
    public static String requestRoot() {
        String id = su("id", 30000);
        if (id.contains("uid=0")) {
            return "已授权：" + id.replace('\\n', ' ');
        }
        return "未授权（请在弹出的 root 管理器里点「允许」）；返回=" + id.replace('\\n', ' ');
    }"""),
])

patch('MainActivity.java', [
    ("""    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }""",
     """    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /** 启动时探一次 root：没有就触发授权弹窗（模块的特权操作都依赖它）。 */
    private void probeRootOnStart() {
        new Thread(() -> {
            String r = Root.requestRoot();
            runOnUiThread(() -> append("root 探测 => " + r));
        }, "MainActivity-probe").start();
    }"""),
    ("""        status = new TextView(this);""",
     """        probeRootOnStart();

        status = new TextView(this);"""),
])

patch('MainActivity.java', [
    ("""        ui.row(root.body, "申请 root", "su -c id", null, v -> {
            run0("su -c id", () -> {
                String out = Root.su("id");
                return out + "\\nroot=" + out.contains("uid=0");
            });
        });""",
     """        ui.row(root.body, "申请 / 检查 root", "su -c id（首次会弹授权框，请点允许）", null, v -> {
            run0("su -c id", Root::requestRoot);
        });"""),
])
print('ok')