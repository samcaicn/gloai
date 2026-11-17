package com.wangzan101.mika.pocket;

/** Runs one monitor while the feature is enabled and at least one task is active. */
public final class GuardLifecycle {
    public interface Port { boolean start(); void stop(); }
    private final Port port;
    private boolean running, closed;
    public GuardLifecycle(Port port) { this.port = port; }
    public synchronized void update(boolean enabled, boolean taskActive) {
        if (closed) return;
        boolean wanted = enabled && taskActive;
        if (wanted == running) return;
        if (wanted) running = port.start();
        else { running = false; port.stop(); }
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (running) { running = false; port.stop(); }
    }
}
