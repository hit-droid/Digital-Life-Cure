package com.digitallife.harness.subagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.LLMClient;
import com.digitallife.brain.Tools;
import com.digitallife.harness.LlmAdapter;
import com.digitallife.harness.LlmFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有序依赖链编排（delegate_pipeline）的契约：
 * 逐步串行、前序结论透传给下一步、失败即停、参数校验与工具装配。
 */
public class SubagentPipelineTest {

    /** 记录每次 chatStream 收到的 user 文本，并统计并发度（证明是串行） */
    static final class RecordingFactory implements LlmFactory {
        final String reply;
        final List<String> seenUserTexts =
                Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        RecordingFactory(String reply) {
            this.reply = reply;
        }

        @Override
        public LlmAdapter create() {
            return new LlmAdapter() {
                /** 只记录本适配器的首次调用，保证「一个子智能体 = 一条记录」 */
                private boolean recorded;

                @Override
                public void chatStream(List<LLMClient.ChatMessage> messages, JSONObject extraSystem,
                                       LLMClient.StreamListener listener) {
                    int cur = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(cur, Math::max);
                    try {
                        if (!recorded) {
                            recorded = true;
                            StringBuilder user = new StringBuilder();
                            if (messages != null) {
                                for (LLMClient.ChatMessage m : messages) {
                                    if ("user".equals(m.role) && m.content != null) {
                                        user.append(m.content).append('\n');
                                    }
                                }
                            }
                            seenUserTexts.add(user.toString());
                        }
                        listener.onDelta(reply);
                        listener.onDone(reply);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }

                @Override
                public void setTools(JSONArray tools) {
                }

                @Override
                public void cancel() {
                }
            };
        }
    }

    /** 静默适配器：不回调 onDone，用来验证超时（复用 Team 的用例思路） */
    static final class SilentFactory implements LlmFactory {
        @Override
        public LlmAdapter create() {
            return new LlmAdapter() {
                @Override
                public void chatStream(List<LLMClient.ChatMessage> messages, JSONObject extraSystem,
                                       LLMClient.StreamListener listener) {
                    // 什么都不回，等超时
                }

                @Override
                public void setTools(JSONArray tools) {
                }

                @Override
                public void cancel() {
                }
            };
        }
    }

    private static Tools hostWith(String... names) {
        Tools t = new Tools(true);
        for (String n : names) {
            t.register(n, "工具 " + n, new String[]{}, args -> n + "-ok");
        }
        return t;
    }

    private static List<SubagentPipeline.Step> steps(String... agentTaskPairs) {
        List<SubagentPipeline.Step> out = new ArrayList<>();
        for (int i = 0; i + 1 < agentTaskPairs.length; i += 2) {
            out.add(new SubagentPipeline.Step(agentTaskPairs[i], agentTaskPairs[i + 1]));
        }
        return out;
    }

    // ==================== 编排核心 ====================

    @Test
    public void runsStepsSequentially() {
        RecordingFactory f = new RecordingFactory("结论");
        SubagentPipeline.Report r = SubagentPipeline.run(
                steps("researcher", "第一步", "secretary", "第二步"),
                hostWith("web_search", "schedule_task"), f, null);

        assertEquals(2, r.okCount());
        assertEquals("依赖链必须串行，不能并发", 1, f.maxInFlight.get());
    }

    @Test
    public void nextStepReceivesPreviousConclusion() {
        RecordingFactory f = new RecordingFactory("结论甲");
        SubagentPipeline.Report r = SubagentPipeline.run(
                steps("researcher", "先调研", "secretary", "再执行"),
                hostWith("web_search", "schedule_task"), f, null);

        assertEquals(2, r.okCount());
        assertEquals(2, f.seenUserTexts.size());
        assertTrue("第一步不该拿到前序结论：" + f.seenUserTexts.get(0),
                !f.seenUserTexts.get(0).contains("前序步骤的结论"));
        assertTrue("第二步必须拿到前序结论：" + f.seenUserTexts.get(1),
                f.seenUserTexts.get(1).contains("前序步骤的结论"));
        assertTrue("第二步必须看到第一步的结论原文：" + f.seenUserTexts.get(1),
                f.seenUserTexts.get(1).contains("结论甲"));
        assertTrue("第二步必须保留自己的任务原文：" + f.seenUserTexts.get(1),
                f.seenUserTexts.get(1).contains("再执行"));
    }

