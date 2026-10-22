package com.rivell.workbench.platform.parsing;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds how many documents are loaded at once. A Word or Excel package is held in memory whole
 * while it is parsed, rendered or verified, so several large files at the same moment could
 * exhaust the heap (DW-081). A caller that finds every slot taken waits its turn; it is never
 * refused.
 *
 * <p>A call made while the same thread already holds a slot runs inside that slot. A second
 * acquire on that thread would otherwise wait for itself.
 */
@Component
public class DocumentWorkLimiter {

    public static final int DEFAULT_MAX_CONCURRENT = 2;

    private final Semaphore slots;
    private final ThreadLocal<Boolean> insideLimit = ThreadLocal.withInitial(() -> false);

    @Autowired
    public DocumentWorkLimiter(
            @Value("${rivell.document-work.max-concurrent:" + DEFAULT_MAX_CONCURRENT + "}")
            int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException(
                    "rivell.document-work.max-concurrent must be at least 1, was " + maxConcurrent);
        }
        this.slots = new Semaphore(maxConcurrent, true);
    }

    public <T> T run(Supplier<T> work) {
        if (insideLimit.get()) {
            return work.get();
        }
        slots.acquireUninterruptibly();
        insideLimit.set(true);
        try {
            return work.get();
        } finally {
            insideLimit.remove();
            slots.release();
        }
    }
}
