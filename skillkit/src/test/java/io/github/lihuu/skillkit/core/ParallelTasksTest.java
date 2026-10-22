package io.github.lihuu.skillkit.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParallelTasksTest {
    @Test
    void twoTasksOverlapAndNeverExceedConfiguredLimit() throws IOException {
        CountDownLatch entered = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ParallelTasks.Task<String> task = () -> {
            int running = active.incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            entered.countDown();
            try {
                if (!entered.await(3, TimeUnit.SECONDS)) throw new IOException("Tasks did not overlap");
                return "done";
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            } finally {
                active.decrementAndGet();
            }
        };
        List<ParallelTasks.Outcome<String>> outcomes = ParallelTasks.run(List.of(task, task, task, task), 2);
        assertTrue(outcomes.stream().allMatch(outcome -> "done".equals(outcome.value())), outcomes.toString());
        assertEquals(2, peak.get());
        assertEquals(0, active.get());
    }

    @Test
    void serialExecutionPreservesOrderAndIsolatesFailures() throws IOException {
        List<ParallelTasks.Outcome<String>> outcomes = ParallelTasks.run(List.of(
                () -> "first", () -> { throw new IOException("expected failure"); }, () -> "third"), 1);
        assertEquals("first", outcomes.get(0).value());
        assertEquals("expected failure", outcomes.get(1).error());
        assertEquals("third", outcomes.get(2).value());
    }
}
