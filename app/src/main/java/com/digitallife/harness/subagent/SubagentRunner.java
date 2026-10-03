package com.digitallife.harness.subagent;

import com.digitallife.brain.Tools;
import com.digitallife.harness.AgentHandle;
import com.digitallife.harness.DeepSeekHarness;
import com.digitallife.harness.LlmFactory;
import com.digitallife.harness.SessionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 子智能体 Consumer：把子任务委派给一个带独立 session log 与最小工具集的
 * harness 实例，跑完后只把结论回传主对话。
 *
 * <p>与主对话共用同一套 plugin 树（session / prompt / tools / agent-loop），
 * 差别只在 preset（提示词 + 工具白名单 + 步数上限），因此子智能体的执行
 * 同样走 turn/step 流程并可被同一套事件观察。
 */
public final class SubagentRunner {

    /** 进度汇报：供 UI 展示协作过程 */
    public interface ProgressListener {
        /**
         * @param agent  子智能体名
         * @param phase  事件类型：
         *               <ul>
         *                 <li>"start" 子任务开始（detail 为任务原文）</li>
         *                 <li>"tool" 调用工具（detail 为「工具名 参数摘要」）</li>
         *                 <li>"result" 工具结果（detail 为「工具名 → 结果摘要」）</li>
         *                 <li>"done" 子任务结束（detail 为结论预览或中断原因）</li>
         *                 <li>"team-ok" 并行批次内某子任务成功（detail 为完整结论）</li>
         *                 <li>"team-fail" 并行批次内某子任务失败（detail 为错误原因）</li>
         *               </ul>
         * @param detail 具体说明
         */
        void onStep(String agent, String phase, String detail);

        /**
         * 带任务原文的进度事件：并行委派时同一个子智能体可能同时接多个任务，
         * 仅凭 agent 名无法区分是哪一条，故把自己也一并透出，供 UI 精确配对卡片。
         * 默认退回三参数版本，顺序委派与测试无需感知。
         */
        default void onTeamStep(String agent, String task, String phase, String detail) {
            onStep(agent, phase, detail);
        }
    }

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

    private static final int TIMEOUT_SEC = 150;

    private SubagentRunner() {
    }

    public static Result run(String presetName, String task, Tools host,
                             LlmFactory factory, ProgressListener progress) {
        return run(presetName, task, host, factory, progress, TIMEOUT_SEC);
    }

    /**
     * @param timeoutSec 子智能体超时秒数（&le;0 时用默认值）；并行编排需要按批控制，
     *                   因此把超时从常量改为可注入。
     */
    public static Result run(String presetName, String task, Tools host,
                             LlmFactory factory, ProgressListener progress, int timeoutSec) {
        return run(presetName, task, task, host, factory, progress, timeoutSec);
    }

    /**
     * @param progressTask 用于 UI 事件（卡片按「agent + 任务原文」配对）的任务文本。
     *                     依赖链编排会把「前序结论」拼进喂给模型的 task，但卡片仍应显示
     *                     用户可读的步骤原文，故把两者拆开；不传时与 task 相同。
     */
    static Result run(String presetName, String task, String progressTask, Tools host,
                      LlmFactory factory, ProgressListener progress, int timeoutSec) {
        final int timeout = timeoutSec > 0 ? timeoutSec : TIMEOUT_SEC;
        final String displayTask = (progressTask == null || progressTask.isEmpty()) ? task : progressTask;
        SubagentPreset preset = SubagentPresets.get(presetName);
        if (preset == null) {
            return new Result(null, "未知子智能体：" + presetName
                    + "（可用：" + availableNames() + "）");
        }
        if (task == null || task.trim().isEmpty()) {
            return new Result(null, "委派任务为空");
        }
        if (factory == null) {
            return new Result(null, "没有可用的模型配置，无法启动子智能体");
        }
        com.digitallife.harness.LlmAdapter llm = factory.create();
        if (llm == null) {
            return new Result(null, "没有可用的模型配置，无法启动子智能体");
        }

        // 独立 plugin 树：子智能体有自己的 session log，不污染主对话历史
        DeepSeekHarness sub = DeepSeekHarness.bootIsolated(null);
        ScopedTools scoped = new ScopedTools(host, preset.tools);
        sub.prompt().setSection("persona", preset.systemPrompt);
        sub.prompt().setToolSchemas(scoped.toJsonArray());

        List<com.digitallife.brain.LLMClient.ChatMessage> seed = new ArrayList<>();
        seed.add(new com.digitallife.brain.LLMClient.ChatMessage("user", task));

        if (progress != null) progress.onTeamStep(preset.name, displayTask, "start", displayTask);

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] text = new String[1];
        final String[] error = new String[1];

