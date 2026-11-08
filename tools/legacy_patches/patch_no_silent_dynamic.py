# -*- coding: utf-8 -*-
"""应用桌面时不再静默回退成动态壁纸：只有本次会话真的从壁纸库选了，才应用动态壁纸；
否则没选照片就明确"保持不动"，避免看起来像"桌面壁纸应用不了"。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:52].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


# 记录"本次会话是否从壁纸库选了动态壁纸"
rep("""    /** 桌面来源：0=壁纸库选的动态壁纸 1=跟随锁屏 2=独立照片。 */
    private int desktopSource = 1;""",
    """    /** 桌面来源：0=壁纸库选的动态壁纸 1=跟随锁屏 2=独立照片。 */
    private int desktopSource = 1;
    /** 本次会话是否**真的**从壁纸库选了动态壁纸（只有这时才允许应用动态壁纸）。 */
    private boolean fromLibrary;""")

rep("""            wallpaper = wp;
            desktopSource = 0;""",
    """            wallpaper = wp;
            fromLibrary = true;
            desktopSource = 0;""")

# 应用：动态壁纸只在"本次会话选了"时才应用
rep("""            if (desktopSource == 0 && wallpaper != null) {
                sb.append(" · 桌面=动态壁纸 ").append(setDesktopFromLibrary());
            } else if (home != null) {""",
    """            if (desktopSource == 0 && fromLibrary && wallpaper != null) {
                sb.append(" · 桌面=动态壁纸 ").append(setDesktopFromLibrary());
            } else if (desktopSource == 0) {
                // 上一次设的是动态壁纸、本次又没选照片 → 不要偷偷换成动态壁纸
                sb.append(" · 桌面=保持不动（先点「壁纸」选一张桌面照片）");
            } else if (home != null) {""")

rep("""            } else {
                sb.append(" · 桌面=未选照片，跳过");
            }""",
    """            } else {
                sb.append(" · 桌面=还没选照片，保持不动");
            }""")

# 相册取消时给个提示
rep("""        if (result != RESULT_OK || data == null || data.getData() == null) {
            append("相册取消");
            return;
        }""",
    """        if (result != RESULT_OK || data == null || data.getData() == null) {
            append("相册取消（没选照片，桌面/锁屏保持不动）");
            toast("没有选择照片");
            return;
        }""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')