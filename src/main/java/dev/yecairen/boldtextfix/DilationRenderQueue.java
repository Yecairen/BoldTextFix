package dev.yecairen.boldtextfix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Runs pixel processing off-thread; atlas uploads stay on the rendering thread. */
public final class DilationRenderQueue implements AutoCloseable {
    public static final DilationRenderQueue INSTANCE = new DilationRenderQueue(System::nanoTime);
    private static final long COOLDOWN_NANOS = 60_000_000L;
    private static final long NOTICE_NANOS = 8_000_000_000L;
    private static final int MAX_PENDING_BYTES = 32 * 1024 * 1024;
    private final LongSupplier clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "BoldTextFix glyph masks");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final List<Work> work = new ArrayList<>();
    private int pendingBytes;
    private int total;
    private int completed;
    private int failed;
    private long startedAt;
    private long elapsedNanos;
    private long finishedAt;
    private long noticeAt;
    private boolean finishing;
    private boolean presented;
    private boolean limited;
    private boolean generated;
    private long generatedAt;
    private long generation;

    public DilationRenderQueue(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized boolean enqueue(int bytes, Supplier<byte[]> generate, Consumer<byte[]> upload) {
        if (bytes <= 0 || bytes > MAX_PENDING_BYTES - this.pendingBytes || this.worker.isShutdown()) {
            return false;
        }
        long now = this.clock.getAsLong();
        this.updateCompletion(now);
        if (this.total == 0 || (this.finishing && now - this.noticeAt >= NOTICE_NANOS)) {
            this.total = this.completed = this.failed = 0;
            this.elapsedNanos = 0L;
            this.startedAt = now;
            this.finishing = false;
        } else if (this.finishing) {
            // Resume the same notice without charging its idle display time to rendering.
            this.startedAt = now;
            this.finishing = false;
        }
        this.total++;
        this.pendingBytes += bytes;
        long currentGeneration = this.generation;
        this.work.add(new Work(bytes, new FutureTask<>(() -> this.generate(generate, currentGeneration)), upload));
        return true;
    }

    /** Wait for the first notice frame only when notifications can be displayed. */
    public synchronized void afterFrame(boolean showNotifications, boolean limited) {
        long now = this.clock.getAsLong();
        this.updateCompletion(now);
        if (this.limited && !limited && !this.finishing && this.total > 0 && this.work.isEmpty()) {
            // Switching to full speed ends a pending final cooldown at the time of the switch.
            this.finishBatch(now);
        }
        this.limited = limited;
        this.notifyAll();
        if (!this.presented && showNotifications) {
            return;
        }
        for (Work item : this.work) {
            if (!item.started) {
                item.started = true;
                this.worker.execute(item.result);
            }
        }
        this.presented = false;
    }

    private byte[] generate(Supplier<byte[]> task, long currentGeneration) throws InterruptedException {
        synchronized (this) {
            while (true) {
                if (currentGeneration != this.generation || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Glyph generation was canceled");
                }
                long remaining = COOLDOWN_NANOS - (this.clock.getAsLong() - this.generatedAt);
                if (!this.limited || !this.generated || remaining <= 0) {
                    break;
                }
                // Only the pixel worker waits. The monitor is released for frames, mode changes and reloads.
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        byte[] pixels = task.get();
        synchronized (this) {
            if (currentGeneration == this.generation && pixels != null) {
                this.generated = true;
                this.generatedAt = this.clock.getAsLong();
            }
        }
        return pixels;
    }

    public synchronized void uploadReady() {
        long deadline = System.nanoTime() + 2_000_000L;
        var iterator = this.work.iterator();
        while (iterator.hasNext()) {
            Work item = iterator.next();
            if (!item.result.isDone()) {
                continue;
            }
            try {
                byte[] pixels = item.result.get();
                if (pixels == null) {
                    throw new IllegalStateException("Glyph mask was not generated");
                }
                item.upload.accept(pixels);
                this.completed++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failedUpload) {
                this.failed++;
            }
            this.pendingBytes -= item.bytes;
            iterator.remove();
            this.finishedAt = this.clock.getAsLong();
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    public synchronized Snapshot snapshot() {
        if (this.total == 0) {
            return null;
        }
        long now = this.clock.getAsLong();
        this.updateCompletion(now);
        if (this.finishing && now - this.noticeAt >= NOTICE_NANOS) {
            return null;
        }
        float remaining = this.finishing ? 1.0F - (float) (now - this.noticeAt) / NOTICE_NANOS : 1.0F;
        return new Snapshot(!this.finishing, this.total, this.completed, this.failed,
                this.elapsedNanos + (this.finishing ? 0L : Math.max(0L, now - this.startedAt)), remaining);
    }

    private void updateCompletion(long now) {
        if (this.total > 0 && this.work.isEmpty() && !this.finishing) {
            long completedAt = this.limited && this.generated
                    ? Math.max(this.finishedAt, this.generatedAt + COOLDOWN_NANOS) : this.finishedAt;
            if (now - completedAt >= 0L) {
                this.finishBatch(completedAt);
            }
        }
    }

    private void finishBatch(long completedAt) {
        this.elapsedNanos += Math.max(0L, completedAt - this.startedAt);
        this.finishing = true;
        this.noticeAt = completedAt;
    }

    public synchronized void markPresented() {
        this.presented = true;
    }

    public synchronized void reset() {
        this.generation++;
        this.generated = false;
        for (Work item : this.work) {
            item.result.cancel(true);
        }
        this.work.clear();
        this.pendingBytes = this.total = this.completed = this.failed = 0;
        this.elapsedNanos = 0L;
        this.finishing = this.presented = false;
        this.notifyAll();
    }

    @Override
    public void close() {
        synchronized (this) {
            this.reset();
            this.worker.shutdown();
        }
        // A completed mask saves on the worker, independently of the discarded atlas upload.
        // Finish that handoff before the client drains the disk writer.
        try {
            if (!this.worker.awaitTermination(5, TimeUnit.SECONDS)) {
                System.getLogger("BoldTextFix").log(System.Logger.Level.WARNING,
                        "Glyph worker did not stop before cache shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static String duration(long nanos) {
        return nanos >= 1_000_000_000L
                ? String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0)
                : String.format(Locale.ROOT, "%.1f ms", nanos / 1_000_000.0);
    }

    public record Snapshot(boolean running, int total, int completed, int failed, long elapsedNanos,
                           float remaining) {
    }

    private static final class Work {
        private final int bytes;
        private final FutureTask<byte[]> result;
        private final Consumer<byte[]> upload;
        private boolean started;

        private Work(int bytes, FutureTask<byte[]> result, Consumer<byte[]> upload) {
            this.bytes = bytes;
            this.result = result;
            this.upload = upload;
        }
    }
}
