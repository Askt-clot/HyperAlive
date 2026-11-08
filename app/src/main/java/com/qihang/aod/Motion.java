package com.qihang.aod;

/**
 * 启航三态完整参数模型（对齐 OpenAlive 官方 {@code SceneMotion}）。
 *
 * <p>10 个 lens 通道（与官方 packet[3..12] 一一对应）：
 * <pre>
 *   0 blur        1 scale     2 distortion  3 decoratorAlpha  4 decoratorAngle
 *   5 x           6 y         7 speed       8 ripple          9 photoAngle
 * </pre>
 *
 * <p>四个过渡方向（tr0 熄屏→锁屏 / tr2 熄屏→桌面 / tr1 锁屏→熄屏 / tr3 桌面→熄屏）
 * 的每通道时长与曲线全部照抄官方，含：
 * <ul>
 *   <li>退出熄屏时 {@code previous==0 && 未中断} → 整体 +150ms 起始延迟；</li>
 *   <li>{@code speed} 通道退出熄屏时为 **0ms**（旋转立刻停）、进入熄屏时 650ms 渐加速；</li>
 *   <li>{@code photoAngle} 进入熄屏时**不建轨**（保持当前角），退出熄屏时 24ms 走
 *       {@code nearestRotation} 到 −360（⇒ 锁屏/桌面不会被圆圈的自转带偏，因为落在 360 的整数倍上）；</li>
 *   <li>{@code ripple: ms=0 + 延迟 650}、{@code decoratorAngle} 各方向不同曲线。</li>
 * </ul>
 *
 * <p>参考：docs/set-sail-transitions-20261022.md、set-sail-three-states-20261021.md、set-sail-aod-20261020.md
 */
final class Motion {

    static final int AOD = 0;
    static final int LOCK = 1;
    static final int HOME = 2;

    static final int N = 10;
    static final int BLUR = 0;
    static final int SCALE = 1;
    static final int DISTORTION = 2;
    static final int DECOR_ALPHA = 3;
    static final int DECOR_ANGLE = 4;
    static final int X = 5;
    static final int Y = 6;
    static final int SPEED = 7;
    static final int RIPPLE = 8;
    static final int PHOTO_ANGLE = 9;

    static final float AOD_SCALE = 0.22f;
    static final float AOD_DISTORTION = 9f;
    /** 魅族官方熄屏圆圈中心在屏幕从上往下 33.5% 高度处（屏幕中间偏上）。因着色器 UV 原点在屏幕底部，u_translation.y = 0.5 - 0.335 = 0.165f */
    static final float AOD_Y = 0.165f;
    /** 桌面稳态不模糊（与锁屏一致），避免解锁进桌面时画面变糊。 */
    static final float HOME_BLUR = 0f;
    static final float AOD_BLUR = 22f;
    static final float DEG_PER_SEC = 40f;
    /** AodMotionWindow：息屏 3000ms 线性降速（「息屏动画持续播放」关闭时才用）。 */
    static final long AOD_WINDOW_MS = 3000;

    // ---- 魅族「留光」(leave_light, style=1) 独立 8 通道轨道参数（对齐 OpenAlive FrameMotion / PhotoStyle）----
    static final int FN = 8;
    static final int F_BLUR = 0;
    static final int F_ROTATION = 1;
    static final int F_SCALE = 2;
    static final int F_CONTENT = 3;
    static final int F_X = 4;
    static final int F_Y = 5;
    static final int F_MICROS = 6;
    static final int F_ALPHA = 7;

    static final float LIUGUANG_ROTATION = -5f;
    static final float LIUGUANG_SCALE = 0.70486f;
    static final float LIUGUANG_EXPANDED_SCALE = 2.35f;
    /** 留光拍立得相框中心在屏幕从上往下 33.5% 高度处（0.5 - 0.335 = 0.165f = AOD_Y），与 FlymeClockView 33.5% 锚点严格同心。 */
    static final float LIUGUANG_AOD_Y = AOD_Y;
    static final float LIUGUANG_END_MICROS = 483667f;

