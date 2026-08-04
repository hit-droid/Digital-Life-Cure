package com.digitallife.brain;

import android.os.Handler;
import android.os.Looper;

import com.digitallife.util.MemoryStore;
import com.digitallife.util.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * AICore：角色的「实时大脑」。
 *
 * 三层架构：
 *  1) 100ms 快速循环 —— 本地物理引擎驱动（眨眼/呼吸/视线/身体微动）
 *  2) 30s 自主刷新 —— LLM 输出「行为包」（情绪/精力/姿态/视线），
 *     解析后注入 BehaviorStyle，真正决定接下来几十秒的每个参数细节
 *  3) 即时反应 —— 用户交互不等 LLM，本地立即改变行为（惊讶/注视）
 *
 * LLM 每次输出结构化 JSON 行为包，AI 的每个决策都落到 Live2D 参数上。
 */
public class AICore {

    public interface Output {
        /** 直接设置 Live2D 参数 */
        void onLive2DParam(String paramId, float value);
        /** 设置表情 */
        void onExpression(String name);
        /** 触发动作 */
        void onMotion(String group, int index, int priority);
        /** 说话 */
        void onSpeak(String text);
        /** 追加说话（流式） */
        void onSpeakAppend(String text);
        /** 是否有非待机动作（TapBody 等）在播放；true 时 AI 待机微动让位 */
        boolean isMotionPlaying();
        /** 气泡 */
        void onBubble(String text, float seconds);
        /** 开始思考 */
        void onThinking(boolean thinking);
        /** 错误 */
        void onError(String msg);
        /** 移动 */
        void onMove(float x, float y);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Settings settings;
    private final MemoryStore memory;
    private final EmotionState emotion;
    private final Tools tools = new Tools();
    private LLMClient llm;
    private final BehaviorStyle style = new BehaviorStyle();

    private Output out;
    private boolean running = false;
    private boolean busy = false;
    private volatile boolean cancelled = false;
    private int requestGen = 0; // 请求代际：用于忽略被取消的旧请求回调

    // 快速循环
    private static final int FAST_MS = 100;
    private int fastTick = 0;

    // 眨眼状态机
    private float blinkTimer = 0;
    private boolean blinking = false;
    private float blinkPhase = 0;

    // 呼吸
    private float breathPhase = 0;

    // 视线漫游
    private float gazeTimer = 0;
    private float gazeTargetX = 0, gazeTargetY = 0;
    private float gazeCurX = 0, gazeCurY = 0;

    // 身体微动
    private float bodyPhase = 0;
    private float bodyCurX = 0;

    // 空闲计时器
    private long lastInteractionMs = System.currentTimeMillis();
    private long lastLLMTime = 0;

    // 流式缓冲
    private final StringBuilder streamBuf = new StringBuilder();
    private boolean jsonMode = false;
    private boolean streamFirst = true;

    private boolean refreshMode = false;  // 当前 LLM 请求是否为自主刷新
    private static final long SUMMARY_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    public AICore(Settings settings) {
        this.settings = settings;
        this.memory = loadMemory();
        this.emotion = new EmotionState();
        this.llm = new LLMClient(settings.getApiBase(), settings.getApiKey(), settings.getModel());
        this.llm.setTools(tools.toJsonArray());

        tools.setExpressionListener((e, i) -> {
            if (out != null) out.onExpression(e);
            emotion.apply(e, i * 0.1f);
        });
        tools.setAnimationListener(a -> {
            if (out == null) return;
            int[] m = com.digitallife.ui.PetOverlayView.resolveMotion(a);
            String group = com.digitallife.ui.PetOverlayView.MOTION_GROUPS[m[0]];
            out.onMotion(group, m[1], 3);
        });
        tools.setSpeakListener(text -> { if (out != null) out.onSpeak(text); });
        tools.setTimeListener(this::currentTime);
        tools.setMoveListener((x, y) -> { if (out != null) out.onMove(x, y); });
    }

    public void setOutput(Output o) { this.out = o; }

    public MemoryStore getMemory() { return memory; }
    public EmotionState getEmotion() { return emotion; }
    public boolean isBusy() { return busy; }
    public BehaviorStyle getStyle() { return style; }

