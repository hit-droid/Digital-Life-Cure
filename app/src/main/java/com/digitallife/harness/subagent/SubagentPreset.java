package com.digitallife.harness.subagent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 子智能体 preset：一个具名的能力组合。
 * 同一套 plugin 树，换 preset 就换了提示词、工具白名单与步数上限。
 */
public final class SubagentPreset {

    public final String name;
    public final String description;
    public final String systemPrompt;
    public final Set<String> tools;
    public final int maxSteps;

    public SubagentPreset(String name, String description, String systemPrompt,
                          String[] tools, int maxSteps) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        Set<String> set = new HashSet<>();
        if (tools != null) {
            for (String t : tools) {
                if (t != null && !t.isEmpty()) set.add(t);
            }
        }
        this.tools = Collections.unmodifiableSet(set);
        this.maxSteps = maxSteps > 0 ? maxSteps : 4;
    }
}
