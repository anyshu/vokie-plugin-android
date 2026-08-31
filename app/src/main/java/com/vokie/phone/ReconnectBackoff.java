package com.vokie.phone;

final class ReconnectBackoff {
    private static final long[] DELAYS_MS = {2_500, 5_000, 10_000, 20_000, 30_000};
    private int attempt;

    long nextDelayMs() {
        long delay = DELAYS_MS[Math.min(attempt, DELAYS_MS.length - 1)];
        attempt += 1;
        return delay;
    }

    void reset() {
        attempt = 0;
    }
}
