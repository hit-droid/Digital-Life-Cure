package com.digitallife.harness.subagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.Tools;
import com.digitallife.harness.LlmAdapter;
import com.digitallife.harness.LlmFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行子智能体编排（fan-out / fan-in）的契约：
 * 真并发、失败隔离、结果汇总、参数校验。
 */
public class SubagentTeamTest {

    /** 可配置的假适配器：可阻塞于栅栏（证明并发）、可静默（证明超时） */
    static final class FakeLlm implements LlmAdapter {
        final String reply;
        final CountDownLatch barrier;
        final AtomicInteger inFlight;
        final AtomicInteger maxInFlight;
        final boolean silent;

        FakeLlm(String reply, CountDownLatch barrier,
                AtomicInteger inFlight, AtomicInteger maxInFlight, boolean silent) {
            this.reply = reply;
            this.barrier = barrier;
            this.inFlight = inFlight;
            this.maxInFlight = maxInFlight;
            this.silent = silent;
        }

        @Override
        public void chatStream(List<com.digitallife.brain.LLMClient.ChatMessage> messages,
                               JSONObject extraSystem,
                               com.digitallife.brain.LLMClient.StreamListener listener) {
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            try {
                if (barrier != null) {
                    barrier.countDown();
                    try {
                        barrier.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (!silent) {
                    listener.onDelta(reply);
                    listener.onDone(reply);
                }
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
    }

    /** 每次 create() 都造一个新假适配器，模拟并行跑在不同线程上 */
    static final class FakeFactory implements LlmFactory {
        final String reply;
        final CountDownLatch barrier;
        final boolean silent;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        FakeFactory(String reply, CountDownLatch barrier, boolean silent) {
            this.reply = reply;
            this.barrier = barrier;
            this.silent = silent;
        }

        @Override
        public LlmAdapter create() {
            return new FakeLlm(reply, barrier, inFlight, maxInFlight, silent);
        }
    }

    private static Tools hostWith(String... names) {
        Tools t = new Tools(true);
        for (String n : names) {
            t.register(n, "工具 " + n, new String[]{}, args -> n + "-ok");
        }
        return t;
    }

    private static List<SubagentTeam.Task> tasks(String... agentTaskPairs) {
        List<SubagentTeam.Task> out = new ArrayList<>();
        for (int i = 0; i + 1 < agentTaskPairs.length; i += 2) {
            out.add(new SubagentTeam.Task(agentTaskPairs[i], agentTaskPairs[i + 1]));
        }
        return out;
    }

    // ==================== 编排核心 ====================

    @Test
    public void runsIndependentTasksConcurrently() {
        CountDownLatch barrier = new CountDownLatch(2);
        FakeFactory f = new FakeFactory("结论", barrier, false);

        SubagentTeam.Report r = SubagentTeam.run(
                tasks("researcher", "查 A", "secretary", "查 B"),
                hostWith("web_search", "schedule_task"), f, null);

        assertEquals(2, r.okCount());
        assertEquals("两个子任务必须同时在跑（否则栅栏会超时）", 2, f.maxInFlight.get());
    }

    @Test
    public void oneBadTaskDoesNotBreakTheBatch() {
        SubagentTeam.Report r = SubagentTeam.run(
                tasks("researcher", "查 A", "nobody", "查 B"),
                hostWith("web_search"), new FakeFactory("结论", null, false), null);

        assertEquals(1, r.okCount());
        assertEquals(1, r.failCount());
        String text = r.render();
        assertTrue(text.contains("1/2 成功"));
        assertTrue(text.contains("未知子智能体"));
    }

    @Test
    public void aggregateListsEveryAgentAndConclusion() {
        SubagentTeam.Report r = SubagentTeam.run(
                tasks("researcher", "查 A", "secretary", "查 B"),
                hostWith("web_search", "schedule_task"),
                new FakeFactory("统一结论", null, false), null);

        String text = r.render();
        assertTrue(text.contains("2/2 成功"));
        assertTrue(text.contains("researcher"));
        assertTrue(text.contains("secretary"));
        assertTrue(text.contains("统一结论"));
    }

    @Test
    public void timeoutIsReportedPerTask() {
        SubagentTeam.Report r = SubagentTeam.run(
                tasks("researcher", "永远不回的活"),
                hostWith("web_search"), new FakeFactory("x", null, true), null, 1);

        assertEquals(0, r.okCount());
        assertEquals(1, r.failCount());
        assertTrue(r.items.get(0).error.contains("超时"));
    }

    @Test
    public void emptyTaskListYieldsEmptyReport() {
        SubagentTeam.Report r = SubagentTeam.run(null, hostWith("web_search"),
                new FakeFactory("x", null, false), null);
        assertTrue(r.items.isEmpty());
        assertTrue(r.render().contains("没有可执行的子任务"));
    }

    @Test
    public void teamEmitsStartAndTeamResultPhases() {
        final List<String> phases = new ArrayList<>();
        SubagentRunner.ProgressListener p = (agent, phase, detail) -> phases.add(phase);
        SubagentTeam.run(tasks("researcher", "查 A", "nobody", "查 B"),
                hostWith("web_search"), new FakeFactory("结论", null, false), p);

        assertTrue(phases.contains("start"));
        assertTrue("成功的子任务必须发 team-ok", phases.contains("team-ok"));
        assertTrue("失败的子任务必须发 team-fail", phases.contains("team-fail"));
    }

    @Test
    public void teamStepsCarryTaskSoDuplicateAgentsAreDistinguishable() {
        // 同一个 agent 并发接多个任务时，UI 必须靠 task 区分卡片，否则会互相覆盖
        final List<String> events = new ArrayList<>();
        SubagentRunner.ProgressListener p = new SubagentRunner.ProgressListener() {
            @Override
            public void onStep(String agent, String phase, String detail) {
            }

            @Override
            public void onTeamStep(String agent, String task, String phase, String detail) {
                events.add(agent + "|" + task + "|" + phase);
            }
        };

        SubagentTeam.run(tasks("researcher", "查 A", "researcher", "查 B"),
                hostWith("web_search"), new FakeFactory("结论", null, false), p);

        assertTrue("start 必须带上任务原文：" + events,
                events.contains("researcher|查 A|start"));
        assertTrue("start 必须带上任务原文：" + events,
                events.contains("researcher|查 B|start"));
        assertTrue("team-ok 必须带上任务原文：" + events,
                events.contains("researcher|查 A|team-ok"));
        assertTrue("team-ok 必须带上任务原文：" + events,
                events.contains("researcher|查 B|team-ok"));
    }

    @Test
    public void taskTrimsInput() {
        SubagentTeam.Task t = new SubagentTeam.Task("  researcher ", "  查资料  ");
        assertEquals("researcher", t.agent);
        assertEquals("查资料", t.task);
        SubagentTeam.Task n = new SubagentTeam.Task(null, null);
        assertEquals("", n.agent);
        assertEquals("", n.task);
    }

    // ==================== delegate_parallel 工具 ====================

    private static Tools hostWithParallelTool(LlmFactory f) {
        Tools host = new Tools(true);
        SubagentRunner.installParallelDelegateTool(host, f, null);
        return host;
    }

    private static JSONObject twoTasks() {
        JSONObject args = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("agent", "researcher").put("task", "查 A"));
            arr.put(new JSONObject().put("agent", "secretary").put("task", "查 B"));
            args.put("tasks", arr);
        } catch (Exception ignored) {
        }
        return args;
    }

    @Test
    public void parallelToolRegistersArraySchema() {
        Tools host = hostWithParallelTool(new FakeFactory("x", null, false));
        JSONArray schemas = host.toJsonArray();
        JSONObject found = null;
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject fn = schemas.optJSONObject(i).optJSONObject("function");
            if (fn != null && "delegate_parallel".equals(fn.optString("name"))) found = fn;
        }
        assertNotNull("delegate_parallel 必须出现在工具表里", found);
        JSONObject tasksProp = found.optJSONObject("parameters")
                .optJSONObject("properties").optJSONObject("tasks");
        assertNotNull(tasksProp);
        assertEquals("array", tasksProp.optString("type"));
        assertEquals("object", tasksProp.optJSONObject("items").optString("type"));
    }

