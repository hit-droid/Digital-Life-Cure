package com.digitallife.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EventBus {

    public interface Handler {
        void handle(Object payload);
    }

    public interface Waterfall<T> {
        T handle(T value, Next<T> next);
    }

    public interface Next<T> {
        T apply(T value);
    }

    private final Map<String, List<Handler>> handlers = new LinkedHashMap<>();
    private final Map<String, List<Waterfall<?>>> waterfalls = new LinkedHashMap<>();

    public synchronized Disposable on(String name, Handler handler) {
        if (name == null || handler == null) return Disposable.NONE;
        List<Handler> list = handlers.get(name);
        if (list == null) {
            list = new ArrayList<>();
            handlers.put(name, list);
        }
        list.add(handler);
        final List<Handler> owned = list;
        return () -> {
            synchronized (EventBus.this) {
                owned.remove(handler);
            }
        };
    }

    public synchronized void emit(String name, Object payload) {
        List<Handler> list = handlers.get(name);
        if (list == null || list.isEmpty()) return;
        List<Handler> snapshot = new ArrayList<>(list);
        for (Handler h : snapshot) {
            try {
                h.handle(payload);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized <T> Disposable waterfall(String name, Waterfall<T> w) {
        if (name == null || w == null) return Disposable.NONE;
        List<Waterfall<?>> list = waterfalls.get(name);
        if (list == null) {
            list = new ArrayList<>();
            waterfalls.put(name, list);
        }
        list.add(w);
        final List<Waterfall<?>> owned = list;
        return () -> {
            synchronized (EventBus.this) {
                owned.remove(w);
            }
        };
    }

    @SuppressWarnings("unchecked")
    public <T> T waterfall(String name, T seed) {
        List<Waterfall<?>> list;
        synchronized (this) {
            List<Waterfall<?>> raw = waterfalls.get(name);
            if (raw == null || raw.isEmpty()) return seed;
            list = new ArrayList<>(raw);
        }
        return invoke(list, 0, seed);
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(List<Waterfall<?>> list, int index, T value) {
        if (index >= list.size()) return value;
        Waterfall<T> w = (Waterfall<T>) list.get(index);
        return w.handle(value, next -> invoke(list, index + 1, next));
    }
}
