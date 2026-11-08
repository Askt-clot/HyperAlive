# -*- coding: utf-8 -*-
"""LensRenderer 支持渲染到任意 Surface（预览用），保留原有 SurfaceHolder 路径。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LensRenderer.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:50].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:80])
    s = s.replace(a, b)


rep("""    private final SurfaceHolder holder;""",
    """    private final SurfaceHolder holder;
    /** 预览用：直接渲染到给定的 Surface（null 时走壁纸的 SurfaceHolder）。 */
    private final Surface previewSurface;""")

rep("""    LensRenderer(Context context, SurfaceHolder holder) {
        this.appContext = context.getApplicationContext();
        this.holder = holder;
        this.thread = new Thread(this, "QihangGL");
    }""",
    """    LensRenderer(Context context, SurfaceHolder holder) {
        this.appContext = context.getApplicationContext();
        this.holder = holder;
        this.previewSurface = null;
        this.thread = new Thread(this, "QihangGL");
    }

    /**
     * 预览用构造：把同一套渲染管线画到任意 Surface（TextureView 的 SurfaceTexture）。
     * 与壁纸引擎共用全部 shader/参数，所以预览与真机效果一致。
     */
    LensRenderer(Context context, Surface surface) {
        this.appContext = context.getApplicationContext();
        this.holder = null;
        this.previewSurface = surface;
        this.thread = new Thread(this, "QihangGLPreview");
    }""")

rep("""                Surface surface = holder.getSurface();""",
    """                Surface surface = previewSurface != null ? previewSurface : holder.getSurface();""")

rep("""        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, holder, surfAttribs, 0);""",
    """        Object window = previewSurface != null ? previewSurface : holder;
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, window, surfAttribs, 0);""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')