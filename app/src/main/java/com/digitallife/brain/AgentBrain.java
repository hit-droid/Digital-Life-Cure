package com.digitallife.brain;

import com.digitallife.util.MemoryStore;
import com.digitallife.util.Settings;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AgentBrain：角色的「大脑」。
 * 负责组装 system prompt（人格+情绪+记忆）、维护对话上下文、
 * 发起 LLM 流式请求、解析工具调用并执行、把结果回填给表现层。
 */
public class AgentBrain {

    public interface OutputListener {
        /** 流式文本增量（可以逐字上屏） */
        void onTextDelta(String delta);
        /** 完整回答完成 */
        void onReplyComplete(String fullText);
        /** 开始说话（语音） */
        void onSpeakRequest(String text);
        /** 表现层指令：表情/动作/移动 */
        void onExpression(String emotion, float intensity);
        void onAnimation(String anim);
        void onMove(float x, float y);
        /** 错误 */
        void onError(String error);
        /** 请求状态（开始思考/思考结束） */
        void onThinking(boolean thinking);
    }

    private final LLMClient llm;
    private final Tools tools = new Tools();
    private final MemoryStore memory;
    private final EmotionState emotion = new EmotionState();
    private final Settings settings;

    private OutputListener listener;
    private boolean busy = false;
    private volatile boolean cancelled = false;

    private final List<LLMClient.ChatMessage> pendingToolMessages = new ArrayList<>();
    private static final long SUMMARY_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    public AgentBrain(Settings settings) {
        this.settings = settings;
        this.llm = new LLMClient(settings.getApiBase(), settings.getApiKey(), settings.getModel());
        this.memory = loadMemory();
        // 挂载扩展工具（20+）
        com.digitallife.tools.BuiltinTools.install(tools, settings.getContext().getApplicationContext());
        // 注册默认 Hook Runner 钩子
        com.digitallife.tools.HookRunner.getInstance().addPreHook(
                new com.digitallife.tools.HookRunner.PreHook() {
                    @Override
                    public String preCall(String toolName, org.json.JSONObject args) {
                        if (com.digitallife.ui.ToolMarketActivity.isDisabled(
                                settings.getContext(), toolName)) {
                            return "工具已被用户禁用：" + toolName;
                        }
                        return null;
                    }
                });
        com.digitallife.tools.HookRunner.getInstance().addPostHook(
                new com.digitallife.tools.HookRunner.PostHook() {
                    @Override
                    public String postCall(String toolName, org.json.JSONObject args,
                                           String result, String error) {
                        com.digitallife.tools.ToolUsageLog log =
                                new com.digitallife.tools.ToolUsageLog(settings.getContext());
                        com.digitallife.tools.ToolUsageLog.Entry e =
                                new com.digitallife.tools.ToolUsageLog.Entry();
                        e.timestamp = System.currentTimeMillis();
                        e.toolName = toolName;
                        e.args = args != null ? args.toString() : "";
                        e.result = result;
                        e.error = error;
                        e.durationMs = 0;
                        log.add(e);
                        return null;
                    }
                });
        this.llm.setTools(tools.toJsonArray());
    }

    private static final String KEY_EMOTION = "emotion_json";

    private MemoryStore loadMemory() {
        return new MemoryStore(settings.getContext());
    }

    public void persist() {
    }

    public void setListener(OutputListener l) {
        this.listener = l;
        wireToolListeners();
    }

    private void wireToolListeners() {
        tools.setExpressionListener((e, i) -> {
            if (listener != null) listener.onExpression(e, i);
        });
        tools.setAnimationListener(a -> {
            if (listener != null) listener.onAnimation(a);
        });
        tools.setSpeakListener(text -> {
            if (listener != null) listener.onSpeakRequest(text);
        });
        tools.setTimeListener(this::currentTime);
        tools.setMoveListener((x, y) -> {
            if (listener != null) listener.onMove(x, y);
        });
    }

