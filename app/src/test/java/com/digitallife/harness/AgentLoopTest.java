package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.LLMClient;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * agent-loop 的契约：turn/step 流程、工具往返续步、上限收尾、取消与过期回调隔离。
 */
public class AgentLoopTest {

    private DeepSeekHarness harness;
    private ScriptedLlm llm;
    private RecordingListener listener;

    /** 记录回调顺序，供断言流程正确性 */
    static final class RecordingListener implements AgentHandle.Listener {
        final List<String> events = new ArrayList<>();
        String doneText;
        String error;
        int doneCount;
        int errorCount;

        @Override
        public void onDelta(String text) {
            events.add("delta:" + text);
        }

        @Override
        public void onToolCall(String name, JSONObject args, String toolCallId) {
            events.add("tool:" + name);
        }

        @Override
        public void onToolResult(String name, boolean ok, String result) {
            events.add("result:" + name + ":" + ok);
        }

        @Override
        public void onDone(String fullText) {
            events.add("done");
            doneCount++;
            doneText = fullText;
        }

        @Override
        public void onError(String error) {
            events.add("error");
            errorCount++;
            this.error = error;
        }

        @Override
        public void onTurn(String phase) {
            events.add("turn:" + phase);
        }
    }

    @Before
    public void setUp() {
        harness = DeepSeekHarness.boot(null);
        llm = new ScriptedLlm();
        listener = new RecordingListener();
    }

