package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.Tools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * ToolPipeline 的契约：三段 waterfall 的顺序、拒绝短路、宿主缺失与异常兜底。
 */
public class ToolPipelineTest {

    private static Tools hostWith(String name, Tools.Executor ex) {
        Tools t = new Tools(true);
        t.register(name, "测试工具", new String[]{}, ex);
        return t;
    }

    @Test
    public void executesThroughHost() {
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("echo", args -> "pong"));
        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "call_1");
        assertNull(c.error);
        assertEquals("pong", c.result);
    }

    @Test
    public void hostExceptionBecomesError() {
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("boom", args -> {
            throw new IllegalStateException("炸了");
        }));
        ToolPipeline.Call c = p.execute("boom", new JSONObject(), "call_1");
        assertNotNull(c.error);
        assertTrue(c.error.contains("炸了"));
        assertNull(c.result);
    }

    @Test
    public void missingHostReportsError() {
        ToolPipeline p = new ToolPipeline(new EventBus());
        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "call_1");
        assertNotNull(c.error);
    }

    @Test
    public void preExecuteRejectionSkipsHost() {
        EventBus bus = new EventBus();
        final boolean[] ran = new boolean[1];
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call, EventBus.Next<ToolPipeline.Call> next) {
                call.rejected = true;
                call.error = "被禁用";
                return call;
            }
        });
        ToolPipeline p = new ToolPipeline(bus);
        p.setHost(hostWith("echo", args -> {
            ran[0] = true;
            return "pong";
        }));

        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "call_1");
        assertTrue(c.rejected);
        assertEquals("被禁用", c.error);
        assertEquals(false, ran[0]);
    }

    @Test
    public void preExecuteCanRewriteArguments() {
        EventBus bus = new EventBus();
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call, EventBus.Next<ToolPipeline.Call> next) {
                try {
                    call.args.put("injected", "yes");
                } catch (Exception ignored) {
                }
                return next.apply(call);
            }
        });
        ToolPipeline p = new ToolPipeline(bus);
        p.setHost(hostWith("echo", args -> args.optString("injected", "no")));

        assertEquals("yes", p.execute("echo", new JSONObject(), "call_1").result);
    }

    @Test
    public void postExecuteObservesResult() {
        EventBus bus = new EventBus();
        final String[] seen = new String[1];
        bus.addWaterfall("tools/post-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call, EventBus.Next<ToolPipeline.Call> next) {
                seen[0] = call.result;
                return next.apply(call);
            }
        });
        ToolPipeline p = new ToolPipeline(bus);
        p.setHost(hostWith("echo", args -> "pong"));

        p.execute("echo", new JSONObject(), "call_1");
        assertEquals("pong", seen[0]);
    }

    @Test
    public void postExecuteRunsEvenWhenRejected() {
        EventBus bus = new EventBus();
        final boolean[] postRan = new boolean[1];
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call, EventBus.Next<ToolPipeline.Call> next) {
                call.rejected = true;
                return call;
            }
        });
        bus.addWaterfall("tools/post-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call, EventBus.Next<ToolPipeline.Call> next) {
                postRan[0] = true;
                return next.apply(call);
            }
        });
        new ToolPipeline(bus).execute("echo", new JSONObject(), "call_1");
        assertTrue(postRan[0]);
    }

    @Test
    public void rejectedCallWithoutMessageGetsDefaultError() {
        EventBus bus = new EventBus();
        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call, EventBus.Next<ToolPipeline.Call> next) {
                call.rejected = true;
                return call;
            }
        });
        ToolPipeline.Call c = new ToolPipeline(bus).execute("echo", new JSONObject(), "call_1");
        assertNotNull(c.error);
    }

    @Test
    public void nullArgsBecomeEmptyObject() {
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("echo", args -> args == null ? "null" : "object"));
        assertEquals("object", p.execute("echo", null, "call_1").result);
    }

    @Test
    public void promptAssemblerReplacesSectionById() {
        PromptAssembler pa = new PromptAssembler();
        pa.setSection("persona", "第一版");
        pa.setSection("persona", "第二版");
        assertEquals("第二版", pa.render());
    }

    @Test
    public void promptAssemblerJoinsSectionsWithBlankLine() {
        PromptAssembler pa = new PromptAssembler();
        pa.setSection("a", "甲");
        pa.setSection("b", "乙");
        assertEquals("甲\n\n乙", pa.render());
    }

    @Test
    public void promptAssemblerSkipsEmptySections() {
        PromptAssembler pa = new PromptAssembler();
        pa.setSection("a", "");
        pa.setSection("b", "乙");
        assertEquals("乙", pa.render());
    }

    @Test
    public void promptAssemblerDescribesToolSchemas() {
        PromptAssembler pa = new PromptAssembler();
        JSONArray schemas = new JSONArray();
        JSONObject tool = new JSONObject();
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "echo");
            fn.put("description", "回显");
            tool.put("type", "function");
            tool.put("function", fn);
            schemas.put(tool);
        } catch (Exception ignored) {
        }
        pa.setToolSchemas(schemas);

        String desc = pa.toolsDesc();
        assertTrue(desc.contains("echo"));
        assertTrue(desc.contains("回显"));
    }

    @Test
    public void promptAssemblerEmptyToolSchemasGiveEmptyDesc() {
        PromptAssembler pa = new PromptAssembler();
        pa.setToolSchemas(null);
        assertEquals("", pa.toolsDesc());
        assertEquals(0, pa.toolSchemas().length());
    }

    // ==================== v1.141.0（#40）：危险工具审批 seam ====================

    @Test
    public void approval_readOnlyToolNeverAsks() {
        final int[] asked = {0};
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("echo", args -> "pong"));
        p.setApproval(new ToolApprovalPolicy(), (n, a, s) -> {
            asked[0]++;
            return ToolApprovalPolicy.Outcome.ALLOW_ONCE;
        });
        assertEquals("pong", p.execute("echo", new JSONObject(), "c1").result);
        assertEquals(0, asked[0]);
    }

    @Test
    public void approval_deniedCall_neverRunsHost() {
        final boolean[] ran = {false};
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> {
            ran[0] = true;
            return "opened";
        }));
        p.setApproval(new ToolApprovalPolicy(),
                (n, a, s) -> ToolApprovalPolicy.Outcome.DENY);

        ToolPipeline.Call c = p.execute("open_app", new JSONObject(), "c1");
        assertTrue(c.rejected);
        assertFalse("拒绝后绝不能执行宿主", ran[0]);
        assertNotNull(c.error);
    }

    @Test
    public void approval_allowedOnce_runsHost() {
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> "opened"));
        p.setApproval(new ToolApprovalPolicy(),
                (n, a, s) -> ToolApprovalPolicy.Outcome.ALLOW_ONCE);

        ToolPipeline.Call c = p.execute("open_app", new JSONObject(), "c1");
        assertFalse(c.rejected);
        assertEquals("opened", c.result);
    }

    @Test
    public void approval_allowSession_asksOnlyOnce() {
        final int[] asked = {0};
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> "opened"));
        p.setApproval(new ToolApprovalPolicy(), (n, a, s) -> {
            asked[0]++;
            return ToolApprovalPolicy.Outcome.ALLOW_SESSION;
        });

        p.execute("open_app", new JSONObject(), "c1");
        p.execute("open_app", new JSONObject(), "c2");
        assertEquals("会话放行后不应再问", 1, asked[0]);
    }

    @Test
    public void approval_deniedThisTurn_skipsSecondAsk() {
        final int[] asked = {0};
        ToolApprovalPolicy policy = new ToolApprovalPolicy();
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> "opened"));
        p.setApproval(policy, (n, a, s) -> {
            asked[0]++;
            return ToolApprovalPolicy.Outcome.DENY;
        });

        assertTrue(p.execute("open_app", new JSONObject(), "c1").rejected);
        assertTrue("同轮第二次应直接短路", p.execute("open_app", new JSONObject(), "c2").rejected);
        assertEquals("同轮不应重复打扰用户", 1, asked[0]);
    }

    @Test
    public void approval_beginTurn_reAsksAfterDenial() {
        final int[] asked = {0};
        ToolApprovalPolicy policy = new ToolApprovalPolicy();
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> "opened"));
        p.setApproval(policy, (n, a, s) -> {
            asked[0]++;
            return ToolApprovalPolicy.Outcome.DENY;
        });

        p.execute("open_app", new JSONObject(), "c1");
        policy.beginTurn();
        p.execute("open_app", new JSONObject(), "c2");
        assertEquals("新一轮应重新询问", 2, asked[0]);
    }

    @Test
    public void approval_nullApprover_deniesDangerousTool() {
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> "opened"));
        p.setApproval(new ToolApprovalPolicy(), null);
        assertTrue("没有可询问的 UI 时按拒绝兜底",
                p.execute("open_app", new JSONObject(), "c1").rejected);
    }

    @Test
    public void approval_notInstalled_keepsOldBehavior() {
        final boolean[] ran = {false};
        ToolPipeline p = new ToolPipeline(new EventBus());
        p.setHost(hostWith("open_app", args -> {
            ran[0] = true;
            return "opened";
        }));
        // 未装配审批：沿用旧行为，直接执行
        assertEquals("opened", p.execute("open_app", new JSONObject(), "c1").result);
        assertTrue(ran[0]);
    }
}
