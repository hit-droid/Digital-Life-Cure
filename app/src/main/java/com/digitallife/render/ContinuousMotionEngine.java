package com.digitallife.render;

import java.util.Random;

/**
 * L1 程序化连续微动作引擎（GL 线程，逐帧调用）。
 *
 * 完全不依赖预设 .motion3.json，靠数学正弦波叠加 + 随机 Saccade 让模型
 * 在没人说话、没有指令时也保持自然的"活"感：
 *  - 多频正弦波：身体重心低频晃动 + 头部倾斜（模拟肌肉无意识微摆）
 *  - 呼吸波：低频 0.25Hz 驱动 ParamBreath
 *  - 眼球跳跃（Saccade）：每 1.5~5s 随机看向某个方向，视线平滑移动
 *  - 眨眼：随机间隔的离散快速眨眼
 *
 * 与 PhysicalController 的分工：
 *  - 头部/身体角度 → addAdditiveTarget（叠加到主动目标上，弹簧平滑到位，不覆盖 AI 意图）
 *  - 呼吸/视线/眨眼 → setDirectValue（直通 native，保留高频离散响应）
 *
 * 风格参数（energy/alertness/speed/amplitude）由 AICore 行为包/生理状态机写入，
 * 通过 volatile 保证跨线程可见。
 */
public class ContinuousMotionEngine {

    private float clock = 0f;
    private final Random rnd = new Random();

    // 正弦波相位
    private float bodyPhase = 0f;
    private float headPhaseY = 0f;
    private float headPhaseZ = 0f;
    private float breathPhase = 0f;

    // 视线 saccade
    private float targetEyeX = 0f;
    private float targetEyeY = 0f;
    private float currentEyeX = 0f;
    private float currentEyeY = 0f;
    private float nextSaccadeTime = 2f;

    // 眨眼状态机
    private float blinkTimer = 3f;
    private boolean blinking = false;
    private float blinkPhase = 0f;

    // 风格参数（跨线程 volatile）
    private volatile float energy = 0.5f;
    private volatile float alertness = 0.5f;
    private volatile float speed = 1f;
    private volatile float amplitude = 1f;
    /** 视线意图基准（AICore 行为包：注视/避开/漫游），saccade 在其上随机抖动 */
    private volatile float gazeBiasX = 0f;
    private volatile float gazeBiasY = 0f;
    /** 非待机动作播放中：让位，仅保留呼吸背景 */
    private volatile boolean actionPlaying = false;
    /**
     * 模型自带动作组（Idle 等）且动作文件齐全：L1 全让位，
     * 交给模型自带的动画/自动眨眼/自动呼吸（Cubism 引擎原生行为）。
     * 无动作模型（VTS 型）：L1 全量接管并调大幅度，营造活物感。
     */
    private volatile boolean modelHasMotions = false;

    /** v1.185.0：触摸跟手——手指在 View 内的归一化坐标（[-1,1]，右/上为正） */
    private volatile float touchGazeX = 0f;
    private volatile float touchGazeY = 0f;
    private volatile boolean touchGazeActive = false;
    /** GL 线程：上一帧是否处于跟手状态，用于识别「松手」那一刻做收尾 */
    private boolean touchGazeWasActive = false;

    /** 由 AICore 行为包/状态机更新（任意线程） */
    public void setStyle(float energy, float alertness, float speed, float amplitude) {
        this.energy = clamp01(energy);
        this.alertness = clamp01(alertness);
        this.speed = speed > 0f ? speed : 1f;
        this.amplitude = amplitude >= 0f ? amplitude : 1f;
    }

    public void setGazeBias(float x, float y) {
        this.gazeBiasX = x;
        this.gazeBiasY = y;
    }

    public void setActionPlaying(boolean playing) {
        this.actionPlaying = playing;
    }

    /**
     * v1.185.0：手指触摸期间让视线/头部跟手（任意线程）。
     *
     * @param nx     手指在 View 内的归一化 x，-1 最左、+1 最右，调用方可用
     *               {@link #normalizeGazeX(float, float)} 换算
     * @param ny     手指归一化 y，-1 最下、+1 最上
     * @param active false 表示松手，视线平滑回落到 saccade
     */
    public void setTouchGaze(float nx, float ny, boolean active) {
        touchGazeX = clamp(nx, -1f, 1f);
        touchGazeY = clamp(ny, -1f, 1f);
        touchGazeActive = active;
    }

