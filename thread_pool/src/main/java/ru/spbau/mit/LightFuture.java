package ru.spbau.mit;

public interface LightFuture<X> {
    X get();
    boolean isReady();
}
