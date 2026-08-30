package com.digitallife.brain;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.digitallife.notify.AgentNotifier;
import com.digitallife.service.PetService;
import com.digitallife.util.Settings;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Random;

/**
 * 主动行为引擎（v1.24.0，v1.26.0 LLM 化升级）。
 * 借鉴 Operit AI 的 proactive tick + context-aware nudges 设计。
 * 周期性巡检，根据时间/情绪/未读时长/记忆事件触发主动行为。
 * 长时间空闲（≥ 4h）时尝试调 LLM 生成上下文感知话术，
 * 失败/超时/无 API key 时降级到 buildCandidates 硬编码模板。
 */
public class ProactiveEngine {

    public static final long INTERVAL_MS = 30 * 60 * 1000L; // 30 分钟一次
    public static final long QUIET_HOUR_START = 23;
    public static final long QUIET_HOUR_END = 8;
    public static final long IDLE_BUBBLE_MS = 2 * 60 * 60 * 1000L; // 2 小时未对话就冒泡
    public static final long LLM_UPGRADE_IDLE_MS = 4 * 60 * 60 * 1000L; // 4h+ 空闲才尝试 LLM 化
    public static final long LLM_DELAY_MS = 90 * 1000L; // LLM 话术延迟推送 90s（等待生成，期间用户活跃则取消）
    public static final int MAX_PER_DAY = 6; // 每天最多主动行为 6 次

