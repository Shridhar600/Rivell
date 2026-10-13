package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
}
