package com.digitallife.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在途 Agent 注册表，对应官方 {@code ctx.agents}。
 */
public final class AgentRegistry {

    private final Map<String, AgentHandle> live = new LinkedHashMap<>();

    public synchronized void register(AgentHandle agent) {
        if (agent == null || agent.id == null) return;
        live.put(agent.id, agent);
    }

    public synchronized void unregister(String id) {
        if (id == null) return;
        live.remove(id);
    }

    public synchronized AgentHandle get(String id) {
        if (id == null) return null;
        return live.get(id);
    }

    public synchronized List<AgentHandle> all() {
        return new ArrayList<>(live.values());
    }

    public synchronized int size() {
        return live.size();
    }
}