    /** cubic-bezier 曲线（Android PathInterpolator 语义）。 */
    static final class Curve {
        static final Curve DEFAULT = new Curve(-1, 0, 0, 0);
        static final Curve LINEAR = new Curve(-2, 0, 0, 0);
        static final Curve EASE = new Curve(.33f, 0f, .67f, 1f);
        static final Curve EXPAND = new Curve(.13f, .04f, 0f, 1f);
        static final Curve COLLAPSE = new Curve(.37f, .04f, .04f, 1f);
        static final Curve DISTORT = new Curve(0f, .5f, 0f, 1f);
        static final Curve ROTATE = new Curve(.17f, 0f, .67f, 1f);
        /** tr2 的 scale/x/y 用 path(0,0,0,1)。 */
        static final Curve CRASH_IN = new Curve(0f, 0f, 0f, 1f);
        /** tr1/tr3 的 blur。 */
        static final Curve BLUR_IN = new Curve(.17f, 0f, .83f, 1f);
        /** tr1/tr3 的 decoratorAngle。 */
        static final Curve DECOR_IN = new Curve(.37f, .02f, .04f, 1f);
        /** 留光专用几何缩放/位移/回缩曲线 path(0.28, 0, 0.33, 1)。 */
        static final Curve LIUGUANG = new Curve(.28f, 0f, .33f, 1f);

        private final float x1;
        private final float y1;
        private final float x2;
        private final float y2;

        Curve(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        float eval(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            if (this == DEFAULT) {
                return t * t * (3f - 2f * t); // AccelerateDecelerate
            }
            if (this == LINEAR) {
                return t;
            }
            float lo = 0f;
            float hi = 1f;
            float s = t;
            for (int i = 0; i < 24; i++) {
                float x = bez(s, x1, x2);
                if (x < t) {
                    lo = s;
                } else {
                    hi = s;
                }
                s = (lo + hi) * 0.5f;
            }
            return bez(s, y1, y2);
        }

        private static float bez(float s, float c1, float c2) {
            float u = 1f - s;
            return 3f * u * u * s * c1 + 3f * u * s * s * c2 + s * s * s;
        }
    }

    final float[] values = new float[N];
    private final float[] from = new float[N];
    private final float[] to = new float[N];
    private final long[] durMs = new long[N];
    private final long[] delayMs = new long[N];
    private final Curve[] curves = new Curve[N];
    private final boolean[] tracked = new boolean[N];

    final float[] frameValues = new float[FN];
    private final float[] frameFrom = new float[FN];
    private final float[] frameTo = new float[FN];
    private final long[] frameDurMs = new long[FN];
    private final long[] frameDelayMs = new long[FN];
    private final Curve[] frameCurves = new Curve[FN];
    private final boolean[] frameTracked = new boolean[FN];
    private final float aspect;

    // ---- 「续跑」用：解锁进桌面时，桌面的扩散要从锁屏侧已播到的进度接着走 ----
    private final float[] ratioFrom = new float[3];
    private boolean ratioRetarget;
    private long ratioStartNanos;
    private long ratioMs = 500;
    private int ratioTarget = HOME;
    private long ratioDelayMs;

    final LensMatrices matrices;
    final float[] photoMatrix = new float[16];
    final float[] decorMatrix = new float[16];
    final float expandedScale;

    int mode = LOCK;
    /** 「息屏动画持续播放」（sail_continuous_aod），官方默认 true。 */
    boolean continuousAod = true;
    /** ratio[息屏, 锁屏, 桌面]，仅用于日志/调试（本实现单 pass，不做三 target 加权）。 */
    final float[] ratio = new float[3];

    private long startNanos;
    private long lastFrameNanos;
    private boolean animating;
    private long aodEnterNanos;
    /** 进入当前模式的时间与"上一个模式"，用于并掉指纹解锁时一闪而过的锁屏态。 */
    private long modeEnterNanos;
    private int modeBefore = -1;
    private int lastTransition = -1;

    Motion(int width, int height) {
        matrices = new LensMatrices(width, height);
        aspect = width > 0 ? (float) height / (float) width : 2.17f;
        expandedScale = "2.33".equals(String.format(java.util.Locale.US, "%.2f", (float) height / width))
                ? 1.3f : 1.24f;
        applyState(LOCK, 0);
        ratio[LOCK] = 1f;
    }

    boolean animating() {
        return animating;
    }

    int lastTransition() {
        return lastTransition;
    }