    @Test
    public void parallelToolReturnsAggregatedConclusions() {
        Tools host = hostWithParallelTool(new FakeFactory("统一结论", null, false));
        String out = host.executeSync("delegate_parallel", twoTasks());
        assertTrue(out.contains("2/2 成功"));
        assertTrue(out.contains("researcher"));
        assertTrue(out.contains("统一结论"));
    }

    @Test
    public void parallelToolParsesStringEncodedTasks() {
        Tools host = hostWithParallelTool(new FakeFactory("统一结论", null, false));
        JSONObject args = new JSONObject();
        try {
            args.put("tasks",
                    "[{\"agent\":\"researcher\",\"task\":\"查 A\"}]");
        } catch (Exception ignored) {
        }
        String out = host.executeSync("delegate_parallel", args);
        assertTrue(out.contains("1/1 成功"));
    }

    @Test
    public void parallelToolRejectsEmptyTasks() {
        Tools host = hostWithParallelTool(new FakeFactory("x", null, false));
        try {
            host.executeSync("delegate_parallel", new JSONObject());
            assertTrue("空 tasks 必须抛错", false);
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("tasks 为空"));
        }
    }

    @Test
    public void parallelToolRejectsTooManyTasks() {
        Tools host = hostWithParallelTool(new FakeFactory("x", null, false));
        JSONObject args = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < SubagentTeam.MAX_TASKS + 1; i++) {
                arr.put(new JSONObject().put("agent", "researcher").put("task", "t" + i));
            }
            args.put("tasks", arr);
        } catch (Exception ignored) {
        }
        try {
            host.executeSync("delegate_parallel", args);
            assertTrue("超量 tasks 必须抛错", false);
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("最多并行"));
        }
    }

    @Test
    public void parallelToolInstallWithNullHostIsNoOp() {
        SubagentRunner.installParallelDelegateTool(null, new FakeFactory("x", null, false), null);
    }

    @Test
    public void presetsStillHaveNoRecursiveDelegation() {
        for (SubagentPreset p : SubagentPresets.all()) {
            assertFalse(p.name + " 不应能再委派",
                    p.tools.contains("delegate_task") || p.tools.contains("delegate_parallel"));
        }
    }

    @Test
    public void maxTasksConstantIsBounded() {
        assertTrue(SubagentTeam.MAX_TASKS >= 2 && SubagentTeam.MAX_TASKS <= 8);
    }
}
