import com.mojang.blaze3d.platform.NativeImage;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.DilationRenderQueue;
import dev.yecairen.boldtextfix.client.DilationProgressOverlay;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class DilationProgressProbe {
    private static int assertions;

    static void run() throws Exception {
        DilationCooldownProbe.run();
        AtomicLong time = new AtomicLong(1_000_000L);
        Thread renderer = Thread.currentThread();
        AtomicReference<Thread> generator = new AtomicReference<>();
        AtomicInteger uploaded = new AtomicInteger();
        try (var queue = new DilationRenderQueue(time::get)) {
            check(queue.snapshot() == null, "No notification without cache misses");
            check(queue.enqueue(2, () -> {
                generator.set(Thread.currentThread());
                return new byte[]{42};
            }, result -> {
                check(Thread.currentThread() == renderer, "Atlas upload stays on caller thread");
                check(result[0] == 42, "Worker pixels reach atlas unchanged");
                uploaded.incrementAndGet();
            }), "First glyph queued");
            queue.afterFrame(true, false);
            check(generator.get() == null, "Generation waits for a visible notification frame");
            check(queue.snapshot().running() && queue.snapshot().total() == 1,
                    "Running notification counts queued glyphs");
            queue.markPresented();
            queue.afterFrame(true, false);
            time.addAndGet(125_000_000L);
            drain(queue, 1);
            check(generator.get() != renderer && uploaded.get() == 1, "Generation is off-thread");
            var done = queue.snapshot();
            check(!done.running() && done.completed() == 1 && done.failed() == 0, "Successful completion");
            check(done.elapsedNanos() == 125_000_000L, "Full speed pauses immediately after upload");
            check(done.remaining() == 1, "Completion begins with full countdown bar");
            time.addAndGet(4_000_000_000L);
            check(queue.snapshot().remaining() == 0.5F, "Countdown half-empty at four seconds");
            check(queue.snapshot().elapsedNanos() == done.elapsedNanos(), "Idle notice time is not rendering time");
            time.addAndGet(3_999_999_999L);
            check(queue.snapshot() != null, "Completion remains for the entire eight seconds");
            time.incrementAndGet();
            check(queue.snapshot() == null, "Completion disappears at exactly eight seconds");

            queue.enqueue(2, () -> new byte[]{1}, result -> uploaded.incrementAndGet());
            queue.enqueue(2, () -> { throw new IllegalStateException("Expected generation failure"); },
                    result -> { throw new AssertionError("Failed task must not upload"); });
            queue.enqueue(2, () -> new byte[]{1}, result -> {
                throw new IllegalStateException("Expected atlas upload failure");
            });
            check(queue.snapshot().total() == 3, "New batch resets previous totals");
            queue.afterFrame(false, false);
            drain(queue, 3);
            check(queue.snapshot().completed() == 1 && queue.snapshot().failed() == 2,
                    "Failures are never reported as successful glyphs");

            queue.reset();
            check(queue.snapshot() == null, "Resource reload clears the notice");
            queue.enqueue(2, () -> new byte[]{1}, result -> uploaded.incrementAndGet());
            queue.reset();
            queue.afterFrame(false, false);
            queue.uploadReady();
            check(uploaded.get() == 2, "Reload discards work before it touches the atlas");

            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            queue.enqueue(2, () -> {
                running.countDown();
                try {
                    new CountDownLatch(1).await(3, TimeUnit.SECONDS);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new byte[]{1};
            }, result -> uploaded.incrementAndGet());
            queue.afterFrame(false, false);
            check(running.await(3, TimeUnit.SECONDS), "Long glyph starts in worker");
            queue.reset();
            check(interrupted.await(3, TimeUnit.SECONDS), "Reload interrupts in-flight pixel work");
            queue.uploadReady();
            check(uploaded.get() == 2 && queue.snapshot() == null, "Canceled job cannot upload or notify");
            check(queue.enqueue(32 * 1024 * 1024, () -> new byte[1], ignored -> {}), "Bounded queue accepts limit");
            check(!queue.enqueue(1, () -> new byte[1], ignored -> {}), "Queue rejects excess memory");
            queue.reset();
        }
        check(DilationRenderQueue.duration(999_000_000L).equals("999.0 ms"), "Subsecond unit is ms");
        check(DilationRenderQueue.duration(1_000_000_000L).equals("1.00 s"), "One second uses s");
        check(DilationRenderQueue.duration(1_250_000_000L).equals("1.25 s"), "Fractional seconds retained");
        checkPixels();
        checkContinuation();
        checkCooldownCompletion();
        checkHiddenNotifications();
        checkCorners();
        System.out.println("DILATION_PROGRESS_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkHiddenNotifications() throws Exception {
        AtomicLong time = new AtomicLong(1_000_000L);
        AtomicInteger uploads = new AtomicInteger();
        BoldTextFixConfig.setDilationNotifications(false);
        check(!DilationProgressOverlay.canDisplay(null), "Disabled notifications skip rendering entirely");
        try (var queue = new DilationRenderQueue(time::get)) {
            for (int batch = 1; batch <= 2; batch++) {
                queue.enqueue(1, () -> new byte[]{1}, pixels -> uploads.incrementAndGet());
                queue.afterFrame(DilationProgressOverlay.canDisplay(null), false);
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (uploads.get() < batch && System.nanoTime() < deadline) {
                    queue.uploadReady();
                    Thread.sleep(1);
                }
                check(uploads.get() == batch, "Glyph generation finishes without a presented notice");
                queue.afterFrame(false, false);
                time.addAndGet(8_000_000_000L);
            }
            check(queue.snapshot() == null, "Hidden completion notices expire without snapshot polling");
            queue.enqueue(1, () -> new byte[]{1}, ignored -> {});
            check(queue.snapshot().total() == 1, "Hidden batches do not accumulate stale counts");
        }
        BoldTextFixConfig.setDilationNotifications(true);
    }

    private static void checkContinuation() throws Exception {
        AtomicLong time = new AtomicLong(1_000_000L);
        try (var queue = new DilationRenderQueue(time::get)) {
            long elapsed = 0L;
            for (int batch = 1; batch <= 3; batch++) {
                final long renderTime = batch * 10_000_000L;
                queue.enqueue(1, () -> {
                    time.addAndGet(renderTime);
                    return new byte[]{1};
                }, ignored -> {});
                var resumed = queue.snapshot();
                check(resumed.running() && resumed.total() == batch && resumed.completed() == batch - 1,
                        "Work arriving during the notice resumes cumulative counts");
                check(resumed.elapsedNanos() == elapsed && resumed.remaining() == 1,
                        "Resuming excludes idle time and restores the full countdown");
                queue.afterFrame(false, false);
                drain(queue, batch);
                elapsed += renderTime;
                check(!queue.snapshot().running() && queue.snapshot().elapsedNanos() == elapsed,
                        "Each rendering segment is accumulated once");
                time.addAndGet(7_999_999_999L);
                check(queue.snapshot() != null && queue.snapshot().elapsedNanos() == elapsed,
                        "The eight-second window restarts after every resumed segment");
            }
            time.incrementAndGet();
            check(queue.snapshot() == null, "Resumed batch expires exactly eight seconds after last completion");
            queue.enqueue(1, () -> new byte[]{1}, ignored -> {});
            var fresh = queue.snapshot();
            check(fresh.total() == 1 && fresh.completed() == 0 && fresh.failed() == 0 && fresh.elapsedNanos() == 0,
                    "A new batch after expiry resets counts and elapsed time");
        }
    }

    private static void checkCooldownCompletion() throws Exception {
        AtomicLong time = new AtomicLong(1_000_000L);
        try (var queue = new DilationRenderQueue(time::get)) {
            queue.enqueue(1, () -> {
                time.addAndGet(40_000_000L);
                return new byte[]{1};
            }, ignored -> {});
            queue.afterFrame(false, true);
            drain(queue, 1);
            check(queue.snapshot().running() && queue.snapshot().elapsedNanos() == 40_000_000L,
                    "The final glyph keeps timing active during its cooldown");
            time.addAndGet(59_999_999L);
            check(queue.snapshot().running() && queue.snapshot().elapsedNanos() == 99_999_999L,
                    "The last nanosecond of the final cooldown is still active");
            time.incrementAndGet();
            var done = queue.snapshot();
            check(!done.running() && done.elapsedNanos() == 100_000_000L && done.remaining() == 1,
                    "The countdown starts exactly when the final cooldown ends");
            time.addAndGet(4_000_000_000L);
            check(queue.snapshot().elapsedNanos() == 100_000_000L && queue.snapshot().remaining() == 0.5F,
                    "Limited mode freezes elapsed time while the notice counts down");

            queue.enqueue(1, () -> {
                time.addAndGet(20_000_000L);
                return new byte[]{1};
            }, ignored -> {});
            queue.afterFrame(false, true);
            drain(queue, 2);
            time.addAndGet(60_000_000L);
            check(!queue.snapshot().running() && queue.snapshot().elapsedNanos() == 180_000_000L,
                    "Resumed limited work adds generation and cooldown but not the idle gap");

            // Do not poll at the cooldown boundary: late frames must not inflate rendering time.
            queue.reset();
            queue.enqueue(1, () -> new byte[]{1}, ignored -> {});
            queue.afterFrame(false, true);
            drain(queue, 1);
            time.addAndGet(4_060_000_000L);
            check(queue.snapshot().elapsedNanos() == 60_000_000L && queue.snapshot().remaining() == 0.5F,
                    "Late polling uses the real cooldown deadline");

            // Changing modes must end only the remaining cooldown, not reopen completed work.
            queue.reset();
            queue.enqueue(1, () -> new byte[]{1}, ignored -> {});
            queue.afterFrame(false, true);
            drain(queue, 1);
            time.addAndGet(20_000_000L);
            queue.afterFrame(false, false);
            check(!queue.snapshot().running() && queue.snapshot().elapsedNanos() == 20_000_000L,
                    "Full speed ends the pending cooldown at the switch time");
            queue.afterFrame(false, true);
            time.addAndGet(20_000_000L);
            check(!queue.snapshot().running() && queue.snapshot().elapsedNanos() == 20_000_000L,
                    "Switching back does not reopen a completed batch");

            queue.reset();
            queue.enqueue(1, () -> new byte[]{1}, ignored -> {});
            queue.afterFrame(false, true);
            drain(queue, 1);
            time.addAndGet(20_000_000L);
            queue.enqueue(1, () -> new byte[]{1}, ignored -> {});
            queue.afterFrame(false, true);
            time.addAndGet(40_000_000L);
            queue.afterFrame(false, true);
            drain(queue, 2);
            check(queue.snapshot().running() && queue.snapshot().total() == 2,
                    "Requests during the final cooldown stay in the active batch");
            time.addAndGet(60_000_000L);
            check(queue.snapshot().elapsedNanos() == 120_000_000L && !queue.snapshot().running(),
                    "Continuous work includes both cooldowns without pausing in between");

            queue.reset();
            queue.enqueue(1, () -> { throw new IllegalStateException("Expected failure"); }, ignored -> {});
            queue.afterFrame(false, true);
            drain(queue, 1);
            check(!queue.snapshot().running() && queue.snapshot().completed() == 0 && queue.snapshot().failed() == 1,
                    "Failed generation is counted honestly and cannot leave the notice running forever");
        }
    }

    private static void checkCorners() throws Exception {
        Method bounds = DilationProgressOverlay.class.getDeclaredMethod("bounds", int.class, int.class,
                int.class, int.class, BoldTextFixConfig.NotificationCorner.class);
        bounds.setAccessible(true);
        for (int[] screen : new int[][]{{320, 240}, {480, 270}, {960, 540}}) {
            for (var corner : BoldTextFixConfig.NotificationCorner.values()) {
                int previousWidth = 0;
                for (int textWidth : new int[]{70, 170, 284}) {
                    Object layout = bounds.invoke(null, screen[0], screen[1], textWidth, 65, corner);
                    int left = coordinate(layout, "left");
                    int top = coordinate(layout, "top");
                    int width = coordinate(layout, "width");
                    check(width == textWidth + 16 && width > previousWidth, "Width follows longest line with padding");
                    check(left == (corner.isRight() ? screen[0] - width - 8 : 8), "Horizontal corner anchor");
                    check(top == (corner.isBottom() ? screen[1] - 65 - 8 : 8), "Vertical corner anchor");
                    check(left >= 8 && left + width <= screen[0] - 8 && top >= 8 && top + 65 <= screen[1] - 8,
                            "All corners fit inside the viewport");
                    previousWidth = width;
                }
            }
        }
    }

    private static int coordinate(Object layout, String key) throws Exception {
        Method method = layout.getClass().getDeclaredMethod(key);
        method.setAccessible(true);
        return (int) method.invoke(layout);
    }

    private static void drain(DilationRenderQueue queue, int expected) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            queue.uploadReady();
            var status = queue.snapshot();
            if (status.completed() + status.failed() == expected) return;
            Thread.sleep(1);
        }
        throw new AssertionError("Worker or atlas upload did not complete");
    }

    private static void checkPixels() throws Exception {
        Class<?> maskType = Class.forName("dev.yecairen.boldtextfix.DilationMask");
        Constructor<?> constructor = maskType.getDeclaredConstructor(int.class, int.class, int.class, float.class);
        constructor.setAccessible(true);
        Method dilate = maskType.getDeclaredMethod("dilate", byte[].class, NativeImage.Format.class);
        dilate.setAccessible(true);
        Method paddedMask = maskType.getDeclaredMethod("padded", byte[].class, int.class);
        paddedMask.setAccessible(true);
        byte[] source = new byte[9];
        source[4] = (byte) 255;
        Object mask = constructor.newInstance(3, 3, 1, 1.0F);
        byte[] pending = (byte[]) paddedMask.invoke(mask, source, 1);
        for (int index = 0; index < pending.length; index++) {
            check(Byte.toUnsignedInt(pending[index]) == (index == 12 ? 255 : 0),
                    "Pending glyph keeps source coverage and transparent padding");
        }
        byte[] pixels = (byte[]) dilate.invoke(mask, source, NativeImage.Format.LUMINANCE);
        check(pixels.length == 25 && Byte.toUnsignedInt(pixels[12]) == 255, "Original coverage preserved");
        for (int target : new int[]{7, 11, 13, 17}) {
            check(Byte.toUnsignedInt(pixels[target]) == 255, "Uniform dilation expands all four sides");
        }
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                check(pixels[y * 5 + x] == pixels[y * 5 + 4 - x]
                        && pixels[y * 5 + x] == pixels[(4 - y) * 5 + x], "Uniform dilation is symmetric");
            }
        }
        check(source[4] == (byte) 255 && source[0] == 0, "Worker never mutates source pixels");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
