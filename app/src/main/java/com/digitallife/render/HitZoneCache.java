package com.digitallife.render;

/**
 * 原生命中查询的「一次性结果缓存」（v1.184.0，纯逻辑，JVM 可测）。
 *
 * <p>背景：悬浮窗在 {@code ACTION_DOWN} 就把触摸拦截了，子 {@code Live2DGLView}
 * 收不到 DOWN，原生 {@code OnTap} / HitArea 判定在桌宠上一直是死路；此前只能用
 * {@code PetTouchReaction} 的固定 38% 高度线近似头/身。v1.184.0 起在按下时把坐标
 * 投递到 GL 线程做一次 {@code nativeHitTestZone}，结果写入本缓存，抬手判定点击时
 * 再按序号取回——这样既不阻塞 UI 线程，也不依赖「多久之前的结果还算数」的猜测量。</p>
 *
 * <p><b>为什么按序号而不是按坐标/时间匹配</b>：一次手势对应一次 {@link #begin()}，
 * 抬手时用同一个序号取；中间若又来了新的按下（新序号），旧序号自然取不到，返回
 * {@link #NO_RESULT}，调用方回落到固定比例分区即可。GL 线程繁忙导致查询没跑完时
 * 同样是 {@link #NO_RESULT}，行为确定。</p>
 *
 * <p>额外兜一层 {@code maxAgeMs}：极端情况下抬手距结果写入过久（如手势被系统冻结），
 * 宁可回落也不用过期数据。所有方法线程安全。</p>
 */
public final class HitZoneCache {

    /** 没有可用结果（未算完 / 序号不符 / 已过期 / 时钟回拨） */
    public static final int NO_RESULT = -1;
    /** 结果有效期上限：超过就不再用，交给调用方回落 */
    public static final long DEFAULT_MAX_AGE_MS = 1500L;

    private int nextSeq;
    private int storedSeq;
    private int storedZone = NO_RESULT;
    private long storedAtMs;

    /** 发起一次查询，返回本次序号；GL 线程完成后用同一序号 {@link #store} */
    public synchronized int begin() {
        return ++nextSeq;
    }

    /**
     * GL 线程写入查询结果。
     *
     * @param seq    {@link #begin()} 返回的序号；非正数忽略
     * @param zone   原生命中码（0 未命中 / 1 头 / 2 身）
     * @param nowMs  写入时刻（{@code SystemClock.uptimeMillis()}）
     */
    public synchronized void store(int seq, int zone, long nowMs) {
        if (seq <= 0) return;
        // 迟到的旧查询不能覆盖更新的结果
        if (seq < storedSeq) return;
        storedSeq = seq;
        storedZone = zone;
        storedAtMs = nowMs;
    }

    /**
     * 取回某次查询的结果。
     *
     * @param seq       {@link #begin()} 返回的序号
     * @param nowMs     当前时刻（单调时钟）
     * @param maxAgeMs  结果有效期；非正数用 {@link #DEFAULT_MAX_AGE_MS}
     * @return 原生命中码；序号不符 / 未算完 / 已过期 / 时钟回拨都返回 {@link #NO_RESULT}
     */
    public synchronized int take(int seq, long nowMs, long maxAgeMs) {
        if (seq <= 0 || seq != storedSeq) return NO_RESULT;
        long age = nowMs - storedAtMs;
        long limit = maxAgeMs > 0 ? maxAgeMs : DEFAULT_MAX_AGE_MS;
        if (age < 0 || age > limit) return NO_RESULT;
        return storedZone;
    }
}