    /** View 坐标 → 视线归一化 x（右为正）；宽度非法时回正前方 */
    public static float normalizeGazeX(float x, float width) {
        if (width <= 0f || Float.isNaN(x)) return 0f;
        return clamp(x / width * 2f - 1f, -1f, 1f);
    }

    /** View 坐标 → 视线归一化 y（上为正，屏幕 y 轴向下需翻转）；高度非法时回正前方 */
    public static float normalizeGazeY(float y, float height) {
        if (height <= 0f || Float.isNaN(y)) return 0f;
        return clamp(1f - y / height * 2f, -1f, 1f);
    }

    /** 由模型能力感知写入：true=模型自带动作动画，L1 角度/视线/眨眼让位 */
    public void setModelHasMotions(boolean hasMotions) {
        this.modelHasMotions = hasMotions;
    }

    /**
     * 必须在 GL 线程 onDrawFrame() 中每帧调用。
     */
    public void update(PhysicalController physics, float dt) {
        clock += dt;
        if (dt <= 0f) return;

        final boolean touching = touchGazeActive;
        if (touchGazeWasActive && !touching) {
            // 松手：清掉跟手期写下的头部叠加（additive 不会自动归零），
            // 视线交还 L1 的 saccade / 模型自带动作。L1 路径下一帧会自行重写，不会留坑。
            physics.addAdditiveTarget("ParamAngleX", 0f);
            physics.addAdditiveTarget("ParamAngleY", 0f);
            physics.setDirectValue("ParamEyeBallX", Float.NaN);
            physics.setDirectValue("ParamEyeBallY", Float.NaN);
            // 收起视线后别立刻随机乱瞟，给一个短暂的「回正」缓冲
            nextSaccadeTime = clock + 1f + rnd.nextFloat() * 1.5f;
        }
        touchGazeWasActive = touching;

        if (modelHasMotions) {
            // 模型自带动作动画 + Cubism 自动眨眼/呼吸：L1 全让位。
            // L2 生理状态机的主动姿态走 physics.setTarget，不依赖本引擎，不受影响。
            // 触摸跟手是用户主动交互，优先级高于待机动画，这里仍然生效。
            if (touching) updateTouchGaze(physics, dt);
            return;
        }

        float amp = amplitude * (0.35f + energy * 0.65f);

        if (actionPlaying) {
            // 动作播放中：角度/视线/眨眼让位，仅保留呼吸背景（跟手例外，见上）
            updateBreath(physics, dt);
            if (touching) updateTouchGaze(physics, dt);
            return;
        }

        updateBodyAndHead(physics, dt, amp);
        updateBreath(physics, dt);
        if (touching) {
            updateTouchGaze(physics, dt);
        } else {
            updateGaze(physics, dt, amp);
        }
        updateBlink(physics, dt);
    }

    /** 身体重心低频晃动 + 头部倾斜（叠加到弹簧目标） */
    private void updateBodyAndHead(PhysicalController physics, float dt, float amp) {
        float sp = speed;
        bodyPhase += dt * 1.9f * sp;
        headPhaseY += dt * 2.5f * sp;
        headPhaseZ += dt * 1.6f * sp;

        // 身体：0.3Hz 低频 + 0.8Hz 中频叠加，相位错开的不规则晃动
        // 幅度加大到峰值 ~3.5°*amp：无动作模型靠 L1 全量接管制造"活"感
        float body = (float) (Math.sin(bodyPhase * 0.3f * 2 * Math.PI) * 2.5f
                + Math.sin(bodyPhase * 0.8f * 2 * Math.PI) * 1.0f) * amp;

        // 头部：不同频率/相位的余弦波轻微倾斜（峰值 ~3.5° / ~2.5°）
        float headY = (float) Math.cos(headPhaseY * 0.4f * 2 * Math.PI) * 3.5f * amp;
        float headZ = (float) Math.sin(headPhaseZ * 0.25f * 2 * Math.PI) * 2.5f * amp;
        float headX = body * 0.6f;

        physics.addAdditiveTarget("ParamBodyAngleX", body);
        physics.addAdditiveTarget("ParamBodyAngleZ", body * 0.5f);
        physics.addAdditiveTarget("ParamAngleX", headX);
        // 警觉时头微抬（正值 = 抬头）
        physics.addAdditiveTarget("ParamAngleY", headY + alertness * 0.1f);
        physics.addAdditiveTarget("ParamAngleZ", headZ);
    }

