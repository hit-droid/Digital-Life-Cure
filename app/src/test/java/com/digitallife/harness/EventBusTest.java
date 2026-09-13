package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * EventBus 的契约：waterfall 是委托链，监听器不调 next() 必须短路后续监听器。
 */
public class EventBusTest {

    @Test
    public void emitReachesEveryHandler() {
        EventBus bus = new EventBus();
        final List<String> seen = new ArrayList<>();
        bus.on("e", p -> seen.add("a"));
        bus.on("e", p -> seen.add("b"));
        bus.emit("e", null);
        assertEquals(2, seen.size());
        assertEquals("a", seen.get(0));
        assertEquals("b", seen.get(1));
    }

    @Test
    public void handlerExceptionDoesNotStopOthers() {
        EventBus bus = new EventBus();
        final List<String> seen = new ArrayList<>();
        bus.on("e", p -> {
            throw new RuntimeException("boom");
        });
        bus.on("e", p -> seen.add("after"));
        bus.emit("e", null);
        assertEquals(1, seen.size());
    }

    @Test
    public void disposableRemovesHandler() {
        EventBus bus = new EventBus();
        final int[] count = new int[1];
        Disposable d = bus.on("e", p -> count[0]++);
        bus.emit("e", null);
        d.dispose();
        bus.emit("e", null);
        assertEquals(1, count[0]);
    }

    @Test
    public void waterfallReturnsSeedWhenNoListeners() {
        EventBus bus = new EventBus();
        assertEquals("seed", bus.waterfall("none", "seed"));
    }

    @Test
    public void waterfallDelegatesThroughNext() {
        EventBus bus = new EventBus();
        bus.addWaterfall("w", new EventBus.Waterfall<String>() {
            @Override
            public String handle(String v, EventBus.Next<String> next) {
                return next.apply(v + "-a");
            }
        });
        bus.addWaterfall("w", new EventBus.Waterfall<String>() {
            @Override
            public String handle(String v, EventBus.Next<String> next) {
                return next.apply(v + "-b");
            }
        });
        assertEquals("x-a-b", bus.waterfall("w", "x"));
    }

    @Test
    public void waterfallShortCircuitsWhenNextNotCalled() {
        EventBus bus = new EventBus();
        final boolean[] secondRan = new boolean[1];
        bus.addWaterfall("w", new EventBus.Waterfall<String>() {
            @Override
            public String handle(String v, EventBus.Next<String> next) {
                return "blocked";
            }
        });
        bus.addWaterfall("w", new EventBus.Waterfall<String>() {
            @Override
            public String handle(String v, EventBus.Next<String> next) {
                secondRan[0] = true;
                return next.apply(v);
            }
        });
        assertEquals("blocked", bus.waterfall("w", "x"));
        assertFalse(secondRan[0]);
    }

    @Test
    public void waterfallCanRewriteValueForLaterListeners() {
        EventBus bus = new EventBus();
        bus.addWaterfall("w", new EventBus.Waterfall<String>() {
            @Override
            public String handle(String v, EventBus.Next<String> next) {
                return next.apply(v + "!");
            }
        });
        assertEquals("hi!", bus.waterfall("w", "hi"));
    }

    @Test
    public void waterfallDisposableStopsParticipation() {
        EventBus bus = new EventBus();
        Disposable d = bus.addWaterfall("w", new EventBus.Waterfall<String>() {
            @Override
            public String handle(String v, EventBus.Next<String> next) {
                return next.apply(v + "-once");
            }
        });
        assertEquals("x-once", bus.waterfall("w", "x"));
        d.dispose();
        assertEquals("x", bus.waterfall("w", "x"));
    }

    @Test
    public void waterfallRegistrationIsOrderedByMountOrder() {
        EventBus bus = new EventBus();
        for (int i = 0; i < 3; i++) {
            final String tag = String.valueOf(i);
            bus.addWaterfall("w", new EventBus.Waterfall<String>() {
                @Override
                public String handle(String v, EventBus.Next<String> next) {
                    return next.apply(v + tag);
                }
            });
        }
        assertEquals("x012", bus.waterfall("w", "x"));
    }

    @Test
    public void emitWithNoHandlersIsNoop() {
        EventBus bus = new EventBus();
        bus.emit("nobody", "payload");
        assertNull(bus.waterfall("nobody", (String) null));
    }
}
