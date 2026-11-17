package com.wangzan101.mika.pocket;

/** A completion check must stay eligible and covered for the whole half second. */
public final class CompletionLockGate {
    private long started = -1;
    public boolean begin(boolean enabled, boolean eligible, boolean idle, boolean covered, long now) {
        started = enabled && eligible && idle && covered ? now : -1;
        return started >= 0;
    }
    public boolean observe(boolean enabled, boolean idle, boolean covered, long now) {
        if (started < 0) return false;
        if (!enabled || !idle || !covered || now < started) { cancel(); return false; }
        if (now - started < 500) return false;
        cancel();
        return true;
    }
    public void cancel() { started = -1; }
}