    /** 息屏窗口降速（continuousAod=false 时才生效）。 */
    private float aodSpeed(long nowNanos) {
        if (continuousAod) {
            return 1f;
        }
        float p = Math.min(1f, (nowNanos - aodEnterNanos) / (AOD_WINDOW_MS * 1_000_000f));
        return 1f - p;
    }

    boolean needsContinuousFrames() {
        return mode == AOD && values[SPEED] > 0f;
    }

    float scale() {
        return values[SCALE];
    }

    float blur() {
        return values[BLUR];
    }

    float distortion() {
        return values[DISTORTION];
    }

    float decorAlpha() {
        return values[DECOR_ALPHA];
    }

    float ripple() {
        return values[RIPPLE];
    }

    float translateX() {
        return values[X];
    }

    float translateY() {
        return values[Y];
    }

    float photoAngle() {
        return values[PHOTO_ANGLE];
    }

    float homeRatio() {
        return ratio[HOME];
    }

    float frameRotation() {
        return frameValues[F_ROTATION];
    }

    float frameScale() {
        return frameValues[F_SCALE];
    }

    float frameContent() {
        return frameValues[F_CONTENT];
    }

    float frameX() {
        return frameValues[F_X];
    }

    float frameY() {
        return frameValues[F_Y];
    }

    long frameMicros() {
        return Math.round(frameValues[F_MICROS]);
    }

    float framePhotoAlpha() {
        return frameValues[F_ALPHA];
    }

    /** 官方 transition() 编码。 */
    static int transition(int from, int to) {
        if (from == AOD) {
            return to == LOCK ? 0 : 2;
        }
        if (to == AOD) {
            return from == LOCK ? 1 : 3;
        }
        return from == LOCK ? 4 : 5;
    }

    /** 官方 nearestRotation()：取最短角路。 */
    static float nearestRotation(float current, float target) {
        float distance = Math.floorMod((int) (target - current), 360);
        return target + (distance >= 180 ? 360 - distance : -distance);
    }

    /** 三态 lens 十通道初值。 */
    private float[] stateOf(int scene) {
        if (scene == AOD) {
            return new float[]{AOD_BLUR, AOD_SCALE, AOD_DISTORTION, 1f, 0f, 0f, AOD_Y, 1f, 0f, Float.NaN};
        }
        float blur = scene == LOCK ? 0f : HOME_BLUR;
        return new float[]{blur, expandedScale, 0f, 0f, -149f, 0f, 0f, 0f, 1.24f, -360f};
    }

    /** 留光（leave_light）三态 8 通道初值（对齐 OpenAlive FrameMotion.state）。 */
    private float[] frameStateOf(int scene) {
        if (scene == AOD) {
            return new float[]{0f, LIUGUANG_ROTATION, LIUGUANG_SCALE, 1f, 0f, LIUGUANG_AOD_Y, 0f, 1f};
        }
        float fullScale = Math.max(LIUGUANG_EXPANDED_SCALE, aspect + 0.05f);
        return new float[]{0f, 0f, fullScale, aspect / fullScale, 0f, 0f, LIUGUANG_END_MICROS, 0f};
    }

    void applyState(int scene, long nowNanos) {
        float[] s = stateOf(scene);
        for (int i = 0; i < N; i++) {
            values[i] = s[i];
        }
        values[PHOTO_ANGLE] = -360f;
        float[] fs = frameStateOf(scene);
        for (int i = 0; i < FN; i++) {
            frameValues[i] = fs[i];
            frameTracked[i] = false;
        }
        mode = scene;
        animating = false;
        startNanos = nowNanos;
    }

    void copyFrom(Motion src) {
        if (src == null) {
            return;
        }
        for (int i = 0; i < N; i++) {
            this.values[i] = src.values[i];
            this.from[i] = src.from[i];
            this.to[i] = src.to[i];
            this.durMs[i] = src.durMs[i];
            this.delayMs[i] = src.delayMs[i];
            this.curves[i] = src.curves[i];
            this.tracked[i] = src.tracked[i];
        }
        for (int i = 0; i < FN; i++) {
            this.frameValues[i] = src.frameValues[i];
            this.frameFrom[i] = src.frameFrom[i];
            this.frameTo[i] = src.frameTo[i];
            this.frameDurMs[i] = src.frameDurMs[i];
            this.frameDelayMs[i] = src.frameDelayMs[i];
            this.frameCurves[i] = src.frameCurves[i];
            this.frameTracked[i] = src.frameTracked[i];
        }
        System.arraycopy(src.ratio, 0, this.ratio, 0, 3);
        this.mode = src.mode;
        this.animating = src.animating;
        this.lastTransition = src.lastTransition;
        this.startNanos = src.startNanos;
    }