        sub.startTurn(llm, scoped, preset.systemPrompt, seed,
                new AgentHandle.Listener() {                    @Override
                    public void onDelta(String t) {
                        // 子智能体正文不直接呈现，只在结束时作为结论回传
                    }

                    @Override
                    public void onToolCall(String name, JSONObject args, String toolCallId) {
                        if (progress != null) {
                            progress.onStep(preset.name, "tool", name + " " + brief(args));
                        }
                    }

                    @Override
                    public void onToolResult(String name, boolean ok, String result) {
                        if (progress != null) {
                            progress.onStep(preset.name, "result",
                                    name + " → " + clip(result));
                        }
                    }

                    @Override
                    public void onDone(String fullText) {
                        text[0] = fullText;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String e) {
                        error[0] = e;
                        latch.countDown();
                    }

                    @Override
                    public void onTurn(String phase) {
                        if ("end".equals(phase)) latch.countDown();
                    }
                }, preset.maxSteps);

        try {
            if (!latch.await(timeout, TimeUnit.SECONDS)) {
                sub.cancel();
                if (progress != null) progress.onStep(preset.name, "done", "超时中断");
                return new Result(null, "子智能体 " + preset.name + " 执行超时（"
                        + timeout + "s）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            sub.cancel();
            return new Result(null, "子智能体执行被中断");
        }

        if (error[0] != null) {
            if (progress != null) progress.onStep(preset.name, "done", "出错");
            return new Result(null, error[0]);
        }
        String out = text[0] == null ? lastAssistantText(sub) : text[0];
        if (out == null || out.trim().isEmpty()) {
            out = "（子智能体 " + preset.name + " 没有产出结论）";
        }
        if (progress != null) {
            progress.onStep(preset.name, "done",
                    out.length() > 120 ? out.substring(0, 120) + "…" : out);
        }
        return new Result(out, null);
    }

    private static String lastAssistantText(DeepSeekHarness sub) {
        if (sub.session() == null) return null;
        List<SessionEvent> all = sub.session().all();
        for (int i = all.size() - 1; i >= 0; i--) {
            SessionEvent e = all.get(i);
            if (SessionEvent.ASSISTANT_MESSAGE.equals(e.type)) {
                String t = e.text();
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    private static String availableNames() {
        StringBuilder sb = new StringBuilder();
        for (SubagentPreset p : SubagentPresets.all()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(p.name);
        }
        return sb.toString();
    }

    private static String brief(JSONObject args) {
        if (args == null) return "";
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

    /** 供 plugin 装配使用：把 delegate_task 注册进宿主工具表 */
    public static void installDelegateTool(Tools host, LlmFactory factory,
                                           ProgressListener progress) {
        if (host == null) return;
        host.register("delegate_task",
                "把一个完整的子任务交给专职子智能体去做，它会独立完成后把结论回传给你。"
                        + "适合步骤多、需要多次工具调用的任务（如查资料要搜很多次、要连查带抓）。"
                        + "简单的事（一两次工具调用就能完成）自己直接做，不要委派。可选子智能体："
                        + SubagentPresets.describeAll(),
                new String[]{"agent", "task"},
                args -> {
                    String agent = args.optString("agent", "");
                    String task = args.optString("task", "");
                    Result r = run(agent, task, host, factory, progress);
                    if (!r.ok()) throw new RuntimeException(r.error);
                    return r.text;
                });
    }

    /**
     * 注册 delegate_parallel：一次并行委派多个互不依赖的子任务（fan-out），
     * 全部结束后汇总各自结论（fan-in）。
     */
    public static void installParallelDelegateTool(Tools host, LlmFactory factory,
                                                   ProgressListener progress) {
        if (host == null) return;
        host.register("delegate_parallel",
                "当有多个互相独立的子任务时，一次性把它们并行交给多个子智能体同时去做，最后汇总结论。"
                        + "适合「同时查几件不相干的事」这类可并行的任务；"
                        + "有先后依赖的任务不要用本工具，改用 delegate_task 逐个来。"
                        + "参数 tasks 是子任务数组，一次最多 " + SubagentTeam.MAX_TASKS + " 个。可选子智能体："
                        + SubagentPresets.describeAll(),
                new String[]{"tasks"},
                args -> {
                    List<SubagentTeam.Task> tasks = parseTasks(args);
                    if (tasks.isEmpty()) {
                        throw new RuntimeException(
                                "tasks 为空：请给出至少一个 {\"agent\":\"...\",\"task\":\"...\"} 子任务");
                    }
                    if (tasks.size() > SubagentTeam.MAX_TASKS) {
                        throw new RuntimeException("一次最多并行 " + SubagentTeam.MAX_TASKS
                                + " 个子任务，请分批调用");
                    }
                    return SubagentTeam.run(tasks, host, factory, progress).render();
                });
        // 同属「团队编排」能力：并行（互不依赖）+ 依赖链（有先后）。一并装配，
        // 调用方（ChatActivity）无需改动即可获得 delegate_pipeline。
        installPipelineDelegateTool(host, factory, progress);
    }

    /**
     * 注册 delegate_pipeline：编排一条**有序依赖链**——逐步串行执行，每一步都能看到
     * 前序步骤的结论，适合「先调研 → 再写稿 → 再校订」这类后一步依赖前一步产出的任务。
     */
    public static void installPipelineDelegateTool(Tools host, LlmFactory factory,
                                                   ProgressListener progress) {
        if (host == null) return;
        host.register("delegate_pipeline",
                "当多个子任务之间有先后依赖、必须按顺序做（后一步要用前一步的结论）时，"
                        + "用本工具编排一条依赖链：逐步串行执行，每一步都能看到前序步骤的结论。"
                        + "例如「先调研 → 再基于调研写稿 → 再校订」。"
                        + "互相独立、可以同时做的任务请改用 delegate_parallel。"
                        + "参数 steps 是有序步骤数组，一条链最多 " + SubagentPipeline.MAX_STEPS + " 步。"
                        + "可选子智能体：" + SubagentPresets.describeAll(),
                new String[]{"steps"},
                args -> {
                    List<SubagentPipeline.Step> steps = parseSteps(args);
                    if (steps.isEmpty()) {
                        throw new RuntimeException(
                                "steps 为空：请给出至少一个 {\"agent\":\"...\",\"task\":\"...\"} 步骤");
                    }
                    if (steps.size() > SubagentPipeline.MAX_STEPS) {
                        throw new RuntimeException("一条依赖链最多 " + SubagentPipeline.MAX_STEPS
                                + " 步，请拆分");
                    }
                    return SubagentPipeline.run(steps, host, factory, progress).render();
                });
    }

    /** 解析 delegate_pipeline 的 steps 参数；兼容模型把数组序列化成字符串的情况 */
    private static List<SubagentPipeline.Step> parseSteps(JSONObject args) {
        List<SubagentPipeline.Step> out = new ArrayList<>();
        if (args == null) return out;
        JSONArray arr = args.optJSONArray("steps");
        if (arr == null) {
            String raw = args.optString("steps", "").trim();
            if (raw.startsWith("[")) {
                try {
                    arr = new JSONArray(raw);
                } catch (Exception ignored) {
                }
            }
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String agent = o.optString("agent", "").trim();
            String task = o.optString("task", "").trim();
            if (agent.isEmpty() && task.isEmpty()) continue;
            out.add(new SubagentPipeline.Step(agent, task));
        }
        return out;
    }

    /** 解析 delegate_parallel 的 tasks 参数；兼容模型把数组序列化成字符串的情况 */
    private static List<SubagentTeam.Task> parseTasks(JSONObject args) {
        List<SubagentTeam.Task> out = new ArrayList<>();
        if (args == null) return out;
        JSONArray arr = args.optJSONArray("tasks");
        if (arr == null) {
            String raw = args.optString("tasks", "").trim();
            if (raw.startsWith("[")) {
                try {
                    arr = new JSONArray(raw);
                } catch (Exception ignored) {
                }
            }
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String agent = o.optString("agent", "").trim();
            String task = o.optString("task", "").trim();
            if (agent.isEmpty() && task.isEmpty()) continue;
            out.add(new SubagentTeam.Task(agent, task));
        }
        return out;
    }
}
