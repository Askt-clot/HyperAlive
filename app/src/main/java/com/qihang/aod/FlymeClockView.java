package com.qihang.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 魅族 Flyme 原生风格 AOD / 锁屏时钟组件。
 *
 * 遵循官方 Flyme 10/11 固件标准排版体系（decoded_aod_display_2.xml / decoded_aod_basic_view.xml）：
 *   1. 垂直流层级：
 *      - [顶部留空]：预留上方 Alive 动态壁纸圆圈视口（圆心 33.5%，下边缘 43.8%）。
 *      - 第一层：时间（FlymeNumber-VF 可变字重字体，单行 HH:mm 或双行堆叠）。
 *      - 第二层：日期与星期（居中单行 M月d日 星期X，AOD 息屏默认关闭农历）。
 *      - 第三层：实用信息（水平横向居中：Flyme 胶囊电池图标 + 实时电量百分比）。
 *   2. 视觉一致性：
 *      - 锁屏与息屏保持同一套排版规格，锁屏时圆圈平滑扩散，时钟位置平稳过渡。
 *   3. 零功耗与防烧屏：
 *      - 触摸事件完全穿透（不干扰锁屏下拉和解锁）。
 *      - OLED 每分钟在 [-3dp, +3dp] 内微小平移。
 */
public class FlymeClockView extends FrameLayout {

    private static final String TAG = "FlymeClockView";

    private static Typeface sTfSfProMedium;
    private static Typeface sTfSfProBold;
    private static Typeface sTfSfProLight;
    private static Typeface sTfSixCaps;
    private static Typeface sTfFlymeNumber;
    private static Typeface sTfSerif;
    private static boolean sFontsLoaded = false;

    private int mClockMode = Motion.AOD;
    private int mAodVisualStyle = AodStyleSettings.STYLE_SET_SAIL;
    private ClockSettings.Config mAodConfig;
    private ClockSettings.Config mLockConfig;
    private ClockSettings.Config mConfig = new ClockSettings.Config();

    // 第一层：时间容器（位于圆圈内部居中）
    private LinearLayout mTimeBox;
    private TextView mHourView;
    private TextView mMinuteView;
    private TextView mSingleTimeView;

    // 第二与第三层：日期与电量容器（位于圆圈外部下方）
    private LinearLayout mDateBatteryBox;
    private TextView mDateView;
    private LinearLayout mBatteryBox;
    private FlymeBatteryIconView mBatteryIcon;
    private TextView mBatteryPercent;

    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("M月d日 EEEE", Locale.CHINESE);
    private final SimpleDateFormat mHourFormat = new SimpleDateFormat("HH", Locale.getDefault());
    private final SimpleDateFormat mMinuteFormat = new SimpleDateFormat("mm", Locale.getDefault());
    private final SimpleDateFormat mSingleTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private BroadcastReceiver mTimeReceiver;
    private BroadcastReceiver mBatteryReceiver;
    private boolean mAttached = false;

    public FlymeClockView(Context context) {
        super(context);
        init(context);
    }

    public FlymeClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setFocusable(false);
        setClickable(false);
        mClockMode = Motion.AOD;
        mAodVisualStyle = ClockSettings.getAodVisualStyle(context);
        mAodConfig = ClockSettings.get(context, Motion.AOD);
        mLockConfig = ClockSettings.get(context, Motion.LOCK);
        mConfig = (mAodConfig != null) ? mAodConfig.copy() : new ClockSettings.Config();
        ensureTypeface(context);

        Typeface tf = getTypeface(mConfig.style, mConfig.weight);

        // ════════════════ 第一层：时间（位于圆圈内部居中） ════════════════
        mTimeBox = new LinearLayout(context);
        mTimeBox.setOrientation(LinearLayout.VERTICAL);
        mTimeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        mTimeBox.setFocusable(false);
        mTimeBox.setClickable(false);

        // 1.1 双行堆叠：时
        mHourView = new TextView(context);
        mHourView.setGravity(Gravity.CENTER);
        mHourView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
        mHourView.setTypeface(tf);
        mHourView.setIncludeFontPadding(false);
        mHourView.setShadowLayer(dp(3), 0, dp(1), 0x40000000);

        LinearLayout.LayoutParams hourLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hourLp.gravity = Gravity.CENTER_HORIZONTAL;
        mTimeBox.addView(mHourView, hourLp);