    /** 呼吸波：0.25Hz（约 4 秒一次），深度受 energy 影响 */
    private void updateBreath(PhysicalController physics, float dt) {
        float deep = 0.3f + energy * 0.7f;
        float sp = 0.6f + energy * 0.4f * speed;
        breathPhase += dt * 1.6f * sp;
        float breathVal = 0.5f + deep * 0.5f * (float) Math.sin(breathPhase * 0.25f * 2 * Math.PI);
        physics.setDirectValue("ParamBreath", breathVal);
    }

    /** 眼球 Saccade：随机跳目标 + 平滑过渡（叠加行为包视线意图） */
    private void updateGaze(PhysicalController physics, float dt, float amp) {
        if (clock >= nextSaccadeTime) {
            if (rnd.nextFloat() < 0.7f) {
                float range = 0.3f + alertness * 0.3f;
                targetEyeX = gazeBiasX + (rnd.nextFloat() - 0.5f) * 2f * range;
                targetEyeY = gazeBiasY + (rnd.nextFloat() - 0.5f) * 1.5f * range;
            } else {
                // 偶尔收回视线看正前方
                targetEyeX = gazeBiasX;
                targetEyeY = gazeBiasY;
            }
            nextSaccadeTime = clock + 1.5f + rnd.nextFloat() * 3.5f;
        }
        float ease = Math.min(1f, dt * 6f);
        currentEyeX += (targetEyeX - currentEyeX) * ease;
        currentEyeY += (targetEyeY - currentEyeY) * ease;
        physics.setDirectValue("ParamEyeBallX", currentEyeX);
        physics.setDirectValue("ParamEyeBallY", currentEyeY);
    }

    /**
     * v1.185.0：触摸跟手——视线直接锁定手指方向，头部随手指小幅偏转。
     *
     * <p>eye 走直通（要跟手就不能被弹簧拖慢），头部走 additive（复用弹簧的回弹手感，
     * 松手后自然回正）。跟手期间压住 saccade 计时，避免眼睛在手指附近乱跳。</p>
     */
    private void updateTouchGaze(PhysicalController physics, float dt) {
        float tx = touchGazeX;
        float ty = touchGazeY;
        // 比 saccade 更快（时间常数 ~70ms），手指划动时视线才「黏」得住
        float ease = Math.min(1f, dt * 14f);
        currentEyeX += (tx - currentEyeX) * ease;
        currentEyeY += (ty - currentEyeY) * ease;
        physics.setDirectValue("ParamEyeBallX", currentEyeX);
        physics.setDirectValue("ParamEyeBallY", currentEyeY);

        // 头部跟随：屏幕边缘 ±7° / ±5°，正中间几乎不动
        physics.addAdditiveTarget("ParamAngleX", tx * 7f);
        physics.addAdditiveTarget("ParamAngleY", ty * 5f);

        // 指尖还在时保持注视，别被随机 saccade 抢走
        nextSaccadeTime = Math.max(nextSaccadeTime, clock + 0.8f);
    }

    /** 随机眨眼：警觉时更频繁 */
    private void updateBlink(PhysicalController physics, float dt) {
        float blinkInterval = 2.5f - alertness * 1.2f;
        if (!blinking) {
            blinkTimer -= dt;
            if (blinkTimer <= 0f) {
                blinking = true;
                blinkPhase = 0f;
                blinkTimer = blinkInterval + rnd.nextFloat() * 2f;
            }
            return;
        }
        blinkPhase += dt * 12f;
        float blinkVal;
        if (blinkPhase < 0.3f) {
            blinkVal = blinkPhase / 0.3f;
        } else if (blinkPhase < 0.4f) {
            blinkVal = 1f;
        } else if (blinkPhase < 0.7f) {
            blinkVal = 1f - (blinkPhase - 0.4f) / 0.3f;
        } else {
            blinkVal = 0f;
            blinking = false;
        }
        physics.setDirectValue("ParamEyeLOpen", 1f - blinkVal);
        physics.setDirectValue("ParamEyeROpen", 1f - blinkVal);
    }

    /** 重置状态（surface 重建时调用） */
    public void reset() {
        clock = 0f;
        bodyPhase = 0f;
        headPhaseY = 0f;
        headPhaseZ = 0f;
        breathPhase = 0f;
        targetEyeX = 0f;
        targetEyeY = 0f;
        currentEyeX = 0f;
        currentEyeY = 0f;
        nextSaccadeTime = 2f;
        blinkTimer = 3f;
        blinking = false;
        blinkPhase = 0f;
        touchGazeX = 0f;
        touchGazeY = 0f;
        touchGazeActive = false;
        touchGazeWasActive = false;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return 0f;
        return v < min ? min : (v > max ? max : v);
    }
}
