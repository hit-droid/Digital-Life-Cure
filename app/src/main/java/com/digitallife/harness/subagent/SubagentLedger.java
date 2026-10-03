package com.digitallife.harness.subagent;

import java.util.ArrayList;
import java.util.List;

/**
 * 子智能体协作台账（v1.128.0）。
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
