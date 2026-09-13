package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.Tools;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具把关与审计的契约：禁用策略在 pre-execute 短路，使用日志在 post-execute 记录。
 * 这层策略原先散在各大脑里，收敛到流水线后必须保证两条都在。
 */
public class ToolGovernanceTest {

    /** 模拟 AICore 的装配：禁用策略 + 使用日志 */
    private static ToolPipeline pipelineWith(List<String> log, List<String> disabled) {
        EventBus bus = new EventBus();
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call,
                                            EventBus.Next<ToolPipeline.Call> next) {
                if (call == null) return null;
                if (disabled.contains(call.name)) {
                    call.rejected = true;
                    call.error = "工具已被用户禁用：" + call.name;
                    return call;
                }
                return next.apply(call);
            }
        });
        bus.addWaterfall("tools/post-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call,
                                            EventBus.Next<ToolPipeline.Call> next) {
                if (call != null) {
                    log.add(call.name + (call.error == null ? ":ok" : ":fail"));
                }
                return next.apply(call);
            }
        });
        ToolPipeline p = new ToolPipeline(bus);
        Tools host = new Tools(true);
        host.register("echo", "回显", new String[]{}, args -> "pong");
        host.register("boom", "抛错", new String[]{}, args -> {
            throw new IllegalStateException("炸了");
        });
        p.setHost(host);
        return p;
    }

    @Test
    public void disabledToolIsRejectedWithoutExecuting() {
        List<String> log = new ArrayList<>();
        ToolPipeline p = pipelineWith(log, java.util.Arrays.asList("echo"));

        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "call_1");
        assertTrue(c.rejected);
        assertNotNull(c.error);
        assertEquals(null, c.result);
    }

    @Test
    public void rejectedToolStillProducesAuditEntry() {
        List<String> log = new ArrayList<>();
        ToolPipeline p = pipelineWith(log, java.util.Arrays.asList("echo"));

        p.execute("echo", new JSONObject(), "call_1");
        assertEquals("被拒绝的调用也必须留下审计记录", 1, log.size());
        assertEquals("echo:fail", log.get(0));
    }

    @Test
    public void allowedToolExecutesAndIsAudited() {
        List<String> log = new ArrayList<>();
        ToolPipeline p = pipelineWith(log, new ArrayList<>());

        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "call_1");
        assertFalse(c.rejected);
        assertEquals("pong", c.result);
        assertEquals(1, log.size());
        assertEquals("echo:ok", log.get(0));
    }

    @Test
    public void failingToolIsAuditedAsFailure() {
        List<String> log = new ArrayList<>();
        ToolPipeline p = pipelineWith(log, new ArrayList<>());

        ToolPipeline.Call c = p.execute("boom", new JSONObject(), "call_1");
        assertNotNull(c.error);
        assertEquals(1, log.size());
        assertEquals("boom:fail", log.get(0));
    }

    @Test
    public void everyCallProducesExactlyOneAuditEntry() {
        List<String> log = new ArrayList<>();
        ToolPipeline p = pipelineWith(log, java.util.Arrays.asList("boom"));

        p.execute("echo", new JSONObject(), "c1");
        p.execute("boom", new JSONObject(), "c2");
        p.execute("echo", new JSONObject(), "c3");
        assertEquals(3, log.size());
    }

    @Test
    public void rejectionShortCircuitsLaterPreHooks() {
        EventBus bus = new EventBus();
        final boolean[] secondRan = new boolean[1];
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call,
                                            EventBus.Next<ToolPipeline.Call> next) {
                call.rejected = true;
                return call;
            }
        });
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call,
                                            EventBus.Next<ToolPipeline.Call> next) {
                secondRan[0] = true;
                return next.apply(call);
            }
        });
        ToolPipeline p = new ToolPipeline(bus);
        Tools host = new Tools(true);
        host.register("echo", "回显", new String[]{}, args -> "pong");
        p.setHost(host);

        p.execute("echo", new JSONObject(), "c1");
        assertFalse(secondRan[0]);
    }
}