    @Test
    public void failureStopsChainAndMarksRestSkipped() {
        SubagentPipeline.Report r = SubagentPipeline.run(
                steps("researcher", "第一步", "nobody", "第二步", "secretary", "第三步"),
                hostWith("web_search", "schedule_task"), new RecordingFactory("结论"), null);

        assertEquals(1, r.okCount());
        assertEquals(1, r.failCount());
        assertEquals(1, r.skippedCount());
        assertTrue("失败原因应回传", r.steps.get(1).error.contains("未知子智能体"));
        assertTrue("失败之后的步骤必须标记未执行", r.steps.get(2).skipped);
        String text = r.render();
        assertTrue(text.contains("1/3 成功"));
        assertTrue(text.contains("未执行"));
    }

    @Test
    public void timeoutIsReportedAndStopsChain() {
        SubagentPipeline.Report r = SubagentPipeline.run(
                steps("researcher", "永远不回的活", "secretary", "后续"),
                hostWith("web_search", "schedule_task"), new SilentFactory(), null, 1);

        assertEquals(0, r.okCount());
        assertEquals(1, r.failCount());
        assertEquals(1, r.skippedCount());
        assertTrue(r.steps.get(0).error.contains("超时"));
    }

    @Test
    public void emptyStepListYieldsEmptyReport() {
        SubagentPipeline.Report r = SubagentPipeline.run(null, hostWith("web_search"),
                new RecordingFactory("x"), null);
        assertTrue(r.steps.isEmpty());
        assertTrue(r.render().contains("没有可执行的步骤"));
    }

    @Test
    public void progressEventsUseOriginalTaskTextNotDecorated() {
        // 依赖链会把「前序结论」拼进喂给模型的文本，但卡片必须用原始任务原文配对
        final List<String> teamTasks = new ArrayList<>();
        SubagentRunner.ProgressListener p = new SubagentRunner.ProgressListener() {
            @Override
            public void onStep(String agent, String phase, String detail) {
            }

            @Override
            public void onTeamStep(String agent, String task, String phase, String detail) {
                if ("start".equals(phase) || "team-ok".equals(phase)) {
                    teamTasks.add(task);
                }
            }
        };

        SubagentPipeline.run(steps("researcher", "先调研", "secretary", "再执行"),
                hostWith("web_search", "schedule_task"), new RecordingFactory("结论甲"), p);

        assertTrue("start/team-ok 必须带原始任务原文：" + teamTasks,
                teamTasks.contains("先调研"));
        assertTrue("start/team-ok 必须带原始任务原文：" + teamTasks,
                teamTasks.contains("再执行"));
        for (String t : teamTasks) {
            assertTrue("UI 事件不应带拼接后的前序结论：" + t,
                    !t.contains("前序步骤的结论"));
        }
    }

    @Test
    public void stepTrimsInput() {
        SubagentPipeline.Step s = new SubagentPipeline.Step("  researcher ", "  先调研  ");
        assertEquals("researcher", s.agent);
        assertEquals("先调研", s.task);
        SubagentPipeline.Step n = new SubagentPipeline.Step(null, null);
        assertEquals("", n.agent);
        assertEquals("", n.task);
    }

    // ==================== delegate_pipeline 工具 ====================

    private static Tools hostWithPipelineTool(LlmFactory f) {
        Tools host = new Tools(true);
        SubagentRunner.installPipelineDelegateTool(host, f, null);
        return host;
    }

