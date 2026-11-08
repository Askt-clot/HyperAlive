# -*- coding: utf-8 -*-
"""把「圆圈内容=桌面壁纸 + 只在息屏旋转」落到壁纸服务与首页。"""
import io
import os

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(name, pairs):
    p = os.path.join(BASE, name)
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        n = s.count(a)
        print('%s: %d <- %r' % (name, n, a[:60].replace('\n', '\\n')))
        if n != 1:
            raise SystemExit('匹配数不对：%s %r' % (name, a[:80]))
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('QihangWallpaper.java', [
    # 进入息屏时刷新圆圈内容（跟随桌面壁纸）
    ("""                    mode = next;
                    renderer.setMode(next, animate);""",
     """                    mode = next;
                    if (next == Motion.AOD) {
                        // 息屏时同步一次「当前桌面壁纸」，圆圈内容跟着桌面走
                        renderer.requestPhotoReload();
                    }
                    renderer.setMode(next, animate);"""),
    # 心跳里顺带做壁纸变更检测
    ("""                    Log.i(TAG, "beat mode=" + mode + " visible=" + isVisible()
                            + " surface=" + (getSurfaceHolder().getSurface() != null
                            && getSurfaceHolder().getSurface().isValid()));
                    heartbeat.postDelayed(this, 30000);""",
     """                    Log.i(TAG, "beat mode=" + mode + " visible=" + isVisible()
                            + " surface=" + (getSurfaceHolder().getSurface() != null
                            && getSurfaceHolder().getSurface().isValid()));
                    if (renderer != null) {
                        // 壁纸被换掉也能在下一次心跳跟上（内部按 stat 指纹判断，未变不重载）
                        renderer.requestPhotoReload();
                    }
                    heartbeat.postDelayed(this, 30000);"""),
])

patch('MainActivity.java', [
    ("""        ui.row(wall.body, "重新加载照片", "更换圆内照片（存到模块外部目录）", null, v -> pickPhoto());""",
     """        ui.row(wall.body, "圆圈内容：跟随桌面壁纸", "不单独设置内容；点一下立即同步", null, v -> {
            run0("同步桌面壁纸", () -> {
                sendReloadPhoto();
                return "已请求同步（看 logcat：圆圈纹理 … 来源=）";
            });
        });"""),
    # 广播辅助
    ("""    private void pickPhoto() {""",
     """    private void sendReloadPhoto() {
        Intent i = new Intent(XposedInit.ACTION_MODE);
        i.setPackage(getPackageName());
        i.putExtra("reload_photo", true);
        sendBroadcast(i);
    }

    private void pickPhoto() {"""),
])
print('ok')