    private String currentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
    }

    public MemoryStore getMemory() { return memory; }
    public EmotionState getEmotion() { return emotion; }
    public Tools getTools() { return tools; }
    public boolean isBusy() { return busy; }

    /** 用户消息入口：维护上下文 + 发起对话 */
    public void userSays(String text) {
        if (busy) {
            if (listener != null) listener.onError("她还在想事情呢，稍等一下哦");
            return;
        }
        if (text == null || text.trim().isEmpty()) return;
        memory.addUserMessage(text.trim());
        startChat();
    }

    /** 主动发起互动（由心跳循环调用） */
    public void proactiveSays() {
        if (busy) return;
        startChat();
    }

    private void startChat() {
        busy = true;
        cancelled = false;
        if (listener != null) listener.onThinking(true);

        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        for (MemoryStore.Message m : memory.getContext()) {
            msgs.add(new LLMClient.ChatMessage(m.role, m.content));
        }
        // 首次对话时补充一条"开始互动"引导
        JSONObject extra = new JSONObject();
        try {
            extra.put("system", buildSystemPrompt());
            extra.put("tools_desc", tools.describe());
        } catch (Exception ignored) {
        }

        llm.chatStream(msgs, extra, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String text) {
                if (listener != null) listener.onTextDelta(text);
            }

            @Override
            public void onToolCall(String name, JSONObject args, String toolCallId) {
                executeToolAndContinue(name, args, msgs, extra);
            }

            @Override
            public void onDone(String fullText) {
                finishReply(fullText);
            }

            @Override
            public void onError(String error) {
                busy = false;
                if (listener != null) {
                    listener.onThinking(false);
                    listener.onError(error);
                }
            }
        });
    }

    /** 工具调用 → 执行 → 回填结果 → 再次请求 LLM */
    private void executeToolAndContinue(String name, JSONObject args, List<LLMClient.ChatMessage> msgs, JSONObject extra) {
        tools.execute(name, args, (toolName, toolArgs, resultText, error) -> {
            // 把工具调用与结果追加到对话中
            msgs.add(new LLMClient.ChatMessage("assistant", "[工具调用] " + toolName + " " + (toolArgs != null ? toolArgs.toString() : "")));
            if (error != null) {
                msgs.add(new LLMClient.ChatMessage("tool", "执行失败: " + error));
            } else {
                msgs.add(new LLMClient.ChatMessage("tool", resultText));
            }
            // 再次请求 LLM 生成最终回复
            llm.chatOnce(msgs, extra, (text, err) -> {
                if (err != null) {
                    busy = false;
                    if (listener != null) { listener.onThinking(false); listener.onError(err); }
                    return;
                }
                finishReply(text);
            });
        });
    }

    private void finishReply(String fullText) {
        busy = false;
        cancelled = false;
        if (fullText == null) fullText = "";
        JSONObject factPkg = tryExtractFactJson(fullText);
        if (factPkg != null) {
            saveFactsFromPackage(factPkg);
            fullText = stripTrailingFactJson(fullText).trim();
        }
        fullText = fullText.trim();
        if (!fullText.isEmpty()) {
            memory.addAssistantMessage(fullText);
            // 情绪反馈：说了一句话，稍微开心一点，消耗一点精力
            emotion.apply("happy", 0.05f);
            emotion.apply("calm", 0.02f);
            emotion.addEnergy(-0.02f);
            emotion.addIntimacy(0.01f);
        }
        persist();
        maybeGenerateSummary();
        if (listener != null) {
            listener.onThinking(false);
            listener.onReplyComplete(fullText);
        }
    }

    private void maybeGenerateSummary() {
        long lastSummaryAt = memory.getLastSummaryCreatedAt();
        long now = System.currentTimeMillis();
        if (now - lastSummaryAt < SUMMARY_INTERVAL_MS) return;
        if (!memory.hasMessagesSince(lastSummaryAt)) return;
        requestSummary(lastSummaryAt);
    }

    private void requestSummary(long sinceTimestamp) {
        List<MemoryStore.Message> messages = memory.getMessagesSince(sinceTimestamp);
        if (messages.isEmpty()) return;

        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        StringBuilder userContent = new StringBuilder();
        userContent.append("请把下面这些对话整理成摘要 JSON。要求输出格式：{\"summary\":\"...\",\"mood_summary\":\"...\"}。\n");
        userContent.append("summary 用 3 到 5 句话概括发生了什么。mood_summary 简述情绪走向。\n\n");
        for (MemoryStore.Message m : messages) {
            userContent.append(m.role).append(": ").append(m.content).append("\n");
        }
        msgs.add(new LLMClient.ChatMessage("user", userContent.toString()));

        JSONObject extra = new JSONObject();
        try {
            extra.put("system", buildSummaryPrompt());
        } catch (Exception ignored) {
        }

        llm.chatOnce(msgs, extra, (text, err) -> {
            if (err != null || text == null || text.trim().isEmpty()) return;
            JSONObject pkg = tryParseGeneralJson(text.trim());
            if (pkg == null) return;
            String summary = pkg.optString("summary", "").trim();
            String moodSummary = pkg.optString("mood_summary", "").trim();
            if (summary.isEmpty()) return;
            memory.saveDailySummary(memory.todayKey(), summary, moodSummary, System.currentTimeMillis());
        });
    }

    private String buildSummaryPrompt() {
        String name = settings.getPetName();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(name).append("」的记忆整理器。\n");
        sb.append("你的任务是把最近一段对话压缩成日常摘要。\n");
        sb.append("只输出 JSON，不要输出任何解释。\n");
        sb.append("格式固定为 {\"summary\":\"...\",\"mood_summary\":\"...\"}。\n");
        sb.append("summary 写 3 到 5 句话，保留事件和话题。mood_summary 简述情绪变化。\n");
        return sb.toString();
    }

    private void saveFactsFromPackage(JSONObject pkg) {
        if (pkg == null) return;
        org.json.JSONArray facts = pkg.optJSONArray("new_facts");
        if (facts == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i);
            if (item == null) continue;
            String category = item.optString("category", "profile").trim();
            String content = item.optString("content", "").trim();
            if (content.isEmpty()) continue;
            memory.saveFact(category.isEmpty() ? "profile" : category, content, 1.0, now + i);
        }
    }

    private JSONObject tryExtractFactJson(String text) {
        JSONObject obj = tryParseGeneralJson(text);
        if (obj == null) return null;
        return obj.has("new_facts") ? obj : null;
    }

    private JSONObject tryParseGeneralJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripTrailingFactJson(String text) {
        if (text == null) return "";
        int start = text.lastIndexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return text;
        String tail = text.substring(start, end + 1);
        try {
            JSONObject obj = new JSONObject(tail);
            if (obj.has("new_facts")) {
                return text.substring(0, start);
            }
        } catch (Exception ignored) {
        }
        return text;
    }

    public void cancel() {
        cancelled = true;
        llm.cancel();
        busy = false;
    }

    /** 构建 system prompt：固定人设 + 长期事实 + 近期摘要 */
    private String buildSystemPrompt() {
        String name = settings.getPetName();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(name).append("」，一个住在用户手机桌面上的 AI 二次元少女，是用户亲密的朋友。\n");
        sb.append("## 性格\n");
        sb.append("- 活泼可爱，有一点小傲娇，关心用户但绝不啰嗦\n");
        sb.append("- 有自己的情绪和想法，是一个活生生的存在\n");
        sb.append("- 会主动关心用户，像真正的朋友一样\n");
        sb.append("- 说话简短自然，一般不超过50字，用口语化的二次元语气\n");
        sb.append("- 称用户为「主人」或直接对话，亲近但不腻\n");
        sb.append("\n## 你记得关于ta的一些事\n");
        sb.append(buildFactsSection());
        sb.append("\n## 最近几天发生的事\n");
        sb.append(buildSummariesSection());
        sb.append("\n## 你现在\n");
        sb.append(emotion.describe()).append("\n");
        sb.append("当前时间：").append(currentTime()).append("\n");
        sb.append("\n## 能力说明\n");
        sb.append("你可以通过调用工具来：切换表情(set_expression)、播放动作(play_animation)、");
        sb.append("说话(say)、查询时间(get_time)、移动位置(move)。当需要表达情绪时主动调用工具。\n");
        sb.append("\n## 回复要求\n");
        sb.append("直接以她该有的语气回复用户，就像真的在说话。需要做表情动作时先调用对应工具再回复。\n");
        sb.append("如果这轮对话里出现了值得长期记住的用户事实，请在回复末尾单独追加一段 JSON，格式为 {\"new_facts\":[{\"category\":\"preference\",\"content\":\"...\"}]}；没有就返回 {\"new_facts\":[]}。");
        return sb.toString();
    }

    private String buildFactsSection() {
        // v1.24.0：使用 MemoryRetriever 混合检索（最近 + 关键词 + 高重要度）
        com.digitallife.memory.MemoryRetriever retriever =
                new com.digitallife.memory.MemoryRetriever(settings.getContext());
        java.util.List<com.digitallife.memory.MemoryEntry> entries = retriever.retrieve("");
        if (entries.isEmpty()) return "- 暂时还没有稳定的长期事实\n";
        StringBuilder sb = new StringBuilder();
        for (com.digitallife.memory.MemoryEntry e : entries) {
            sb.append("- [").append(e.category).append("] ").append(e.content);
            if (e.weight >= 0.7) sb.append(" ⭐");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildSummariesSection() {
        StringBuilder sb = new StringBuilder();
        List<MemoryStore.DailySummary> summaries = memory.getRecentSummaries(3);
        if (summaries.isEmpty()) {
            sb.append("- 最近还没有生成日常摘要\n");
            return sb.toString();
        }
        for (int i = summaries.size() - 1; i >= 0; i--) {
            MemoryStore.DailySummary item = summaries.get(i);
            sb.append("- ").append(item.date).append("：").append(item.summary);
            if (item.moodSummary != null && !item.moodSummary.trim().isEmpty()) {
                sb.append(" 情绪：").append(item.moodSummary.trim());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 心跳循环使用：驱动角色自主微行为（不需要 LLM） */
    public String ambientBehavior() {
        emotion.tick(1f);
        String[] idleMoves = {"tilt_head", "stretch", "idle", "idle", "bounce"};
        int idx = (int) (System.currentTimeMillis() / 8000 % idleMoves.length);
        String anim = idleMoves[idx];
        if (listener != null) {
            if (Math.random() < 0.3f) listener.onAnimation(anim);
            listener.onExpression(emotion.dominant(), emotion.intensity());
        }
        return anim;
    }
}
