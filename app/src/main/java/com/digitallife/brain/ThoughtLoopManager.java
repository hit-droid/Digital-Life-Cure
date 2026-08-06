package com.digitallife.brain;

import com.digitallife.util.Settings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L3 心理独白引擎：桌宠的灵魂。
 *
 * 不需要用户发消息，每隔 8~12 分钟静默采集一次系统状态（时间/电量/前台应用/空闲时长）
 * 与生理状态（精力/无聊度/状态），构造世界观 prompt 调 LLM，得到一段
 * 「[动作标签] + 短文本」的内心独白，由接入方触发对应动作并展示气泡。
 *
 * 独立于 AICore 对话循环运行（自带 LLMClient），互不阻塞。
 * 未配置 API 或 stop() 后不启动。
 */
public class ThoughtLoopManager {

    /** 独白产出回调（接入方：触发动作 + 气泡） */
    public interface Listener {
        void onThought(String actionTag, String text);
    }

    private static final Pattern TAG = Pattern.compile("\\[([a-zA-Z]+)\\]");
    private static final long MIN_INTERVAL_MS = 8 * 60 * 1000L;
    private static final long MAX_INTERVAL_MS = 12 * 60 * 1000L;
    private static final long FIRST_DELAY_MS = 5 * 60 * 1000L;

    private final Settings settings;
    private final EnvironmentSensors sensors;
    private final PetVitalsManager vitals;
    private final Listener listener;

    private final Thread thread = new Thread(this::loop, "thought-loop");
    private volatile boolean running = false;
    private volatile long lastInteractionMs = System.currentTimeMillis();
    private LLMClient llm;

    public ThoughtLoopManager(Settings settings, EnvironmentSensors sensors,
                              PetVitalsManager vitals, Listener listener) {
        this.settings = settings;
        this.sensors = sensors;
        this.vitals = vitals;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        thread.start();
    }

    public void stop() {
        running = false;
        if (llm != null) llm.cancel();
        thread.interrupt();
    }

    /** 用户交互（任意线程）：重置空闲计时 */
    public void noteInteraction() {
        lastInteractionMs = System.currentTimeMillis();
    }

    private void loop() {
        try {
            Thread.sleep(FIRST_DELAY_MS);
            while (running) {
                long interval = MIN_INTERVAL_MS + (long) (Math.random() * (MAX_INTERVAL_MS - MIN_INTERVAL_MS));
                Thread.sleep(interval);
                if (!running) return;
                try {
                    thinkOnce();
                } catch (Throwable ignored) {
                }
            }
        } catch (InterruptedException e) {
            // 停止
        }
    }

    private void thinkOnce() {
        if (!settings.isConfigured()) return;
        if (llm == null) {
            llm = new LLMClient(settings.getApiBase(), settings.getApiKey(), settings.getModel());
        }

        int idleMin = (int) ((System.currentTimeMillis() - lastInteractionMs) / 60000L);
        EnvironmentSensors.SystemContext ctx = sensors.capture(idleMin);

        StringBuilder sb = new StringBuilder();
        sb.append("你是一只住在用户 Android 桌面上的 Live2D 数字生命。\n");
        sb.append("请以它的身份进行一次【内部心理独白与主动吐槽】，像在自言自语。\n\n");
        sb.append("[当前系统状态]\n");
        sb.append("时间: ").append(ctx.timeStr).append("\n");
        sb.append("电量: ").append(ctx.batteryPercent).append("%");
        sb.append(ctx.isCharging ? "（充电中）" : "").append("\n");
        sb.append("当前前台应用: ").append(ctx.foregroundApp).append("\n");
        sb.append("用户已空闲: ").append(idleMin).append(" 分钟\n");
        sb.append("生理状态: 精力=").append((int) vitals.getEnergy())
                .append(", 无聊度=").append((int) vitals.getBoredom())
                .append(", 状态=").append(vitals.getState()).append("\n\n");
        sb.append("输出格式（严格遵守）：必须以 [动作标签] 开头，后接一句 20 字以内的短文本。\n");
        sb.append("动作标签只能是 happy/sleepy/think/nod/shake/annoyed 之一。\n");
        sb.append("示例：[sleepy]都凌晨两点了...你还不打算睡觉吗？");

        llm.chatOnce(java.util.Collections.singletonList(
                new LLMClient.ChatMessage("user", sb.toString())), null, (text, err) -> {
            if (!running || text == null || text.trim().isEmpty()) return;
            String tag = "think";
            Matcher m = TAG.matcher(text);
            if (m.find()) tag = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String clean = text.replaceAll("\\[[^\\]]*\\]", "").trim();
            if (clean.isEmpty()) return;
            if (listener != null) listener.onThought(tag, clean);
        });
    }
}
