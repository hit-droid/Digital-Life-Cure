package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
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
}