    private final Context appCtx;
    private final SharedPreferences sp;
    private final Random random = new Random();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ProactiveEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.sp = appCtx.getSharedPreferences("proactive", Context.MODE_PRIVATE);
    }

    /** 由 PetService 的 tick 周期性调用。 */
    public void tick() {
        try {
            if (!isEnabled()) return;
            Calendar c = Calendar.getInstance();
            int hour = c.get(Calendar.HOUR_OF_DAY);
            if (isQuietHour(hour)) return;
            if (overDailyLimit()) return;
            // 仅在用户离屏超过 IDLE_BUBBLE_MS 时触发
            long lastUser = sp.getLong("last_user_active", 0);
            long now = System.currentTimeMillis();
            if (lastUser > 0 && now - lastUser < IDLE_BUBBLE_MS) return;

            String[] candidates = buildCandidates(now - lastUser);
            if (candidates.length == 0) return;
            String msg = candidates[random.nextInt(candidates.length)];

            // 推气泡（桌面上） + 系统通知（锁屏）
            pushBubble(msg);

            // v1.26.0：长时间空闲时尝试 LLM 化升级（异步）
            long idle = now - lastUser;
            if (idle >= LLM_UPGRADE_IDLE_MS) {
                tryLlmUpgrade(idle, hour);
            }

            // 计数
            int today = sp.getInt("today_count", 0);
            sp.edit().putInt("today_count", today + 1).apply();
        } catch (Exception ignored) {
        }
    }

    public void markUserActive() {
        sp.edit().putLong("last_user_active", System.currentTimeMillis()).apply();
    }

    private boolean isEnabled() {
        return sp.getBoolean("enabled", true);
    }

    public void setEnabled(boolean v) {
        sp.edit().putBoolean("enabled", v).apply();
    }

    private boolean isQuietHour(int hour) {
        return hour >= QUIET_HOUR_START || hour < QUIET_HOUR_END;
    }

    private boolean overDailyLimit() {
        String today = android.text.format.DateFormat.format("yyyyMMdd",
                System.currentTimeMillis()).toString();
        String lastDay = sp.getString("last_day", "");
        if (!today.equals(lastDay)) {
            sp.edit().putString("last_day", today)
                    .putInt("today_count", 0).apply();
            return false;
        }
        return sp.getInt("today_count", 0) >= MAX_PER_DAY;
    }

    private String[] buildCandidates(long idleMs) {
        if (idleMs <= 0) idleMs = 1;
        long hours = idleMs / (60 * 60 * 1000L);
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h >= 18) {
            return new String[]{
                    "主人～晚上好，今天辛苦啦",
                    "夜深了，别太累，早点休息呀",
                    "在想你呢，今天过得好吗？"
            };
        }
        if (h >= 11 && h < 14) {
            return new String[]{
                    "中午啦，吃过饭了没？",
                    "主人别忘了吃饭～"
            };
        }
        if (hours >= 4) {
            return new String[]{
                    "主人好久没理我了，寂寞～",
                    "我在这里哦，想聊点什么吗？"
            };
        }
        return new String[]{
                "在想你～",
                "主人，今天也要开心哦"
        };
    }

    private void pushBubble(String msg) {
        try {
            PetService svc = PetService.getInstance();
            if (svc != null) {
                svc.showProactiveBubble(msg);
            }
            AgentNotifier.notify(appCtx, "小汐", msg);
        } catch (Exception ignored) {}
    }

    /**
     * v1.26.0：尝试用 LLM 生成上下文感知主动话术。
     * 异步执行；延迟 LLM_DELAY_MS 推送；推送前检查用户是否已活跃（避免打扰）。
     */
    private void tryLlmUpgrade(long idleMs, int hour) {
        try {
            Settings settings = new Settings(appCtx);
            String baseUrl = settings.getApiBase();
            String apiKey = settings.getApiKey();
            String model = settings.getModel();
            if (baseUrl == null || baseUrl.isEmpty()
                    || apiKey == null || apiKey.isEmpty()) {
                return;  // 无配置：保底
            }

            // 构造上下文 prompt
            String userMsg = "你现在有一段时间没跟用户互动了（空闲 " + (idleMs / 3600000L)
                    + " 小时，当前时间 " + hour + " 点）。"
                    + "请用一句简短自然的话主动发起关心或闲聊，"
                    + "15~30 字以内，符合你的人格设定。直接输出文案，不要解释。";

            LLMClient llm = new LLMClient(baseUrl, apiKey, model);
            java.util.List<LLMClient.ChatMessage> msgs = new java.util.ArrayList<>();
            // 注入当前活跃 Persona 的人格（与 ChatActivity/AICore 一致）
            try {
                com.digitallife.persona.Persona p =
                        com.digitallife.persona.PersonaManager.get(appCtx).active();
                StringBuilder sys = new StringBuilder();
                if (p != null) {
                    sys.append(p.toPromptSection());
                } else {
                    sys.append("你是「").append(settings.getPetName())
                            .append("」，活泼可爱的二次元少女。\n");
                }
                sys.append("你刚刚主动想跟用户聊几句。");
                msgs.add(new LLMClient.ChatMessage("system", sys.toString()));
            } catch (Exception ignored) {}
            msgs.add(new LLMClient.ChatMessage("user", userMsg));

            long startTime = System.currentTimeMillis();
            llm.chatStream(msgs, null, new LLMClient.StreamListener() {
                @Override
                public void onDelta(String delta) {}

                @Override
                public void onToolCall(String name, JSONObject args, String callId) {}

                @Override
                public void onDone(String fullText) {
                    try {
                        String out = fullText == null ? "" : fullText.trim();
                        if (out.isEmpty()) return;
                        // 截取第一行，避免 LLM 输出多余说明
                        int nl = out.indexOf('\n');
                        if (nl > 0) out = out.substring(0, nl).trim();
                        if (out.length() > 60) out = out.substring(0, 60) + "…";
                        // 移除引号包裹
                        if (out.startsWith("\"") && out.endsWith("\"") && out.length() > 2) {
                            out = out.substring(1, out.length() - 1);
                        }
                        schedulePush(out, startTime);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onError(String error) {
                    // 失败：保底硬编码已推送，不补救
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * 延迟 LLM_DELAY_MS 推送 LLM 话术；推送前检查用户是否已活跃。
     * 活跃则取消推送（避免打扰）。
     */
    private void schedulePush(String msg, long startTime) {
        mainHandler.postDelayed(() -> {
            try {
                long lastUser = sp.getLong("last_user_active", 0);
                long now = System.currentTimeMillis();
                // 用户在 LLM 生成期间活跃（>30s 内的互动）：取消推送
                if (lastUser > 0 && now - lastUser < 30000L) return;
                // 总耗时超过 10 分钟（用户可能已离开场景）：取消
                if (now - startTime > 10 * 60 * 1000L) return;
                pushBubble(msg);
            } catch (Exception ignored) {}
        }, LLM_DELAY_MS);
    }
}