    /** 过渡已进行的毫秒数；没有正在进行的过渡时返回 -1。 */
    long transitionElapsedMs(long nowNanos) {
        if (!animating || startNanos == 0) {
            return -1;
        }
        return (nowNanos - startNanos) / 1_000_000L;
    }

    /** 本轮过渡的整体时长（各通道 delay+dur 的最大值）。 */
    long totalDurationMs() {
        long max = 0;
        for (int i = 0; i < N; i++) {
            if (tracked[i]) {
                max = Math.max(max, delayMs[i] + durMs[i]);
            }
        }
        return max;
    }

    /** 过渡剩余时长（ms）；未在过渡中返回 0。 */
    long remainingMs(long nowNanos) {
        if (!animating) {
            return 0;
        }
        return Math.max(0, totalDurationMs() - (nowNanos - startNanos) / 1_000_000L);
    }

    /** 按绝对时间把通道值推到 now（渲染循环若暂停过，values 会滞后一帧以上）。 */
    private void catchUpChannels(long nowNanos) {
        for (int i = 0; i < N; i++) {
            if (!tracked[i]) {
                continue;
            }
            long t = nowNanos - startNanos - delayMs[i] * 1_000_000L;
            float p;
            if (t <= 0) {
                p = 0f;
            } else if (durMs[i] <= 0) {
                p = 1f;
            } else {
                p = Math.min(1f, t / (durMs[i] * 1_000_000f));
            }
            values[i] = from[i] + (to[i] - from[i]) * curves[i].eval(p);
        }
        for (int i = 0; i < FN; i++) {
            if (!frameTracked[i]) {
                continue;
            }
            long t = nowNanos - startNanos - frameDelayMs[i] * 1_000_000L;
            float p;
            if (t <= 0) {
                p = 0f;
            } else if (frameDurMs[i] <= 0) {
                p = 1f;
            } else {
                p = Math.min(1f, t / (frameDurMs[i] * 1_000_000f));
            }
            frameValues[i] = frameFrom[i] + (frameTo[i] - frameFrom[i]) * frameCurves[i].eval(p);
        }
    }

    /**
     * 把状态摆到「{@code from}→{@code to} 这条过渡已经走了 {@code elapsedMs}」的位置（只算不播）。
     *
     * <p>用途：桌面槽位的 Engine 在锁屏期间**不可见也不渲染**，因此它没有自己的进度；
     * 解锁要接手锁屏侧已经播了一半的扩散时，先用它把值对齐到同一时刻，
     * 再 {@link #continueTo} 续跑，切换瞬间才不跳变。
     */
    void seekTransition(int from, int to, long elapsedMs, long nowNanos) {
        int tr = transition(from, to);
        buildTracks(from, to, tr, false);
        startNanos = nowNanos - Math.max(0, elapsedMs) * 1_000_000L;   // 回拨：使"现在"正好是 elapsedMs
        modeBefore = from;
        mode = to;
        modeEnterNanos = nowNanos;
        animating = true;
        lastTransition = tr;
        ratiosTo(to, nowNanos, 1);
        catchUpChannels(nowNanos);
    }

