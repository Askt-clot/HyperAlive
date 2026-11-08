package com.qihang.aod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * 全屏沉浸式照片裁切页（对齐 {@link EditorActivity} 全屏预览布局，裁切核心移植自 OpenAlive）。
 * 底层裁切画布铺满全屏（所见即所得），顶栏与底部控件悬浮于画面之上。
 */
public final class PhotoCropActivity extends Activity {

    static final String EXTRA_SOURCE = "photo";
    static final String EXTRA_TARGET = "target";
    static final String EXTRA_LIUGUANG = "is_liuguang";

    static Intent intent(Context ctx, String stagedFileName, String targetName) {
        return intent(ctx, stagedFileName, targetName, false);
    }

    static Intent intent(Context ctx, String stagedFileName, String targetName, boolean isLiuguang) {
        Intent i = new Intent(ctx, PhotoCropActivity.class);
        i.putExtra(EXTRA_SOURCE, stagedFileName);
        i.putExtra(EXTRA_TARGET, targetName);
        i.putExtra(EXTRA_LIUGUANG, isLiuguang);
        return i;
    }

    private final PhotoViewport viewport = new PhotoViewport();
    private String source;
    private String target;
    private boolean isLiuguang;
    private Bitmap bitmap;
    private CropView cropView;
    private TextView done;
    private int screenWidth;
    private int screenHeight;
    private boolean saving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        source = getIntent().getStringExtra(EXTRA_SOURCE);
        target = getIntent().getStringExtra(EXTRA_TARGET);
        isLiuguang = getIntent().getBooleanExtra(EXTRA_LIUGUANG, false);
        if (target == null || (!PhotoStore.LOCK.equals(target) && !PhotoStore.HOME.equals(target) && !PhotoStore.FRAME.equals(target))) {
            target = PhotoStore.LOCK;
        }
        if (source == null || !source.matches("photo-[A-Za-z0-9._-]+")
                || !new File(getFilesDir(), source).isFile()) {
            finish();
            return;
        }

        Point screen = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(screen);
        screenWidth = screen.x > 0 ? screen.x : getResources().getDisplayMetrics().widthPixels;
        screenHeight = screen.y > 0 ? screen.y : getResources().getDisplayMetrics().heightPixels;

        viewport.setFreeMove(isLiuguang);
        android.content.SharedPreferences cp = getSharedPreferences("photo_crop_prefs", MODE_PRIVATE);