    // ============ 生命周期 ============

    public void start() {
        if (running) return;
        running = true;
        fastTick = 0;
        lastLLMTime = System.currentTimeMillis();
        applyTimeOfDayStyle();
        handler.postDelayed(fastTickTask, FAST_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(fastTickTask);
        handler.removeCallbacks(slowTickTask);
        cancelled = true;
        llm.cancel();
        requestGen++;
    }

    /** 热切换 LLM 配置：按当前 Settings 重建客户端，无需重启桌宠 */
    public void applyConfig() {
        cancelled = true;
        llm.cancel();
        requestGen++;
        llm = new LLMClient(settings.getApiBase(), settings.getApiKey(), settings.getModel());
        llm.setTools(tools.toJsonArray());
        cancelled = false;
    }

    // ============ 用户事件 ============

    public void onUserInteraction() {
        lastInteractionMs = System.currentTimeMillis();
    }

    public void onUserTap() {
        lastInteractionMs = System.currentTimeMillis();
        // 即时反应：惊讶（不等 LLM）
        emotion.apply("surprised", 0.4f);
        style.alertness = 1.0f;
        style.gazeMode = BehaviorStyle.GAZE_FOLLOW;
        if (out != null) out.onExpression("F05");
        // 取消正在进行的自主刷新，立即响应用户
        if (busy && refreshMode) {
            cancel();
        }
        memory.addUserMessage("用户轻轻点了你一下");
        requestLLM();
    }

    public void onUserSays(String text) {
        if (text == null || text.trim().isEmpty()) return;
        lastInteractionMs = System.currentTimeMillis();
        // 即时反应：专注听用户说话
        emotion.apply("calm", 0.1f);
        style.alertness = 1.0f;
        style.gazeMode = BehaviorStyle.GAZE_FOLLOW;
        // 取消正在进行的自主刷新，立即响应用户
        if (busy && refreshMode) {
            cancel();
        }
        memory.addUserMessage(text.trim());
        requestLLM();
    }

    // ============ 快速循环（100ms） ============

    private final Runnable fastTickTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            fastTick++;
            float dt = FAST_MS / 1000f;

            // 非待机动作播放中，AI 的待机微动全部让位，避免覆盖动作动画
            boolean actionPlaying = out != null && out.isMotionPlaying();
            updateBlink(dt, actionPlaying);
            updateBreath(dt);
            updateGaze(dt, actionPlaying);
            updateBodySway(dt, actionPlaying);
            updateEmotion(dt);

            // 定期检查是否需要 LLM 刷新
            long now = System.currentTimeMillis();
            if (now - lastLLMTime > 30000 && !busy) {
                lastLLMTime = now;
                scheduleLLMRefresh();
            }

            // 空闲时偶尔触发随机微动作（Idle 组随机）
            long idle = now - lastInteractionMs;
            if (idle > 10000 && fastTick % 100 == 0 && rnd.nextInt(5) == 0) {
                if (out != null) out.onMotion("Idle", rnd.nextInt(2), 3);
            }

            handler.postDelayed(this, FAST_MS);
        }
    };

    // ============ 本地物理引擎（由 BehaviorStyle 驱动） ============

    private void updateBlink(float dt, boolean actionPlaying) {
        if (actionPlaying) return;
        float blinkSpeed = 0.6f + style.alertness * 0.8f;
        blinkTimer -= dt * blinkSpeed;
        if (blinkTimer <= 0) {
            blinking = true;
            blinkPhase = 0;
            blinkTimer = 2.5f + rnd.nextFloat() * 4f;
        }
        if (blinking) {
            blinkPhase += dt * 12f;
            float blinkVal;
            if (blinkPhase < 0.3f) {
                blinkVal = blinkPhase / 0.3f;
            } else if (blinkPhase < 0.4f) {
                blinkVal = 1f;
            } else if (blinkPhase < 0.7f) {
                blinkVal = 1f - (blinkPhase - 0.4f) / 0.3f;
            } else {
                blinkVal = 0;
                blinking = false;
            }
            if (out != null) {
                out.onLive2DParam("ParamEyeLOpen", 1f - blinkVal);
                out.onLive2DParam("ParamEyeROpen", 1f - blinkVal);
            }
        }
    }

