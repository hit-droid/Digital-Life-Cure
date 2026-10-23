package com.digitallife.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v1.184.0「原生 HitArea 命中查询」的契约：{@link HitZoneCache} 只在「同一次手势
 * 的查询已算完且未过期」时交回结果，其余一律 {@link HitZoneCache#NO_RESULT}，
 * 让悬浮窗安全回落到固定比例分区。
 */
public class HitZoneCacheTest {

    private static final long NOW = 10_000L;

    @Test
    public void begin_incrementsSequence() {
        HitZoneCache c = new HitZoneCache();
        int a = c.begin();
        int b = c.begin();
        assertTrue("序号必须递增且可用", a > 0 && b > a);
    }

    @Test
    public void take_beforeStore_returnsNoResult() {
        HitZoneCache c = new HitZoneCache();
        int seq = c.begin();
        assertEquals(HitZoneCache.NO_RESULT, c.take(seq, NOW, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void storeThenTake_returnsHeadAndBody() {
        HitZoneCache c = new HitZoneCache();
        int head = c.begin();
        c.store(head, Live2DGLView.HIT_HEAD, NOW);
        assertEquals(Live2DGLView.HIT_HEAD, c.take(head, NOW + 20, HitZoneCache.DEFAULT_MAX_AGE_MS));

        int body = c.begin();
        c.store(body, Live2DGLView.HIT_BODY, NOW + 100);
        assertEquals(Live2DGLView.HIT_BODY, c.take(body, NOW + 120, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void store_noneHit_isZeroNotNoResult() {
        // 原生明确回「不在任何 HitArea 内」时必须交回 0，调用方才知道该回落；
        // 若错当成 NO_RESULT，行为虽然一样，但语义上会把「算过」和「没算」混为一谈。
        HitZoneCache c = new HitZoneCache();
        int seq = c.begin();
        c.store(seq, Live2DGLView.HIT_NONE, NOW);
        assertEquals(Live2DGLView.HIT_NONE, c.take(seq, NOW, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void take_otherGestureSequence_returnsNoResult() {
        HitZoneCache c = new HitZoneCache();
        int first = c.begin();
        int second = c.begin();
        c.store(second, Live2DGLView.HIT_HEAD, NOW);
        // 上一次手势的序号不能再取到本次结果
        assertEquals(HitZoneCache.NO_RESULT, c.take(first, NOW, HitZoneCache.DEFAULT_MAX_AGE_MS));
        assertEquals(Live2DGLView.HIT_HEAD, c.take(second, NOW, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void store_olderSequence_doesNotOverwriteNewer() {
        HitZoneCache c = new HitZoneCache();
        int first = c.begin();
        int second = c.begin();
        c.store(second, Live2DGLView.HIT_BODY, NOW);
        // 旧的 GL 查询迟到，不能把新结果冲掉
        c.store(first, Live2DGLView.HIT_HEAD, NOW);
        assertEquals(Live2DGLView.HIT_BODY, c.take(second, NOW, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void store_ignoresNonPositiveSequence() {
        HitZoneCache c = new HitZoneCache();
        c.store(0, Live2DGLView.HIT_HEAD, NOW);
        assertEquals(HitZoneCache.NO_RESULT, c.take(0, NOW, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void take_expiredResult_returnsNoResult() {
        HitZoneCache c = new HitZoneCache();
        int seq = c.begin();
        c.store(seq, Live2DGLView.HIT_HEAD, NOW);
        long justInTime = NOW + HitZoneCache.DEFAULT_MAX_AGE_MS;
        assertEquals(Live2DGLView.HIT_HEAD,
                c.take(seq, justInTime, HitZoneCache.DEFAULT_MAX_AGE_MS));
        assertEquals(HitZoneCache.NO_RESULT,
                c.take(seq, justInTime + 1, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void take_clockWentBackwards_returnsNoResult() {
        HitZoneCache c = new HitZoneCache();
        int seq = c.begin();
        c.store(seq, Live2DGLView.HIT_HEAD, NOW);
        assertEquals(HitZoneCache.NO_RESULT, c.take(seq, NOW - 1, HitZoneCache.DEFAULT_MAX_AGE_MS));
    }

    @Test
    public void take_nonPositiveMaxAge_usesDefault() {
        HitZoneCache c = new HitZoneCache();
        int seq = c.begin();
        c.store(seq, Live2DGLView.HIT_BODY, NOW);
        assertEquals(Live2DGLView.HIT_BODY, c.take(seq, NOW + 1000, 0));
        assertEquals(HitZoneCache.NO_RESULT,
                c.take(seq, NOW + HitZoneCache.DEFAULT_MAX_AGE_MS + 1, -5));
    }
}
