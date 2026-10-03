package com.digitallife.harness.subagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.Tools;
import com.digitallife.harness.LlmAdapter;
import com.digitallife.harness.LlmFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 子智能体 seam 的契约：最小权限、上下文隔离、结论回传。
 */
public class SubagentTest {

    /** 记录被下发的工具 schema 名，用于断言权限收敛 */
    static final class RecordingLlm implements LlmAdapter {
        final List<List<String>> toolNames = new ArrayList<>();
        final List<List<com.digitallife.brain.LLMClient.ChatMessage>> requests =
                new ArrayList<>();
        String reply = "结论";
        int cancelCount;

        @Override
        public void chatStream(List<com.digitallife.brain.LLMClient.ChatMessage> messages,
                               JSONObject extraSystem,
                               com.digitallife.brain.LLMClient.StreamListener listener) {
            requests.add(new ArrayList<>(messages));
            listener.onDelta(reply);
            listener.onDone(reply);
        }

        @Override
        public void setTools(JSONArray tools) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn != null) names.add(fn.optString("name"));
            }
            toolNames.add(names);
        }

        @Override
        public void cancel() {
            cancelCount++;
        }
    }

    private static Tools hostWith(String... names) {
        Tools t = new Tools(true);
        for (String n : names) {
            t.register(n, "工具 " + n, new String[]{}, args -> n + "-ok");
        }
        return t;
    }

    private static LlmFactory factoryOf(final LlmAdapter llm) {
        return () -> llm;
    }

    @Test
    public void rejectsUnknownPreset() {
        SubagentRunner.Result r = SubagentRunner.run("nobody", "做事",
                hostWith("web_search"), factoryOf(new RecordingLlm()), null);
        assertFalse(r.ok());
        assertTrue(r.error.contains("未知子智能体"));
    }

    @Test
    public void rejectsEmptyTask() {
        SubagentRunner.Result r = SubagentRunner.run("researcher", "  ",
                hostWith("web_search"), factoryOf(new RecordingLlm()), null);
        assertFalse(r.ok());
    }

    @Test
    public void rejectsMissingFactory() {
        SubagentRunner.Result r = SubagentRunner.run("researcher", "查资料",
                hostWith("web_search"), null, null);
        assertFalse(r.ok());
    }

    @Test
    public void returnsConclusionText() {
        RecordingLlm llm = new RecordingLlm();
        llm.reply = "查到三条结论";
        SubagentRunner.Result r = SubagentRunner.run("researcher", "查资料",
                hostWith("web_search"), factoryOf(llm), null);
        assertTrue(r.ok());
        assertEquals("查到三条结论", r.text);
    }

    @Test
    public void onlyWhitelistedToolsAreExposed() {
        RecordingLlm llm = new RecordingLlm();
        SubagentRunner.run("researcher", "查资料",
                hostWith("web_search", "web_fetch", "schedule_task", "delete_everything"),
                factoryOf(llm), null);

        assertFalse(llm.toolNames.isEmpty());
        for (List<String> names : llm.toolNames) {
            assertTrue("研究员应拿到 web_search", names.contains("web_search"));
            assertFalse("研究员不应拿到 schedule_task", names.contains("schedule_task"));
            assertFalse("研究员不应拿到 delete_everything", names.contains("delete_everything"));
        }
    }

    @Test
    public void secretaryGetsItsOwnWhitelist() {
        RecordingLlm llm = new RecordingLlm();
        SubagentRunner.run("secretary", "建个提醒",
                hostWith("schedule_task", "web_search"), factoryOf(llm), null);

        for (List<String> names : llm.toolNames) {
            assertTrue(names.contains("schedule_task"));
            assertFalse("日程管家不应拿到 web_search", names.contains("web_search"));
        }
    }

    @Test
    public void subagentContextIsIsolatedFromCaller() {
        RecordingLlm llm = new RecordingLlm();
        SubagentRunner.run("researcher", "只给这一句",
                hostWith("web_search"), factoryOf(llm), null);

        List<com.digitallife.brain.LLMClient.ChatMessage> first = llm.requests.get(0);
        assertEquals("子智能体只应看到任务这一条输入", 1, first.size());
        assertEquals("user", first.get(0).role);
        assertEquals("只给这一句", first.get(0).content);
    }

    @Test
    public void progressReportsStartAndDone() {
        final List<String> phases = new ArrayList<>();
        SubagentRunner.run("researcher", "查资料", hostWith("web_search"),
                factoryOf(new RecordingLlm()), (agent, phase, detail) -> phases.add(phase));
        assertTrue(phases.contains("start"));
        assertTrue(phases.contains("done"));
    }

    @Test
    public void scopedToolsHideNonWhitelisted() {
        Tools parent = hostWith("web_search", "schedule_task");
        ScopedTools scoped = new ScopedTools(parent, new java.util.HashSet<>(
                java.util.Arrays.asList("web_search")));

        assertTrue(scoped.allows("web_search"));
        assertFalse(scoped.allows("schedule_task"));

        JSONArray schemas = scoped.toJsonArray();
        assertEquals(1, schemas.length());
        assertTrue(scoped.describe().contains("web_search"));
        assertFalse(scoped.describe().contains("schedule_task"));
    }

    @Test
    public void scopedToolsRejectOutOfScopeExecution() {
        Tools parent = hostWith("web_search", "schedule_task");
        ScopedTools scoped = new ScopedTools(parent, new java.util.HashSet<>(
                java.util.Arrays.asList("web_search")));

        final String[] err = new String[1];
        scoped.execute("schedule_task", new JSONObject(), (n, a, r, e) -> err[0] = e);
        assertNotNull(err[0]);
        assertTrue(err[0].contains("权限"));
    }

    @Test
    public void scopedToolsDelegateAllowedExecution() {
        Tools parent = hostWith("web_search");
        ScopedTools scoped = new ScopedTools(parent, new java.util.HashSet<>(
                java.util.Arrays.asList("web_search")));

        final String[] res = new String[1];
        scoped.execute("web_search", new JSONObject(), (n, a, r, e) -> res[0] = r);
        assertEquals("web_search-ok", res[0]);
    }

    @Test
    public void presetsExposeDescriptionForDelegateTool() {
        String desc = SubagentPresets.describeAll();
        assertTrue(desc.contains("researcher"));
        assertTrue(desc.contains("secretary"));
        assertTrue("撰稿/审校应可被委派（供「研究→写稿→审校」链条使用）", desc.contains("writer"));
        assertTrue("撰稿/审校应可被委派（供「研究→写稿→审校」链条使用）", desc.contains("critic"));
    }

    @Test
    public void writerAndCriticPresetsAreRegisteredAndReadOnly() {
        for (String name : new String[]{"writer", "critic"}) {
            SubagentPreset p = SubagentPresets.get(name);
            assertNotNull(name + " 必须注册进 preset 注册表", p);
            assertTrue(name + " 不应带写操作/联网工具",
                    !p.tools.contains("web_search") && !p.tools.contains("schedule_task"));
            for (String t : p.tools) {
                assertFalse(name + " 不该有委派工具：" + t, "delegate_task".equals(t));
            }
            assertTrue(name + " 步数应受限", p.maxSteps >= 1 && p.maxSteps <= 3);
        }
    }

    @Test
    public void presetsHaveNoRecursiveDelegation() {
        for (SubagentPreset p : SubagentPresets.all()) {
            assertFalse(p.name + " 不应能再委派",
                    p.tools.contains("delegate_task"));
        }
    }

    @Test
    public void installDelegateToolRegistersSchema() {
        Tools host = new Tools(true);
        SubagentRunner.installDelegateTool(host, factoryOf(new RecordingLlm()), null);
        JSONArray schemas = host.toJsonArray();
        boolean found = false;
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject fn = schemas.optJSONObject(i).optJSONObject("function");
            if (fn != null && "delegate_task".equals(fn.optString("name"))) found = true;
        }
        assertTrue("delegate_task 必须出现在工具表里", found);
    }

    @Test
    public void delegateToolReturnsSubagentConclusion() {
        RecordingLlm llm = new RecordingLlm();
        llm.reply = "子结论";
        Tools host = new Tools(true);
        SubagentRunner.installDelegateTool(host, factoryOf(llm), null);

        JSONObject args = new JSONObject();
        try {
            args.put("agent", "researcher");
            args.put("task", "查资料");
        } catch (Exception ignored) {
        }
        assertEquals("子结论", host.executeSync("delegate_task", args));
    }

    @Test
    public void delegateToolFailsLoudlyOnUnknownAgent() {
        Tools host = new Tools(true);
        SubagentRunner.installDelegateTool(host, factoryOf(new RecordingLlm()), null);
        JSONObject args = new JSONObject();
        try {
            args.put("agent", "nobody");
        } catch (Exception ignored) {
        }
        try {
            host.executeSync("delegate_task", args);
            assertTrue("未知子智能体必须抛错", false);
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("未知子智能体"));
        }
    }

    @Test
    public void presetMaxStepsIsRespected() {
        SubagentPreset p = new SubagentPreset("x", "d", "s", new String[]{}, 0);
        assertEquals("非法步数应回退到 4", 4, p.maxSteps);
    }

    @Test
    public void presetIgnoresBlankToolNames() {
        SubagentPreset p = new SubagentPreset("x", "d", "s",
                new String[]{"a", "", null, "b"}, 3);
        assertEquals(2, p.tools.size());
        assertTrue(p.tools.contains("a"));
        assertTrue(p.tools.contains("b"));
    }

    @Test
    public void scopedToolsHandleNullParent() {
        ScopedTools scoped = new ScopedTools(null, new java.util.HashSet<>(
                java.util.Arrays.asList("a")));
        assertEquals(0, scoped.toJsonArray().length());
        final String[] err = new String[1];
        scoped.execute("a", new JSONObject(), (n, ar, r, e) -> err[0] = e);
        assertNotNull(err[0]);
    }

    @Test
    public void unknownPresetListsAvailableNames() {
        SubagentRunner.Result r = SubagentRunner.run("nobody", "做事",
                hostWith("web_search"), factoryOf(new RecordingLlm()), null);
        assertTrue(r.error.contains("researcher"));
        assertTrue(r.error.contains("secretary"));
    }

    @Test
    public void emptyReplyFallsBackToNotice() {
        RecordingLlm llm = new RecordingLlm();
        llm.reply = "";
        SubagentRunner.Result r = SubagentRunner.run("researcher", "查资料",
                hostWith("web_search"), factoryOf(llm), null);
        assertTrue(r.ok());
        assertNotNull(r.text);
        assertTrue(r.text.contains("没有产出结论"));
    }

    @Test
    public void resultOkReflectsError() {
        SubagentRunner.Result ok = new SubagentRunner.Result("t", null);
        assertTrue(ok.ok());
        SubagentRunner.Result bad = new SubagentRunner.Result(null, "e");
        assertFalse(bad.ok());
        assertNull(bad.text);
    }
}