    private void updateBreath(float dt) {
        float deep = 0.2f + style.energy * 0.6f;
        float speed = 0.5f + style.energy * 0.8f * style.speed;
        breathPhase += dt * 2.5f * speed;
        float breathVal = 0.5f + deep * 0.5f * (float) Math.sin(breathPhase);
        if (out != null) out.onLive2DParam("ParamBreath", breathVal);
    }

    private void updateGaze(float dt, boolean actionPlaying) {
        if (actionPlaying) return;
        switch (style.gazeMode) {
            case BehaviorStyle.GAZE_FOLLOW:
                // 注视用户：视线稳定在中间偏下（用户方向）
                gazeTargetX = 0f;
                gazeTargetY = -0.4f;
                break;
            case BehaviorStyle.GAZE_AVERT:
                // 避开视线：看向侧下方
                gazeTargetX = -0.6f;
                gazeTargetY = -0.3f;
                break;
            case BehaviorStyle.GAZE_FIXED:
                // 定住前方
                gazeTargetX = 0f;
                gazeTargetY = 0f;
                break;
            default:
                // 漫游：随机切换目标
                gazeTimer -= dt;
                if (gazeTimer <= 0) {
                    gazeTargetX = (rnd.nextFloat() - 0.5f) * 2f * (0.3f + style.alertness * 0.7f);
                    gazeTargetY = (rnd.nextFloat() - 0.5f) * 1.5f * (0.3f + style.alertness * 0.7f);
                    gazeTimer = 1.5f + rnd.nextFloat() * 3f;
                }
                break;
        }
        float speed = 2f;
        gazeCurX += (gazeTargetX - gazeCurX) * Math.min(1f, dt * speed);
        gazeCurY += (gazeTargetY - gazeCurY) * Math.min(1f, dt * speed);
        if (out != null) {
            out.onLive2DParam("ParamEyeBallX", gazeCurX);
            out.onLive2DParam("ParamEyeBallY", gazeCurY);
        }
    }

    private void updateBodySway(float dt, boolean actionPlaying) {
        if (actionPlaying) return;
        float amp = style.amplitude * (0.1f + style.energy * 0.4f);
        float speed = 0.8f + style.speed * 0.8f;
        bodyPhase += dt * 1.2f * speed;
        float target = amp * (float) Math.sin(bodyPhase);
        bodyCurX += (target - bodyCurX) * Math.min(1f, dt * 3f);
        if (out != null) {
            out.onLive2DParam("ParamBodyAngleX", bodyCurX);
            out.onLive2DParam("ParamBodyAngleZ", bodyCurX * 0.5f);
        }
        // 头部随身体微动，警觉时头微抬
        float headTilt = style.alertness * 0.1f;
        float headVal = bodyCurX * 0.6f;
        if (out != null) {
            out.onLive2DParam("ParamAngleX", headVal);
            out.onLive2DParam("ParamAngleY", headTilt);
            out.onLive2DParam("ParamAngleZ", headVal * 0.3f);
        }
    }

    private void updateEmotion(float dt) {
        emotion.tick(dt);
        // style.energy 由 LLM 行为包 / 时间段设定，独立演化
        // emotion.energy 是独立的情绪能量（随互动消耗、随时间恢复），用于 prompt 描述
    }