    /**
     * 从**当前进度**接着跑到 {@code next}（不重启）——用于「锁屏扩散还没播完就解锁进桌面」：
     * 起点取当前值，各通道时长取该通道的剩余时间，已跑完的通道直接落到目标值，
     * blend 权重也从当前值线性续接到目标态。
     */
    void continueTo(int next, long nowNanos) {
        if (!animating) {
            setMode(next, true, nowNanos);
            return;
        }
        catchUpChannels(nowNanos);
        long elapsedTotal = (nowNanos - startNanos) / 1_000_000L;
        float[] target = stateOf(next);
        int prevMode = mode;
        long maxLeft = 0;
        for (int i = 0; i < N; i++) {
            long el = Math.max(0, elapsedTotal - delayMs[i]);
            long left = Math.max(0, durMs[i] - el);
            from[i] = values[i];
            to[i] = target[i];
            tracked[i] = !Float.isNaN(target[i]);
            durMs[i] = tracked[i] ? left : 0;
            delayMs[i] = 0;
            if (tracked[i]) {
                maxLeft = Math.max(maxLeft, durMs[i]);
            }
        }
        float[] frameTarget = frameStateOf(next);
        for (int i = 0; i < FN; i++) {
            long el = Math.max(0, elapsedTotal - frameDelayMs[i]);
            long left = Math.max(0, frameDurMs[i] - el);
            frameFrom[i] = frameValues[i];
            frameTo[i] = frameTarget[i];
            frameTracked[i] = true;
            frameDurMs[i] = left;
            frameDelayMs[i] = 0;
        }
        ratiosTo(next, nowNanos, maxLeft);
        lastTransition = transition(prevMode, next);
        modeBefore = prevMode;
        modeEnterNanos = nowNanos;
        mode = next;
        startNanos = nowNanos;
        animating = true;
        if (next == AOD) {
            aodEnterNanos = nowNanos;
        }
        android.util.Log.i("QihangAod", "续跑 prev=" + prevMode + " next=" + next + " tr="
                + lastTransition + " 剩余=" + maxLeft + "ms");
    }

    /** 让 blend 权重从当前值续接到 {@code next} 的稳态（用时长 ms）。 */
    private void ratiosTo(int next, long nowNanos, long ms) {
        System.arraycopy(ratio, 0, ratioFrom, 0, 3);
        ratioTarget = next;
        ratioStartNanos = nowNanos;
        ratioMs = Math.max(1, ms);
        ratioRetarget = true;
    }

    void setMode(int next, boolean animate, long nowNanos) {
        int prev = mode;
        if (next == prev) {
            // 同态重复事件（AOD↔KEYGUARD 抖动常见）直接忽略：否则 transition(0,0) 会算出
            // tr2 这种不存在的过渡，把正在播的动画打断成"立刻跳到目标态"。
            return;
        }
        // 若在锁屏扩散动画未结束时进入桌面（如预览图中快速连点 或 解锁抢步），直接续跑，绝不重头重放
        if (animating && prev == LOCK && next == HOME) {
            continueTo(next, nowNanos);
            return;
        }
        if (!animate) {
            applyState(next, nowNanos);
            for (int i = 0; i < 3; i++) {
                ratio[i] = i == next ? 1f : 0f;
            }
            if (next == AOD) {
                aodEnterNanos = nowNanos;
            }
            return;
        }
        boolean interrupted = animating;
        int tr = transition(prev, next);
        android.util.Log.i("QihangAod", "过渡 prev=" + prev + " next=" + next + " tr=" + tr
                + " interrupted=" + interrupted + (prev != mode ? "（并掉了中间锁屏态）" : ""));
        lastTransition = tr;
        buildTracks(prev, next, tr, interrupted);
        modeBefore = mode;
        modeEnterNanos = nowNanos;
        mode = next;
        if (next == AOD) {
            aodEnterNanos = nowNanos;
        }
        animating = true;
    }

