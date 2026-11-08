package com.qihang.aod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 首页/编辑器的**动态效果图**：里面有 TextureView（实时渲染）+ ImageView（静态回退），
 * 照 OpenAlive 的 HomeScenePreview 做法 —— 有渲染管线就实时跑，没有就显示静态效果图。
 *
 * <p>实时画面由 {@link LensRenderer} 画到 TextureView 自己的 Surface 上，
 * 用视图尺寸当渲染分辨率（比全屏小很多，开销低），与壁纸引擎共用同一套 shader 与参数。
 */
final class LivePreviewView extends FrameLayout implements TextureView.SurfaceTextureListener {

    private final TextureView texture;
    private final ImageView still;
    private LensRenderer renderer;
    private Surface surface;
    private int mode;
    private boolean active;

    LivePreviewView(Context c, int mode) {
        super(c);
        this.mode = mode;
        setBackgroundColor(Color.BLACK);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, getWidth(), getHeight(),
                        12 * getResources().getDisplayMetrics().density);
            }
        });
        setClickable(false);
        setFocusable(false);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        texture.setClickable(false);
        addView(texture, new LayoutParams(-1, -1));
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setVisibility(GONE);
        addView(still, new LayoutParams(-1, -1));

        this.aodStyle = AodStyleSettings.getStyle(c);
        ensureAodClock();
    }

    private FlymeClockView aodClock;
    private int aodStyle = AodStyleSettings.STYLE_SET_SAIL;
    private boolean aodStyleOverridden = false;

    void setAodStyle(int style) {
        aodStyle = style;
        aodStyleOverridden = true;
        if (renderer != null) {
            renderer.setAodStyle(style);
        }
        if (aodClock != null) {
            aodClock.setAodVisualStyle(style);
        }
    }

    void refreshAodClock() {
        if (aodClock != null && (mode == Motion.AOD || mode == Motion.LOCK)) {
            aodClock.reloadConfig();
        }
    }

    void applyAodClockConfig(ClockSettings.Config c) {
        if (aodClock != null) {
            aodClock.applyConfig(c);
        }
    }

    private void ensureAodClock() {
        if (mode != Motion.AOD && mode != Motion.LOCK) {
            if (aodClock != null) {
                aodClock.setClockMode(mode, true);
            }
            return;
        }
        if (aodClock == null) {
            aodClock = new FlymeClockView(getContext());
            aodClock.setAodVisualStyle(aodStyle);
            aodClock.setClickable(false);
            aodClock.setFocusable(false);
            int[] real = FlymeClockView.realScreenSize(getContext());
            addView(aodClock, new LayoutParams(real[0], real[1]));
        }
        aodClock.setClockMode(mode, false);
        aodClock.setVisibility(VISIBLE);
        post(this::scaleAodClock);
    }

    private void scaleAodClock() {
        if (aodClock == null || getWidth() <= 0 || getHeight() <= 0) return;
        int[] real = FlymeClockView.realScreenSize(getContext());
        int screenW = real[0];
        int screenH = real[1];

        float scaleX = (float) getWidth() / (float) screenW;
        float scaleY = (float) getHeight() / (float) screenH;

        aodClock.setPivotX(0);
        aodClock.setPivotY(0);
        aodClock.setScaleX(scaleX);
        aodClock.setScaleY(scaleY);
        aodClock.setTranslationX(0);
        aodClock.setTranslationY(0);
    }

    private String photoName;
    /** 桌面来源（编辑器传入的当前选择）；-1 = 用模块记录里的值（首页瓦片）。 */
    private int homeKind = -1;
    /** 编辑器当前选中的魅族动态壁纸组件（pkg/cls）；null = 首页瓦片按 LastSet 读取。 */
    private String dynamicComponent;

    /** 编辑器用：告诉预览"桌面现在是什么来源"，切档时据此换照片。 */
    void setHomeKind(int kind) {
        homeKind = kind;
    }

    /** 编辑器用：告诉预览当前选中的动态壁纸组件（从壁纸库点进来时非空）。 */
    void setDynamicComponent(String comp) {
        dynamicComponent = comp;
    }

    /** 当前是否处于魅族自带动态壁纸模式，若是则返回组件名（pkg/cls），否则返回 null。 */
    private String activeDynamicComponent() {
        int kind = homeKind >= 0 ? homeKind : LastSet.kind(getContext());
        if (kind == LastSet.KIND_DYNAMIC) {
            if (dynamicComponent != null && !dynamicComponent.isEmpty()) {
                return dynamicComponent;
            }
            String saved = LastSet.component(getContext());
            if (saved != null && !saved.isEmpty()) {
                return saved;
            }
        }
        return null;
    }

    /** 预览该读哪张照片：动态壁纸模式走自带预览图；自绘模式熄屏/锁屏=锁屏照片，桌面按来源。 */
    private String photoForScene() {
        if (activeDynamicComponent() != null) {
            return null;
        }
        if (mode == Motion.LOCK || mode == Motion.AOD) {
            return PhotoStore.LOCK;
        }
        int kind = homeKind >= 0 ? homeKind : LastSet.kind(getContext());
        if (kind == LastSet.KIND_PHOTO) {
            return PhotoStore.HOME;
        }
        if (kind == LastSet.KIND_FOLLOW) {
            return PhotoStore.LOCK;
        }
        return null;                    // 桌面是魅族 Alive 动态壁纸 → 走静态效果图
    }

    /** 选完照片调用：立刻换成新照片（不用再选第二次）。 */
    void reloadPhoto() {
        photoName = photoForScene();
        if (renderer != null) {
            syncPreviewPhotos();
            renderer.requestPhotoReloadForce();
        }
    }

    /** 当前动态壁纸对应的 OpenAlive 实时渲染器 variant ID（0=无/非动态壁纸）。 */
    private int activeDynamicVariant() {
        String comp = activeDynamicComponent();
        return comp != null ? LibraryActivity.variantFor(getContext(), comp) : 0;
    }

    /** 将锁屏图与（可选）独立桌面图以及动态壁纸 variant 同步给预览渲染器。 */
    private void syncPreviewPhotos() {
        if (renderer == null) {
            return;
        }
        int variant = activeDynamicVariant();
        renderer.setPreviewVariant(variant);
        if (variant > 0) {
            return;
        }
        int kind = homeKind >= 0 ? homeKind : LastSet.kind(getContext());
        String home = (kind == LastSet.KIND_PHOTO
                && PhotoStore.file(getContext(), PhotoStore.HOME).exists())
                ? PhotoStore.HOME : null;
        renderer.setPreviewPhotos(PhotoStore.LOCK, home);
    }

    /** 渲染还没出帧时先显示的效果图（assets 里的兜底）。 */
    void setFallbackAsset(String asset) {
        try (java.io.InputStream in = getContext().getAssets().open(asset)) {
            still.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in));
        } catch (Throwable t) {
            android.util.Log.w(Root.TAG, "效果图缺失 " + asset, t);
        }
    }

    /** 换场景（编辑器切三态用）。 */
    void setScene(int scene) {
        setScene(scene, true);
    }

    /** animate=true 时播放官方过渡（熄屏→锁屏是 950ms 圆扩散 EXPAND，→桌面是 tr2）。 */
    void setScene(int scene, boolean animate) {
        mode = scene;
        ensureAodClock();
        if (aodClock != null) {
            aodClock.setClockMode(scene, animate);
        }
        photoName = photoForScene();
        if (renderer != null) {
            syncPreviewPhotos();
            renderer.setMode(scene, animate);
        }
        // 场景变了可能要从静态图切回实时（桌面动态壁纸 ↔ 自绘）
        if (active) {
            startScene();
        }
    }

    /** 进页面：能实时就实时，否则显示静态效果图。 */
    void startScene() {
        active = true;
        if (!aodStyleOverridden) {
            aodStyle = AodStyleSettings.getStyle(getContext());
        }
        ensureAodClock();
        if (aodClock != null) {
            aodClock.setAodVisualStyle(aodStyle);
        }
        String fresh = photoForScene();
        photoName = fresh;
        if (renderer != null) {
            renderer.setAodStyle(aodStyle);
            syncPreviewPhotos();
        }
        Bitmap fallback = staticFallback();
        if (fallback != null) {
            releaseSurface();
            texture.setVisibility(GONE);
            still.setImageBitmap(fallback);
            still.setVisibility(VISIBLE);
            return;
        }
        texture.setVisibility(VISIBLE);
        still.setVisibility(GONE);      // 占位图不再盖住效果图
        if (texture.isAvailable()) {
            attach(texture.getSurfaceTexture());
        }
        // 只有"渲染真的没起来"才把占位图顶出来（避免一片黑）
        postDelayed(this::showStillIfNoFrame, 1200);
    }

    /** 离开页面：销毁 GL 线程（不留后台渲染）。 */
    void stopScene() {
        active = false;
        releaseSurface();
    }

    /** 这个场景有没有"我们自己的管线"；若魅族动态壁纸已有 OpenAlive 实时管线则直接实时渲染，否则退回静态效果图。 */
    private Bitmap staticFallback() {
        String comp = activeDynamicComponent();
        if (comp != null) {
            android.graphics.drawable.Drawable d =
                    LibraryActivity.thumbnailFor(getContext(), comp);
            Bitmap bmp = LivePreview.toBitmap(d);
            if (bmp != null) {
                still.setImageBitmap(bmp);
            }
            if (activeDynamicVariant() > 0) {
                return null; // 有实时 OpenGL 动态预览管线，不退回静态图
            }
            return bmp;
        }
        return null;
    }

    private void attach(SurfaceTexture st) {
        if (!active || renderer != null || st == null) {
            return;
        }
        surface = new Surface(st);
        renderer = new LensRenderer(getContext(), surface);
        renderer.setAodStyle(aodStyle);
        photoName = photoForScene();
        syncPreviewPhotos();
        // 首次落位一律用静稳态：熄屏靠 speed=1 持续自转，但不重播入场动画
        //（否则每次进页面都"嗖"一下，看起来比编辑器里转得快）。用户切场景才播过渡。
        renderer.setMode(mode, false);
        renderer.setVisible(true);
        renderer.start();
        android.util.Log.i(Root.TAG, "预览已启动 mode=" + mode + " " + texture.getWidth()
                + "x" + texture.getHeight());
    }

    private void showStillIfNoFrame() {
        if (!active || still.getDrawable() == null) {
            return;
        }
        if (renderer == null || !renderer.hasDrawn()) {
            still.setVisibility(VISIBLE);
            android.util.Log.w(Root.TAG, "预览没出帧，退回占位图 mode=" + mode);
        }
    }

    private void releaseSurface() {
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        attach(st);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        releaseSurface();
        attach(st);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releaseSurface();
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        scaleAodClock();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::scaleAodClock);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}