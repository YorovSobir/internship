package ru.spbau.mit.api;

import ru.spbau.mit.LightFuture;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

public interface ThreadPool {
    <T> LightFuture<T> submit(Callable<T> callable);
    LightFuture<?> submit(Runnable runnable);
    boolean isShutdown();
    void shutdown();

}
