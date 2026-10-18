package com.digitallife.care;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.digitallife.model.MotionLibrary;
import com.digitallife.service.PetService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 连招播放器（v1.178.0）：把「按模型目录 + 连招名扫描候选 → 解析步骤 → 主线程逐步下发」从
 * {@code MotionPoolActivity} 抽成可复用组件，供**界面点播 / 开机自动播放 / 定时播放**三处共用。
 *
 * <p>为什么可以复用：连招播放只依赖两件事——模型扫描结果（{@link MotionLibrary}）与桌宠渲染桥
 * （{@link PetService#onMotion} / {@link PetService#onExpression}），两者都与「是谁触发的」无关。
 * 此前这套调度锁在动作池页面里，开机 / 定时都够不着；抽出来后自动化路径直接调用即可。</p>
 *
 * <p>顺序保证：动作 / 表情都经 GL 线程同一 FIFO {@code queueEvent}，故「逐步下发」天然有序；
 * 新动作会打断上一步，无需等它自然结束。用 {@code generation} 计数作废旧代次的延迟任务，
 * 避免多条连招串台。</p>
 *
 * <p>纯逻辑边界：{@link #flatten} 只做「扫描结果 → 候选」的映射，{@link #candidatesFor} 在其上
 * 加一次扫描；界面已有扫描结果时直接调 {@code flatten}，避免重复读盘。</p>
 */
public class ComboPlayer {

    /** 播放过程中的提示（可为空：自动化路径静默播放，只取返回值） */
    public interface Listener {
        void onMessage(String text);
    }

    private final Context ctx;
    private final Handler handler;
    private final Listener listener;
    private int generation = 0;
    private boolean playing = false;

    public ComboPlayer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.listener = listener;
    }

    /**
     * 按模型目录 + 连招名解析并播放。
     *
     * @return 可播步骤数；{@code -1} = 桌宠未运行 / 找不到连招，{@code 0} = 连招无可播步骤
     */
    public int play(String modelDir, String comboName) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            message("桌宠未在运行，请先开启桌宠再试");
            return -1;
        }
        ComboPlan.Combo combo = ComboStore.find(ctx, modelDir, comboName);
        if (combo == null) {
            message("找不到连招「" + comboName + "」");
            return -1;
        }
        List<ComboPlan.Resolved> steps =
                ComboPlan.resolveAll(combo.steps, candidatesFor(ctx, modelDir));
        return playSteps(svc, comboName, combo.size(), steps);
    }

    /**
     * 播放已解析好的步骤（界面自行扫描 / 校验后调用，省一次重复扫描）。
     *
     * @param totalSteps 连招原始步数，用于提示「N 步已失效」
     * @return 可播步骤数；{@code -1} 无桌宠，{@code 0} 无可播步骤
     */
    public int playSteps(PetService svc, String comboName, int totalSteps,
                         List<ComboPlan.Resolved> steps) {
        if (svc == null) return -1;
        if (steps == null || steps.isEmpty()) {
            message("连招「" + comboName + "」没有可播放的步骤（动作 / 表情可能已被删除或改名）");
            return 0;
        }
        if (steps.size() < totalSteps) {
            message("连招有 " + (totalSteps - steps.size()) + " 步已失效，将播放其余 "
                    + steps.size() + " 步");
        }
        final int gen = ++generation;
        playing = true;
        handler.removeCallbacksAndMessages(null);
        long total = 0;
        for (ComboPlan.Resolved r : steps) total += r.dwellMs;
        message("▶ 连招「" + comboName + "」开始（" + steps.size() + " 步，约 "
                + String.format(Locale.ROOT, "%.1f", total / 1000.0) + " 秒）");
        stepAt(svc, comboName, steps, 0, gen);
        return steps.size();
    }

    /** 下发第 {@code i} 步并在其停留时长后调度下一步；代次作废即停 */
    private void stepAt(PetService svc, String comboName, List<ComboPlan.Resolved> steps,
                        int i, int gen) {
        if (gen != generation) return;
        if (i >= steps.size()) {
            playing = false;
            message("连招「" + comboName + "」播放完成");
            return;
        }
        ComboPlan.Resolved r = steps.get(i);
        if (r.motion) svc.onMotion(r.group, r.index, 3);
        else svc.onExpression(r.exprName);
        final int next = i + 1;
        handler.postDelayed(() -> stepAt(svc, comboName, steps, next, gen), r.dwellMs);
    }

    /** 是否正在播放（含两步之间的等待） */
    public boolean isPlaying() {
        return playing;
    }

    /** 作废当前播放并清掉未执行的延迟任务（页面销毁 / 手动中止时调用） */
    public void cancel() {
        generation++;
        playing = false;
        handler.removeCallbacksAndMessages(null);
    }

    /** 扫描指定模型目录并把结果拍平成解析候选（自动化路径用；界面已有扫描结果时直接 {@link #flatten}） */
    public static List<ComboPlan.Candidate> candidatesFor(Context ctx, String modelDir) {
        if (modelDir == null || modelDir.isEmpty()) return new ArrayList<>();
        for (MotionLibrary.ModelActions m : MotionLibrary.scanAll(ctx, modelDir)) {
            if (modelDir.equals(m.dirName)) return flatten(m);
        }
        return new ArrayList<>();
    }

    /** 把某模型的扫描结果拍平成解析候选（只取「声明 + 文件存在」的可播项）。纯映射，无副作用。 */
    public static List<ComboPlan.Candidate> flatten(MotionLibrary.ModelActions m) {
        List<ComboPlan.Candidate> out = new ArrayList<>();
        if (m == null) return out;
        for (MotionLibrary.Group g : m.groups) {
            for (int i = 0; i < g.motions.size(); i++) {
                MotionLibrary.Motion mo = g.motions.get(i);
                if (!mo.declared || !mo.exists) continue;
                out.add(new ComboPlan.Candidate(true, g.name, mo.file, null, i,
                        mo.duration, mo.loop));
            }
        }
        for (MotionLibrary.Expr e : m.expressions) {
            if (!e.declared || !e.exists) continue;
            out.add(new ComboPlan.Candidate(false, null, e.file, e.name, -1, 0, false));
        }
        return out;
    }

    private void message(String text) {
        if (listener != null) listener.onMessage(text);
    }
}
