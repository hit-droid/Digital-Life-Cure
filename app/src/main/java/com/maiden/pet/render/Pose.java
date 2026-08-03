package com.maiden.pet.render;

import java.util.Random;

/**
 * 角色姿态控制器：管理角色当前的表情、动作、情绪状态与视觉参数。
 * 渲染引擎只读取该对象的状态值，实现「表现层」与「决策层」解耦。
 */
public class Pose {
    public static final String[] EMOTIONS = {"neutral", "happy", "sad", "angry", "surprised", "shy", "excited", "sleepy"};
    public static final String[] ACTIONS = {"idle", "wave", "clap", "point", "tilt_head", "stretch", "bounce", "sit"};

    /** 当前表情 / 动作 / 目标表情（平滑过渡用） */
    public String emotion = "neutral";
    public String action = "idle";
    public String targetEmotion = "neutral";

    /** 情绪强度 0~1，影响动作幅度 */
    public float intensity = 0.5f;

    /** 口型开合度 0~1（说话时随音量/音素驱动） */
    public float mouth = 0f;

    /** 眨眼参数：0=睁眼，1=闭眼 */
    public float blink = 0f;
    private float blinkTimer = 0f;

    /** 视线：-1~1，注视点偏移 */
    public float gazeX = 0f;
    public float gazeY = 0f;

    /** 身体呼吸相位（内部自动更新） */
    public float breathPhase = 0f;

    /** 是否处于「活跃/互动」状态（影响帧率与动画强度） */
    public boolean active = false;

    /** 是否正在说话 */
    public boolean speaking = false;

    private final Random rnd = new Random();

    /** 每帧调用，更新眨眼计时与呼吸相位 */
    public void update(float dt) {
        breathPhase += dt * 2.0f;
        if (!active) {
            blinkTimer -= dt;
            if (blinkTimer <= 0f) {
                blink = 1f;
                blinkTimer = 0.08f + rnd.nextFloat() * 0.05f;
            } else if (blinkTimer < 0.06f) {
                blink = 0f;
                blinkTimer = 2.0f + rnd.nextFloat() * 3.5f;
            }
        } else {
            blink = Math.max(0f, blink - dt * 4f);
        }
        if (speaking) {
            mouth = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(breathPhase * 3.5f + rnd.nextFloat() * 0.3f));
        } else {
            mouth = Math.max(0f, mouth - dt * 3f);
        }
    }

    /** 设置表情并触发平滑过渡 */
    public void setEmotion(String e) {
        if (e == null) return;
        for (String s : EMOTIONS) if (s.equals(e)) { emotion = s; return; }
    }

    /** 设置动作 */
    public void setAction(String a) {
        if (a == null) return;
        for (String s : ACTIONS) if (s.equals(a)) { action = s; return; }
    }
}
