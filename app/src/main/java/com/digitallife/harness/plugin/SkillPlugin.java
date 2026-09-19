package com.digitallife.harness.plugin;

import android.content.Context;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.PromptAssembler;
import com.digitallife.skill.SkillManager;

/**
 * 把已安装技能的名称与描述织进 system prompt 的 skills 段（正文仍走 load_skill）。
 */
public final class SkillPlugin implements Plugin {

    private final Context app;

    public SkillPlugin(Context app) {
        this.app = app != null ? app.getApplicationContext() : null;
    }

    @Override
    public String id() {
        return "dsh-skills";
    }

    @Override
    public void activate(HarnessContext ctx) {
        PromptAssembler pa = ctx.get("systemPrompt");
        if (pa == null || app == null) return;
        pa.setSection("skills", render());
    }

    private String render() {
        try {
            java.util.List<SkillManager.Skill> skills = SkillManager.list(app);
            if (skills == null || skills.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("## 已安装技能\n");
            sb.append("需要时用 skill_summary / load_skill 加载完整流程，不要凭空发挥。\n");
            int n = 0;
            for (SkillManager.Skill s : skills) {
                if (s == null || s.name == null) continue;
                sb.append("- ").append(s.name);
                if (s.description != null && !s.description.isEmpty()) {
                    sb.append("：").append(s.description);
                }
                sb.append("\n");
                n++;
                if (n >= 8) break;
            }
            return n == 0 ? "" : sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