        Window w = getWindow();
        if (w != null) {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // ---- 底层：全屏裁切画布（铺满整个屏幕，所见即所得）----
        cropView = new CropView();
        root.addView(cropView, new FrameLayout.LayoutParams(-1, -1));

        // ---- 顶栏：取消 / 标题 / 完成（悬浮在上方，与 EditorActivity 一致）----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), statusBarHeight() + dp(14), dp(20), dp(10));

        TextView cancel = pillButton("取消", 0xCC3A3A3C, 0xFFFFFFFF);
        cancel.setOnClickListener(v -> finish());
        top.addView(cancel, new LinearLayout.LayoutParams(dp(74), dp(36)));

        TextView title = new TextView(this);
        title.setText("裁切照片");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(4), 0, dp(1), 0x80000000);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        done = pillButton("完成", 0xFF2F6BFF, 0xFFFFFFFF);
        done.setEnabled(false);
        done.setOnClickListener(v -> save());
        top.addView(done, new LinearLayout.LayoutParams(dp(74), dp(36)));

        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- 底部悬浮区：提示文字 + 复位胶囊按钮 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(20), dp(8), dp(20), dp(34));

        TextView hint = new TextView(this);
        hint.setText(isLiuguang ? "双指缩放拖动 · 蓝框内为留光相框取景区域" : "双指缩放，拖动调整位置");
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-2, -2);
        hintLp.bottomMargin = dp(14);
        bottom.addView(hint, hintLp);

        TextView reset = pillButton("复位", 0xCC3A3A3C, 0xFFFFFFFF);
        reset.setOnClickListener(v -> {
            if (!saving) {
                viewport.resetDefault(isLiuguang);
                cropView.invalidate();
            }
        });
        bottom.addView(reset, new LinearLayout.LayoutParams(dp(86), dp(38)));

        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);

        new Thread(() -> {
            try {
                File srcFile = new File(getFilesDir(), source);
                Bitmap decoded = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(srcFile),
                        (decoder, info, input) -> {
                            int w1 = info.getSize().getWidth();
                            int h1 = info.getSize().getHeight();
                            float scale = Math.min(1f, 2048f / Math.max(w1, h1));
                            decoder.setTargetSize(
                                     Math.max(1, Math.round(w1 * scale)),
                                    Math.max(1, Math.round(h1 * scale)));
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) {
                        decoded.recycle();
                        return;
                    }
                    bitmap = decoded;
                    viewport.setFreeMove(isLiuguang);
                    viewport.dimensions(bitmap.getWidth(), bitmap.getHeight(), screenWidth, screenHeight);
                    if (state != null) {
                        viewport.restore(
                                state.getFloat("x", 0.5f),
                                state.getFloat("y", 0.5f),
                                state.getFloat("zoom", 1f));
                    } else if (cp.contains("x_" + target)) {
                        viewport.restore(
                                cp.getFloat("x_" + target, 0.5f),
                                cp.getFloat("y_" + target, 0.5f),
                                cp.getFloat("zoom_" + target, 1f));
                    } else {
                        viewport.resetDefault(isLiuguang);
                    }
                    done.setEnabled(true);
                    cropView.invalidate();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        Toast.makeText(this, "无法读取照片：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            }
        }, "PhotoCropDecode").start();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) {
                return getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
        }
        return dp(28);
    }

    private TextView pillButton(String label, int bgColor, int fgColor) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(fgColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(18));
        b.setBackground(d);
        return b;
    }

    final class CropView extends View {
        final RectF frame = new RectF();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final ScaleGestureDetector scale;
        float lastX;
        float lastY;
        boolean touching;

        CropView() {
            super(PhotoCropActivity.this);
            setContentDescription("照片全屏裁切区域，可双指缩放和拖动");
            scale = new ScaleGestureDetector(
                    PhotoCropActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            if (frame.width() <= 0 || frame.height() <= 0) {
                                return false;
                            }
                            viewport.scale(
                                    detector.getScaleFactor(),
                                    (detector.getFocusX() - frame.left) / frame.width(),
                                    (detector.getFocusY() - frame.top) / frame.height());
                            invalidate();
                            return true;
                        }
                    });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            frame.set(0f, 0f, Math.max(1f, w), Math.max(1f, h));
            if (w > 0 && h > 0) {
                screenWidth = w;
                screenHeight = h;
                if (bitmap != null) {
                    float cx = viewport.centerX();
                    float cy = viewport.centerY();
                    float z = viewport.zoom();
                    viewport.setFreeMove(isLiuguang);
                    viewport.dimensions(bitmap.getWidth(), bitmap.getHeight(), screenWidth, screenHeight);
                    viewport.restore(cx, cy, z);
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || frame.isEmpty()) {
                return;
            }
            canvas.save();
            canvas.clipRect(frame);

            // 自由移动图片时，图片外的区域严格保持纯黑色（1:1 满足留光取景规范）
            canvas.drawColor(0xFF000000);

            float s = frame.width() / (bitmap.getWidth() * viewport.width());
            Matrix matrix = new Matrix();
            matrix.setScale(s, s);
            matrix.postTranslate(
                    frame.left - viewport.left() * bitmap.getWidth() * s,
                    frame.top - viewport.top() * bitmap.getHeight() * s);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();

            if (isLiuguang) {
                float cx = frame.left + frame.width() * 0.5f;
                float cy = frame.top + frame.height() * (348.5f / 720f);
                float boxSize = Math.min(frame.width(), frame.height()) * (320f / 720f);
                float hBox = boxSize / 2f;
                RectF box = new RectF(cx - hBox, cy - hBox, cx + hBox, cy + hBox);

                // 半透明遮罩取景框外区域，强化“所见即所得”视觉认知
                Paint maskPaint = new Paint();
                maskPaint.setColor(0x70000000);
                canvas.drawRect(frame.left, frame.top, frame.right, box.top, maskPaint);
                canvas.drawRect(frame.left, box.bottom, frame.right, frame.bottom, maskPaint);
                canvas.drawRect(frame.left, box.top, box.left, box.bottom, maskPaint);
                canvas.drawRect(box.right, box.top, frame.right, box.bottom, maskPaint);

                if (touching) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1));
                    paint.setColor(0x66FFFFFF);
                    for (int i = 1; i < 3; i++) {
                        float x = box.left + box.width() * i / 3f;
                        float y = box.top + box.height() * i / 3f;
                        canvas.drawLine(x, box.top, x, box.bottom, paint);
                        canvas.drawLine(box.left, y, box.right, y, paint);
                    }
                }

                // 拍立得相框取景参考框（虚线边框 + 半透明提示）
                Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                guidePaint.setStyle(Paint.Style.STROKE);
                guidePaint.setStrokeWidth(dp(1.8f));
                guidePaint.setColor(0xD960A5FA);
                guidePaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
                canvas.drawRoundRect(box, dp(8), dp(8), guidePaint);

                Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                textPaint.setColor(0xEEFFFFFF);
                textPaint.setTextSize(dp(12));
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
                canvas.drawText("留光相框取景范围", cx, cy - hBox - dp(8), textPaint);
            } else if (touching) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0x88ffffff);
                for (int i = 1; i < 3; i++) {
                    float x = frame.left + frame.width() * i / 3f;
                    float y = frame.top + frame.height() * i / 3f;
                    canvas.drawLine(x, frame.top, x, frame.bottom, paint);
                    canvas.drawLine(frame.left, y, frame.right, y, paint);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null || saving || frame.isEmpty()) {
                return false;
            }
            int action = event.getActionMasked();
            scale.onTouchEvent(event);
            float x = 0f;
            float y = 0f;
            int count = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex()) {
                    continue;
                }
                x += event.getX(i);
                y += event.getY(i);
                count++;
            }
            if (count > 0) {
                x /= count;
                y /= count;
            }
            if (action == MotionEvent.ACTION_MOVE && touching) {
                viewport.drag((x - lastX) / frame.width(), (y - lastY) / frame.height());
            }
            lastX = x;
            lastY = y;
            touching = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
            invalidate();
            return true;
        }
    }

    static Bitmap export(File input, PhotoViewport selection, int outWidth, int outHeight, boolean isLiuguang) throws IOException {
        Bitmap decoded = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(input),
                (decoder, info, src) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    float maxDim = Math.max(outWidth, outHeight);
                    float scale = Math.min(1f, (maxDim * 1.5f) / Math.max(w, h));
                    int tw = Math.max(1, Math.round(w * scale));
                    int th = Math.max(1, Math.round(h * scale));
                    decoder.setTargetSize(tw, th);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });

        Bitmap result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        // 图片外的区域严格保持纯黑色
        canvas.drawColor(0xFF000000);

        float s = (float) outWidth / (decoded.getWidth() * selection.width());
        Matrix matrix = new Matrix();
        matrix.setScale(s, s);
        matrix.postTranslate(
                -selection.left() * decoded.getWidth() * s,
                -selection.top() * decoded.getHeight() * s);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(decoded, matrix, paint);

        decoded.recycle();
        return result;
    }

    private void save() {
        if (bitmap == null || saving) {
            return;
        }
        saving = true;
        done.setEnabled(false);
        done.setText("保存中");

        final float saveX = viewport.centerX();
        final float saveY = viewport.centerY();
        final float saveZoom = viewport.zoom();

        PhotoViewport selected = new PhotoViewport();
        selected.setFreeMove(isLiuguang);
        selected.dimensions(bitmap.getWidth(), bitmap.getHeight(), screenWidth, screenHeight);
        selected.restore(saveX, saveY, saveZoom);

        new Thread(() -> {
            try {
                getSharedPreferences("photo_crop_prefs", MODE_PRIVATE)
                        .edit()
                        .putFloat("x_" + target, saveX)
                        .putFloat("y_" + target, saveY)
                        .putFloat("zoom_" + target, saveZoom)
                        .apply();

                File inputFile = new File(getFilesDir(), source);
                Bitmap image = export(inputFile, selected, screenWidth, screenHeight, isLiuguang);
                try {
                    PhotoStore.save(PhotoCropActivity.this, image, target);
                    try {
                        PhotoStore.copyFile(inputFile, PhotoStore.rawFile(PhotoCropActivity.this, target));
                    } catch (Throwable t) {
                        Log.w(Root.TAG, "同步原始照片失败: " + target, t);
                    }
                } finally {
                    image.recycle();
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) {
                        return;
                    }
                    Intent res = new Intent();
                    res.putExtra(EXTRA_TARGET, target);
                    setResult(RESULT_OK, res);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        saving = false;
                        done.setEnabled(true);
                        done.setText("完成");
                        Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "PhotoCropExport").start();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putFloat("x", viewport.centerX());
        state.putFloat("y", viewport.centerY());
        state.putFloat("zoom", viewport.zoom());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
        if (isFinishing() && source != null && source.matches("photo-[A-Za-z0-9._-]+")) {
            new File(getFilesDir(), source).delete();
        }
        super.onDestroy();
    }
}
