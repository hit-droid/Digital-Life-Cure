package com.digitallife.render;

/**
 * 二阶弹簧质点系统（Spring-Mass-Damper）控制器。
 *
 * AI（AICore / CareExecutor）只负责写目标值（target），GL 渲染线程每帧做弹簧积分，
 * 把离散的目标跳变翻译成「加速 -> 偏头 -> 过冲 -> 回弹 -> 稳住」的连续物理动画，
 * 赋予模型肌肉弹性与真实重力感。
 *
 * 半隐式欧拉积分（先更新速度、再用新速度更新位置），配合 dt 钳制，数值稳定。
 */
public class PhysicalController {

    /** 受弹簧控制的 AI 行为参数（头部 + 身体），与 C++ 侧 IsAiControlledMotionParameter 对应 */
    public static final String[] PARAM_IDS = {
            "ParamAngleX",
            "ParamAngleY",
            "ParamAngleZ",
            "ParamBodyAngleX",
            "ParamBodyAngleZ"
    };

    /** 每参数 [stiffness(弹性), damping(阻尼)]：头轻回弹、身体更重更慢 */
    private static final float[][] CONFIGS = {
            {150f, 15f},
            {150f, 15f},
            {120f, 12f},
            {80f, 12f},
            {80f, 12f}
    };

    private static final int N = PARAM_IDS.length;
    private static final float MAX_DT = 0.1f;

    private final float[] targets = new float[N];
    private final float[] current = new float[N];
    private final float[] velocity = new float[N];
    private final float[] k = new float[N];
    private final float[] d = new float[N];
    private final float[] out = new float[N];
    private final boolean[] initialized = new boolean[N];

    public PhysicalController() {
        for (int i = 0; i < N; i++) {
            k[i] = CONFIGS[i][0];
            d[i] = CONFIGS[i][1];
        }
    }

    /** 该参数是否由物理弹簧接管 */
    public boolean isControlled(String paramId) {
        return indexOf(paramId) >= 0;
    }

    /** 该参数在批量写入数组中的索引 */
    public int indexOf(String paramId) {
        for (int i = 0; i < N; i++) {
            if (PARAM_IDS[i].equals(paramId)) return i;
        }
        return -1;
    }

    /**
     * 从任意线程写入目标值。
     * 首次写入时把 current 直接同步到目标，避免接入瞬间从 0 猛拉。
     */
    public synchronized void setTarget(String paramId, float value) {
        int i = indexOf(paramId);
        if (i < 0) return;
        if (!initialized[i]) {
            initialized[i] = true;
            current[i] = value;
            targets[i] = value;
        } else {
            targets[i] = value;
        }
    }

    /**
     * GL 渲染线程每帧调用：弹簧积分，返回按 PARAM_IDS 顺序排列的当前值数组。
     * 未被接管的参数输出 NaN 哨兵，native 侧跳过写入，保留模型自身运动。
     */
    public synchronized float[] update(float dt) {
        float h = dt;
        if (h > MAX_DT) h = MAX_DT;
        if (h < 0f) h = 0f;
        for (int i = 0; i < N; i++) {
            if (!initialized[i]) {
                out[i] = Float.NaN;
                continue;
            }
            float displacement = current[i] - targets[i];
            float acceleration = -k[i] * displacement - d[i] * velocity[i];
            velocity[i] += acceleration * h;
            current[i] += velocity[i] * h;
            out[i] = current[i];
        }
        return out;
    }

    /** 重置物理状态（模型切换/释放时调用，避免沿用旧模型的数值） */
    public synchronized void reset() {
        for (int i = 0; i < N; i++) {
            targets[i] = 0f;
            current[i] = 0f;
            velocity[i] = 0f;
            initialized[i] = false;
        }
    }
}
