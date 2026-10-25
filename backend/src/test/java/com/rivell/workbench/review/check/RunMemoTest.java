package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class RunMemoTest {

    @Test
    void eachRunIsComputedOnce() throws Exception {
        var memo = new RunMemo<String>(16);
        var runId = UUID.randomUUID();
        var computes = new AtomicInteger();

        assertThat(memo.get(runId, () -> {
            computes.incrementAndGet();
            return "value";
        })).isEqualTo("value");
        assertThat(memo.get(runId, () -> {
            computes.incrementAndGet();
            return "other";
        })).isEqualTo("value");
        assertThat(computes).hasValue(1);
    }

    @Test
    void aNewRunStaysMemoisedWhenTheOldestIsEvicted() throws Exception {
        // UUID.hashCode() is Long.hashCode(mostSigBits ^ leastSigBits) (JDK 21 source), so
        // new UUID(k, k) has hashCode 0 for every k. ConcurrentHashMap picks a bin by
        // spread(hashCode) & 15, so oldest and newest below share a bin (0) while middle
        // sits in another one (5): evicting the oldest, the bin's only node, is what makes
        // the nested remove detach the node the new mapping is about to be attached to.
        var oldest = new UUID(1, 1);
        var middle = new UUID(0, 5);
        var newest = new UUID(2, 2);
        assertThat(oldest.hashCode()).isEqualTo(newest.hashCode()).isZero();
        assertThat(middle.hashCode()).isNotEqualTo(oldest.hashCode());

        var memo = new RunMemo<String>(2);
        var oldestComputes = new AtomicInteger();
        var newestComputes = new AtomicInteger();

        assertThat(memo.get(oldest, () -> {
            oldestComputes.incrementAndGet();
            return "oldest";
        })).isEqualTo("oldest");
        assertThat(memo.get(middle, () -> "middle")).isEqualTo("middle");
        assertThatCode(() -> memo.get(newest, () -> {
            newestComputes.incrementAndGet();
            return "newest";
        })).doesNotThrowAnyException();

        assertThat(memo.get(newest, () -> {
            newestComputes.incrementAndGet();
            return "newest";
        })).isEqualTo("newest");
        assertThat(newestComputes).hasValue(1);

        assertThat(memo.get(oldest, () -> {
            oldestComputes.incrementAndGet();
            return "oldest";
        })).isEqualTo("oldest");
        assertThat(oldestComputes).hasValue(2);
    }

    @Test
    void twoThreadsAskingForOneRunComputeOnce() throws Exception {
        var memo = new RunMemo<String>(2);
        var runId = UUID.randomUUID();
        var computes = new AtomicInteger();
        var computing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Supplier<String> compute = () -> {
            computes.incrementAndGet();
            computing.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "value";
        };

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> memo.get(runId, compute));
            assertThat(computing.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> memo.get(runId, compute));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            assertThat(computes).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * DW-067: a run becomes evictable only once it is actually in the memo, so the eviction
     * order can never name a run the map does not hold. The order entry used to be written
     * inside {@code computeIfAbsent}'s mapping function, which runs before the mapping is
     * installed; an eviction that polled it in that window removed nothing and left the run
     * beyond capacity, untracked.
     *
     * <p>The deque is replaced with a subclass that pauses the insert of one key and records
     * whether the memo already holds it at that moment. That is the invariant, and it is the
     * one the bug violated.
     */
    @Test
    void aRunEntersTheEvictionOrderOnlyAfterItIsInstalled() throws Exception {
        var memo = new RunMemo<String>(2);
        var runId = new UUID(1, 1);
        var installedWhenOrdered = new AtomicBoolean();
        var order = new PausingOrder(runId, () -> installedWhenOrdered.set(
                runs(memo).containsKey(runId)));
        var orderField = RunMemo.class.getDeclaredField("order");
        orderField.setAccessible(true);
        orderField.set(memo, order);

        var executor = Executors.newSingleThreadExecutor();
        try {
            var call = executor.submit(() -> memo.get(runId, () -> "value"));
            assertThat(order.firstInsertStarted.await(5, TimeUnit.SECONDS))
                    .as("the run must reach the eviction order").isTrue();

            assertThat(installedWhenOrdered)
                    .as("a run must be in the memo before its eviction-order entry exists")
                    .isTrue();

            order.releaseFirstInsert.countDown();
            assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("value");
        } finally {
            executor.shutdownNow();
        }
    }

    private static java.util.Map<?, ?> runs(RunMemo<?> memo) {
        try {
            var field = RunMemo.class.getDeclaredField("runs");
            field.setAccessible(true);
            return (java.util.Map<?, ?>) field.get(memo);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A deque whose first insert pauses after the entry is visible. */
    private static final class PausingOrder extends ConcurrentLinkedDeque<UUID> {

        private final UUID pausedKey;
        private final Runnable onFirstInsert;
        private final AtomicBoolean paused = new AtomicBoolean();
        private final CountDownLatch firstInsertStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstInsert = new CountDownLatch(1);

        PausingOrder(UUID pausedKey, Runnable onFirstInsert) {
            this.pausedKey = pausedKey;
            this.onFirstInsert = onFirstInsert;
        }

        @Override
        public void addLast(UUID runId) {
            super.addLast(runId);
            // Only the named run pauses: a second insert on the same deque must not wait for it.
            if (runId.equals(pausedKey) && paused.compareAndSet(false, true)) {
                onFirstInsert.run();
                firstInsertStarted.countDown();
                try {
                    releaseFirstInsert.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
