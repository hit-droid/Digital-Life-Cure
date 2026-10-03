package com.digitallife.ui.pet;

import com.digitallife.brain.EmotionState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 情绪外显纯逻辑回归（Issue #48）。
 *
 * <p>没这两层防抖，「两个相近情绪抢主导」会让表情每秒乱跳；最短停留算错则永远切不动。
 * 这里把阈值、迟滞、最短停留三条边界都钉死。</p>
 */
public class EmotionExpressionTest {

    /** 构造指定情绪向量；未给的维度保持 {@link EmotionState} 默认值 */
    private static EmotionState state(float happy, float sad, float angry,
                                      float surprised, float shy, float calm) {
        EmotionState e = new EmotionState();
        e.apply("happy", happy - 0.5f);
        e.apply("sad", sad);
        e.apply("angry", angry);
        e.apply("surprised", surprised);
        e.apply("shy", shy - 0.1f);
        e.apply("calm", calm - 0.6f);
        return e;
    }

    // ---------- 目标表情判定 ----------

    @Test
    public void targetFor_initialState_isCalm() {
        // 默认 happy .5 / calm .6：没有明显情绪，应保持平静
        assertEquals(EmotionExpression.CALM_EXPRESSION, EmotionExpression.targetFor(new EmotionState()));
    }

    @Test
    public void targetFor_strongHappy_mapsToF02() {
        EmotionState e = state(0.9f, 0f, 0f, 0f, 0.1f, 0.2f);
        assertEquals("F02", EmotionExpression.targetFor(e));
    }

    @Test
    public void targetFor_weakEmotionBelowThreshold_isCalm() {
        EmotionState e = state(0f, 0.3f, 0f, 0f, 0.1f, 0.1f);
        assertEquals(EmotionExpression.CALM_EXPRESSION, EmotionExpression.targetFor(e));
    }

    @Test
    public void targetFor_emotionNotAboveCalm_isCalm() {
        // happy .55 >= 阈值但 <= calm .6：平静占优，不外显
        EmotionState e = state(0.55f, 0f, 0f, 0f, 0.1f, 0.6f);
        assertEquals(EmotionExpression.CALM_EXPRESSION, EmotionExpression.targetFor(e));
    }

    // ---------- update：切换 / 不变 ----------

    @Test
    public void update_unchangedReturnsNull() {
        EmotionExpression ee = new EmotionExpression();
        assertNull(ee.update(new EmotionState(), 0L));
        assertEquals("F01", ee.current());
    }

    @Test
    public void update_strongEmotion_switches() {
        EmotionExpression ee = new EmotionExpression();
        EmotionState e = state(0.9f, 0f, 0f, 0f, 0.1f, 0.2f);
        assertEquals("F02", ee.update(e, 0L));
        assertEquals("F02", ee.current());
    }

    @Test
    public void update_respectsMinDwell() {
        EmotionExpression ee = new EmotionExpression();
        assertEquals("F02", ee.update(state(0.9f, 0f, 0f, 0f, 0.1f, 0.1f), 0L));
        EmotionState sad = state(0f, 0.9f, 0f, 0f, 0.1f, 0.1f);
        // 停留期内不切
        assertNull(ee.update(sad, EmotionExpression.MIN_DWELL_MS - 1));
        assertEquals("F02", ee.current());
        // 到点后允许切
        assertEquals("F03", ee.update(sad, EmotionExpression.MIN_DWELL_MS));
    }

    @Test
    public void update_hysteresisBlocksCloseSwitch() {
        EmotionExpression ee = new EmotionExpression();
        assertEquals("F02", ee.update(state(0.9f, 0f, 0f, 0f, 0.1f, 0.1f), 0L));
        // sad .65 领先 happy .60，但差距 < SWITCH_MARGIN .12 → 迟滞挡住
        EmotionState close = state(0.6f, 0.65f, 0f, 0f, 0.1f, 0f);
        assertNull(ee.update(close, EmotionExpression.MIN_DWELL_MS));
        assertEquals("F02", ee.current());
        // 差距拉开到 .25 后放行
        EmotionState clear = state(0.6f, 0.85f, 0f, 0f, 0.1f, 0f);
        assertEquals("F03", ee.update(clear, EmotionExpression.MIN_DWELL_MS));
    }

    // ---------- 交互覆盖对齐 ----------

    @Test
    public void syncCurrent_blocksImmediateOverride() {
        EmotionExpression ee = new EmotionExpression();
        ee.syncCurrent("F04", 1000L);
        assertEquals("F04", ee.current());
        // 同步后 1 秒内不抢回平静
        assertNull(ee.update(new EmotionState(), 1500L));
        // 过了最短停留即可回落
        assertEquals("F01", ee.update(new EmotionState(), 1000L + EmotionExpression.MIN_DWELL_MS));
    }

    // ---------- 静态映射 ----------

    @Test
    public void expressionFor_mapsDimensions() {
        assertEquals("F01", EmotionExpression.expressionFor("calm"));
        assertEquals("F02", EmotionExpression.expressionFor("happy"));
        assertEquals("F02", EmotionExpression.expressionFor("excited"));
        assertEquals("F03", EmotionExpression.expressionFor("sad"));
        assertEquals("F04", EmotionExpression.expressionFor("angry"));
        assertEquals("F05", EmotionExpression.expressionFor("surprised"));
        assertEquals("F06", EmotionExpression.expressionFor("shy"));
        assertEquals("F01", EmotionExpression.expressionFor(null));
    }

    @Test
    public void dimOf_mapsExpressions() {
        assertEquals("calm", EmotionExpression.dimOf("F01"));
        assertEquals("happy", EmotionExpression.dimOf("F02"));
        assertEquals("sad", EmotionExpression.dimOf("F03"));
        assertEquals("angry", EmotionExpression.dimOf("F04"));
        assertEquals("surprised", EmotionExpression.dimOf("F05"));
        assertEquals("shy", EmotionExpression.dimOf("F06"));
        assertEquals("calm", EmotionExpression.dimOf(null));
        assertEquals("calm", EmotionExpression.dimOf("unknown"));
    }

    @Test
    public void motionFor_mapsExpressions() {
        assertNull(EmotionExpression.motionFor("F01"));
        assertEquals("happy", EmotionExpression.motionFor("F02"));
        assertEquals("sad", EmotionExpression.motionFor("F03"));
        assertNull(EmotionExpression.motionFor("F04"));
        assertEquals("surprised", EmotionExpression.motionFor("F05"));
        assertEquals("tilt_head", EmotionExpression.motionFor("F06"));
        assertNull(EmotionExpression.motionFor(null));
    }
}