    /** 按官方 lens() 四个分支装 10 条轨（每通道时长/曲线/延迟）。 */
    private void buildTracks(int prev, int next, int tr, boolean interrupted) {
        float[] target = stateOf(next);
        for (int i = 0; i < N; i++) {
            from[i] = values[i];
            to[i] = target[i];
            durMs[i] = 1000;
            delayMs[i] = 0;
            curves[i] = Curve.DEFAULT;
            tracked[i] = !Float.isNaN(target[i]);
        }
        switch (tr) {
            case 0: // 熄屏 → 锁屏
                durMs[BLUR] = 183;
                curves[BLUR] = Curve.EASE;
                durMs[SCALE] = 950;
                curves[SCALE] = Curve.EXPAND;
                durMs[DISTORTION] = 800;
                curves[DISTORTION] = Curve.DISTORT;
                durMs[DECOR_ALPHA] = 33;
                curves[DECOR_ALPHA] = Curve.EASE;
                durMs[DECOR_ANGLE] = 1000;
                durMs[X] = 950;
                curves[X] = Curve.EXPAND;
                durMs[Y] = 950;
                curves[Y] = Curve.EXPAND;
                durMs[SPEED] = 0;                      // 旋转立刻停
                durMs[RIPPLE] = 950;
                curves[RIPPLE] = Curve.EASE;
                durMs[PHOTO_ANGLE] = 24;
                curves[PHOTO_ANGLE] = Curve.ROTATE;
                from[PHOTO_ANGLE] = nearestRotation(values[PHOTO_ANGLE], target[PHOTO_ANGLE]);
                break;
            case 2: // 熄屏 → 桌面（魅族官方标准 tr2：1000ms CRASH_IN 大圆爆发扩散）
                if (!interrupted && prev == AOD) {
                    float[] aodInit = stateOf(AOD);
                    for (int i = 0; i < N; i++) {
                        from[i] = aodInit[i];
                    }
                }
                durMs[BLUR] = 183;
                curves[BLUR] = Curve.EASE;
                durMs[SCALE] = 1000;
                curves[SCALE] = Curve.CRASH_IN;
                durMs[DISTORTION] = 900;
                curves[DISTORTION] = Curve.DISTORT;
                durMs[DECOR_ALPHA] = 33;
                curves[DECOR_ALPHA] = Curve.EASE;
                durMs[DECOR_ANGLE] = 1000;
                durMs[X] = 1000;
                curves[X] = Curve.CRASH_IN;
                durMs[Y] = 1000;
                curves[Y] = Curve.CRASH_IN;
                durMs[SPEED] = 0;
                durMs[RIPPLE] = 1000;
                curves[RIPPLE] = Curve.EASE;
                durMs[PHOTO_ANGLE] = 24;
                curves[PHOTO_ANGLE] = Curve.ROTATE;
                from[PHOTO_ANGLE] = nearestRotation(values[PHOTO_ANGLE], target[PHOTO_ANGLE]);
                break;
            case 1: // 锁屏 → 熄屏
            case 3: // 桌面 → 熄屏
                for (int i = 0; i < 8; i++) {
                    durMs[i] = 650;
                }
                curves[BLUR] = Curve.BLUR_IN;
                curves[SCALE] = Curve.COLLAPSE;
                curves[X] = Curve.COLLAPSE;
                curves[Y] = Curve.COLLAPSE;
                curves[DISTORTION] = Curve.EASE;
                curves[DECOR_ALPHA] = Curve.EASE;
                curves[DECOR_ANGLE] = Curve.DECOR_IN;
                curves[SPEED] = Curve.DEFAULT;
                durMs[SPEED] = 650;
                durMs[RIPPLE] = 0;                     // 官方：rippleDelay=650, ms[8]=0
                delayMs[RIPPLE] = 650;
                tracked[PHOTO_ANGLE] = false;          // 目标 NaN → 不建轨，保持当前角
                break;
            case 4: // 锁屏 → 桌面（500ms 交叉渐变）
                for (int i = 0; i < 8; i++) {
                    durMs[i] = 500;
                }
                curves[BLUR] = Curve.EASE;
                curves[SCALE] = Curve.EXPAND;
                curves[X] = Curve.EXPAND;
                curves[Y] = Curve.EXPAND;
                curves[DISTORTION] = Curve.DISTORT;
                durMs[RIPPLE] = 500;
                curves[RIPPLE] = Curve.EASE;
                durMs[PHOTO_ANGLE] = 24;
                curves[PHOTO_ANGLE] = Curve.ROTATE;
                durMs[SPEED] = 0;
                break;
            default: // 5：桌面 → 锁屏（500ms 交叉渐变）
                for (int i = 0; i < 8; i++) {
                    durMs[i] = 500;
                }
                curves[BLUR] = Curve.EASE;
                durMs[PHOTO_ANGLE] = 24;
                curves[PHOTO_ANGLE] = Curve.ROTATE;
                durMs[SPEED] = 0;
                break;
        }
        long delay = (prev == AOD && !interrupted) ? 150 : 0; // 退出熄屏的整体起始延迟
        for (int i = 0; i < N; i++) {
            delayMs[i] += delay;
        }
        buildFrameTracks(prev, next, tr, interrupted, delay);
        ratioDelayMs = delay;
        ratioRetarget = false;
        startNanos = System.nanoTime();
    }

