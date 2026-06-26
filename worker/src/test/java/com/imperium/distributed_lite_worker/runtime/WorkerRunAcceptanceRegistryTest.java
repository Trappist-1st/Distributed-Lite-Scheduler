package com.imperium.distributed_lite_worker.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRunAcceptanceRegistryTest {

    @Test
    void tryAccept_isIdempotentUntilRelease() {
        WorkerRunAcceptanceRegistry registry = new WorkerRunAcceptanceRegistry();

        assertTrue(registry.tryAccept(100L));
        assertFalse(registry.tryAccept(100L));
        assertTrue(registry.isInFlight(100L));

        registry.release(100L);

        assertFalse(registry.isInFlight(100L));
        assertTrue(registry.tryAccept(100L));
    }
}
