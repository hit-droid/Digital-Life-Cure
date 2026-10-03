package com.digitallife.harness.subagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.brain.Tools;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * 子智能体协作台账的契约：环形缓冲上限、时间正序、成功/失败计数、入库前规范化。
 */
public class SubagentLedgerTest {

    private SubagentLedger ledger;

    @Before
    public void setUp() {
        ledger = SubagentLedger.getInstance();
        ledger.clear();
    }

    @Test
    public void clearLeavesEmptyLedger() {
        ledger.record("researcher", "查资料", true, null, 10);
        ledger.clear();
        assertEquals(0, ledger.total());
        assertTrue(ledger.recent(10).isEmpty());
        assertEquals(0, ledger.okCount());
        assertEquals(0, ledger.failCount());
    }

    @Test
    public void recentKeepsChronologicalOrder() {
        ledger.record("researcher", "a", true, null, 1);
        ledger.record("writer", "b", true, null, 1);
        ledger.record("critic", "c", true, null, 1);

        List<SubagentLedger.Entry> all = ledger.recent(10);
        assertEquals(3, all.size());
        assertEquals("researcher", all.get(0).agent);
        assertEquals("writer", all.get(1).agent);
        assertEquals("critic", all.get(2).agent);
    }

    @Test
    public void recentReturnsOnlyNewestN() {
        ledger.record("a", "1", true, null, 1);
        ledger.record("b", "2", true, null, 1);
        ledger.record("c", "3", true, null, 1);

        List<SubagentLedger.Entry> last2 = ledger.recent(2);
        assertEquals(2, last2.size());
        assertEquals("b", last2.get(0).agent);
        assertEquals("c", last2.get(1).agent);
    }

    @Test
    public void ringBufferDropsOldestBeyondCapacity() {
        int overflow = 50;
        for (int i = 0; i < SubagentLedger.MAX_ENTRIES + overflow; i++) {
            ledger.record("agent-" + i, "task", true, null, 1);
        }
        assertEquals(SubagentLedger.MAX_ENTRIES, ledger.total());
        // 最早的 50 条被挤掉，保留窗口的第一条应是第 50 条
        assertEquals("agent-" + overflow,
                ledger.recent(SubagentLedger.MAX_ENTRIES).get(0).agent);
    }

    @Test
    public void countsSuccessAndFailure() {
        ledger.record("researcher", "ok1", true, null, 1);
        ledger.record("critic", "bad", false, "模型报错", 1);
        ledger.record("writer", "ok2", true, null, 1);

        assertEquals(3, ledger.total());
        assertEquals(2, ledger.okCount());
        assertEquals(1, ledger.failCount());
    }

    @Test
    public void failureWithoutReasonGetsPlaceholder() {
        ledger.record("critic", "bad", false, null, 1);
        SubagentLedger.Entry e = ledger.recent(1).get(0);
        assertFalse(e.ok);
        assertEquals("未知错误", e.error);
    }

    @Test
    public void successClearsErrorField() {
        ledger.record("researcher", "ok", true, "上一轮的残留错误", 1);
        assertNull(ledger.recent(1).get(0).error);
    }

    @Test
    public void longMultilineTaskIsFoldedAndTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) sb.append('x');
        String task = "第一行\n第二行" + sb;

        ledger.record("researcher", task, true, null, 1);
        String stored = ledger.recent(1).get(0).task;

        assertFalse("不应保留换行：" + stored, stored.contains("\n"));
        assertEquals(SubagentLedger.TASK_CHAR_LIMIT + 1, stored.length());
        assertTrue(stored.endsWith("…"));
    }

    @Test
    public void nullAgentAndTaskAreSafe() {
        ledger.record(null, null, true, null, 1);
        SubagentLedger.Entry e = ledger.recent(1).get(0);
        assertEquals("", e.agent);
        assertEquals("", e.task);
    }

    @Test
    public void negativeDurationIsClampedToZero() {
        ledger.record("researcher", "task", true, null, -5);
        assertEquals(0, ledger.recent(1).get(0).durationMs);
    }

    /** 埋点在 SubagentRunner 里：启动前就失败的委派（未知 preset）也要留痕 */
    @Test
    public void runnerRecordsRejectedDelegation() {
        SubagentRunner.Result r = SubagentRunner.run("nobody", "做事", new Tools(true), null, null);
        assertFalse(r.ok());

        assertEquals(1, ledger.total());
        SubagentLedger.Entry e = ledger.recent(1).get(0);
        assertFalse(e.ok);
        assertEquals("nobody", e.agent);
        assertTrue(e.error.contains("未知子智能体"));
    }

    // ==================== 按 agent 聚合（v1.130.0）====================

    @Test
    public void byAgentOnEmptyLedgerIsEmpty() {
        assertTrue(ledger.byAgent().isEmpty());
    }

    @Test
    public void byAgentAggregatesCountsAverageAndRate() {
        ledger.record("researcher", "a", true, null, 100);
        ledger.record("researcher", "b", false, "超时", 200);
        ledger.record("researcher", "c", true, null, 300);

        List<SubagentLedger.AgentStat> stats = ledger.byAgent();
        assertEquals(1, stats.size());
        SubagentLedger.AgentStat s = stats.get(0);
        assertEquals("researcher", s.agent);
        assertEquals(3, s.runs);
        assertEquals(2, s.ok);
        assertEquals(1, s.fail);
        assertEquals(200L, s.avgDurationMs);
        assertEquals(67, s.successRate()); // 2/3 四舍五入
    }

    @Test
    public void byAgentOrdersByRunsDescThenNameAsc() {
        ledger.record("writer", "w", true, null, 1);
        ledger.record("critic", "c", true, null, 1);
        ledger.record("researcher", "r1", true, null, 1);
        ledger.record("researcher", "r2", true, null, 1);

        List<SubagentLedger.AgentStat> stats = ledger.byAgent();
        assertEquals(3, stats.size());
        assertEquals("researcher", stats.get(0).agent); // 2 次最多
        assertEquals("critic", stats.get(1).agent);     // 同为 1 次，按名字升序
        assertEquals("writer", stats.get(2).agent);
    }

    @Test
    public void byAgentGroupsEmptyAgentAndKeepsLastTimestamp() {
        ledger.record(null, "t", false, "未知子智能体", 1);

        List<SubagentLedger.AgentStat> stats = ledger.byAgent();
        assertEquals(1, stats.size());
        assertEquals("", stats.get(0).agent);
        assertEquals(0, stats.get(0).successRate());
        assertTrue(stats.get(0).lastTimestamp > 0);
    }
}
