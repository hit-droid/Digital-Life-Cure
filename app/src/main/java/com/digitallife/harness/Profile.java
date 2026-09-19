package com.digitallife.harness;

import android.content.Context;

import com.digitallife.harness.plugin.AgentLoopPlugin;
import com.digitallife.harness.plugin.AgentsPlugin;
import com.digitallife.harness.plugin.ClockPlugin;
import com.digitallife.harness.plugin.CompressPlugin;
import com.digitallife.harness.plugin.GuardPlugin;
import com.digitallife.harness.plugin.MemoryPlugin;
import com.digitallife.harness.plugin.PersonaPlugin;
import com.digitallife.harness.plugin.SessionPlugin;
import com.digitallife.harness.plugin.SkillPlugin;
import com.digitallife.harness.plugin.SystemPromptPlugin;
import com.digitallife.harness.plugin.ToolsPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 具名插件树。对齐官方 profile = 有序 bundle 叠加。
 * {@code chat} 挂全量能力；{@code isolated} 给子智能体 / 护理大脑，不含角色记忆技能。
 */
public final class Profile {

    public static final String CHAT = "chat";
    public static final String ISOLATED = "isolated";

    public final String name;
    private final List<Plugin> plugins;

    public Profile(String name, List<Plugin> plugins) {
        this.name = name == null ? "" : name;
        this.plugins = plugins == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(plugins));
    }

    public List<Plugin> plugins() {
        return plugins;
    }

    public static Profile chat(Context app) {
        List<Plugin> row = new ArrayList<>();
        row.add(new SessionPlugin());
        row.add(new SystemPromptPlugin());
        row.add(new ToolsPlugin());
        row.add(new AgentLoopPlugin());
        row.add(new AgentsPlugin());
        row.add(new PersonaPlugin(app));
        row.add(new MemoryPlugin(app));
        row.add(new SkillPlugin(app));
        row.add(new ClockPlugin());
        row.add(new CompressPlugin());
        row.add(new GuardPlugin(app));
        return new Profile(CHAT, row);
    }

    public static Profile isolated(Context app) {
        List<Plugin> row = new ArrayList<>();
        row.add(new SessionPlugin());
        row.add(new SystemPromptPlugin());
        row.add(new ToolsPlugin());
        row.add(new AgentLoopPlugin());
        row.add(new AgentsPlugin());
        row.add(new ClockPlugin());
        row.add(new CompressPlugin());
        row.add(new GuardPlugin(app));
        return new Profile(ISOLATED, row);
    }
}
