package com.digitallife.harness.subagent;

import com.digitallife.brain.Tools;
import com.digitallife.harness.AgentHandle;
import com.digitallife.harness.DeepSeekHarness;
import com.digitallife.harness.LlmFactory;
import com.digitallife.harness.SessionEvent;

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
         * @param phase  "start" 开始 / "tool" 调用工具 / "result" 工具结果 / "done" 结束
         * @param detail 具体说明
         */
        void onStep(String agent, String phase, String detail);
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

        if (progress != null) progress.onStep(preset.name, "start", task);

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
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                sub.cancel();
                if (progress != null) progress.onStep(preset.name, "done", "超时中断");
                return new Result(null, "子智能体 " + preset.name + " 执行超时（"
                        + TIMEOUT_SEC + "s）");
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
}
