package com.digitallife.brain;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/**
 * 多智能体团队（v1.111.0）。
 *
 * <p>主智能体（对话大脑）可以把子任务委派给专职子智能体。子智能体拥有
 * <b>独立的消息上下文</b>（看不到主对话历史）与 <b>最小工具集</b>（只能调用
 * 自己职责范围内的工具），执行结束后只把最终结论回传给主对话。
 *
 * <p>这样设计的目的：
 * <ul>
 *   <li>上下文隔离——调研过程中的几十次搜索/抓取不会污染主对话历史；</li>
 *   <li>职责聚焦——子智能体的提示词只围绕一件事，产出质量更高；</li>
 *   <li>权限收敛——子智能体拿不到与职责无关的工具（如研究员不能改定时任务）。</li>
 * </ul>
 *
 * <p>子智能体不允许再委派（工具白名单里没有 delegate_task），因此不存在递归失控。
 */
public final class AgentTeam {
    private AgentTeam() {
    }
    /** 子智能体定义 */
    public static final class SubAgent {
        public final String name;
        public final String description;
        public final String systemPrompt;
        public final Set<String> tools;
        public final int maxRounds;
        SubAgent(String name, String description, String systemPrompt,
                 String[] tools, int maxRounds) {
            this.name = name;
            this.description = description;
            this.systemPrompt = systemPrompt;
            this.tools = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(tools)));
            this.maxRounds = maxRounds;
        }
    }
    private static final Map<String, SubAgent> AGENTS = new LinkedHashMap<>();
    static {
        AGENTS.put("researcher", new SubAgent(
                "researcher",
                "研究员：联网检索资料、抓取网页正文、查记忆，产出带来源的结论",
                "你是专职研究员。你会收到一个具体的调研任务。\n"
                        + "工作方法：\n"
                        + "1. 先用 web_search 多路检索（可换关键词多搜几次），从结果里挑可靠的来源；\n"
                        + "2. 对关键来源用 web_fetch 抓正文，确认细节，不要只凭搜索摘要下结论；\n"
                        + "3. 需要方法论时可用 skill_summary 查看可用技能，用 load_skill 加载后再执行；\n"
                        + "4. 涉及用户既往偏好时，用 memory_search 查一下记忆。\n"
                        + "输出要求：\n"
                        + "- 直接给结论，用中文，条理清晰，必要时分点；\n"
                        + "- 每个关键结论标注来源（网页标题 + 链接）；\n"
                        + "- 资料冲突或不确定时明确说出来，不要编造；\n"
                        + "- 不要写「作为研究员」这类自我介绍，直接给内容。",
                new String[]{"web_search", "web_fetch", "skill_summary",
                        "load_skill", "memory_search", "memory_recall"},
                5));
        AGENTS.put("secretary", new SubAgent(
                "secretary",
                "日程管家：建/查/取消定时任务与提醒，做数据备份与恢复",
                "你是专职日程与数据管家。你会收到一个具体的执行任务。\n"
                        + "可用能力：schedule_task 建任务（一次性或周期）、list_tasks 查看现有任务、\n"
                        + "cancel_task 取消任务、set_reminder 设提醒、get_calendar_today 看今天日程、\n"
                        + "backup_data 备份数据、list_backups 看备份、restore_data 恢复数据、\n"
                        + "send_notification 发通知。\n"
                        + "输出要求：\n"
                        + "- 先确认任务里的关键信息（时间、内容）是否完整，缺就先用文字问清楚，不要猜；\n"
                        + "- 执行后明确报告：做了什么、任务 id、下次触发时间；\n"
                        + "- 用中文，简洁，不寒暄。",
                new String[]{"schedule_task", "list_tasks", "cancel_task", "set_reminder",
                        "get_calendar_today", "backup_data", "list_backups",
                        "restore_data", "send_notification"},
                4));
    }
    /** 可委派的子智能体列表（供 delegate_task 的 schema 与提示词使用） */
    public static List<SubAgent> all() {
        return new ArrayList<>(AGENTS.values());
    }
    public static SubAgent get(String name) {
        return name == null ? null : AGENTS.get(name);
    }
    /** 子智能体执行过程中对外汇报进度（供 UI 显示协作过程） */
    public interface ProgressListener {
        /**
         * @param agent   子智能体名
         * @param phase   "start" 开始 / "tool" 调用工具 / "result" 工具结果 / "done" 结束
         * @param detail  具体说明
         */
        void onStep(String agent, String phase, String detail);
    }
    /** 子智能体需要独立的大模型客户端（避免与主对话共用 tools/线程池状态） */
    public interface LlmFactory {
        LLMClient create();
    }
    /** 执行结果 */
    public static final class Result {
        public final String text;
        public final String error;
        Result(String text, String error) {
            this.text = text;
            this.error = error;
        }
        public boolean ok() {
            return error == null;
        }
    }
    /** 单个子智能体的整体超时（秒）：防止卡住主对话 */
    private static final int TIMEOUT_SEC = 150;
    /**
     * 同步执行一个子智能体并返回它的最终结论。
     * 在调用方线程阻塞等待（子智能体跑在它自己的单线程池上，因此不会与调用方互相阻塞）。
     */
    public static Result run(String agentName, String task, Tools host,
                             LlmFactory factory, ProgressListener progress) {
        SubAgent a = get(agentName);
        if (a == null) {
            StringBuilder names = new StringBuilder();
            for (String k : AGENTS.keySet()) {
                if (names.length() > 0) names.append("、");
                names.append(k);
            }
            return new Result(null, "未知子智能体：" + agentName + "（可用：" + names + "）");
        }
        if (task == null || task.trim().isEmpty()) {
            return new Result(null, "委派任务为空");
        }
        LLMClient llm = factory == null ? null : factory.create();
        if (llm == null) {
            return new Result(null, "没有可用的模型配置，无法启动子智能体");
        }
        // 上下文隔离：子智能体只看到「任务」这一条用户输入
        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("user", task));
        // 最小工具集：只暴露白名单内的工具
        llm.setTools(host == null ? new JSONArray() : host.toJsonArray(a.tools));
        if (progress != null) progress.onStep(a.name, "start", task);
        final String[] finalText = new String[1];
        final String[] error = new String[1];
        final int[] rounds = new int[1];
        final CountDownLatch latch = new CountDownLatch(1);
        runRound(a, llm, msgs, host, progress, rounds, finalText, error, latch);
        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                llm.cancel();
                if (progress != null) progress.onStep(a.name, "done", "超时中断");
                return new Result(null, "子智能体 " + a.name + " 执行超时（" + TIMEOUT_SEC + "s）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            llm.cancel();
            return new Result(null, "子智能体执行被中断");
        }
        if (error[0] != null) {
            if (progress != null) progress.onStep(a.name, "done", "出错");
            return new Result(null, error[0]);
        }
        String text = finalText[0];
        if (text == null || text.trim().isEmpty()) {
            text = "（子智能体 " + a.name + " 没有产出结论）";
        }
        if (progress != null) {
            progress.onStep(a.name, "done", text.length() > 120
                    ? text.substring(0, 120) + "…" : text);
        }
        return new Result(text, null);
    }
    /** 跑一轮；onDone 时若还在工具链中且未超轮次，则携带工具结果续跑下一轮 */
    private static void runRound(final SubAgent a, final LLMClient llm,
                                 final List<LLMClient.ChatMessage> msgs, final Tools host,
                                 final ProgressListener progress, final int[] rounds,
                                 final String[] finalText, final String[] error,
                                 final CountDownLatch latch) {
        LLMClient.StreamListener listener = new LLMClient.StreamListener() {
            @Override
            public void onDelta(String t) {
                // 子智能体的正文不直接呈现给用户，只累积，最后作为结论回传
                if (t == null) return;
                finalText[0] = finalText[0] == null ? t : finalText[0] + t;
            }
            @Override
            public void onToolCall(String name, JSONObject args, String toolCallId) {
                if (name == null || name.isEmpty()) return;
                // 权限收敛：白名单外的工具直接拒绝，不执行
                if (!a.tools.contains(name)) {
                    appendToolResult(msgs, name, toolCallId,
                            "拒绝：该工具不在子智能体 " + a.name + " 的权限范围内");
                    if (progress != null) {
                        progress.onStep(a.name, "result", name + " → 无权限，已拒绝");
                    }
                    return;
                }
                JSONObject callArgs = args != null ? args : new JSONObject();
                if (progress != null) {
                    progress.onStep(a.name, "tool", name + " " + brief(callArgs));
                }
                String res = null;
                String err = null;
                if (host == null) {
                    err = "工具宿主不可用";
                } else {
                    try {
                        res = host.executeSync(name, callArgs);
                    } catch (Exception e) {
                        err = com.digitallife.ui.UiKit.safeMsg(e);
                    }
                }
                String out = err != null ? "工具执行失败：" + err
                        : (res != null && !res.isEmpty() ? res : "（工具无返回）");
                appendToolResult(msgs, name, toolCallId, out);
                if (progress != null) {
                    progress.onStep(a.name, "result", name + " → " + clip(out));
                }
            }
            @Override
            public void onDone(String fullText) {
                boolean toolEnd = (fullText == null || fullText.isEmpty())
                        && !msgs.isEmpty()
                        && "tool".equals(msgs.get(msgs.size() - 1).role);
                if (toolEnd && rounds[0] < a.maxRounds) {
                    rounds[0]++;
                    // 工具结果已入链，携带上下文继续跑下一轮（新任务排入同一单线程池，不死锁）
                    runRound(a, llm, msgs, host, progress, rounds, finalText, error, latch);
                    return;
                }
                if (fullText != null && !fullText.isEmpty()) {
                    finalText[0] = fullText;
                }
                latch.countDown();
            }
            @Override
            public void onError(String e) {
                error[0] = e;
                latch.countDown();
            }
        };
        llm.chatStream(msgs, extraOf(a), listener);
    }
    private static JSONObject extraOf(SubAgent a) {
        JSONObject extra = new JSONObject();
        try {
            extra.put("system", a.systemPrompt);
        } catch (Exception ignored) {
        }
        return extra;
    }
    /** 把一次工具调用的结果按 OpenAI 协议拼进消息链 */
    private static void appendToolResult(List<LLMClient.ChatMessage> msgs, String name,
                                         String toolCallId, String output) {
        try {
            String id = (toolCallId == null || toolCallId.isEmpty())
                    ? "call_" + name + "_" + System.currentTimeMillis() : toolCallId;
            JSONArray tcs = new JSONArray();
            JSONObject tc = new JSONObject();
            tc.put("id", id);
            tc.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", "{}");
            tc.put("function", fn);
            tcs.put(tc);
            LLMClient.ChatMessage asst = new LLMClient.ChatMessage("assistant", null);
            asst.toolCalls = tcs;
            msgs.add(asst);
            LLMClient.ChatMessage tool = new LLMClient.ChatMessage("tool", output);
            tool.toolCallId = id;
            msgs.add(tool);
        } catch (Exception ignored) {
        }
    }
    private static String brief(JSONObject args) {
        try {
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = args.keys();
            int n = 0;
            while (it.hasNext() && n < 3) {
                String k = it.next();
                sb.append(k).append("=").append(clip(String.valueOf(args.opt(k))));
                sb.append(" ");
                n++;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replace("\n", " ").trim();
        return one.length() > 100 ? one.substring(0, 100) + "…" : one;
    }
}
