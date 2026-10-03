package com.digitallife.harness.subagent;

import com.digitallife.brain.Tools;
import com.digitallife.harness.LlmFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行子智能体编排：把多个互不依赖的子任务同时委派出去（fan-out），
 * 全部结束后汇总成一份报告（fan-in）。
 *
 * <p>与 {@link SubagentRunner} 的关系：单个子任务的执行、隔离、最小权限、
 * 超时仍由 {@link SubagentRunner} 负责；本类只做「并发调度 + 结果汇总」，
 * 因此两者共用同一套 preset 与 progress 事件。
 *
 * <p>失败隔离：某个子任务失败（未知 preset / 空任务 / 超时 / 模型报错）只影响
 * 它自己那一条结果，不会中断整批，其余子任务照常完成并回传。
 */
public final class SubagentTeam {

    /** 一次并行最多允许多少个子任务，防止把模型/网络压垮 */
    public static final int MAX_TASKS = 5;
    /** 单个子任务的默认超时（秒） */
    public static final int DEFAULT_TIMEOUT_SEC = 180;
    /** 汇总报告里每条结论的字符上限，避免工具结果撑爆上下文 */
    private static final int ITEM_CHAR_LIMIT = 1200;

    /** 一个待委派的子任务 */
    public static final class Task {
        public final String agent;
        public final String task;

        public Task(String agent, String task) {
            this.agent = agent == null ? "" : agent.trim();
            this.task = task == null ? "" : task.trim();
        }
    }

    /** 单个子任务的结果 */
    public static final class Item {
        public final String agent;
        public final String task;
        public final String text;
        public final String error;

        Item(String agent, String task, String text, String error) {
            this.agent = agent;
            this.task = task;
            this.text = text;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** 一批子任务的汇总 */
    public static final class Report {
        public final List<Item> items;

        Report(List<Item> items) {
            this.items = items;
        }

        public int okCount() {
            int n = 0;
            for (Item it : items) if (it.ok()) n++;
            return n;
        }

        public int failCount() {
            return items.size() - okCount();
        }

        /** 渲染成回传给主智能体的文本 */
        public String render() {
            if (items.isEmpty()) return "没有可执行的子任务。";
            StringBuilder sb = new StringBuilder();
            sb.append("并行委派完成：").append(okCount()).append("/").append(items.size()).append(" 成功");
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                sb.append("\n\n【").append(i + 1).append("】").append(it.agent)
                        .append(" · 任务：").append(clip(it.task, 80));
                if (it.ok()) {
                    sb.append("\n状态：成功\n结论：").append(clip(it.text, ITEM_CHAR_LIMIT));
                } else {
                    sb.append("\n状态：失败\n原因：").append(it.error == null ? "未知错误" : it.error);
                }
            }
            return sb.toString();
        }
    }

    private SubagentTeam() {
    }

    public static Report run(List<Task> tasks, Tools host, LlmFactory factory,
                             SubagentRunner.ProgressListener progress) {
        return run(tasks, host, factory, progress, DEFAULT_TIMEOUT_SEC);
    }

    /**
     * 并发执行一批子任务并汇总。
     *
     * @param timeoutSec 单个子任务的超时秒数（&le;0 时用默认值）
     */
    public static Report run(List<Task> tasks, Tools host, LlmFactory factory,
                             SubagentRunner.ProgressListener progress, int timeoutSec) {
        List<Item> items = new ArrayList<>();
        if (tasks == null || tasks.isEmpty()) {
            return new Report(items);
        }
        final int timeout = timeoutSec > 0 ? timeoutSec : DEFAULT_TIMEOUT_SEC;
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size(), new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "subagent-team-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        try {
            List<Future<Item>> futures = new ArrayList<>();
            for (final Task t : tasks) {
                futures.add(pool.submit(new Callable<Item>() {
                    @Override
                    public Item call() {
                        SubagentRunner.Result r = SubagentRunner.run(
                                t.agent, t.task, host, factory, progress, timeout);
                        if (r.ok()) {
                            notifyTeam(progress, t.agent, t.task, "team-ok", r.text);
                            return new Item(t.agent, t.task, r.text, null);
                        }
                        notifyTeam(progress, t.agent, t.task, "team-fail", r.error);
                        return new Item(t.agent, t.task, null, r.error);
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                Task t = tasks.get(i);
                try {
                    items.add(futures.get(i).get());
                } catch (Exception e) {
                    items.add(new Item(t.agent, t.task, null, "子智能体执行异常：" + msg(e)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Report(items);
    }

    private static void notifyTeam(SubagentRunner.ProgressListener progress,
                                   String agent, String task, String phase, String detail) {
        if (progress == null) return;
        try {
            progress.onTeamStep(agent, task, phase, detail);
        } catch (Exception ignored) {
        }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }
}
