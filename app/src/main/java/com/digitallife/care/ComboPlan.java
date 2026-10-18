package com.digitallife.care;

import java.util.ArrayList;
import java.util.List;

/**
 * 连招（组合动作）纯逻辑：把**同一个模型内**的若干动作 / 表情按顺序编成一段，一键连播。
 *
 * <p>为什么限定「同一模型」：跨模型需要 {@code nativeChangeScene} 重载模型，会打断正在播放
 * 的动作、也让「上一步还没播完就切模型」变得不可控；因此连招只在同一模型内编排，跨模型点播
 * 由界面走「切播」二次确认（见 {@code MotionPoolActivity}）。</p>
 *
 * <p>纯逻辑、无 Android 依赖：界面把 {@code MotionLibrary} 扫描结果拍平成 {@link Candidate}
 * 列表再交给这里解析，故 {@link #dwellMs} / {@link #resolve} / 命名相关都能直接单测。</p>
 *
 * <p>步骤落盘只记 <b>组名 + 文件</b>（动作）/ <b>表情名</b>（表情），不记组内下标——因为用户
 * 之后可能改名 / 删动作 / 重排，写死下标会错位；播放时按当前扫描结果重新解析，解析不到的步骤
 * 直接跳过（界面提示「N 步已失效」）。</p>
 */
public final class ComboPlan {

    public static final String KIND_MOTION = "motion";
    public static final String KIND_EXPR = "expr";

    /** 单步最短停留（防止极短动作一闪而过） */
    public static final long MIN_STEP_MS = 400;
    /** 时长未知时的兜底停留 */
    public static final long DEFAULT_UNKNOWN_MS = 1500;
    /** 单步最长停留（超长动作也不让连招卡住） */
    public static final long MAX_STEP_MS = 6000;
    /** 循环动作的停留上限（循环动作不会自然结束，按固定时长切走） */
    public static final long LOOP_CAP_MS = 3000;
    /** 步间过渡间隙（给淡出留一点时间） */
    public static final long GAP_MS = 150;
    /** 表情的固定停留（表情会一直保持，需要显式停顿再切下一步） */
    public static final long EXPR_STEP_MS = 1500;
    /** 一条连招的步骤上限 */
    public static final int MAX_STEPS = 20;

    private ComboPlan() {
    }

    /** 一步：动作（group + file）或表情（name + file） */
    public static final class Step {
        public final String kind;
        /** 动作所属组名（表情为 null） */
        public final String group;
        /** 相对模型目录的文件路径（统一 '/'） */
        public final String file;
        /** 表情名（引擎按名播放；动作为 null） */
        public final String name;

        public Step(String kind, String group, String file, String name) {
            this.kind = KIND_EXPR.equals(kind) ? KIND_EXPR : KIND_MOTION;
            this.group = group;
            this.file = file;
            this.name = name;
        }

        public boolean isMotion() {
            return KIND_MOTION.equals(kind);
        }
    }

    /** 一条连招：名称 + 有序步骤 */
    public static final class Combo {
        public final String name;
        public final List<Step> steps = new ArrayList<>();

        public Combo(String name) {
            this.name = sanitizeName(name);
        }

        public int size() {
            return steps.size();
        }
    }

    /** 解析用候选项（由界面从扫描结果拍平；避免纯逻辑依赖 Android / MotionLibrary） */
    public static final class Candidate {
        public final boolean motion;
        public final String group;
        public final String file;
        public final String name;
        /** 动作在其组内的原始下标（表情为 -1） */
        public final int index;
        public final double durationSec;
        public final boolean loop;

        public Candidate(boolean motion, String group, String file, String name,
                         int index, double durationSec, boolean loop) {
            this.motion = motion;
            this.group = group;
            this.file = file;
            this.name = name;
            this.index = index;
            this.durationSec = durationSec;
            this.loop = loop;
        }
    }

    /** 解析结果：一步落到可播放的具体目标 + 建议停留时长 */
    public static final class Resolved {
        public final boolean motion;
        public final String group;
        public final int index;
        public final String exprName;
        public final long dwellMs;

        Resolved(boolean motion, String group, int index, String exprName, long dwellMs) {
            this.motion = motion;
            this.group = group;
            this.index = index;
            this.exprName = exprName;
            this.dwellMs = dwellMs;
        }
    }

    /**
     * 一步的建议停留时长（毫秒）。
     *
     * <ul>
     *   <li>表情：固定 {@link #EXPR_STEP_MS}（表情会保持，需显式停顿再切下一步）。</li>
     *   <li>循环动作：不会自然结束，按时长封顶 {@link #LOOP_CAP_MS}（时长未知则用该值）。</li>
     *   <li>单次动作：自身时长 + 过渡间隙，落在 [MIN, MAX] 区间；时长未知用兜底值。</li>
     * </ul>
     */
    public static long dwellMs(boolean motion, double durationSec, boolean loop) {
        if (!motion) return EXPR_STEP_MS;
        if (loop) {
            if (durationSec > 0) return Math.min(Math.round(durationSec * 1000), LOOP_CAP_MS);
            return LOOP_CAP_MS;
        }
        if (durationSec > 0) {
            long d = Math.round(durationSec * 1000) + GAP_MS;
            return Math.max(MIN_STEP_MS, Math.min(d, MAX_STEP_MS));
        }
        return DEFAULT_UNKNOWN_MS;
    }

    /** 把一步解析成可播放项；解析不到返回 {@code null}（动作被删 / 改名 / 组消失） */
    public static Resolved resolve(Step step, List<Candidate> candidates) {
        if (step == null || candidates == null) return null;
        for (Candidate c : candidates) {
            if (c.motion != step.isMotion()) continue;
            if (c.motion) {
                if (eq(c.group, step.group) && eq(c.file, step.file)) {
                    return new Resolved(true, c.group, c.index, null,
                            dwellMs(true, c.durationSec, c.loop));
                }
            } else {
                // 引擎按表情名播放，故以名为主键；名缺失时退回按文件匹配
                if (eq(c.name, step.name)) {
                    return new Resolved(false, null, -1, c.name, EXPR_STEP_MS);
                }
                if (isBlank(step.name) && eq(c.file, step.file)) {
                    return new Resolved(false, null, -1, c.name, EXPR_STEP_MS);
                }
            }
        }
        return null;
    }

    /** 解析整条连招；丢掉解析不到的步骤，保留可播部分 */
    public static List<Resolved> resolveAll(List<Step> steps, List<Candidate> candidates) {
        List<Resolved> out = new ArrayList<>();
        if (steps == null) return out;
        for (Step s : steps) {
            Resolved r = resolve(s, candidates);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** 名称清洗：去首尾空白；返回可能为空串（调用方据此拒绝保存） */
    public static String sanitizeName(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 是否已有同名连招（忽略大小写） */
    public static boolean hasName(List<Combo> combos, String name) {
        String n = sanitizeName(name);
        if (n.isEmpty() || combos == null) return false;
        for (Combo c : combos) {
            if (c != null && c.name != null && c.name.equalsIgnoreCase(n)) return true;
        }
        return false;
    }

    /** 生成一个不与现有重名的默认名（连招1 / 连招2 …） */
    public static String suggestName(List<Combo> combos) {
        for (int i = 1; i < 10000; i++) {
            String n = "连招" + i;
            if (!hasName(combos, n)) return n;
        }
        return "连招";
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
