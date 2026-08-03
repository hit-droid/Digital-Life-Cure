package com.maiden.pet.brain;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * 心跳循环：本地行为调度器。
 * 每 tick（约 8 秒）检查一次环境状态，决定角色是否需要自主行为：
 * 定时问候、长时间无互动时刷存在感、随机微动作。
 * 这是「每分每秒实时在线」的本地载体，不依赖网络与 LLM。
 */
public class Heartbeat {

    public interface Listener {
        /** 触发一次自主互动（可调用 LLM 主动说话） */
        void onProactiveInteraction(String reason);
        /** 触发一次低成本的本地微行为（不调 LLM） */
        void onAmbientBehavior();
        /** 心跳内嵌的时钟事件（如整点问候） */
        void onClockTick(String message);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Random rnd = new Random();

    private long lastInteractionTime = System.currentTimeMillis();
    private int lastHour = -1;
    private boolean running = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tick();
            handler.postDelayed(this, 8000);
        }
    };

    public Heartbeat(Listener l) {
        this.listener = l;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.postDelayed(ticker, 8000);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(ticker);
    }

    /** 用户主动互动时调用，重置「冷落计时」 */
    public void onUserInteraction() {
        lastInteractionTime = System.currentTimeMillis();
    }

    private void tick() {
        if (listener == null) return;

        // 1) 整点问候（仅一次）
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour != lastHour) {
            lastHour = hour;
            String msg = greetingFor(hour);
            if (listener != null) listener.onClockTick(msg);
            return;
        }

        // 2) 长时间无互动：偶尔主动刷存在感（降低频率，避免打扰）
        long idleMs = System.currentTimeMillis() - lastInteractionTime;
        if (idleMs > 15 * 60 * 1000 && rnd.nextInt(10) == 0) {
            String[] prompts = {"伸个懒腰看看你在做什么~", "呼……有点无聊呢，要不要说说话？", "在忙吗？我就在这儿陪着你哦"};
            if (listener != null) {
                String msg = prompts[rnd.nextInt(prompts.length)];
                // 一半概率走 LLM，一半本地微行为
                if (rnd.nextBoolean()) {
                    listener.onProactiveInteraction(msg);
                } else {
                    listener.onAmbientBehavior();
                }
            }
            lastInteractionTime = System.currentTimeMillis();
            return;
        }

        // 3) 常规：本地微行为（呼吸、小动作、偶尔视线变化）
        if (rnd.nextInt(3) == 0) {
            if (listener != null) listener.onAmbientBehavior();
        }
    }

    private String greetingFor(int hour) {
        if (hour >= 5 && hour < 9) return "早安~ 新的一天，我在桌面上陪着你哦！";
        if (hour >= 9 && hour < 12) return "上午好呀，工作/学习加油！";
        if (hour >= 12 && hour < 14) return "中午好，记得按时吃饭哦~";
        if (hour >= 14 && hour < 18) return "下午好，要不要休息一下呀？";
        if (hour >= 18 && hour < 23) return "晚上好~ 今天的你辛苦啦！";
        return "夜深了，要记得早点休息呀，我会守着你的。";
    }
}
