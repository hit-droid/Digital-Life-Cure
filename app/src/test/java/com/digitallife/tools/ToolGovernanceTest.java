package com.digitallife.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.digitallife.brain.Tools;
import com.digitallife.harness.ToolPipeline;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 工具把关与审计装配的契约：禁用即短路、每次调用留审计、日志真实落盘。
 * 这段策略曾因 AgentBrain 删除而断流，必须有测试锁住。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToolGovernanceTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ToolUsageLog.getInstance(ctx).clear();
    }

    private static Tools hostWith(boolean shouldThrow) {
        Tools t = new Tools(true);
        t.register("echo", "回显", new String[]{}, args -> "pong");
        t.register("boom", "抛错", new String[]{}, args -> {
            if (shouldThrow) throw new IllegalStateException("炸了");
            return "ok";
        });
        return t;
    }

    @Test
    public void allowedToolExecutes() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "c1");
        assertNull(c.error);
        assertEquals("pong", c.result);
    }

    @Test
    public void callIsRecordedInUsageLog() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        p.execute("echo", new JSONObject(), "c1");

        List<ToolUsageLog.Entry> entries = ToolUsageLog.getInstance(ctx).all();
        assertEquals("每次调用都应留一条审计", 1, entries.size());
        assertEquals("echo", entries.get(0).toolName);
        // Entry 反序列化时空字段为空串而非 null
        assertTrue("成功调用不应有错误信息",
                entries.get(0).error == null || entries.get(0).error.isEmpty());
    }

    @Test
    public void failingCallIsRecordedWithError() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(true));
        ToolPipeline.Call c = p.execute("boom", new JSONObject(), "c1");

        assertNotNull(c.error);
        List<ToolUsageLog.Entry> entries = ToolUsageLog.getInstance(ctx).all();
        assertEquals(1, entries.size());
        assertEquals("boom", entries.get(0).toolName);
        assertNotNull("失败的调用必须记录错误信息", entries.get(0).error);
    }

    @Test
    public void rejectedCallStillLeavesAuditTrail() {
        // 用户禁用某工具后，被拒绝的调用也必须可见，否则用户无法确认「它确实没执行」
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        p.execute("disabled_tool_xyz", new JSONObject(), "c1");
        List<ToolUsageLog.Entry> entries = ToolUsageLog.getInstance(ctx).all();
        assertEquals("被拒绝/未知的调用同样要留审计", 1, entries.size());
        assertEquals("disabled_tool_xyz", entries.get(0).toolName);
    }

    @Test
    public void everyCallProducesExactlyOneEntry() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        p.execute("echo", new JSONObject(), "c1");
        p.execute("echo", new JSONObject(), "c2");
        p.execute("boom", new JSONObject(), "c3");

        assertEquals(3, ToolUsageLog.getInstance(ctx).all().size());
    }

    @Test
    public void auditEntriesAreNewestFirst() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        p.execute("echo", new JSONObject(), "c1");
        p.execute("boom", new JSONObject(), "c2");

        List<ToolUsageLog.Entry> entries = ToolUsageLog.getInstance(ctx).all();
        assertEquals(2, entries.size());
        assertEquals("最新调用应排在最前", "boom", entries.get(0).toolName);
        assertEquals("echo", entries.get(1).toolName);
    }

    @Test
    public void installWithoutContextSkipsPolicyButStillExecutes() {
        // ctx 为 null 时不应崩溃（测试与无 Context 场景）
        ToolPipeline p = ToolGovernance.install(null, hostWith(false));
        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "c1");
        assertEquals("pong", c.result);
    }

    @Test
    public void unknownToolYieldsReadableText() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        ToolPipeline.Call c = p.execute("no_such_tool_abc", new JSONObject(), "c1");
        // Tools.execute 对未知工具返回可读文本而非异常
        assertNotNull(c.result);
        assertTrue("未知工具应返回可读提示，实际：" + c.result,
                c.result.contains("未知工具") || c.result.contains("no_such_tool_abc"));
    }

    @Test
    public void pipelineWithoutHostReportsError() {
        ToolPipeline p = ToolGovernance.install(ctx, null);
        ToolPipeline.Call c = p.execute("echo", new JSONObject(), "c1");
        assertNotNull(c.error);
    }

    @Test
    public void argsAreCapturedInAudit() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        JSONObject args = new JSONObject();
        try {
            args.put("q", "查询内容");
        } catch (Exception ignored) {
        }
        p.execute("echo", args, "c1");

        List<ToolUsageLog.Entry> entries = ToolUsageLog.getInstance(ctx).all();
        assertTrue("审计应保留入参，便于回溯", entries.get(0).args.contains("查询内容"));
    }

    @Test
    public void clearEmptiesAudit() {
        ToolPipeline p = ToolGovernance.install(ctx, hostWith(false));
        p.execute("echo", new JSONObject(), "c1");
        assertFalse(ToolUsageLog.getInstance(ctx).all().isEmpty());

        ToolUsageLog.getInstance(ctx).clear();
        assertTrue(ToolUsageLog.getInstance(ctx).all().isEmpty());
    }
}
