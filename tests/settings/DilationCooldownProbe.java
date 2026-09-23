import dev.yecairen.boldtextfix.DilationRenderQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class DilationCooldownProbe {
    private static int assertions;

    static void run() throws Exception {
        checkSpacingAndSwitches();
        checkResetAndClose();
        System.out.println("DILATION_COOLDOWN_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkSpacingAndSwitches() throws Exception {
        AtomicLong time = new AtomicLong(1_000_000L);
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger uploads = new AtomicInteger();
        Thread renderer = Thread.currentThread();
        try (var queue = new DilationRenderQueue(time::get)) {
            for (int index = 0; index < 3; index++) {
                queue.enqueue(1, () -> {
                    if (Thread.currentThread() == renderer) throw new AssertionError("Generation ran on renderer");
                    starts.add(time.get());
                    time.addAndGet(40_000_000L);
                    return new byte[]{1};
                }, pixels -> uploads.incrementAndGet());
            }
            queue.afterFrame(false, true);
            drain(queue, uploads, 1);
            check(starts.equals(List.of(1_000_000L)), "First glyph starts immediately without cooldown");
            time.addAndGet(59_999_999L);
            queue.afterFrame(false, true);
            assertStaysAt(starts, 1);
            time.incrementAndGet();
            queue.afterFrame(false, true);
            drain(queue, uploads, 2);
            check(starts.get(1) == 101_000_000L, "Second glyph starts 60 ms after completion, not after start");
            time.addAndGet(59_999_999L);
            queue.afterFrame(false, true);
            assertStaysAt(starts, 2);
            time.incrementAndGet();
            queue.afterFrame(false, true);
            drain(queue, uploads, 3);
            check(starts.get(2) == 201_000_000L, "Every glyph receives its own completion-to-start cooldown");

            queue.enqueue(1, () -> { starts.add(time.get()); return new byte[]{1}; }, p -> uploads.incrementAndGet());
            queue.afterFrame(false, true);
            assertStaysAt(starts, 3);
            queue.afterFrame(false, false);
            drain(queue, uploads, 4);
            check(starts.get(3) == 241_000_000L, "Switching to full speed releases an already waiting glyph");

            CountDownLatch generating = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            queue.enqueue(1, () -> {
                starts.add(time.get());
                generating.countDown();
                try {
                    if (!finish.await(2, TimeUnit.SECONDS)) throw new AssertionError("No finish signal");
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                time.addAndGet(20_000_000L);
                return new byte[]{1};
            }, p -> uploads.incrementAndGet());
            queue.enqueue(1, () -> { starts.add(time.get()); return new byte[]{1}; }, p -> uploads.incrementAndGet());
            queue.afterFrame(false, false);
            check(generating.await(2, TimeUnit.SECONDS), "Full-speed glyph starts on worker");
            queue.afterFrame(false, true);
            finish.countDown();
            drain(queue, uploads, 5);
            assertStaysAt(starts, 5);
            time.addAndGet(60_000_000L);
            queue.afterFrame(false, true);
            drain(queue, uploads, 6);
            check(starts.get(5) == 321_000_000L, "Switch to limited applies to work already queued");

            for (int index = 0; index < 8; index++) {
                queue.enqueue(1, () -> { starts.add(time.get()); return new byte[]{1}; }, p -> uploads.incrementAndGet());
            }
            queue.afterFrame(false, false);
            drain(queue, uploads, 14);
            check(starts.subList(6, 14).stream().allMatch(t -> t == 321_000_000L),
                    "Full speed drains all pending glyphs without advancing the clock");
            check(queue.snapshot().completed() == 14 && queue.snapshot().failed() == 0,
                    "Pacing never drops generated glyphs or uploads");
        }
    }

    private static void checkResetAndClose() throws Exception {
        AtomicLong time = new AtomicLong(1L);
        AtomicInteger uploads = new AtomicInteger();
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        var queue = new DilationRenderQueue(time::get);
        try {
            for (int index = 0; index < 2; index++) {
                final long id = index;
                queue.enqueue(1, () -> { starts.add(id); return new byte[]{1}; }, p -> uploads.incrementAndGet());
            }
            queue.afterFrame(false, true);
            drain(queue, uploads, 1);
            assertStaysAt(starts, 1);
            queue.reset();
            check(queue.snapshot() == null, "Reload clears the waiting batch");
            queue.enqueue(1, () -> { starts.add(2L); return new byte[]{1}; }, p -> uploads.incrementAndGet());
            queue.afterFrame(false, true);
            drain(queue, uploads, 2);
            check(starts.equals(List.of(0L, 2L)), "Reload cancels waiting glyphs and clears their cooldown");

            queue.enqueue(1, () -> { starts.add(3L); return new byte[]{1}; }, p -> uploads.incrementAndGet());
            queue.afterFrame(false, true);
            assertStaysAt(starts, 2);
        } finally {
            long before = System.nanoTime();
            queue.close();
            check(System.nanoTime() - before < 1_000_000_000L, "Close promptly interrupts cooldown");
        }
        check(starts.equals(List.of(0L, 2L)) && uploads.get() == 2, "Closed queue cannot generate or upload pending glyphs");
        check(!queue.enqueue(1, () -> new byte[]{1}, p -> {}), "Closed queue rejects further work");
    }

    private static void assertStaysAt(List<Long> starts, int count) throws Exception {
        Thread.sleep(25);
        check(starts.size() == count, "Waiting worker cannot start early");
    }

    private static void drain(DilationRenderQueue queue, AtomicInteger uploads, int expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            queue.uploadReady();
            if (uploads.get() >= expected) {
                check(uploads.get() == expected, "Only eligible glyphs upload");
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Glyph did not upload; expected " + expected + ", got " + uploads.get());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
