package com.digitallife.harness.plugin;

import android.content.Context;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.PromptAssembler;
import com.digitallife.persona.Persona;
import com.digitallife.persona.PersonaManager;
import com.digitallife.util.Settings;

/**
 * 把当前角色卡织进 system prompt 的 persona 段。
 * 没有角色卡时退回 Settings 里的宠物名。
 */
public final class PersonaPlugin implements Plugin {

    private final Context app;

    public PersonaPlugin(Context app) {
        this.app = app != null ? app.getApplicationContext() : null;
    }

    @Override
    public String id() {
        return "dsh-persona";
    }

    @Override
    public void activate(HarnessContext ctx) {
        PromptAssembler pa = ctx.get("systemPrompt");
        if (pa == null) return;
        pa.setSection("persona", render());
    }

    private String render() {
        if (app == null) return fallback("小汐");
        Persona p = null;
        try {
            p = PersonaManager.get(app).active();
        } catch (Exception ignored) {
        }
        if (p != null && p.name != null && !p.name.isEmpty()) {
            String card = p.toPromptSection();
            if (card != null && !card.trim().isEmpty()) {
                return card.trim()
                        + "\n你活泼可爱，有一点小傲娇，关心用户但绝不啰嗦。"
                        + "\n说话简短自然，一般不超过 80 字，用口语化的二次元语气，亲近但不腻。"
                        + "\n直接以纯文本回复，不要输出 JSON 或任何标记。";
            }
        }
        String name = "小汐";
        try {
            name = new Settings(app).getPetName();
        } catch (Exception ignored) {
        }
        return fallback(name);
    }

    private static String fallback(String name) {
        String n = (name == null || name.isEmpty()) ? "小汐" : name;
        return "你是「" + n + "」，一个住在用户手机里的 AI 二次元少女，是用户亲密的朋友。\n"
                + "你活泼可爱，有一点小傲娇，关心用户但绝不啰嗦。\n"
                + "说话简短自然，一般不超过 80 字，用口语化的二次元语气，亲近但不腻。\n"
                + "直接以纯文本回复，不要输出 JSON 或任何标记。";
    }
}
