package ru.spbau.mit.java2;

import org.junit.*;
import ru.spbau.mit.LightFuture;
import ru.spbau.mit.ThreadPoolImpl;
import ru.spbau.mit.api.ThreadPool;
import ru.spbau.mit.LightExecutionException;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ThreadPoolTest {

    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static volatile ThreadPool threadPool = new ThreadPoolImpl(CORES);

    private static ThreadPool getThreadPool() {
        if (threadPool.isShutdown()) {
            threadPool = new ThreadPoolImpl(CORES);
        }
        return threadPool;
    }

    private static long threadPoolThreadsCount() {
        ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
        int noThreads = currentGroup.activeCount();
        Thread[] lstThreads = new Thread[noThreads];
        currentGroup.enumerate(lstThreads);
        return Arrays.stream(lstThreads)
                .filter(t -> t.getName().matches("threadPool: thread-[0-9]+"))
                .count();
    }

    @Test
    public void testRunningThread() {
        Assert.assertEquals(CORES, threadPoolThreadsCount());
    }

    @Test
    public void testSubmit() {
        ThreadPool curThreadPool = getThreadPool();
        List<Callable<Integer>> callables = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 2 * CORES; ++i) {
            int finalI = i;
            callables.add(() -> finalI);
            expected.add(finalI);
        }
        List<LightFuture<Integer>> futures = new ArrayList<>(callables.size());
        callables.forEach(s -> futures.add(curThreadPool.submit(s)));

        List<Integer> actual = futures
                .stream()
                .map(LightFuture::get)
                .collect(Collectors.toList());
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testBalance() throws InterruptedException {
        ThreadPool threadPool = getThreadPool();
        AtomicInteger counter = new AtomicInteger(0);
        Runnable runnable = () -> {
            counter.getAndIncrement();
            while (counter.get() != CORES + 1);
        };
        for (int i = 0; i < 2 * CORES; ++i) {
            threadPool.submit(runnable);
        }

        // wait while scheduler schedule all tasks
        Thread.sleep(3000);

        List<Integer> workersQueueSize = ((ThreadPoolImpl) threadPool).workersQueueSize();
        workersQueueSize.forEach(s -> Assert.assertEquals(1, s.intValue()));
        counter.getAndIncrement();
    }

    @Test(expected = LightExecutionException.class)
    public void testGetException() {
        ThreadPool curThreadPool = getThreadPool();
        LightFuture<?> future = curThreadPool.submit((Runnable) () -> {
            throw new RuntimeException();
        });
        future.get();
    }

    @Test
    public void testReady() {
        ThreadPool curThreadPool = getThreadPool();
        LightFuture<Integer> future = curThreadPool.submit(() -> {
            final long bigNumber = 1_000_000_000;
            int rez = 0;
            for (int i = 0; i < bigNumber; ++i) {
                rez += i;
            }
            return rez;
        });
        Assert.assertEquals(false, future.isReady());
    }

    @Test
    public void testShutdown() {
        ThreadPool curThreadPool = getThreadPool();
        curThreadPool.shutdown();
        Assert.assertEquals(0, threadPoolThreadsCount());
    }
}
