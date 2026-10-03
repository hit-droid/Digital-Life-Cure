package com.digitallife.harness.subagent;

import com.digitallife.brain.Tools;
import com.digitallife.harness.LlmFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 有序依赖链编排：把一串**有先后依赖**的子任务按顺序交给子智能体执行，
 * 每一步都能拿到前序步骤的结论作为输入，全部结束后汇总成一份报告。
 *
 * <p>与 {@link SubagentTeam} 的分工：Team 处理「互不依赖、可并发」的任务（fan-out/fan-in）；
 * Pipeline 处理「必须按顺序、后一步依赖前一步产出」的任务（研究 → 写稿 → 校订）。
 * 两者都复用 {@link SubagentRunner} 的独立 harness 树、最小权限与超时控制。
 *
 * <p>失败即停：某一步失败后不再继续执行后续步骤（它们依赖前序结论，硬跑没有意义），
 * 报告里把剩余步骤标为「未执行」，并给出失败原因，不抛整批异常。
 */
public final class SubagentPipeline {

    /** 一条依赖链最多多少步，防止模型拼出过长的链条 */
    public static final int MAX_STEPS = 5;
    /** 每一步的默认超时（秒） */
    public static final int DEFAULT_TIMEOUT_SEC = 180;
    /** 注入下一步的前序结论总字符上限，避免把上下文撑爆 */
    private static final int CONTEXT_CHAR_LIMIT = 2000;
    /** 单条前序结论注入时的字符上限 */
    private static final int CONTEXT_ITEM_LIMIT = 800;
    /** 最终报告里每条结论的字符上限 */
    private static final int ITEM_CHAR_LIMIT = 1200;

    /** 依赖链里的一步 */
    public static final class Step {
        public final String agent;
        public final String task;

        public Step(String agent, String task) {
            this.agent = agent == null ? "" : agent.trim();
            this.task = task == null ? "" : task.trim();
        }
    }

    /** 一步的执行结果 */
    public static final class StepResult {
        public final String agent;
        public final String task;
        public final String text;
        public final String error;
        /** 前序步骤失败导致本步未执行 */
        public final boolean skipped;

        StepResult(String agent, String task, String text, String error, boolean skipped) {
            this.agent = agent;
            this.task = task;
            this.text = text;
            this.error = error;
            this.skipped = skipped;
        }

        public boolean ok() {
            return error == null && !skipped;
        }
    }

    /** 一条依赖链的汇总 */
    public static final class Report {
        public final List<StepResult> steps;

        Report(List<StepResult> steps) {
            this.steps = steps;
        }

        public int okCount() {
            int n = 0;
            for (StepResult s : steps) if (s.ok()) n++;
            return n;
        }

        public int failCount() {
            int n = 0;
            for (StepResult s : steps) if (!s.ok() && !s.skipped) n++;
            return n;
        }

        public int skippedCount() {
            int n = 0;
            for (StepResult s : steps) if (s.skipped) n++;
            return n;
        }

        /** 渲染成回传给主智能体的文本 */
        public String render() {
            if (steps.isEmpty()) return "没有可执行的步骤。";
            StringBuilder sb = new StringBuilder();
            sb.append("依赖链执行完成：").append(okCount()).append("/").append(steps.size()).append(" 成功");
            if (failCount() > 0) sb.append("，").append(failCount()).append(" 失败");
            if (skippedCount() > 0) sb.append("，").append(skippedCount()).append(" 未执行");
            for (int i = 0; i < steps.size(); i++) {
                StepResult s = steps.get(i);
                sb.append("\n\n【").append(i + 1).append("】").append(s.agent)
                        .append(" · 任务：").append(clip(s.task, 80));
                if (s.ok()) {
                    sb.append("\n状态：成功\n结论：").append(clip(s.text, ITEM_CHAR_LIMIT));
                } else if (s.skipped) {
                    sb.append("\n状态：未执行（前序步骤失败）");
                } else {
                    sb.append("\n状态：失败\n原因：").append(s.error == null ? "未知错误" : s.error);
                }
            }
            return sb.toString();
        }
    }

    private SubagentPipeline() {
    }

    public static Report run(List<Step> steps, Tools host, LlmFactory factory,
                             SubagentRunner.ProgressListener progress) {
        return run(steps, host, factory, progress, DEFAULT_TIMEOUT_SEC);
    }

    /**
     * 顺序执行依赖链。
     *
     * @param timeoutSec 每一步的超时秒数（&le;0 时用默认值）
     */
    public static Report run(List<Step> steps, Tools host, LlmFactory factory,
                             SubagentRunner.ProgressListener progress, int timeoutSec) {
        List<StepResult> results = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            return new Report(results);
        }
        final int timeout = timeoutSec > 0 ? timeoutSec : DEFAULT_TIMEOUT_SEC;
        final StringBuilder prior = new StringBuilder();
        boolean aborted = false;

        for (int i = 0; i < steps.size(); i++) {
            final Step s = steps.get(i);
            if (aborted) {
                results.add(new StepResult(s.agent, s.task, null, null, true));
                continue;
            }
            // 喂给模型的是「前序结论 + 本步任务」；UI 事件仍用原始任务原文，卡片才配对得上
            String modelTask = decorate(s.task, prior.toString());
            SubagentRunner.Result r = SubagentRunner.run(
                    s.agent, modelTask, s.task, host, factory, progress, timeout);
            if (r.ok()) {
                results.add(new StepResult(s.agent, s.task, r.text, null, false));
                notifyTeam(progress, s.agent, s.task, "team-ok", r.text);
                appendPrior(prior, i, s.agent, r.text);
            } else {
                results.add(new StepResult(s.agent, s.task, null, r.error, false));
                notifyTeam(progress, s.agent, s.task, "team-fail", r.error);
                aborted = true;
            }
        }
        return new Report(results);
    }

    /** 把本步结论追加进「前序结论」缓冲，并按上限截断（保留最近的部分） */
    private static void appendPrior(StringBuilder prior, int index, String agent, String text) {
        if (prior.length() > 0) prior.append('\n');
        prior.append("【第").append(index + 1).append("步 · ").append(agent).append("】\n")
                .append(clip(text, CONTEXT_ITEM_LIMIT));
        if (prior.length() > CONTEXT_CHAR_LIMIT) {
            String tail = prior.substring(prior.length() - CONTEXT_CHAR_LIMIT);
            prior.setLength(0);
            prior.append(tail);
        }
    }

    private static String decorate(String task, String prior) {
        if (prior == null || prior.isEmpty()) return task;
        return "【前序步骤的结论（供你参考，不必复述）】\n" + prior
                + "\n\n【你这一步的任务】\n" + task;
    }

    private static void notifyTeam(SubagentRunner.ProgressListener progress,
                                   String agent, String task, String phase, String detail) {
        if (progress == null) return;
        try {
            progress.onTeamStep(agent, task, phase, detail);
        } catch (Exception ignored) {
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }
}
