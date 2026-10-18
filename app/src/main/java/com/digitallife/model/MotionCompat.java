package com.digitallife.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 跨模型复用兼容性判定（纯逻辑，无 Android 依赖）。
 *
 * <p>Live2D 动作通过参数 ID 驱动模型：动作文件 {@code .motion3.json} 的每条曲线
 * {@code Target=Parameter} 都记着一个参数 ID（如 {@code ParamAngleX}），只有目标模型
 * 真的拥有这些参数（{@code .cdi3.json} 的 {@code Parameters[].Id}），动作才动得起来。
 * 把一个动作复用到参数集不同的模型上，轻则轻微错位、重则完全没反应——本类把这个
 * 隐性约束变成一个可见的判定，替代此前那句笼统的「可能错位」提示。</p>
 *
 * <p>判定只看**参数 ID 集合的命中率**，不解析曲线数值，也不碰骨骼层级，故是启发式，
 * 不作为「不能复用」的硬阻断——界面仍允许用户强行复用。</p>
 */
public final class MotionCompat {

    private MotionCompat() {
    }

    /** 判定结果：命中数 / 总数 / 结论文案 / 目标缺失的参数 ID（示例，最多若干条） */
    public static final class Verdict {
        public final int matched;
        public final int total;
        public final String label;
        public final List<String> missing;

        Verdict(int matched, int total, String label, List<String> missing) {
            this.matched = matched;
            this.total = total;
            this.label = label;
            this.missing = missing;
        }

        /** 命中率 0~1；total 为 0 时返回 0（无曲线，无从判定） */
        public double ratio() {
            return total == 0 ? 0 : (double) matched / total;
        }
    }

    private static final int MISSING_SAMPLE = 5;

    /**
     * 比对动作引用的参数 ID 与目标模型拥有的参数 ID。
     *
     * @param motionParams 动作的参数 ID 集合（{@code null}/空 = 动作无参数曲线）
     * @param modelParams  目标模型的参数 ID 集合；{@code null} = 取不到模型参数清单（如缺 cdi3）
     * @return 判定结果
     */
    public static Verdict judge(Set<String> motionParams, Set<String> modelParams) {
        int total = motionParams == null ? 0 : motionParams.size();
        if (total == 0) {
            return new Verdict(0, 0, "该动作没有参数曲线，无从判定兼容性", Collections.emptyList());
        }
        if (modelParams == null) {
            return new Verdict(0, total, "目标模型未提供参数清单，无法判定（可强行复用后验证）",
                    Collections.emptyList());
        }
        int matched = 0;
        List<String> missing = new ArrayList<>();
        for (String p : motionParams) {
            if (modelParams.contains(p)) {
                matched++;
            } else if (missing.size() < MISSING_SAMPLE) {
                missing.add(p);
            }
        }
        double r = (double) matched / total;
        String label;
        if (r >= 0.8) label = "参数高度匹配，可直接复用";
        else if (r >= 0.5) label = "部分参数缺失，复用后可能轻微错位";
        else if (r > 0) label = "多数参数缺失，复用后可能明显错位";
        else label = "参数完全不匹配，复用后大概率无效果";
        return new Verdict(matched, total, label, missing);
    }
}
