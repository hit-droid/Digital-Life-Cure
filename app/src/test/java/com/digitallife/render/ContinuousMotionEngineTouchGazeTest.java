package com.digitallife.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v1.185.0「触摸眼神跟手」的契约：
 * 手指按下时视线直接锁定手指方向、头部小幅跟随；松手后视线交还 saccade，
 * 跟手期写下的头部叠加必须清零（否则模型会永久歪着头）。
 */
public class ContinuousMotionEngineTouchGazeTest {

    private static final float EPS = 1e-4f;

    // ==================== 坐标归一化 ====================

    @Test
    public void normalizeX_centerLeftRight() {
        assertEquals(0f, ContinuousMotionEngine.normalizeGazeX(50f, 100f), EPS);
        assertEquals(-1f, ContinuousMotionEngine.normalizeGazeX(0f, 100f), EPS);
        assertEquals(1f, ContinuousMotionEngine.normalizeGazeX(100f, 100f), EPS);
    }

    @Test
    public void normalizeX_clampsOutOfBounds() {
        assertEquals(-1f, ContinuousMotionEngine.normalizeGazeX(-40f, 100f), EPS);
        assertEquals(1f, ContinuousMotionEngine.normalizeGazeX(180f, 100f), EPS);
    }

    @Test
    public void normalizeX_invalidWidthOrNaN_returnsCenter() {
        assertEquals(0f, ContinuousMotionEngine.normalizeGazeX(30f, 0f), EPS);
        assertEquals(0f, ContinuousMotionEngine.normalizeGazeX(30f, -10f), EPS);
        assertEquals(0f, ContinuousMotionEngine.normalizeGazeX(Float.NaN, 100f), EPS);
    }

    @Test
    public void normalizeY_flipsScreenAxis() {
        // 屏幕上沿（y=0）是视线向上 +1，下沿是 -1
        assertEquals(1f, ContinuousMotionEngine.normalizeGazeY(0f, 100f), EPS);
        assertEquals(0f, ContinuousMotionEngine.normalizeGazeY(50f, 100f), EPS);
        assertEquals(-1f, ContinuousMotionEngine.normalizeGazeY(100f, 100f), EPS);
        assertEquals(0f, ContinuousMotionEngine.normalizeGazeY(40f, 0f), EPS);
    }

    // ==================== 跟手 / 松手 ====================

    @Test
    public void touchActive_eyeLocksToFinger() {
        ContinuousMotionEngine engine = new ContinuousMotionEngine();
        PhysicalController physics = new PhysicalController();

        engine.setTouchGaze(1f, 1f, true);
        engine.update(physics, 0.2f); // ease = min(1, 2.8) = 1，一帧到位

        float[] direct = physics.directValues();
        assertEquals(1f, direct[physics.indexOfDirect("ParamEyeBallX")], EPS);
        assertEquals(1f, direct[physics.indexOfDirect("ParamEyeBallY")], EPS);
    }

    @Test
    public void touchActive_headFollowsFingerWithBoundedOffset() {
        ContinuousMotionEngine engine = new ContinuousMotionEngine();
        PhysicalController physics = new PhysicalController();

        engine.setTouchGaze(1f, 1f, true);
        float[] values = null;
        for (int i = 0; i < 30; i++) {
            engine.update(physics, 0.016f);
            values = physics.update(0.016f);
        }

        // 头部 additive 最多 ±7°/±5°，弹簧积分后方向必须为正、且不会超过目标太多
        int angleX = physics.indexOf("ParamAngleX");
        int angleY = physics.indexOf("ParamAngleY");
        assertTrue(values[angleX] > 0f);
        assertTrue(values[angleX] <= 8f);
        assertTrue(values[angleY] > 0f);
        assertTrue(values[angleY] <= 6f);
    }

    @Test
    public void touchGaze_clampsAndIgnoresNaN() {
        ContinuousMotionEngine engine = new ContinuousMotionEngine();
        PhysicalController physics = new PhysicalController();

        engine.setTouchGaze(5f, -5f, true);
        engine.update(physics, 0.2f);
        float[] direct = physics.directValues();
        assertEquals(1f, direct[physics.indexOfDirect("ParamEyeBallX")], EPS);
        assertEquals(-1f, direct[physics.indexOfDirect("ParamEyeBallY")], EPS);

        engine.setTouchGaze(Float.NaN, Float.NaN, true);
        engine.update(physics, 0.2f);
        // NaN 归一化到正前方，不会把脏值写进 native
        assertFalse(Float.isNaN(direct[physics.indexOfDirect("ParamEyeBallX")]));
        assertEquals(0f, direct[physics.indexOfDirect("ParamEyeBallX")], EPS);
    }

    @Test
    public void release_clearsHeadAdditiveForMotionModel() {
        ContinuousMotionEngine engine = new ContinuousMotionEngine();
        PhysicalController physics = new PhysicalController();
        // 模型自带动作：L1 让位，跟手期写下的 additive 不会每帧被重写，正好验证收尾是否干净
        engine.setModelHasMotions(true);
        // 让两个角度参数进入弹簧接管，松手后能直接读回数值（否则未接管输出 NaN）
        physics.setTarget("ParamAngleX", 0f);
        physics.setTarget("ParamAngleY", 0f);

        engine.setTouchGaze(-1f, 1f, true);
        for (int i = 0; i < 10; i++) {
            engine.update(physics, 0.016f);
            physics.update(0.016f);
        }
        float[] values = physics.update(0.016f);
        assertTrue(values[physics.indexOf("ParamAngleX")] < 0f);

        engine.setTouchGaze(0f, 0f, false);
        engine.update(physics, 0.016f);
        float[] after = physics.update(0.016f);
        // 松手瞬间还在回弹，但叠加目标已经清零；多跑几帧必须收敛回 0
        for (int i = 0; i < 200; i++) {
            after = physics.update(0.016f);
        }
        assertEquals(0f, after[physics.indexOf("ParamAngleX")], EPS);
        assertEquals(0f, after[physics.indexOf("ParamAngleY")], EPS);
        // 松手后视线参数交还模型自带动作（NaN = native 跳过）
        assertTrue(Float.isNaN(physics.directValues()[physics.indexOfDirect("ParamEyeBallX")]));
        assertTrue(Float.isNaN(physics.directValues()[physics.indexOfDirect("ParamEyeBallY")]));
    }

    @Test
    public void release_doesNotJumpImmediately() {
        ContinuousMotionEngine engine = new ContinuousMotionEngine();
        PhysicalController physics = new PhysicalController();
        engine.setModelHasMotions(true);

        engine.setTouchGaze(1f, 0f, true);
        engine.update(physics, 0.2f);
        engine.setTouchGaze(0f, 0f, false);
        engine.update(physics, 0.2f);
        // 刚松手不会立刻随机乱瞟：一个 tick 内仍是 NaN（模型自带动作接管）
        assertTrue(Float.isNaN(physics.directValues()[physics.indexOfDirect("ParamEyeBallX")]));
    }

    @Test
    public void reset_clearsTouchState() {
        ContinuousMotionEngine engine = new ContinuousMotionEngine();
        PhysicalController physics = new PhysicalController();

        engine.setTouchGaze(1f, 1f, true);
        engine.update(physics, 0.016f);
        engine.reset();
        engine.update(physics, 0.016f);

        // reset 后不再跟手，走 saccade：视线值有限且不会再被钉在 1
        float eyeX = physics.directValues()[physics.indexOfDirect("ParamEyeBallX")];
        assertFalse(Float.isNaN(eyeX));
        assertTrue(Math.abs(eyeX) < 0.9f);
    }
}