    /** 装载魅族「留光」(leave_light) 8 通道轨道（对齐 OpenAlive PhotoStyle.get(1) 与 FrameMotion.prepare）。 */
    private void buildFrameTracks(int prev, int next, int tr, boolean interrupted, long wakeDelay) {
        float[] target = frameStateOf(next);
        for (int i = 0; i < FN; i++) {
            frameFrom[i] = frameValues[i];
            frameTo[i] = target[i];
            frameDurMs[i] = 400;
            frameDelayMs[i] = 0;
            frameCurves[i] = Curve.DEFAULT;
            frameTracked[i] = true;
        }
        if (tr == 2 && !interrupted && prev == AOD) {
            float[] aodInit = frameStateOf(AOD);
            for (int i = 0; i < FN; i++) {
                frameFrom[i] = aodInit[i];
            }
        }
        if (tr == 0 || tr == 2) {
            // 退出熄屏（展开）：rotation 167ms EASE；scale/x/y 517ms + 50ms 延迟 + LIUGUANG(0.28,0,0.33,1)
            frameDurMs[F_ROTATION] = 167;
            frameDelayMs[F_ROTATION] = 0;
            frameCurves[F_ROTATION] = Curve.EASE;

            frameDurMs[F_SCALE] = 517;
            frameDelayMs[F_SCALE] = 50;
            frameCurves[F_SCALE] = Curve.LIUGUANG;

            frameDurMs[F_X] = 517;
            frameDelayMs[F_X] = 50;
            frameCurves[F_X] = Curve.LIUGUANG;

            frameDurMs[F_Y] = 517;
            frameDelayMs[F_Y] = 50;
            frameCurves[F_Y] = Curve.LIUGUANG;
        } else if (tr == 1 || tr == 3) {
            // 进入熄屏（回缩）：rotation/scale/x/y 统一 450ms + LIUGUANG(0.28,0,0.33,1)
            frameDurMs[F_ROTATION] = 450;
            frameDelayMs[F_ROTATION] = 0;
            frameCurves[F_ROTATION] = Curve.LIUGUANG;

            frameDurMs[F_SCALE] = 450;
            frameDelayMs[F_SCALE] = 0;
            frameCurves[F_SCALE] = Curve.LIUGUANG;

            frameDurMs[F_X] = 450;
            frameDelayMs[F_X] = 0;
            frameCurves[F_X] = Curve.LIUGUANG;

            frameDurMs[F_Y] = 450;
            frameDelayMs[F_Y] = 0;
            frameCurves[F_Y] = Curve.LIUGUANG;
        } else if (tr == 4) {
            frameDurMs[F_ALPHA] = 800;
            frameCurves[F_ALPHA] = Curve.EASE;
        }
        frameDurMs[F_MICROS] = Math.round(Math.abs(frameFrom[F_MICROS] - frameTo[F_MICROS]) / 1000f);
        frameDelayMs[F_MICROS] = 0;
        frameCurves[F_MICROS] = Curve.LINEAR;

        for (int i = 0; i < FN; i++) {
            frameDelayMs[i] += wakeDelay;
        }
    }

