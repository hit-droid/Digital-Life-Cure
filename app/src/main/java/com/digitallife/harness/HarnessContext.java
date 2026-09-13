package com.digitallife.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HarnessContext {

    private final Map<String, Object> services = new LinkedHashMap<>();
    private final EventBus events = new EventBus();
    private final List<Disposable> effects = new ArrayList<>();

    public EventBus events() {
        return events;
    }

    public synchronized <T> void provide(String key, T service) {
        if (key == null || service == null) return;
        services.put(key, service);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T get(String key) {
        if (key == null) return null;
        Object v = services.get(key);
        return v == null ? null : (T) v;
    }

    public synchronized Disposable effect(Disposable d) {
        if (d == null) return Disposable.NONE;
        effects.add(d);
        return d;
    }

    public synchronized void dispose() {
        for (int i = effects.size() - 1; i >= 0; i--) {
            try {
                effects.get(i).dispose();
            } catch (Exception ignored) {
            }
        }
        effects.clear();
        services.clear();
    }
}
