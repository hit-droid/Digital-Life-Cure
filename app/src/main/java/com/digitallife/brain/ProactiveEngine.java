package com.digitallife.brain;

import android.content.Context;
import android.content.SharedPreferences;

import com.digitallife.notify.AgentNotifier;
import com.digitallife.service.PetService;

import java.util.Calendar;
import java.util.Random;

/**
 * 主动行为引擎（v1.24.0）。
 * 借鉴 Operit AI 的 proactive tick + context-aware nudges 设计。
 * 周期性巡检，根据时间/情绪/未读时长/记忆事件触发主动行为。
 */
public class ProactiveEngine {

    public static final long INTERVAL_MS = 30 * 60 * 1000L; // 30 分钟一次
    public static final long QUIET_HOUR_START = 23;
    public static final long QUIET_HOUR_END = 8;
    public static final long IDLE_BUBBLE_MS = 2 * 60 * 60 * 1000L; // 2 小时未对话就冒泡
    public static final int MAX_PER_DAY = 6; // 每天最多主动行为 6 次

    private final Context appCtx;
    private final SharedPreferences sp;
    private final Random random = new Random();

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
            com.digitallife.service.PetService svc =
                    com.digitallife.service.PetService.getInstance();
            if (svc != null) {
                svc.showProactiveBubble(msg);
            }
            AgentNotifier.notify(appCtx, "小汐", msg);

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
}
