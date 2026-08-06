package com.digitallife.brain;

import java.util.Calendar;

/**
 * L2 生理/心理状态机（Vitals System）。
 *
 * 极轻量级：由桌宠服务每秒 tick 一次，演化精力/无聊度/情绪三个生理指标，
 * 依据时间（深夜困倦）与交互（触摸回神）做状态转移。
 *
 * 状态变化通过 Listener 回调，由接入方映射为 Live2D 姿态目标/动作，
 * 全程本地决策、不耗网络。数值仅供「动机」参考，渲染细节由 AICore 行为包与 L1 引擎负责。
 */
public class PetVitalsManager {

    /** 状态变化回调（接入方负责映射为模型姿态） */
    public interface Listener {
        void onStateChanged(PetState state);
    }

    private final Listener listener;

    /** 精力 0~100：随白天消耗、深夜加速下降，触摸/互动补充 */
    private float energy = 100f;
    /** 无聊度 0~100：无人互动随时间上涨，互动后大幅回落 */
    private float boredom = 0f;
    /** 情绪 0~100：默认 50，互动上升，长时间冷落下降 */
    private float mood = 50f;

    private PetState currentState = PetState.IDLE;
    private long lastEnergyGainMs = 0L;

    public PetVitalsManager(Listener listener) {
        this.listener = listener;
    }

    public synchronized void tick(int hourOfDay, boolean isUserTouching) {
        boolean night = hourOfDay >= 23 || hourOfDay < 6;

        // 精力：白天缓慢下降，深夜加速下降；每 10 分钟无消耗时回一点（模拟休息）
        float energyDecay = night ? 0.08f : 0.02f;
        energy = Math.max(0f, energy - energyDecay);
        long now = System.currentTimeMillis();
        if (!isUserTouching && now - lastEnergyGainMs > 10 * 60 * 1000L) {
            energy = Math.min(100f, energy + 0.5f);
            lastEnergyGainMs = now;
        }

        // 无聊度 / 情绪：触摸回神，冷落积累
        if (isUserTouching) {
            boredom = Math.max(0f, boredom - 2.0f);
            mood = Math.min(100f, mood + 0.5f);
        } else {
            boredom = Math.min(100f, boredom + 0.15f);
            mood = Math.max(0f, mood - 0.02f);
        }

        // 状态转移（优先级：犯困 > 无聊 > 生气 > 开心 > 发呆）
        PetState newState;
        if (energy < 20f) {
            newState = PetState.SLEEPY;
        } else if (boredom > 75f) {
            newState = PetState.BORED;
        } else if (mood < 20f) {
            newState = PetState.ANNOYED;
        } else if (mood > 80f) {
            newState = PetState.HAPPY;
        } else {
            newState = PetState.IDLE;
        }

        if (newState != currentState) {
            currentState = newState;
            if (listener != null) {
                listener.onStateChanged(newState);
            }
        }
    }

    /** 用户互动：回神、减无聊 */
    public synchronized void noteInteraction() {
        boredom = Math.max(0f, boredom - 5f);
        mood = Math.min(100f, mood + 1f);
        if (energy < 60f) energy = Math.min(100f, energy + 0.5f);
    }

    public synchronized PetState getState() {
        return currentState;
    }

    public synchronized float getEnergy() {
        return energy;
    }

    public synchronized float getBoredom() {
        return boredom;
    }

    public synchronized float getMood() {
        return mood;
    }

    /** 当前时间的小时数（状态机 tick 用） */
    public static int currentHour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }
}
