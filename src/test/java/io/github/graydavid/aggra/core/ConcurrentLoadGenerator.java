package io.github.graydavid.aggra.core;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.graydavid.onemoretry.Try;

/**
 * A utility class to help with generating concurrent load. Spawns *concurrency* threads to run *targetWork*,
 * synchronizes them to start together, runs them, and then waits for them all to finish.
 */
public class ConcurrentLoadGenerator {
    private final List<Runnable> targetWork;

    public ConcurrentLoadGenerator(int concurrency, Runnable targetWork) {
        this.targetWork = IntStream.range(0, concurrency)
                .mapToObj(i -> targetWork)
                .collect(Collectors.toUnmodifiableList());
    }

    public ConcurrentLoadGenerator(List<Runnable> targetWork) {
        this.targetWork = List.copyOf(targetWork);
    }

    private int concurrency() {
        return targetWork.size();
    }

    public void run() {
        ExecutorService executorService = Executors.newFixedThreadPool(concurrency());
        CountDownLatch allThreadsReadySignal = new CountDownLatch(concurrency());
        CountDownLatch startExecutionSignal = new CountDownLatch(1);
        CountDownLatch allThreadsFinishedSignal = new CountDownLatch(concurrency());
        Function<Integer, Runnable> readyStartFinish = i -> () -> {
            allThreadsReadySignal.countDown();
            try {
                Try.runUnchecked(startExecutionSignal::await);
                targetWork.get(i).run();
            } finally {
                allThreadsFinishedSignal.countDown();
            }
        };

        IntStream.range(0, concurrency()).forEach(i -> executorService.execute(readyStartFinish.apply(i)));

        Try.runUnchecked(allThreadsReadySignal::await);
        startExecutionSignal.countDown();
        Try.runUnchecked(allThreadsFinishedSignal::await);
        executorService.shutdown();
    }
}
