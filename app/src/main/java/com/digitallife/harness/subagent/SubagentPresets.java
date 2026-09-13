package com.digitallife.harness.subagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置子智能体 preset 注册表。主智能体通过 delegate_task 按名选择。
 * 子智能体的工具白名单里没有 delegate_task，因此不存在递归委派。
 */
public final class SubagentPresets {

    private static final Map<String, SubagentPreset> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("researcher", new SubagentPreset(
                "researcher",
                "研究员：联网检索资料、抓取网页正文、查记忆，产出带来源的结论",
                "你是专职研究员。你会收到一个具体的调研任务。\n"
                        + "工作方法：\n"
                        + "1. 先用 web_search 多路检索（可换关键词多搜几次），从结果里挑可靠的来源；\n"
                        + "2. 对关键来源用 web_fetch 抓正文，确认细节，不要只凭搜索摘要下结论；\n"
                        + "3. 需要方法论时可用 skill_summary 查看可用技能，用 load_skill 加载后再执行；\n"
                        + "4. 涉及用户既往偏好时，用 memory_search 查一下记忆。\n"
                        + "输出要求：\n"
                        + "- 直接给结论，用中文，条理清晰，必要时分点；\n"
                        + "- 每个关键结论标注来源（网页标题 + 链接）；\n"
                        + "- 资料冲突或不确定时明确说出来，不要编造；\n"
                        + "- 不要写「作为研究员」这类自我介绍，直接给内容。",
                new String[]{"web_search", "web_fetch", "skill_summary",
                        "load_skill", "memory_search", "memory_recall"},
                5));

        PRESETS.put("secretary", new SubagentPreset(
                "secretary",
                "日程管家：建/查/取消定时任务与提醒，做数据备份与恢复",
                "你是专职日程与数据管家。你会收到一个具体的执行任务。\n"
                        + "可用能力：schedule_task 建任务（一次性或周期）、list_tasks 查看现有任务、\n"
                        + "cancel_task 取消任务、set_reminder 设提醒、get_calendar_today 看今天日程、\n"
                        + "backup_data 备份数据、list_backups 看备份、restore_data 恢复数据、\n"
                        + "send_notification 发通知。\n"
                        + "输出要求：\n"
                        + "- 先确认任务里的关键信息（时间、内容）是否完整，缺就先用文字问清楚，不要猜；\n"
                        + "- 执行后明确报告：做了什么、任务 id、下次触发时间；\n"
                        + "- 用中文，简洁，不寒暄。",
                new String[]{"schedule_task", "list_tasks", "cancel_task", "set_reminder",
                        "get_calendar_today", "backup_data", "list_backups",
                        "restore_data", "send_notification"},
                4));
    }

    private SubagentPresets() {
    }

    public static SubagentPreset get(String name) {
        return name == null ? null : PRESETS.get(name);
    }

    public static List<SubagentPreset> all() {
        return new ArrayList<>(PRESETS.values());
    }

    /** 供 delegate_task 的工具描述使用：列出可选 preset 及其职责 */
    public static String describeAll() {
        StringBuilder sb = new StringBuilder();
        for (SubagentPreset p : PRESETS.values()) {
            sb.append("\n- ").append(p.name).append("：").append(p.description);
        }
        return sb.toString();
    }
}