    private List<LLMClient.ChatMessage> history(String... pairs) {
        List<LLMClient.ChatMessage> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.add(new LLMClient.ChatMessage(pairs[i], pairs[i + 1]));
        }
        return out;
    }

    private AgentHandle start(List<LLMClient.ChatMessage> history) {
        return harness.startTurn(llm, null, "人设", history, listener);
    }

    @Test
    public void plainReplyEndsTurnInOneStep() {
        llm.enqueue(ScriptedLlm.text("你好呀"));
        start(history("user", "在吗"));

        assertEquals(1, llm.streamCount);
        assertEquals(1, listener.doneCount);
        assertEquals("你好呀", listener.doneText);
        assertEquals(0, listener.errorCount);

        List<SessionEvent> types = harness.session().all();
        assertTrue(hasType(types, SessionEvent.TURN_START));
        assertTrue(hasType(types, SessionEvent.STEP_START));
        assertTrue(hasType(types, SessionEvent.ASSISTANT_MESSAGE));
        assertTrue(hasType(types, SessionEvent.STEP_END));
        assertTrue(hasType(types, SessionEvent.TURN_END));
        assertTrue(hasType(types, SessionEvent.REQUEST_HEADER));
        assertFalse(harness.isBusy());
        assertEquals(0, harness.agents().size());
    }

    @Test
    public void seedsHistoryIntoSessionLog() {
        llm.enqueue(ScriptedLlm.text("嗯"));
        start(history("user", "第一句", "assistant", "第一答", "user", "第二句"));

        List<LLMClient.ChatMessage> sent = llm.requests.get(0);
        assertEquals(3, sent.size());
        assertEquals("第一句", sent.get(0).content);
        assertEquals("第一答", sent.get(1).content);
        assertEquals("第二句", sent.get(2).content);
    }

    @Test
    public void systemPromptTravelsAsExtraNotAsMessage() {
        llm.enqueue(ScriptedLlm.text("好"));
        start(history("user", "hi"));

        for (LLMClient.ChatMessage m : llm.requests.get(0)) {
            assertFalse("system 不应混进消息链", "system".equals(m.role));
        }
        assertNotNull(llm.extras.get(0));
        assertTrue("人设必须出现在 extra.system",
                llm.extras.get(0).optString("system").contains("人设"));
        assertTrue("ClockPlugin 必须织进当前时间",
                llm.extras.get(0).optString("system").contains("当前时间："));
    }

    @Test
    public void toolCallContinuesToNextStep() {
        JSONObject args = new JSONObject();
        try {
            args.put("q", "天气");
        } catch (Exception ignored) {
        }
        llm.enqueue(ScriptedLlm.call("unknown_tool", args), ScriptedLlm.text("查到了"));

        start(history("user", "查天气"));

        assertEquals(2, llm.streamCount);
        assertEquals(1, listener.doneCount);
        assertEquals("查到了", listener.doneText);
        assertTrue(listener.events.contains("tool:unknown_tool"));
        assertTrue(listener.events.contains("result:unknown_tool:false"));
        assertFalse(harness.isBusy());
    }

    @Test
    public void toolRoundTripIsProjectedIntoSecondRequest() {
        JSONObject args = new JSONObject();
        llm.enqueue(ScriptedLlm.call("unknown_tool", args), ScriptedLlm.text("完成"));

        start(history("user", "做事"));

        List<LLMClient.ChatMessage> second = llm.requests.get(1);
        boolean sawAssistantWithCalls = false;
        boolean sawToolResult = false;
        for (LLMClient.ChatMessage m : second) {
            if ("assistant".equals(m.role) && m.toolCalls != null && m.toolCalls.length() > 0) {
                sawAssistantWithCalls = true;
            }
            if ("tool".equals(m.role)) {
                sawToolResult = true;
                assertNotNull("tool 消息必须带 tool_call_id", m.toolCallId);
            }
        }
        assertTrue("第二次请求必须包含 assistant(tool_calls)", sawAssistantWithCalls);
        assertTrue("第二次请求必须包含 tool 结果", sawToolResult);
    }

    @Test
    public void stepCapStopsRunawayToolLoop() {
        JSONObject args = new JSONObject();
        ScriptedLlm.Turn[] turns = new ScriptedLlm.Turn[12];
        for (int i = 0; i < turns.length; i++) turns[i] = ScriptedLlm.call("unknown_tool", args);
        llm.enqueue(turns);

        start(history("user", "无限工具"));

        assertEquals("步数上限必须封顶请求次数", DeepSeekHarness.MAX_STEPS, llm.streamCount);
        assertTrue("上限收尾必须回调 onDone", listener.doneCount >= 1);
        assertTrue("上限收尾必须判定为工具收尾", harness.endedOnTool());
        assertFalse(harness.isBusy());
    }

    @Test
    public void stepCapEmitsTurnEnd() {
        JSONObject args = new JSONObject();
        ScriptedLlm.Turn[] turns = new ScriptedLlm.Turn[12];
        for (int i = 0; i < turns.length; i++) turns[i] = ScriptedLlm.call("unknown_tool", args);
        llm.enqueue(turns);

        start(history("user", "无限工具"));

        List<SessionEvent> all = harness.session().all();
        assertEquals("只应有一个 turn/end", 1, countType(all, SessionEvent.TURN_END));
    }

    @Test
    public void errorSurfacesAndClosesTurn() {
        llm.enqueue();
        start(history("user", "hi"));

        assertEquals(1, listener.errorCount);
        assertEquals(0, listener.doneCount);
        assertTrue(hasType(harness.session().all(), SessionEvent.ASSISTANT_ATTEMPT));
        assertTrue(hasType(harness.session().all(), SessionEvent.TURN_END));
        assertFalse(harness.isBusy());
    }

    @Test
    public void cancelMarksTurnAndSuppressesCompletion() {
        llm.enqueue(ScriptedLlm.text("半句话"));
        AgentHandle handle = start(history("user", "hi"));
        harness.cancel();

        assertTrue(handle.cancelled);
        assertTrue(handle.retired);
        assertFalse(harness.isBusy());
        assertEquals(1, llm.cancelCount);
    }

    @Test
    public void staleCallbacksDoNotPolluteNextTurn() {
        llm.enqueue(ScriptedLlm.text("第一轮"));
        AgentHandle first = start(history("user", "问题一"));

        llm.enqueue(ScriptedLlm.text("第二轮"));
        AgentHandle second = start(history("user", "问题二"));

        assertTrue("旧句柄必须标记为退休", first.retired);
        assertFalse(second.retired);

        List<SessionEvent> all = harness.session().all();
        assertEquals("新轮次的日志只应有自己的 turn/end", 1, countType(all, SessionEvent.TURN_END));
        assertEquals("新轮次的日志只应有自己的 turn/start", 1, countType(all, SessionEvent.TURN_START));
    }

    @Test
    public void generationIsolatesToolCallbacksAcrossTurns() {
        JSONObject args = new JSONObject();
        llm.enqueue(ScriptedLlm.call("unknown_tool", args), ScriptedLlm.text("旧轮"));
        start(history("user", "旧问题"));

        llm.enqueue(ScriptedLlm.text("新轮"));
        start(history("user", "新问题"));

        List<SessionEvent> all = harness.session().all();
        assertEquals("旧轮次的 tool/result 不应写进新轮次日志",
                0, countType(all, SessionEvent.TOOL_RESULT));
    }

    @Test
    public void toolSchemasArePushedToProvider() {
        llm.enqueue(ScriptedLlm.text("好"));
        start(history("user", "hi"));
        assertTrue("每步都应下发工具 schema", llm.toolSchemas.size() >= 1);
    }

    @Test
    public void promptSectionIsRendered() {
        llm.enqueue(ScriptedLlm.text("好"));
        start(history("user", "hi"));
        assertTrue(harness.prompt().render().contains("人设"));
        assertTrue(harness.prompt().section("clock").contains("当前时间："));
    }

    @Test
    public void endedOnToolIsFalseForPlainReply() {
        llm.enqueue(ScriptedLlm.text("普通回答"));
        start(history("user", "hi"));
        assertFalse(harness.endedOnTool());
    }

    @Test
    public void compressorDropsOldMessagesFromWireHistory() {
        String blob = new String(new char[800]).replace('\0', 'x');
        List<LLMClient.ChatMessage> hist = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            hist.add(new LLMClient.ChatMessage("user", blob + i));
            hist.add(new LLMClient.ChatMessage("assistant", blob + "a" + i));
        }
        llm.enqueue(ScriptedLlm.text("收"));
        start(hist);

        List<LLMClient.ChatMessage> sent = llm.requests.get(0);
        assertTrue("压缩后条数必须小于原文", sent.size() < hist.size());
        assertTrue(sent.get(0).content.contains("已省略更早的"));
    }

    @Test
    public void cancelOnIdleHarnessIsSafe() {
        harness.cancel();
        assertFalse(harness.isBusy());
        assertNull(harness.liveAgent());
    }

    private static boolean hasType(List<SessionEvent> events, String type) {
        for (SessionEvent e : events) if (type.equals(e.type)) return true;
        return false;
    }

    private static int countType(List<SessionEvent> events, String type) {
        int n = 0;
        for (SessionEvent e : events) if (type.equals(e.type)) n++;
        return n;
    }
}
