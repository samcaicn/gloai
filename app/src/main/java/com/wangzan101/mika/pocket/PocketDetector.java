package com.wangzan101.mika.pocket;

/** Debounces covered and dark readings, with manual-dismiss suppression. */
public final class PocketDetector {
    private long since = -1;
    private boolean fired, suppressed;
    public boolean update(boolean candidate, boolean ready, boolean away, long now) {
        if (!ready) { since = -1; return false; }
        if (suppressed) {
            if (away) { since = -1; fired = false; suppressed = false; }
            return false;
        }
        if (!candidate) { since = -1; fired = false; return false; }
        if (fired) return false;
        if (since < 0) { since = now; return false; }
        if (now - since < 2000) return false;
        fired = true;
        return true;
    }
    public void dismiss() { suppressed = true; }
    public void failed() { fired = false; since = -1; }
}