    /** 每帧推进：动画 → 自转 → 钉圆心 → 刷新矩阵。 */
    void advance(long nowNanos) {
        float dt = (lastFrameNanos != 0 && nowNanos > lastFrameNanos)
                ? (nowNanos - lastFrameNanos) / 1_000_000_000f : 0f;
        lastFrameNanos = nowNanos;

        if (animating) {
            boolean done = true;
            for (int i = 0; i < N; i++) {
                if (!tracked[i]) {
                    continue;
                }
                long t = nowNanos - startNanos - delayMs[i] * 1_000_000L;
                float p;
                if (t <= 0) {
                    p = 0f;
                } else if (durMs[i] <= 0) {
                    p = 1f;
                } else {
                    p = Math.min(1f, t / (durMs[i] * 1_000_000f));
                }
                values[i] = from[i] + (to[i] - from[i]) * curves[i].eval(p);
                if (p < 1f) {
                    done = false;
                }
            }
            for (int i = 0; i < FN; i++) {
                if (!frameTracked[i]) {
                    continue;
                }
                long t = nowNanos - startNanos - frameDelayMs[i] * 1_000_000L;
                float p;
                if (t <= 0) {
                    p = 0f;
                } else if (frameDurMs[i] <= 0) {
                    p = 1f;
                } else {
                    p = Math.min(1f, t / (frameDurMs[i] * 1_000_000f));
                }
                frameValues[i] = frameFrom[i] + (frameTo[i] - frameFrom[i]) * frameCurves[i].eval(p);
                if (p < 1f) {
                    done = false;
                }
            }
            if (done) {
                animating = false;
                for (int i = 0; i < N; i++) {
                    if (tracked[i]) {
                        values[i] = to[i];
                    }
                }
                for (int i = 0; i < FN; i++) {
                    if (frameTracked[i]) {
                        frameValues[i] = frameTo[i];
                    }
                }
            }
        }

        // 官方 advance()：仅息屏、且 speed>0 时按 40°/s × speed 累积
        if (mode == AOD && values[SPEED] > 0f && dt > 0f) {
            float mul = aodSpeed(nowNanos);
            values[PHOTO_ANGLE] = (values[PHOTO_ANGLE] - dt * DEG_PER_SEC * values[SPEED] * mul) % 360f;
        }
        // 息屏且无动画在跑时，每帧把圆心钉回 (aodX, aodY)
        if (mode == AOD && !animating) {
            values[X] = 0f;
            values[Y] = AOD_Y;
            frameValues[F_X] = 0f;
            frameValues[F_Y] = LIUGUANG_AOD_Y;
        }

        updateRatio(nowNanos);
        matrices.write(values[X], values[Y], values[PHOTO_ANGLE], values[SCALE], values[DECOR_ANGLE],
                photoMatrix, decorMatrix);
    }

    private void updateRatio(long nowNanos) {
        // 官方 blendDuration()（aod==0、lock 默认 2、home 默认 6）：
        //   tr0 = max(950, 850) = 950；tr2 = 300；tr1 = 650；tr3 = 650
        if (!animating) {
            ratio[0] = mode == AOD ? 1f : 0f;
            ratio[1] = mode == LOCK ? 1f : 0f;
            ratio[2] = mode == HOME ? 1f : 0f;
            return;
        }
        // 续跑（continueTo / seekTransition）时 blend 权重也从当前值走，而不是按 lastTransition 从头算
        if (ratioRetarget) {
            float p = Math.min(1f, Math.max(0f, (nowNanos - ratioStartNanos) / (ratioMs * 1_000_000f)));
            float e = Curve.LINEAR.eval(p);
            for (int i = 0; i < 3; i++) {
                float t = i == ratioTarget ? 1f : 0f;
                ratio[i] = ratioFrom[i] + (t - ratioFrom[i]) * e;
            }
            if (p >= 1f) {
                ratioRetarget = false;
            }
            return;
        }
        long tr = lastTransition;
        long dur;
        Curve c;
        int target;
        int src;
        switch ((int) tr) {
            case 0:
                dur = 950;
                c = Curve.LINEAR;
                src = AOD;
                target = LOCK;
                break;
            case 2:
                dur = 650;
                c = Curve.EASE;
                src = AOD;
                target = HOME;
                break;
            case 1:
                dur = 650;
                c = Curve.LINEAR;
                src = LOCK;
                target = AOD;
                break;
            case 3:
                dur = 650;
                c = Curve.EASE;
                src = HOME;
                target = AOD;
                break;
            case 4:
                dur = 500;
                c = Curve.LINEAR;
                src = LOCK;
                target = HOME;
                break;
            default:
                dur = 500;
                c = Curve.LINEAR;
                src = HOME;
                target = LOCK;
                break;
        }
        float p = Math.min(1f, Math.max(0f,
                (nowNanos - startNanos - ratioDelayMs * 1_000_000L) / (dur * 1_000_000f)));
        float e = c.eval(p);
        for (int i = 0; i < 3; i++) {
            float s = i == src ? 1f : 0f;
            float t = i == target ? 1f : 0f;
            ratio[i] = s + (t - s) * e;
        }
    }
}