package com.digitallife.ui.pet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 桌宠触摸分区反应纯逻辑回归（Issue #37）。
 *
 * <p>分区判定一旦算错，「摸头」会变成「戳身子」；反馈表一旦两边写成一样，
 * 这个功能就等于没做。悬浮窗场景没法在单测里点一下看效果，所以把边界和差异都钉住。</p>
 */
public class PetTouchReactionTest {

    // 人偶区域：水平 200~800，垂直 200~1000（模拟 modelRect）
    private static final int L = 200, T = 200, R = 800, B = 1000;

    @Test
    public void zoneFor_topBand_isHead() {
        assertEquals(PetTouchReaction.ZONE_HEAD, PetTouchReaction.zoneFor(500f, 210f, L, T, R, B));
        assertEquals(PetTouchReaction.ZONE_HEAD, PetTouchReaction.zoneFor(500f, 250f, L, T, R, B));
    }

    @Test
    public void zoneFor_lowerBand_isBody() {
        assertEquals(PetTouchReaction.ZONE_BODY, PetTouchReaction.zoneFor(500f, 700f, L, T, R, B));
        assertEquals(PetTouchReaction.ZONE_BODY, PetTouchReaction.zoneFor(500f, 995f, L, T, R, B));
    }

    @Test
    public void zoneFor_boundaryAtHeadRatio_isBody() {
        // 正好落在判定线上算身体（ratio < HEAD_RATIO 才是头）
        int y = T + Math.round(PetTouchReaction.HEAD_RATIO * (B - T));
        assertEquals(PetTouchReaction.ZONE_BODY, PetTouchReaction.zoneFor(500f, y, L, T, R, B));
        assertEquals(PetTouchReaction.ZONE_HEAD, PetTouchReaction.zoneFor(500f, y - 1, L, T, R, B));
    }

    @Test
    public void zoneFor_outsideRect_isNone() {
        assertEquals(PetTouchReaction.ZONE_NONE, PetTouchReaction.zoneFor(100f, 500f, L, T, R, B));
        assertEquals(PetTouchReaction.ZONE_NONE, PetTouchReaction.zoneFor(900f, 500f, L, T, R, B));
        assertEquals(PetTouchReaction.ZONE_NONE, PetTouchReaction.zoneFor(500f, 100f, L, T, R, B));
        assertEquals(PetTouchReaction.ZONE_NONE, PetTouchReaction.zoneFor(500f, 1100f, L, T, R, B));
    }

    @Test
    public void zoneFor_degenerateRect_isNone_notCrash() {
        // 尺寸还没测量时为 0，不能除零，也不能当成头
        assertEquals(PetTouchReaction.ZONE_NONE, PetTouchReaction.zoneFor(0f, 0f, 0, 0, 0, 0));
        assertEquals(PetTouchReaction.ZONE_NONE, PetTouchReaction.zoneFor(0f, 0f, 10, 10, 10, 10));
    }

    @Test
    public void headAndBody_reactionsDiffer() {
        PetTouchReaction.Reaction head = PetTouchReaction.reactionFor(PetTouchReaction.ZONE_HEAD);
        PetTouchReaction.Reaction body = PetTouchReaction.reactionFor(PetTouchReaction.ZONE_BODY);

        assertNotEquals("摸头与戳身子文案必须不同", head.bubble, body.bubble);
        assertNotEquals("摸头与戳身子表情必须不同", head.expression, body.expression);
        assertTrue("摸头该比戳身子更涨亲密度", head.intimacy > body.intimacy);
        assertTrue("摸头该带害羞情绪", head.emotion("shy") > 0f);
        assertEquals(0f, body.emotion("shy"), 1e-6f);
        assertTrue("戳身子该带惊讶情绪", body.emotion("surprised") > 0f);
        assertEquals(0f, head.emotion("surprised"), 1e-6f);
    }

    @Test
    public void knownZones_haveBubbleAndExpression() {
        for (String zone : new String[]{PetTouchReaction.ZONE_HEAD, PetTouchReaction.ZONE_BODY}) {
            PetTouchReaction.Reaction r = PetTouchReaction.reactionFor(zone);
            assertNotNull(zone + " 应有气泡文案", r.bubble);
            assertFalse(zone + " 气泡文案不该为空", r.bubble.isEmpty());
            assertNotNull(zone + " 应有表情", r.expression);
            assertNotNull(zone + " 应有动作组", r.motionGroup);
        }
    }

    @Test
    public void unknownZone_fallsBackToNone() {
        PetTouchReaction.Reaction none = PetTouchReaction.reactionFor(PetTouchReaction.ZONE_NONE);
        for (PetTouchReaction.Reaction r : new PetTouchReaction.Reaction[]{
                none,
                PetTouchReaction.reactionFor(null),
                PetTouchReaction.reactionFor("head"),      // 大小写不符
                PetTouchReaction.reactionFor("Tail"),      // 未知分区
        }) {
            assertNull(r.bubble);
            assertNull(r.expression);
            assertEquals(0f, r.intimacy, 1e-6f);
            assertTrue(r.emotionDims().isEmpty());
            // 兜底仍保留原有的随机 TapBody 动作，不改变既有单击手感
            assertEquals("TapBody", r.motionGroup);
            assertTrue(r.motionIndex < 0);
        }
    }

    @Test
    public void reactionEmotions_areReadOnly() {
        PetTouchReaction.Reaction head = PetTouchReaction.reactionFor(PetTouchReaction.ZONE_HEAD);
        try {
            head.emotionDims().clear();
            throw new AssertionError("情绪维度集合应只读");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