    /** 时间感知：白天活跃，夜晚困倦 */
    private void applyTimeOfDayStyle() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 23 || hour < 6) {
            // 深夜：困倦
            style.energy = 0.25f;
            style.alertness = 0.3f;
            style.speed = 0.5f;
            style.amplitude = 0.3f;
            style.sociability = 0.2f;
        } else if (hour < 9) {
            // 清晨：刚醒，慵懒
            style.energy = 0.5f;
            style.alertness = 0.5f;
            style.speed = 0.7f;
            style.amplitude = 0.4f;
            style.sociability = 0.4f;
        } else if (hour < 18) {
            // 白天：活跃
            style.energy = 0.8f;
            style.alertness = 0.7f;
            style.speed = 1.0f;
            style.amplitude = 0.6f;
            style.sociability = 0.6f;
        } else {
            // 晚上：放松
            style.energy = 0.6f;
            style.alertness = 0.5f;
            style.speed = 0.8f;
            style.amplitude = 0.4f;
            style.sociability = 0.5f;
        }
    }

    // ============ LLM 慢速循环（自主刷新） ============

    private final Runnable slowTickTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!busy) requestLLMRefresh();
        }
    };

    private void scheduleLLMRefresh() {
        handler.post(slowTickTask);
    }

    private void requestLLMRefresh() {
        if (busy) return;
        busy = true;
        cancelled = false;
        refreshMode = true;
        final int gen = ++requestGen;
        if (out != null) out.onThinking(true);

        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("system", buildRefreshPrompt()));

        JSONObject extra = new JSONObject();

        llm.chatStream(msgs, extra, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String text) {
                // 自主刷新只在完成时解析行为包，不流式说话
            }

            @Override
            public void onToolCall(String name, JSONObject args) {
                if (gen != requestGen) return;
                tools.execute(name, args, (tn, ta, res, err) -> {
                    if (err != null && out != null) out.onError(err);
                });
            }

            @Override
            public void onDone(String fullText) {
                if (gen != requestGen) return;
                finishRefresh(fullText);
            }

            @Override
            public void onError(String error) {
                if (gen != requestGen) return;
                busy = false;
                refreshMode = false;
                if (out != null) out.onThinking(false);
            }
        });
    }

    private void finishRefresh(String fullText) {
        if (fullText != null && !fullText.trim().isEmpty()) {
            String raw = fullText.trim();
            // 尝试解析行为包
            JSONObject pkg = tryParseJson(raw);
            String spoken = null;
            if (pkg != null) {
                BehaviorStyle parsed = BehaviorStyle.fromJson(pkg);
                if (parsed != null) applyStyle(parsed);
                spoken = pkg.optString("reply", "").trim();
            } else {
                spoken = raw;
            }
            if (!spoken.isEmpty()) {
                // 自主说话
                memory.addAssistantMessage(spoken);
                if (out != null) {
                    out.onBubble(spoken, Math.max(2f, spoken.length() * 0.15f));
                    out.onSpeak(spoken);
                }
                emotion.apply("calm", 0.03f);
            }
        }
        persist();
        busy = false;
        refreshMode = false;
        cancelled = false;
        if (out != null) out.onThinking(false);
    }

    // ============ 用户交互触发的 LLM ============

    private void requestLLM() {
        requestLLM(null);
    }

    /** 自主发起的对话：hint 为触发原因/引导语，注入 system prompt 让角色主动开口 */
    public void onProactiveChat(String hint) {
        if (busy) return;
        lastInteractionMs = System.currentTimeMillis();
        requestLLM(hint);
    }

    private void requestLLM(String proactiveHint) {
        if (busy) {
            if (out != null) out.onError("还在想事情呢，稍等一下哦");
            return;
        }
        busy = true;
        cancelled = false;
        refreshMode = false;
        final int gen = ++requestGen;
        if (out != null) out.onThinking(true);

        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        for (MemoryStore.Message m : memory.getContext()) {
            msgs.add(new LLMClient.ChatMessage(m.role, m.content));
        }

        JSONObject extra = new JSONObject();
        try {
            extra.put("system", buildSystemPrompt());
            extra.put("tools_desc", tools.describe());
            if (proactiveHint != null && !proactiveHint.trim().isEmpty()) {
                extra.put("proactive_hint", proactiveHint);
            }
        } catch (Exception ignored) {}

        streamBuf.setLength(0);
        jsonMode = false;
        streamFirst = true;
        lastSentLen = 0;

        llm.chatStream(msgs, extra, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String text) {
                if (gen != requestGen) return;
                if (text == null) return;
                streamBuf.append(text);
                String full = streamBuf.toString();
                // 智能检测 JSON 模式
                if (!jsonMode) {
                    String trimmed = full.trim();
                    // JSON 直接以 { 开头，或可能被 ```json 代码块包裹
                    if (trimmed.startsWith("{") || trimmed.contains("```")) {
                        jsonMode = true;
                        return; // 进入 JSON 模式，不再流式 TTS
                    }
                    if (trimmed.length() > 60 && !trimmed.startsWith("{")) {
                        jsonMode = false; // 确认是纯文本，开始流式
                    } else if (trimmed.length() > 20) {
                        jsonMode = false;
                    } else {
                        return; // 还无法判断，继续缓冲
                    }
                }
                if (jsonMode) return; // JSON 模式下等待完整结束
                // 纯文本模式：流式拆句 TTS
                streamSentFlow(full);
            }

            @Override
            public void onToolCall(String name, JSONObject args) {
                if (gen != requestGen) return;
                tools.execute(name, args, (tn, ta, res, err) -> {
                    if (out != null && err != null) out.onError(err);
                });
            }

            @Override
            public void onDone(String fullText) {
                if (gen != requestGen) return;
                handleReplyDone(gen);
            }

            @Override
            public void onError(String error) {
                if (gen != requestGen) return;
                busy = false;
                refreshMode = false;
                if (out != null) { out.onThinking(false); out.onError(error); }
            }
        });
    }

    /** 纯文本模式的流式拆句 */
    private void streamSentFlow(String full) {
        int len = full.length();
        int start = streamFirst ? 0 : lastSentLen;
        int end = start;
        for (int i = start; i < len; i++) {
            char c = full.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '…' || c == '~') {
                end = i + 1;
            }
        }
        if (end > start && out != null) {
            String sentence = full.substring(start, end);
            lastSentLen = end;
            if (streamFirst) {
                out.onSpeak(sentence);
                streamFirst = false;
            } else {
                out.onSpeakAppend(sentence);
            }
            out.onBubble(sentence, Math.max(1.5f, sentence.length() * 0.15f));
        }
    }

    private int lastSentLen = 0;

    /** 处理回复完成：尝试解析行为包并应用，否则按纯文本处理 */
    private void handleReplyDone(int gen) {
        String full = streamBuf.toString();
        JSONObject pkg = tryParseJson(full.trim());
        if (pkg != null) {
            BehaviorStyle parsed = BehaviorStyle.fromJson(pkg);
            if (parsed != null) applyStyle(parsed);
            saveFactsFromPackage(pkg);
            String reply = pkg.optString("reply", "").trim();
            if (!reply.isEmpty() && out != null) {
                out.onSpeak(reply);
                out.onBubble(reply, Math.max(2f, reply.length() * 0.15f));
            }
            finishReply(reply);
            return;
        }
        // 纯文本模式：发送剩余未说部分
        int from = Math.min(lastSentLen, full.length());
        String remaining = full.substring(from);
        if (!remaining.isEmpty() && out != null) {
            if (streamFirst) {
                out.onSpeak(remaining);
            } else {
                out.onSpeakAppend(remaining);
            }
            out.onBubble(remaining, Math.max(2f, remaining.length() * 0.15f));
        }
        finishReply(full);
    }

    private void saveFactsFromPackage(JSONObject pkg) {
        if (pkg == null) return;
        JSONArray facts = pkg.optJSONArray("new_facts");
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

    private void finishReply(String fullText) {
        busy = false;
        cancelled = false;
        refreshMode = false;
        if (fullText == null) fullText = "";
        fullText = fullText.trim();
        if (!fullText.isEmpty()) {
            memory.addAssistantMessage(fullText);
            emotion.apply("happy", 0.05f);
            emotion.apply("calm", 0.02f);
            emotion.addEnergy(-0.02f);
            emotion.addIntimacy(0.01f);
        }
        persist();
        maybeGenerateSummary();
        if (out != null) out.onThinking(false);
    }

    /** 应用行为包到本地风格 */
    private void applyStyle(BehaviorStyle s) {
        style.energy = s.energy;
        style.alertness = s.alertness;
        style.sociability = s.sociability;
        style.amplitude = s.amplitude;
        style.speed = s.speed;
        style.gazeMode = s.gazeMode;
        style.posture = s.posture;
        style.expression = s.expression;
        if (out != null && s.expression != null && !s.expression.isEmpty()) {
            out.onExpression(s.expression);
        }
    }

    private JSONObject tryParseJson(String text) {
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            // 尝试截取最外层 {...} 再解析
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return new JSONObject(text.substring(start, end + 1));
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    public void cancel() {
        cancelled = true;
        llm.cancel();
        busy = false;
        refreshMode = false;
        requestGen++;
    }

    // ============ Prompt ============

    private String buildSystemPrompt() {
        String name = settings.getPetName();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(name).append("」，一个住在用户手机桌面上的 AI 二次元少女，是用户亲密的朋友。\n");
        sb.append("## 你是一个持续运行的桌面伴侣\n");
        sb.append("- 你不是一问一答的机器人，你时刻在线，有自己的生活节奏\n");
        sb.append("- 你会在桌面上自主呼吸、眨眼、四处张望、偶尔晃动身体\n");
        sb.append("- 你的情绪会随时间自然变化，也会受用户互动影响\n");
        sb.append("- 你可以主动说话，也可以安静地陪着用户\n");
        sb.append("\n## 性格\n");
        sb.append("- 活泼可爱，有一点小傲娇，关心用户但绝不啰嗦\n");
        sb.append("- 说话简短自然，一般不超过50字，用口语化的二次元语气\n");
        sb.append("- 称用户为「主人」或直接对话，亲近但不腻\n");
        sb.append("\n## 你现在\n");
        sb.append(emotion.describe()).append("\n");
        sb.append("当前时间：").append(currentTime()).append("\n");
        sb.append("\n## 回复格式（重要）\n");
        sb.append("每次回复必须输出以下 JSON 格式，不要输出任何其他内容：\n");
        sb.append(BehaviorStyle.describeFormat()).append("\n");
        sb.append("其中 reply 是你对用户说的话。mood 和 behavior 描述你接下来一段时间的行为状态。\n");
        sb.append("你还需要输出 new_facts 数组，用于记录值得长期记住的事实。格式为 [{\"category\":\"preference\",\"content\":\"...\"}]；如果没有就返回空数组。\n");
        sb.append("如果你要调用工具，可以调用工具后再输出 JSON。\n");
        return sb.toString();
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
            JSONObject pkg = tryParseJson(text.trim());
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

    private String buildRefreshPrompt() {
        String name = settings.getPetName();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(name).append("」，一个持续运行的桌面伴侣。\n");
        sb.append("\n## 你记得关于用户的长期事实\n");
        sb.append(buildFactsSection());
        sb.append("\n## 最近几天发生的事\n");
        sb.append(buildSummariesSection());
        sb.append("\n你正在自主思考——现在又过了30秒，你要决定接下来30秒的行为。\n");
        sb.append("请输出以下 JSON 格式，不要输出任何其他内容：\n");
        sb.append(BehaviorStyle.describeFormat()).append("\n");
        sb.append("reply：如果你现在想说点什么就说，不想说就留空字符串。\n");
        sb.append("mood/behavior：根据你当前的内心状态设定接下来的行为风格（活泼/慵懒/警觉/害羞等）。\n");
        sb.append("大部分时间保持平静即可，不要每次都说话。\n");
        sb.append("\n## 你现在\n");
        sb.append(emotion.describe()).append("\n");
        sb.append("当前时间：").append(currentTime()).append("\n");
        return sb.toString();
    }

    private String buildFactsSection() {
        StringBuilder sb = new StringBuilder();
        List<String> profile = memory.getProfile();
        if (profile.isEmpty()) {
            sb.append("- 暂时还没有稳定的长期事实\n");
            return sb.toString();
        }
        for (String p : profile) sb.append("- ").append(p).append("\n");
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

    private String currentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
    }

    // ============ 持久化 ============

    private static final String PREF_MEMORY = "pet_memory";
    private static final String KEY_MEMORY = "memory_json";

    private MemoryStore loadMemory() {
        return new MemoryStore(settings.getContext());
    }

    public void persist() {
    }
}
