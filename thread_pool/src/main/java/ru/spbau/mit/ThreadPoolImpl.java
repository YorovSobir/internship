package ru.spbau.mit;

import ru.spbau.mit.api.ThreadPool;

import java.util.*;
import java.util.concurrent.*;

public final class ThreadPoolImpl implements ThreadPool {

    private static final int TASK_COUNT_THRESHOLD = 3;
    private volatile boolean shutdown = false;
    private final Queue<Runnable> taskQueue = new LinkedList<>();
    private final Set<Worker> workers = new ConcurrentSkipListSet<>((o1, o2) -> {
        int res = o1.tasks.size() - o2.tasks.size();
        if (res == 0) {
            return (int) (o1.getId() - o2.getId());
        }
        return res;
    });
    private Scheduler scheduler;

    public ThreadPoolImpl(int noOfThreads) {
        for (int i = 0; i < noOfThreads; ++i) {
            // name are only used in tests
            Worker worker = new Worker("threadPool: thread-" + i);
            worker.start();
            workers.add(worker);
        }
        scheduler = new Scheduler();
        scheduler.start();
    }

    private <T> void addFuture(LightFutureImpl<T> future) {
        synchronized (taskQueue) {
            taskQueue.add(future::run);
            taskQueue.notify();
        }
    }

    @Override
    public LightFuture<?> submit(Runnable runnable) {
        if (isShutdown()) {
            throw new RuntimeException("there are not running workers");
        }

        LightFutureImpl<?> future = new LightFutureImpl<>(runnable);
        addFuture(future);
        return future;
    }

    @Override
    public <T> LightFuture<T> submit(Callable<T> callable) {
        if (isShutdown()) {
            throw new RuntimeException("there are not running workers");
        }

        LightFutureImpl<T> future = new LightFutureImpl<>(callable);
        addFuture(future);
        return future;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized void shutdown() {
        scheduler.interrupt();
        try {
            scheduler.join();
        } catch (InterruptedException e) {
            throw new RuntimeException("interrupted when join scheduler", e);
        }
        workers.forEach(Thread::interrupt);
        workers.forEach(w -> {
            try {
                w.join();
            } catch (InterruptedException e) {
                throw new RuntimeException("interrupted when shutdown", e);
            }
        });
        workers.clear();
        shutdown = true;
    }

    // this method are only used in tests
    public List<Integer> workersQueueSize() {
        List<Integer> res = new ArrayList<>();
        workers.forEach(w -> res.add(w.tasks.size()));
        return res;
    }

    // this method are only used in tests
    public Queue<Runnable> getTaskQueue() {
        return taskQueue;
    }

    private final class Scheduler extends Thread {

        @Override
        public void run() {
            while (!isInterrupted()) {
                synchronized (taskQueue) {
                    while (taskQueue.isEmpty()) {
                        try {
                            taskQueue.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    Iterator<Worker> iterator = workers.iterator();
                    if (iterator.hasNext()) {
                        Worker worker = iterator.next();
                        if (worker.tasks.size() > TASK_COUNT_THRESHOLD) {
                            continue;
                        }
                        Runnable task = taskQueue.poll();
                        iterator.remove();
                        synchronized (worker.tasks) {
                            worker.tasks.add(task);
                            worker.tasks.notify();
                        }
                        workers.add(worker);
                    }
                    // notify for test
                    taskQueue.notify();
                }
            }
        }
    }

    private final class Worker extends Thread {

        private final Queue<Runnable> tasks = new LinkedList<>();

        Worker(String name) {
            super(name);
        }

        @Override
        public void run() {
            Runnable task;
            while (!isInterrupted()) {
                synchronized (tasks) {
                    while (tasks.isEmpty()) {
                        try {
                            tasks.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    workers.remove(this);
                    task = tasks.poll();
                    workers.add(this);
                }
                task.run();
            }
        }
    }

    private final class LightFutureImpl<X> implements LightFuture<X> {
        private final Callable<X> callable;
        private X value;
        private Throwable throwable;
        private volatile boolean done = false;

        LightFutureImpl(Callable<X> callable) {
            this.callable = callable;
        }

        LightFutureImpl(Runnable runnable) {
            this.callable = () -> {
                runnable.run();
                return null;
            };
        }

        private void run() {
            setValue();
        }

        private void setValue() {
            if (!done) {
                synchronized (this) {
                    if (!done) {
                        try {
                            value = callable.call();
                        } catch (Throwable e) {
                            throwable = e;
                        } finally {
                            done = true;
                            notifyAll();
                        }
                    }
                }
            }
        }

        @Override
        public X get() {
            setValue();
            if (throwable != null) {
                throw new LightExecutionException("callable throws exception", throwable);
            }
            return value;
        }

        @Override
        public boolean isReady() {
            return done;
        }
    }
}
