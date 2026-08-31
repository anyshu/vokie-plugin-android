package com.vokie.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReconnectBackoffTest {
    @Test
    public void increasesDelayAndCapsAtThirtySeconds() {
        ReconnectBackoff backoff = new ReconnectBackoff();

        assertEquals(2_500, backoff.nextDelayMs());
        assertEquals(5_000, backoff.nextDelayMs());
        assertEquals(10_000, backoff.nextDelayMs());
        assertEquals(20_000, backoff.nextDelayMs());
        assertEquals(30_000, backoff.nextDelayMs());
        assertEquals(30_000, backoff.nextDelayMs());

        backoff.reset();
        assertEquals(2_500, backoff.nextDelayMs());
    }
}
