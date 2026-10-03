package com.digitallife.harness.subagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子智能体协作台账（v1.128.0；v1.129.0 增加 {@link #byAgent()} 按 agent 聚合）。
 *
 * <p>每次子智能体执行（不管来自 {@code delegate_task} 串行、{@code delegate_parallel}
 * 并行还是 {@code delegate_pipeline} 依赖链）都由 {@link SubagentRunner} 在这里记一条，
 * 供智能体控制台（{@code AgentConsoleActivity}）展示「谁、做了什么、成没成、花了多久」。
 *
 * <p>纯 Java、无 Android 依赖，环形缓冲参考 {@code BrainLog}；并发下由
 * {@code SubagentTeam} 的多线程写入，故所有读写都加锁。
 */
public final class SubagentLedger {

    /** 环形缓冲上限，超过后丢弃最旧的一条 */
    public static final int MAX_ENTRIES = 200;
    /** 入库前任务原文的截断长度，避免长任务把控制台撑爆 */
    public static final int TASK_CHAR_LIMIT = 60;

    /** 一次子智能体执行的记录 */
    public static final class Entry {
        public final long timestamp;
        public final String agent;
        public final String task;
        public final boolean ok;
        public final String error;
        public final long durationMs;

        Entry(long timestamp, String agent, String task, boolean ok, String error, long durationMs) {
            this.timestamp = timestamp;
            this.agent = agent;
            this.task = task;
            this.ok = ok;
            this.error = error;
            this.durationMs = durationMs;
        }
    }

    /**
     * 单个子智能体在当前台账窗口内的汇总（v1.129.0 深化）。
     *
     * <p>台账原先只有「一条条明细」，看不出**哪个子智能体最不可靠 / 最慢**。
     * 按 agent 聚合后，控制台能一眼看出「writer 跑了 5 次失败了 3 次」这类问题。
     */
    public static final class AgentStat {
        public final String agent;
        public final int runs;
        public final int ok;
        public final int fail;
        public final long avgDurationMs;
        public final long lastTimestamp;

        AgentStat(String agent, int runs, int ok, int fail, long avgDurationMs, long lastTimestamp) {
            this.agent = agent;
            this.runs = runs;
            this.ok = ok;
            this.fail = fail;
            this.avgDurationMs = avgDurationMs;
            this.lastTimestamp = lastTimestamp;
        }

        /** 成功率，0~100 四舍五入；无执行时为 0 */
        public int successRate() {
            return runs == 0 ? 0 : (int) Math.round(ok * 100.0 / runs);
        }
    }

    private static final SubagentLedger INSTANCE = new SubagentLedger();

    private final List<Entry> entries = new ArrayList<>();

    private SubagentLedger() {
    }

    public static SubagentLedger getInstance() {
        return INSTANCE;
    }

    /**
     * 记一条执行结果。所有出口都应调用（含未知 preset / 空任务 / 无模型配置这类
     * 启动前就失败的情况），这样控制台能看出「模型点了委派但没跑起来」。
     */
    public synchronized void record(String agent, String task, boolean ok, String error, long durationMs) {
        entries.add(new Entry(
                System.currentTimeMillis(),
                agent == null ? "" : agent,
                normalizeTask(task),
                ok,
                ok ? null : (error == null ? "未知错误" : error),
                durationMs < 0 ? 0 : durationMs));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    /** 最近 n 条，按时间正序（最旧在前）；n &le; 0 返回全部 */
    public synchronized List<Entry> recent(int n) {
        int size = entries.size();
        if (n <= 0 || n >= size) return new ArrayList<>(entries);
        return new ArrayList<>(entries.subList(size - n, size));
    }

    public synchronized int total() {
        return entries.size();
    }

    public synchronized int okCount() {
        int n = 0;
        for (Entry e : entries) if (e.ok) n++;
        return n;
    }

    public synchronized int failCount() {
        return entries.size() - okCount();
    }

    /**
     * 按子智能体聚合当前窗口内的执行情况。
     *
     * <p>排序固定为「执行次数降序，同次数按 agent 名升序」，便于控制台稳定展示、也便于测试断言。
     * 空 agent（preset 名为空）归为单独一条，控制台会显示成 {@code ?}——这能暴露
     * 「点了委派但 preset 名没传对」的情况。
     */
    public synchronized List<AgentStat> byAgent() {
        Map<String, int[]> counts = new HashMap<>();   // agent -> [runs, ok, fail]
        Map<String, Long> totalMs = new HashMap<>();
        Map<String, Long> lastTs = new HashMap<>();
        for (Entry e : entries) {
            int[] c = counts.get(e.agent);
            if (c == null) {
                c = new int[3];
                counts.put(e.agent, c);
            }
            c[0]++;
            if (e.ok) c[1]++;
            else c[2]++;
            Long t = totalMs.get(e.agent);
            totalMs.put(e.agent, (t == null ? 0L : t) + e.durationMs);
            Long last = lastTs.get(e.agent);
            if (last == null || e.timestamp > last) lastTs.put(e.agent, e.timestamp);
        }
        List<AgentStat> out = new ArrayList<>();
        for (Map.Entry<String, int[]> en : counts.entrySet()) {
            String agent = en.getKey();
            int[] c = en.getValue();
            long total = totalMs.containsKey(agent) ? totalMs.get(agent) : 0L;
            out.add(new AgentStat(agent, c[0], c[1], c[2],
                    c[0] == 0 ? 0 : total / c[0],
                    lastTs.containsKey(agent) ? lastTs.get(agent) : 0L));
        }
        Collections.sort(out, new Comparator<AgentStat>() {
            @Override
            public int compare(AgentStat a, AgentStat b) {
                if (a.runs != b.runs) return b.runs - a.runs;
                return a.agent.compareTo(b.agent);
            }
        });
        return out;
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** 折叠换行 + 截断，控制台里每条记录保持单行 */
    private static String normalizeTask(String task) {
        if (task == null) return "";
        String t = task.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() <= TASK_CHAR_LIMIT) return t;
        return t.substring(0, TASK_CHAR_LIMIT) + "…";
    }
}
