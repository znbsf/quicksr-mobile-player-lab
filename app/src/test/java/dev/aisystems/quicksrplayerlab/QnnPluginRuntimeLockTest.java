package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public final class QnnPluginRuntimeLockTest {
    @Test
    public void waitingForProcessLockCanBeInterrupted() throws Exception {
        CountDownLatch waiterStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean acquired = new AtomicBoolean();
        QnnPluginRuntime.lockProcess();
        Thread waiter = new Thread(() -> {
            boolean ownsLock = false;
            waiterStarted.countDown();
            try {
                QnnPluginRuntime.lockProcess();
                ownsLock = true;
                acquired.set(true);
            } catch (InterruptedException expected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                if (ownsLock) {
                    QnnPluginRuntime.unlockProcess();
                }
            }
        }, "qnn-lock-waiter");

        try {
            waiter.start();
            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
            waiter.interrupt();
            waiter.join(2_000L);
            assertFalse("waiter did not stop after interruption", waiter.isAlive());
            assertTrue(interrupted.get());
            assertFalse(acquired.get());
        } finally {
            QnnPluginRuntime.unlockProcess();
            if (waiter.isAlive()) {
                waiter.join(2_000L);
            }
        }
    }
}
