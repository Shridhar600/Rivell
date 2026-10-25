package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rivell.workbench.foundation.DocumentWorkBusyException;
import com.rivell.workbench.foundation.DocumentWorkLimiter;
import com.rivell.workbench.platform.docx.DocxRedlineRenderer;
import com.rivell.workbench.platform.docx.DocxRedlineVerifier;

/**
 * DW-081 / DW-095: expensive document work (parse, redline render, redline verify) runs at most N
 * at a time. Extra callers wait for a bounded budget, then are refused instead of queueing.
 */
class DocumentWorkLimiterTest {

    private static final DocxParserProperties PROPERTIES =
            new DocxParserProperties(200L * 1024 * 1024);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void atMostTheConfiguredNumberOfCallsRunAtOnceAndTheRestWait() throws Exception {
        var limiter = new DocumentWorkLimiter(2);
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var completed = new AtomicInteger();
        var twoRunning = new CountDownLatch(2);

        var calls = new ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < 6; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> limiter.run(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                twoRunning.countDown();
                await(twoRunning);
                sleep(50);
                running.decrementAndGet();
                completed.incrementAndGet();
                return "done";
            }), executor));
        }

        for (var call : calls) {
            assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo("done");
        }
        assertThat(completed).hasValue(6);
        assertThat(peak).hasValue(2);
    }

    @Test
    void aCallThatIsAlreadyInsideTheLimitDoesNotWaitForASecondSlot() {
        var limiter = new DocumentWorkLimiter(1);

        var result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> limiter.run(() -> limiter.run(() -> "nested")));

        assertThat(result).isEqualTo("nested");
    }

    @Test
    void aCallerThatWaitsPastTheBudgetIsRefusedInsteadOfQueuedForever() throws Exception {
        var limiter = new DocumentWorkLimiter(1, Duration.ofMillis(100));
        var slotTaken = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> limiter.run(() -> {
            slotTaken.countDown();
            await(release);
            return null;
        }), executor);
        assertThat(slotTaken.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            var outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    limiter.run(() -> "ran");
                    return "ran";
                } catch (DocumentWorkBusyException refused) {
                    return "refused";
                }
            }, executor);
            try {
                assertThat(outcome.get(2, TimeUnit.SECONDS))
                        .as("a caller never waits for a slot forever")
                        .isEqualTo("refused");
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aSlotIsFreedWhenTheWorkThrows() {
        var limiter = new DocumentWorkLimiter(1);

        assertThatThrownBy(() -> limiter.run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        var result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> limiter.run(() -> "next"));
        assertThat(result).isEqualTo("next");
    }

    @Test
    void aLimitBelowOneIsRefusedAtStartup() {
        assertThatThrownBy(() -> new DocumentWorkLimiter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStructuralParserWaitsForAFreeSlot() throws Exception {
        var limiter = new DocumentWorkLimiter(1);
        var parser = new DocxStructuralParser(PROPERTIES, new SignalHeadingDetector(), limiter);

        assertWaitsForTheSlot(limiter,
                () -> parser.parse(new ByteArrayInputStream(new byte[0]), UUID.randomUUID()));
    }

    @Test
    void theWorkbookExtractorWaitsForAFreeSlot() throws Exception {
        var limiter = new DocumentWorkLimiter(1);
        var extractor = new XlsxKnowledgeExtractor(PROPERTIES, limiter);

        assertWaitsForTheSlot(limiter, () -> extractor.extractStructure(
                UUID.randomUUID(), new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void theRedlineRendererWaitsForAFreeSlot() throws Exception {
        var limiter = new DocumentWorkLimiter(1);
        var renderer = new DocxRedlineRenderer(PROPERTIES, limiter);

        assertWaitsForTheSlot(limiter, () -> renderer.render(new byte[0], null));
    }

    @Test
    void theRedlineVerifierWaitsForAFreeSlot() throws Exception {
        var limiter = new DocumentWorkLimiter(1);
        var verifier = new DocxRedlineVerifier(PROPERTIES, limiter);

        assertWaitsForTheSlot(limiter, () -> verifier.verify(new byte[0], new byte[0], null, List.of()));
    }

    /**
     * With the only slot taken, the call must not finish; once freed, it must. The inputs are
     * not real documents, so the call may fail once it runs: only the wait matters here.
     */
    private void assertWaitsForTheSlot(DocumentWorkLimiter limiter, Runnable call)
            throws Exception {
        var slotTaken = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> limiter.run(() -> {
            slotTaken.countDown();
            await(release);
            return null;
        }), executor);
        assertThat(slotTaken.await(5, TimeUnit.SECONDS)).isTrue();

        var waiting = CompletableFuture.runAsync(() -> {
            try {
                call.run();
            } catch (RuntimeException afterTheWait) {
                // not a real document
            }
        }, executor);
        sleep(300);
        assertThat(waiting).as("the call must wait while the only slot is taken").isNotDone();

        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        waiting.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