        // 1.2 双行堆叠：分
        mMinuteView = new TextView(context);
        mMinuteView.setGravity(Gravity.CENTER);
        mMinuteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
        mMinuteView.setTypeface(tf);
        mMinuteView.setIncludeFontPadding(false);
        mMinuteView.setShadowLayer(dp(3), 0, dp(1), 0x40000000);

        LinearLayout.LayoutParams minuteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        minuteLp.gravity = Gravity.CENTER_HORIZONTAL;
        minuteLp.topMargin = -dp(10);
        mTimeBox.addView(mMinuteView, minuteLp);

        // 1.3 单行居中时间：HH:mm
        mSingleTimeView = new TextView(context);
        mSingleTimeView.setGravity(Gravity.CENTER);
        mSingleTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f);
        mSingleTimeView.setTypeface(tf);
        mSingleTimeView.setIncludeFontPadding(false);
        mSingleTimeView.setShadowLayer(dp(3), 0, dp(1), 0x40000000);

        LinearLayout.LayoutParams singleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        singleLp.gravity = Gravity.CENTER_HORIZONTAL;
        mTimeBox.addView(mSingleTimeView, singleLp);

        LayoutParams timeBoxLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeBoxLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        addView(mTimeBox, timeBoxLp);

        // ════════════════ 第二层与第三层：日期与电量（位于圆圈外部下方） ════════════════
        mDateBatteryBox = new LinearLayout(context);
        mDateBatteryBox.setOrientation(LinearLayout.VERTICAL);
        mDateBatteryBox.setGravity(Gravity.CENTER_HORIZONTAL);
        mDateBatteryBox.setFocusable(false);
        mDateBatteryBox.setClickable(false);

        // 2.1 日期与星期
        mDateView = new TextView(context);
        mDateView.setGravity(Gravity.CENTER);
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mDateView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mDateView.setIncludeFontPadding(false);
        mDateView.setShadowLayer(dp(2), 0, dp(1), 0x40000000);

        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.gravity = Gravity.CENTER_HORIZONTAL;
        mDateBatteryBox.addView(mDateView, dateLp);

        // 2.2 实用信息（电池图标 + 百分比）
        mBatteryBox = new LinearLayout(context);
        mBatteryBox.setOrientation(LinearLayout.HORIZONTAL);
        mBatteryBox.setGravity(Gravity.CENTER_VERTICAL);
        mBatteryBox.setFocusable(false);
        mBatteryBox.setClickable(false);

        mBatteryIcon = new FlymeBatteryIconView(context);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.gravity = Gravity.CENTER_VERTICAL;
        mBatteryBox.addView(mBatteryIcon, iconLp);

        mBatteryPercent = new TextView(context);
        mBatteryPercent.setGravity(Gravity.CENTER_VERTICAL);
        mBatteryPercent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mBatteryPercent.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mBatteryPercent.setIncludeFontPadding(false);
        mBatteryPercent.setShadowLayer(dp(2), 0, dp(1), 0x40000000);

        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pctLp.gravity = Gravity.CENTER_VERTICAL;
        pctLp.leftMargin = dp(4.5f);
        mBatteryBox.addView(mBatteryPercent, pctLp);

        LinearLayout.LayoutParams batteryBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batteryBoxLp.gravity = Gravity.CENTER_HORIZONTAL;
        batteryBoxLp.topMargin = dp(5f);
        mDateBatteryBox.addView(mBatteryBox, batteryBoxLp);

        LayoutParams dateBatteryBoxLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateBatteryBoxLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        addView(mDateBatteryBox, dateBatteryBoxLp);

        applyConfig(mConfig);
        updateTime();
    }

    public void applyConfig(ClockSettings.Config config) {
        if (config == null) return;
        mConfig = config;
        if (mClockMode == Motion.LOCK) {
            mLockConfig = config;
        } else {
            mAodConfig = config;
        }
        if (!mConfig.enabled) {
            setVisibility(View.GONE);
            return;
        }
        if (mClockMode == Motion.AOD || mClockMode == Motion.LOCK) {
            setVisibility(View.VISIBLE);
        }
        ensureTypeface(getContext());

        Typeface tf = getTypeface(mConfig.style, mConfig.weight);
        mHourView.setTypeface(tf);
        mMinuteView.setTypeface(tf);
        mSingleTimeView.setTypeface(tf);

        // 样式切换与官方微调比例
        LinearLayout.LayoutParams minLp = (LinearLayout.LayoutParams) mMinuteView.getLayoutParams();
        if (mConfig.style == ClockSettings.STYLE_SINGLE_LINE) {
            mHourView.setVisibility(View.GONE);
            mMinuteView.setVisibility(View.GONE);
            mSingleTimeView.setVisibility(View.VISIBLE);
            mSingleTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f);
        } else {
            mHourView.setVisibility(View.VISIBLE);
            mMinuteView.setVisibility(View.VISIBLE);
            mSingleTimeView.setVisibility(View.GONE);

            if (mConfig.style == ClockSettings.STYLE_VERTICAL_STRETCH) {
                // 样式 2：纵向拉伸（SixCaps 超高挑修长，大字号、负间距咬合）
                mHourView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f);
                mMinuteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f);
                minLp.topMargin = -dp(18);
            } else if (mConfig.style == ClockSettings.STYLE_SERIF) {
                // 样式 4：典雅衬线（DMSerifDisplay 艺术衬线数字）
                mHourView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f);
                mMinuteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f);
                minLp.topMargin = -dp(12);
            } else if (mConfig.style == ClockSettings.STYLE_DISTINCTIVE_SELF) {
                // 样式 3：极简现代（FlymeNumber 无衬线大字）
                mHourView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
                mMinuteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
                minLp.topMargin = -dp(10);
            } else {
                // 样式 0：灵动双行（官方 Alive 经典 150/500 比例与 160/500 紧密咬合行距）
                mHourView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
                mMinuteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
                minLp.topMargin = -dp(14);
            }
            mMinuteView.setLayoutParams(minLp);
        }

        // 颜色应用 (主色与副色半透明)
        int primaryColor = mConfig.color;
        int dateColor = (primaryColor & 0x00FFFFFF) | 0xD9000000;
        mHourView.setTextColor(primaryColor);
        mMinuteView.setTextColor(primaryColor);
        mSingleTimeView.setTextColor(primaryColor);
        mDateView.setTextColor(dateColor);
        mBatteryPercent.setTextColor(dateColor);
        mBatteryIcon.setColor(primaryColor);

        // 可变字重（FlymeNumber-VF 的 wght 轴）
        try {
            if (mConfig.style == ClockSettings.STYLE_DISTINCTIVE_SELF) {
                String wghtStr = "'wght' " + mConfig.weight;
                mHourView.setFontVariationSettings(wghtStr);
                mMinuteView.setFontVariationSettings(wghtStr);
                mSingleTimeView.setFontVariationSettings(wghtStr);
            } else {
                mHourView.setFontVariationSettings(null);
                mMinuteView.setFontVariationSettings(null);
                mSingleTimeView.setFontVariationSettings(null);
            }
        } catch (Throwable ignored) {
        }

        // 缩放倍率
        if (mTimeBox != null) {
            mTimeBox.setScaleX(mConfig.scale);
            mTimeBox.setScaleY(mConfig.scale);
        }
        if (mDateBatteryBox != null) {
            mDateBatteryBox.setScaleX(mConfig.scale);
            mDateBatteryBox.setScaleY(mConfig.scale);
        }

        updatePosition(getHeight());
        updateTime();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) {
            updatePosition(h);
        }
    }

    /**
     * 根据魅族官方 Alive AOD 坐标体系计算基准 Y 位置：
     *   - 动态壁纸圆圈圆心与留光相框中心统一位于从上往下 33.5% 屏高处 (cy，AOD_Y = 0.165f 归一化坐标)。
     *   - 时间 (mTimeBox) 居中位于圆圈/相框内部。
     *   - 日期与电量 (mDateBatteryBox) 位于圆圈/相框外部下方。
     */
    private void updatePosition(int containerHeight) {
        if (mTimeBox == null || mDateBatteryBox == null || mConfig == null) return;
        int[] real = realScreenSize(getContext());
        int screenW = real[0];
        int screenH = containerHeight > 0 ? containerHeight : real[1];

        // 1. 锚点位置：所有熄屏样式与锁屏状态统一固定在 33.5% 屏高，杜绝熄屏↔锁屏时钟跳动
        float anchorRatio = 0.335f;
        int cy = Math.round(screenH * anchorRatio);

        // 2. 时间置于圆圈中心
        if (mTimeBox.getLayoutParams() instanceof LayoutParams) {
            LayoutParams lpTime = (LayoutParams) mTimeBox.getLayoutParams();
            int timeTop;
            if (mConfig.style == ClockSettings.STYLE_SINGLE_LINE) {
                timeTop = cy - dp(22) + dp(mConfig.offsetY);
            } else if (mConfig.style == ClockSettings.STYLE_VERTICAL_STRETCH) {
                timeTop = cy - dp(60) + dp(mConfig.offsetY);
            } else if (mConfig.style == ClockSettings.STYLE_SERIF) {
                timeTop = cy - dp(50) + dp(mConfig.offsetY);
            } else {
                timeTop = cy - dp(52) + dp(mConfig.offsetY);
            }
            if (lpTime.topMargin != timeTop) {
                lpTime.topMargin = timeTop;
                mTimeBox.setLayoutParams(lpTime);
            }
        }

        // 3. 日期与电量置于圆圈外部下方
        // 圆圈半径为 screenW * 0.215f，圆圈下边缘为 cy + screenW * 0.215f
        if (mDateBatteryBox.getLayoutParams() instanceof LayoutParams) {
            LayoutParams lpDateBattery = (LayoutParams) mDateBatteryBox.getLayoutParams();
            int circleBottom = cy + Math.round(screenW * 0.22f);
            int infoTop = circleBottom + dp(16) + dp(mConfig.offsetY);
            if (lpDateBattery.topMargin != infoTop) {
                lpDateBattery.topMargin = infoTop;
                mDateBatteryBox.setLayoutParams(lpDateBattery);
            }
        }
    }

    static int[] realScreenSize(Context context) {
        if (context != null) {
            try {
                android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(dm);
                    int w = Math.min(dm.widthPixels, dm.heightPixels);
                    int h = Math.max(dm.widthPixels, dm.heightPixels);
                    if (w > 0 && h > 0) {
                        return new int[]{w, h};
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                int w = Math.min(dm.widthPixels, dm.heightPixels);
                int h = Math.max(dm.widthPixels, dm.heightPixels);
                if (w > 0 && h > 0) {
                    return new int[]{w, h};
                }
            } catch (Throwable ignored) {
            }
        }
        return new int[]{1200, 2608};
    }

    public void setClockMode(int targetMode, boolean animate) {
        if (targetMode != Motion.AOD && targetMode != Motion.LOCK) {
            // 非时钟场景（如桌面 mode=2）：淡出并隐藏
            animate().cancel();
            if (animate && getVisibility() == View.VISIBLE && getAlpha() > 0.05f) {
                animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    setVisibility(View.GONE);
                    setAlpha(1f);
                }).start();
            } else {
                setVisibility(View.GONE);
                setAlpha(1f);
            }
            mClockMode = targetMode;
            return;
        }

        if (mAodConfig == null) mAodConfig = ClockSettings.get(getContext(), Motion.AOD);
        if (mLockConfig == null) mLockConfig = ClockSettings.get(getContext(), Motion.LOCK);
        ClockSettings.Config targetConfig = (targetMode == Motion.LOCK) ? mLockConfig : mAodConfig;
        if (targetConfig != null && !targetConfig.enabled) {
            animate().cancel();
            setVisibility(View.GONE);
            mClockMode = targetMode;
            return;
        }

        setVisibility(View.VISIBLE);
        if (targetMode == mClockMode) {
            return;
        }

        mClockMode = targetMode;

        boolean same = (mAodConfig != null && mAodConfig.isSameStyle(mLockConfig));
        if (same) {
            // 时钟样式完全相同：无缝过渡，不闪烁
            animate().cancel();
            setAlpha(1f);
            applyConfig(targetConfig);
            return;
        }

        // 样式不同：熄屏切换到锁屏（或锁屏切回熄屏）时，当前时钟淡出消失，然后显示新样式时钟
        animate().cancel();
        if (animate && getVisibility() == View.VISIBLE && getAlpha() > 0.05f) {
            animate().alpha(0f).setDuration(120).withEndAction(() -> {
                applyConfig(targetConfig);
                animate().alpha(1f).setDuration(220).start();
            }).start();
        } else {
            applyConfig(targetConfig);
            setAlpha(1f);
        }
    }

    public void setAodVisualStyle(int visualStyle) {
        if (mAodVisualStyle != visualStyle) {
            mAodVisualStyle = visualStyle;
            updatePosition(getHeight());
        }
    }

    public int getAodVisualStyle() {
        return mAodVisualStyle;
    }

    public void reloadConfig() {
        mAodVisualStyle = ClockSettings.getAodVisualStyle(getContext());
        mAodConfig = ClockSettings.get(getContext(), Motion.AOD);
        mLockConfig = ClockSettings.get(getContext(), Motion.LOCK);
        ClockSettings.Config current = (mClockMode == Motion.LOCK) ? mLockConfig : mAodConfig;
        applyConfig(current);
    }

    public void updateTime() {
        Date now = new Date();
        String dateStr = mDateFormat.format(now);
        if (mConfig.showLunar) {
            String lunar = LunarCalendar.getLunarDateString(now);
            if (lunar != null && !lunar.isEmpty()) {
                dateStr += " " + lunar;
            }
        }
        mDateView.setText(dateStr);

        if (mConfig.style == ClockSettings.STYLE_SINGLE_LINE) {
            mSingleTimeView.setText(mSingleTimeFormat.format(now));
        } else {
            mHourView.setText(mHourFormat.format(now));
            mMinuteView.setText(mMinuteFormat.format(now));
        }
        applyAntiBurnIn(now);
    }

    /** 更新电池状态 */
    private void updateBattery(Intent batteryIntent) {
        if (batteryIntent == null) return;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int pct = 100;
        if (level >= 0 && scale > 0) {
            pct = Math.round((level * 100f) / scale);
        }
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);

        mBatteryPercent.setText(pct + "%");
        mBatteryIcon.setLevel(pct, isCharging);
    }

    /** OLED 防烧屏微偏移：每分钟在 [-3dp, +3dp] 内微小平移 */
    private void applyAntiBurnIn(Date now) {
        long min = now.getTime() / 60000L;
        int dx = (int) ((min * 3) % 7) - 3;
        int dy = (int) ((min * 5) % 7) - 3;
        if (mTimeBox != null) {
            mTimeBox.setTranslationX(dp(dx));
            mTimeBox.setTranslationY(dp(dy));
        }
        if (mDateBatteryBox != null) {
            mDateBatteryBox.setTranslationX(dp(dx));
            mDateBatteryBox.setTranslationY(dp(dy));
        }
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        // 触摸事件完全穿透，不拦截锁屏滑动解锁或通知下拉手势
        return false;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached) return;
        mAttached = true;
        updateTime();

        // 1. 时间与配置变更广播
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        timeFilter.addAction(ClockSettings.ACTION_STYLE_CHANGED);
        timeFilter.addAction(AodStyleSettings.ACTION_STYLE_CHANGED);

        mTimeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String act = intent.getAction();
                if (AodStyleSettings.ACTION_STYLE_CHANGED.equals(act)) {
                    int style = intent.getIntExtra(AodStyleSettings.KEY_STYLE, -1);
                    if (style >= 0) {
                        try {
                            getContext().getSharedPreferences(ClockSettings.PREF_NAME, Context.MODE_PRIVATE)
                                    .edit().putInt(AodStyleSettings.KEY_STYLE, style).apply();
                        } catch (Throwable ignored) {}
                        setAodVisualStyle(style);
                    } else {
                        reloadConfig();
                    }
                } else if (ClockSettings.ACTION_STYLE_CHANGED.equals(act)) {
                    if (intent.hasExtra(ClockSettings.KEY_AOD_STYLE) || intent.hasExtra(ClockSettings.KEY_STYLE)) {
                        mAodConfig = ClockSettings.fromIntentAod(intent);
                        mLockConfig = ClockSettings.fromIntentLock(intent);
                        ClockSettings.saveLocal(getContext(), mAodConfig, mLockConfig);
                        applyConfig(mClockMode == Motion.LOCK ? mLockConfig : mAodConfig);
                    } else {
                        reloadConfig();
                    }
                } else {
                    updateTime();
                }
            }
        };

        // 2. 电池广播（黏性广播即时获取当前电量）
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateBattery(intent);
            }
        };

        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getContext().registerReceiver(mTimeReceiver, timeFilter, Context.RECEIVER_EXPORTED);
                Intent sticky = getContext().registerReceiver(mBatteryReceiver,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
                updateBattery(sticky);
            } else {
                getContext().registerReceiver(mTimeReceiver, timeFilter);
                Intent sticky = getContext().registerReceiver(mBatteryReceiver,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                updateBattery(sticky);
            }
        } catch (Throwable t) {
            Log.w(TAG, "注册广播失败", t);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            if (mTimeReceiver != null) {
                try {
                    getContext().unregisterReceiver(mTimeReceiver);
                } catch (Throwable ignored) {
                }
                mTimeReceiver = null;
            }
            if (mBatteryReceiver != null) {
                try {
                    getContext().unregisterReceiver(mBatteryReceiver);
                } catch (Throwable ignored) {
                }
                mBatteryReceiver = null;
            }
        }
        mAttached = false;
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    public static synchronized void ensureTypeface(Context context) {
        if (sFontsLoaded) {
            return;
        }
        Context modCtx = null;
        try {
            if ("com.qihang.aod".equals(context.getPackageName())) {
                modCtx = context;
            } else {
                modCtx = context.createPackageContext("com.qihang.aod", Context.CONTEXT_IGNORE_SECURITY);
            }
        } catch (Throwable ignored) {
        }

        sTfSfProMedium = tryLoadFont(context, modCtx, "fonts/SFPRO-SemiCondensedMedium.otf", "SFPRO-SemiCondensedMedium.otf");
        sTfSfProBold = tryLoadFont(context, modCtx, "fonts/SFPRO-SemiCondensedBold.otf", "SFPRO-SemiCondensedBold.otf");
        sTfSfProLight = tryLoadFont(context, modCtx, "fonts/SFPRO-SemiCondensedLight.otf", "SFPRO-SemiCondensedLight.otf");
        sTfSixCaps = tryLoadFont(context, modCtx, "fonts/SixCaps-Regular.ttf", "SixCaps-Regular.ttf");
        sTfFlymeNumber = tryLoadFont(context, modCtx, "fonts/FlymeNumber-VF.ttf", "FlymeNumber-VF.ttf");
        sTfSerif = tryLoadFont(context, modCtx, "fonts/DMSerifDisplay-Regular.ttf", "DMSerifDisplay-Regular.ttf");
        if (sTfSerif == null) {
            sTfSerif = tryLoadFont(context, modCtx, "fonts/DMSerifDisplay.otf", "DMSerifDisplay.otf");
        }

        if (modCtx != null) {
            saveAllFontsToSharedTmp(modCtx);
        }
        sFontsLoaded = true;
    }

    private static Typeface tryLoadFont(Context ctx, Context modCtx, String assetPath, String fallbackTmpName) {
        if (ctx != null) {
            try {
                Typeface tf = Typeface.createFromAsset(ctx.getAssets(), assetPath);
                if (tf != null) return tf;
            } catch (Throwable ignored) {}
        }
        if (modCtx != null) {
            try {
                Typeface tf = Typeface.createFromAsset(modCtx.getAssets(), assetPath);
                if (tf != null) return tf;
            } catch (Throwable ignored) {}
        }
        try {
            File f = new File("/data/local/tmp/" + fallbackTmpName);
            if (f.exists() && f.length() > 0) {
                Typeface tf = Typeface.createFromFile(f);
                if (tf != null) return tf;
            }
        } catch (Throwable ignored) {}
        try {
            File f = new File("/product/fonts/" + fallbackTmpName);
            if (f.exists() && f.length() > 0) {
                Typeface tf = Typeface.createFromFile(f);
                if (tf != null) return tf;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void saveAllFontsToSharedTmp(Context modCtx) {
        String[] fonts = {
                "SFPRO-SemiCondensedMedium.otf",
                "SFPRO-SemiCondensedBold.otf",
                "SFPRO-SemiCondensedLight.otf",
                "SixCaps-Regular.ttf",
                "FlymeNumber-VF.ttf",
                "DMSerifDisplay-Regular.ttf"
        };
        for (String fName : fonts) {
            try {
                File f = new File("/data/local/tmp/" + fName);
                if (!f.exists() || f.length() == 0) {
                    InputStream is = modCtx.getAssets().open("fonts/" + fName);
                    FileOutputStream fos = new FileOutputStream(f);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.close();
                    is.close();
                    f.setReadable(true, false);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static Typeface getTypeface(int style, int weight) {
        switch (style) {
            case ClockSettings.STYLE_VERTICAL_STRETCH:
                return sTfSixCaps != null ? sTfSixCaps : Typeface.DEFAULT_BOLD;
            case ClockSettings.STYLE_DISTINCTIVE_SELF:
                return sTfFlymeNumber != null ? sTfFlymeNumber : Typeface.DEFAULT_BOLD;
            case ClockSettings.STYLE_SERIF:
                return sTfSerif != null ? sTfSerif : Typeface.SERIF;
            case ClockSettings.STYLE_SINGLE_LINE:
            case ClockSettings.STYLE_DOUBLE_STACK:
            default:
                if (weight > 600 && sTfSfProBold != null) {
                    return sTfSfProBold;
                } else if (weight < 350 && sTfSfProLight != null) {
                    return sTfSfProLight;
                }
                return sTfSfProMedium != null ? sTfSfProMedium
                        : (sTfFlymeNumber != null ? sTfFlymeNumber : Typeface.DEFAULT_BOLD);
        }
    }

    /**
     * 魅族风格水平微胶囊电池视图（完全向量绘制，自适应 DPI 与换肤色）
     */
    private static class FlymeBatteryIconView extends View {
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mBodyRect = new RectF();
        private final RectF mCapRect = new RectF();
        private final RectF mLevelRect = new RectF();

        private int mLevel = 100;
        private boolean mIsCharging = false;
        private int mColor = 0xFFFFFFFF;

        public FlymeBatteryIconView(Context context) {
            super(context);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mFillPaint.setStyle(Paint.Style.FILL);
            setFocusable(false);
            setClickable(false);
        }

        public void setLevel(int level, boolean charging) {
            mLevel = Math.max(0, Math.min(100, level));
            mIsCharging = charging;
            invalidate();
        }

        public void setColor(int color) {
            mColor = color;
            invalidate();
        }

        private float dp(float dp) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                    getResources().getDisplayMetrics());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension((int) dp(19f), (int) dp(10.5f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float stroke = dp(1.1f);
            mStrokePaint.setStrokeWidth(stroke);
            int dateColor = (mColor & 0x00FFFFFF) | 0xD9000000;
            mStrokePaint.setColor(dateColor);

            // 1. 电池主体外框 (留出右侧正极突起)
            float capWidth = dp(2.2f);
            float bodyRight = w - capWidth - dp(0.6f);
            float halfStroke = stroke * 0.5f;
            mBodyRect.set(halfStroke, halfStroke, bodyRight - halfStroke, h - halfStroke);
            float corner = dp(2.2f);
            canvas.drawRoundRect(mBodyRect, corner, corner, mStrokePaint);

            // 2. 电池右侧正极触点
            float capH = h * 0.44f;
            float capTop = (h - capH) * 0.5f;
            mCapRect.set(bodyRight, capTop, w - halfStroke, capTop + capH);
            mStrokePaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(mCapRect, dp(1f), dp(1f), mStrokePaint);
            mStrokePaint.setStyle(Paint.Style.STROKE);

            // 3. 内部电量填充
            int fillColor;
            if (mIsCharging) {
                fillColor = 0xFF34D399; // 充电状态采用魅族薄荷冷翠
            } else if (mLevel <= 20) {
                fillColor = 0xFFF87171; // 低电量警告红
            } else {
                fillColor = dateColor;
            }
            mFillPaint.setColor(fillColor);

            float innerPad = dp(1.7f);
            float maxInnerW = (bodyRight - halfStroke) - (halfStroke + innerPad * 2);
            float fillW = maxInnerW * (mLevel / 100f);
            if (fillW > 0) {
                mLevelRect.set(halfStroke + innerPad, halfStroke + innerPad,
                        halfStroke + innerPad + fillW, (h - halfStroke) - innerPad);
                canvas.drawRoundRect(mLevelRect, dp(1.0f), dp(1.0f), mFillPaint);
            }
        }
    }
}