    private static JSONObject twoSteps() {
        JSONObject args = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("agent", "researcher").put("task", "先调研"));
            arr.put(new JSONObject().put("agent", "secretary").put("task", "再执行"));
            args.put("steps", arr);
        } catch (Exception ignored) {
        }
        return args;
    }

    @Test
    public void pipelineToolRegistersArraySchema() {
        Tools host = hostWithPipelineTool(new RecordingFactory("x"));
        JSONObject found = null;
        JSONArray schemas = host.toJsonArray();
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject fn = schemas.optJSONObject(i).optJSONObject("function");
            if (fn != null && "delegate_pipeline".equals(fn.optString("name"))) found = fn;
        }
        assertNotNull("delegate_pipeline 必须出现在工具表里", found);
        JSONObject stepsProp = found.optJSONObject("parameters")
                .optJSONObject("properties").optJSONObject("steps");
        assertNotNull(stepsProp);
        assertEquals("array", stepsProp.optString("type"));
        assertEquals("object", stepsProp.optJSONObject("items").optString("type"));
    }

    @Test
    public void pipelineToolReturnsStepConclusions() {
        Tools host = hostWithPipelineTool(new RecordingFactory("统一结论"));
        String out = host.executeSync("delegate_pipeline", twoSteps());
        assertTrue(out.contains("2/2 成功"));
        assertTrue(out.contains("researcher"));
        assertTrue(out.contains("统一结论"));
    }

    @Test
    public void pipelineToolParsesStringEncodedSteps() {
        Tools host = hostWithPipelineTool(new RecordingFactory("统一结论"));
        JSONObject args = new JSONObject();
        try {
            args.put("steps", "[{\"agent\":\"researcher\",\"task\":\"先调研\"}]");
        } catch (Exception ignored) {
        }
        assertTrue(host.executeSync("delegate_pipeline", args).contains("1/1 成功"));
    }

    @Test
    public void pipelineToolRejectsEmptySteps() {
        Tools host = hostWithPipelineTool(new RecordingFactory("x"));
        try {
            host.executeSync("delegate_pipeline", new JSONObject());
            assertTrue("空 steps 必须抛错", false);
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("steps 为空"));
        }
    }

    @Test
    public void pipelineToolRejectsTooManySteps() {
        Tools host = hostWithPipelineTool(new RecordingFactory("x"));
        JSONObject args = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < SubagentPipeline.MAX_STEPS + 1; i++) {
                arr.put(new JSONObject().put("agent", "researcher").put("task", "t" + i));
            }
            args.put("steps", arr);
        } catch (Exception ignored) {
        }
        try {
            host.executeSync("delegate_pipeline", args);
            assertTrue("超量 steps 必须抛错", false);
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("最多"));
        }
    }

    @Test
    public void parallelInstallerAlsoInstallsPipeline() {
        Tools host = new Tools(true);
        SubagentRunner.installParallelDelegateTool(host, new RecordingFactory("x"), null);
        JSONArray schemas = host.toJsonArray();
        boolean hasParallel = false;
        boolean hasPipeline = false;
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject fn = schemas.optJSONObject(i).optJSONObject("function");
            if (fn == null) continue;
            if ("delegate_parallel".equals(fn.optString("name"))) hasParallel = true;
            if ("delegate_pipeline".equals(fn.optString("name"))) hasPipeline = true;
        }
        assertTrue("并行编排装配时也应带上依赖链工具", hasParallel && hasPipeline);
    }

    @Test
    public void pipelineToolInstallWithNullHostIsNoOp() {
        SubagentRunner.installPipelineDelegateTool(null, new RecordingFactory("x"), null);
    }

    @Test
    public void maxStepsConstantIsBounded() {
        assertTrue(SubagentPipeline.MAX_STEPS >= 2 && SubagentPipeline.MAX_STEPS <= 8);
    }
}
