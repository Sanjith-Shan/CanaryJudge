package io.canaryjudge.target;

import com.sun.management.GarbageCollectionNotificationInfo;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heap in use right after the most recent garbage collection, exported as {@code cj_heap_after_gc_bytes}.
 *
 * <p>Plain heap usage is a poor canary metric: it saws up and down with every young collection, and two
 * identical JVMs can differ by tens of megabytes for minutes depending on when each last ran a full
 * collection that cleared start-up garbage. Heap after GC follows what is actually retained, which is what
 * a leak grows. A full collection right after start-up gives every instance the same clean starting point.
 */
@Component
public class HeapAfterGc {
    private final AtomicLong afterGc = new AtomicLong(-1);

    public HeapAfterGc(MeterRegistry registry) {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter emitter)) continue;
            emitter.addNotificationListener((n, h) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                long used = 0;
                for (var e : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                    if (isHeapPool(e.getKey())) used += e.getValue().getUsed();
                }
                afterGc.set(used);
            }, null, null);
        }
        Gauge.builder("cj_heap_after_gc_bytes", afterGc, a -> a.get() < 0 ? Double.NaN : a.get())
                .description("heap in use after the most recent garbage collection").register(registry);
    }

    private static boolean isHeapPool(String pool) {
        for (var p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().equals(pool)) return p.getType() == java.lang.management.MemoryType.HEAP;
        }
        return false;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanStart() {
        System.gc();
    }
}
