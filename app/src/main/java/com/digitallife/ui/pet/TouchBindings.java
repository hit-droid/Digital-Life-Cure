package com.digitallife.ui.pet;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 触摸事件 → 表情 / 动作 绑定（v1.176.0，纯逻辑 + 存储，JVM 可测）。
 *
 * <p>背景：{@link PetTouchReaction} 把各触摸分区写死成「摸头 F06/Shy、戳身子 F05/TapBody、
 * 点空白处随机 TapBody」，双击在 {@code PetService} 里写死成 F02——用户改不了。本类让用户为
 * 每个事件**分别指定触发表情与触发动作组**，只覆盖视觉（表情 + 动作），气泡文案与情绪 /
 * 亲密度增量仍走默认反应。</p>
 *
 * <p>绑定按**名字**存（不绑具体模型）：播放时若为空则回落该事件的默认值（见 {@link #effective}），
 * 当前模型没有绑定的那个表情 / 动作也能靠默认值兜住，不会哑火。也可用哨兵值 {@link #NONE}
 * 明确「这个事件什么都不绑」（等同关闭默认），与「没设过、用默认」区分开。</p>
 *
 * <p>事件 id：{@link #EVENT_HEAD}/{@link #EVENT_BODY} 复用 {@link PetTouchReaction} 的分区常量，
 * 另加 {@link #EVENT_DOUBLE_TAP}（双击，默认表情 F02、无动作）与 {@link #EVENT_NONE}
 * （点空白处，默认无表情、随机 TapBody）。存储 key 为 {@code <eventId>.expression} /
 * {@code <eventId>.motion}，空串表示「未设」。</p>
 */
public final class TouchBindings {

    private static final String PREF = "care_touch_bindings";
    private static final String KEY_EXPR_SUFFIX = ".expression";
    private static final String KEY_MOTION_SUFFIX = ".motion";

    /** 摸头（复用分区常量） */
    public static final String EVENT_HEAD = PetTouchReaction.ZONE_HEAD;
    /** 戳身子（复用分区常量） */
    public static final String EVENT_BODY = PetTouchReaction.ZONE_BODY;
    /** 双击 */
    public static final String EVENT_DOUBLE_TAP = "DoubleTap";
    /** 点空白处（未命中人偶区域） */
    public static final String EVENT_NONE = "None";

    /** 哨兵：明确「该事件不绑表情 / 动作」（区别于空串的「未设、用默认」） */
    public static final String NONE = "-none-";

    /** 一个可绑定事件：id + 人话名 + 默认表情名 + 默认动作组名（默认可为 null） */
    public static final class Event {
        public final String id;
        public final String label;
        public final String defaultExpression;
        public final String defaultMotionGroup;

        Event(String id, String label, String defaultExpression, String defaultMotionGroup) {
            this.id = id;
            this.label = label;
            this.defaultExpression = defaultExpression;
            this.defaultMotionGroup = defaultMotionGroup;
        }
    }

    private static final Event[] EVENTS = {
            new Event(EVENT_HEAD, "摸头", "F06", "Shy"),
            new Event(EVENT_BODY, "戳身子", "F05", "TapBody"),
            new Event(EVENT_DOUBLE_TAP, "双击", "F02", null),
            new Event(EVENT_NONE, "点空白处", null, "TapBody"),
    };

    private TouchBindings() {
    }

    /** 全部可绑定事件（新副本，顺序即列表显示顺序） */
    public static Event[] events() {
        return EVENTS.clone();
    }

    /** 按 id 找事件；未知 id 返回 null */
    public static Event eventFor(String id) {
        if (id == null) return null;
        for (Event e : EVENTS) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /**
     * 生效值：绑定非空且非哨兵 → 用绑定；空串 → 回落默认；哨兵 {@link #NONE} → 明确不绑（null）。
     * 默认值也空时返回 null。
     */
    public static String effective(String bound, String fallback) {
        if (bound == null || bound.isEmpty()) {
            return (fallback == null || fallback.isEmpty()) ? null : fallback;
        }
        if (NONE.equals(bound)) return null;
        return bound;
    }

    /** 某事件的当前绑定（未设的字段为空串，不会为 null） */
    public static final class Binding {
        public final String expression;
        public final String motionGroup;

        Binding(String expression, String motionGroup) {
            this.expression = expression == null ? "" : expression;
            this.motionGroup = motionGroup == null ? "" : motionGroup;
        }

        public boolean isEmpty() {
            return expression.isEmpty() && motionGroup.isEmpty();
        }
    }

    public static Binding get(Context ctx, String eventId) {
        if (ctx == null || eventId == null) return new Binding("", "");
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return new Binding(sp.getString(eventId + KEY_EXPR_SUFFIX, ""),
                sp.getString(eventId + KEY_MOTION_SUFFIX, ""));
    }

    /** 该事件是否仍是最初默认（两项都没设过） */
    public static boolean isDefault(Context ctx, String eventId) {
        return get(ctx, eventId).isEmpty();
    }

    /**
     * 设表情绑定。{@code value} 为 null / 空串 → 清掉（回到默认）；{@link #NONE} → 明确不绑。
     */
    public static void setExpression(Context ctx, String eventId, String value) {
        put(ctx, eventId + KEY_EXPR_SUFFIX, value);
    }

    /** 设动作组绑定，语义同 {@link #setExpression} */
    public static void setMotionGroup(Context ctx, String eventId, String value) {
        put(ctx, eventId + KEY_MOTION_SUFFIX, value);
    }

    /** 该事件恢复默认（两项一起清掉） */
    public static void clear(Context ctx, String eventId) {
        if (ctx == null || eventId == null) return;
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove(eventId + KEY_EXPR_SUFFIX)
                .remove(eventId + KEY_MOTION_SUFFIX)
                .apply();
    }

    private static void put(Context ctx, String key, String value) {
        if (ctx == null || key == null) return;
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        if (value == null || value.isEmpty()) e.remove(key);
        else e.putString(key, value);
        e.apply();
    }

    /** 候选筛选：把名字去重并剔除空串（UI 选择器用） */
    public static List<String> distinctNames(Iterable<String> names) {
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        for (String n : names) {
            if (n != null && !n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return out;
    }
}
