package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class QuickSrSessionCleanupTest {
    @Test
    public void cleanupPreservesPrimaryAndAttemptsEveryResource() {
        Exception primary = new Exception("open failed");
        Exception firstCleanup = new Exception("session close failed");
        Exception secondCleanup = new Exception("unregister failed");
        AtomicInteger attempts = new AtomicInteger();

        Throwable observed = QuickSrSession.closeAll(
                primary,
                () -> {
                    attempts.incrementAndGet();
                    throw firstCleanup;
                },
                () -> attempts.incrementAndGet(),
                () -> {
                    attempts.incrementAndGet();
                    throw secondCleanup;
                });

        assertSame(primary, observed);
        assertEquals(3, attempts.get());
        assertArrayEquals(
                new Throwable[]{firstCleanup, secondCleanup},
                observed.getSuppressed());
    }

    @Test
    public void cleanupWithoutPrimaryUsesFirstFailureAndSuppressesTheRest() {
        Exception firstCleanup = new Exception("first");
        Exception secondCleanup = new Exception("second");

        Throwable observed = QuickSrSession.closeAll(
                null,
                () -> {
                    throw firstCleanup;
                },
                () -> {
                    throw secondCleanup;
                });

        assertSame(firstCleanup, observed);
        assertArrayEquals(new Throwable[]{secondCleanup}, observed.getSuppressed());
    }
}
