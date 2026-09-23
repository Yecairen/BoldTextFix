package dev.yecairen.boldtextfix;

import com.mojang.logging.LogUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * A bounded two-level cache for completed dilation masks. The renderer only blocks on a small
 * cache hit read; writes and occasional pruning stay off the render thread.
 */
public final class GlyphDiskCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x4254_4632; // BTF2
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_MEMORY_BYTES = 8 * 1024 * 1024;
    private static final long MAX_DISK_BYTES = 256L * 1024L * 1024L;
    private static final AtomicInteger WRITES_SINCE_PRUNE = new AtomicInteger();
    private static final AtomicBoolean IO_FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean CORRUPTION_REPORTED = new AtomicBoolean();
    private static final Object MEMORY_LOCK = new Object();
    private static final LinkedHashMap<String, byte[]> MEMORY = new LinkedHashMap<>(64, 0.75F, true);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BoldTextFix cache I/O");
        thread.setDaemon(true);
        return thread;
    });

    private static int memoryBytes;

    private GlyphDiskCache() {
    }

    public static byte[] get(String key, int expectedLength) {
        if (key == null || expectedLength <= 0 || expectedLength > MAX_DISK_BYTES) {
            return null;
        }

        synchronized (MEMORY_LOCK) {
            byte[] cached = MEMORY.get(key);
            if (cached != null && cached.length == expectedLength) {
                recordUse(key, cached);
                return cached;
            }
            if (cached != null) {
                removeMemoryLocked(key, cached);
            }
        }

        Path path = pathFor(key);
        if (!Files.isRegularFile(path)) {
            return null;
        }

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("Invalid glyph cache header");
            }
            int length = input.readInt();
            if (length != expectedLength || length < 0 || length > MAX_DISK_BYTES) {
                throw new IOException("Invalid glyph cache length");
            }
            byte[] result = input.readNBytes(length);
            if (result.length != length || input.read() != -1) {
                throw new IOException("Incomplete or oversized glyph cache data");
            }
            putMemory(key, result);
            recordUse(key, result);
            return result;
        } catch (IOException | RuntimeException failure) {
            if (CORRUPTION_REPORTED.compareAndSet(false, true)) {
                LOGGER.warn("[BoldTextFix] Cannot read glyph cache {}; regenerating and replacing it", path, failure);
            }
            return null;
        }
    }

    public static void putAsync(String key, byte[] data) {
        if (key == null || data == null || data.length == 0 || data.length > MAX_DISK_BYTES) {
            return;
        }

        byte[] stableCopy = Arrays.copyOf(data, data.length);
        putMemory(key, stableCopy);
        IO.execute(() -> write(key, stableCopy));
    }

    /** Only mask reuse calls this; drawing an already baked atlas glyph does not touch disk. */
    private static void recordUse(String key, byte[] pixels) {
        IO.execute(() -> {
            Path target = pathFor(key);
            try {
                if (!Files.isRegularFile(target) || Files.size(target) != pixels.length + 12L) {
                    // A mask can still be in memory after its old disk copy was pruned or removed.
                    write(key, pixels);
                } else {
                    Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
                }
            } catch (IOException | RuntimeException failure) {
                reportIoFailure(target, failure);
            }
        });
    }

    /** Drain successful generations on normal exit instead of abandoning the daemon's writes. */
    public static void close() {
        IO.shutdown();
        try {
            if (!IO.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[BoldTextFix] Glyph cache writes did not finish before client shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[BoldTextFix] Interrupted while waiting for glyph cache writes", interrupted);
        }
    }

    private static void putMemory(String key, byte[] value) {
        synchronized (MEMORY_LOCK) {
            byte[] previous = MEMORY.put(key, value);
            if (previous != null) {
                memoryBytes -= previous.length;
            }
            memoryBytes += value.length;
            Iterator<Map.Entry<String, byte[]>> iterator = MEMORY.entrySet().iterator();
            while (memoryBytes > MAX_MEMORY_BYTES && iterator.hasNext()) {
                Map.Entry<String, byte[]> oldest = iterator.next();
                memoryBytes -= oldest.getValue().length;
                iterator.remove();
            }
        }
    }

    private static void removeMemoryLocked(String key, byte[] value) {
        MEMORY.remove(key);
        memoryBytes -= value.length;
    }

    private static void write(String key, byte[] data) {
        Path target = pathFor(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), key, ".tmp");
            try {
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                        temporary,
                        StandardOpenOption.TRUNCATE_EXISTING
                )))) {
                    output.writeInt(MAGIC);
                    output.writeInt(FORMAT_VERSION);
                    output.writeInt(data.length);
                    output.write(data);
                }

                // Reaching generation means the old entry was missing or unusable: replace it.
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException failure) {
            reportIoFailure(target, failure);
        }

        if (WRITES_SINCE_PRUNE.incrementAndGet() >= 64) {
            WRITES_SINCE_PRUNE.set(0);
            prune();
        }
    }

    private static void reportIoFailure(Path path, Exception failure) {
        if (IO_FAILURE_REPORTED.compareAndSet(false, true)) {
            LOGGER.warn("[BoldTextFix] Cannot maintain glyph cache {}; it may need regeneration after restart", path, failure);
        }
    }

    private static void prune() {
        Path root = cacheRoot();
        if (!Files.isDirectory(root)) {
            return;
        }

        try (var stream = Files.walk(root)) {
            List<Path> entries = stream
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .toList();
            List<Path> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparingLong(GlyphDiskCache::lastModified).reversed());

            long retained = 0L;
            for (Path path : sorted) {
                long size = Files.size(path);
                if (retained + size <= MAX_DISK_BYTES) {
                    retained += size;
                } else {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A failed cleanup should never affect the active renderer.
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static Path pathFor(String key) {
        String shard = key.length() >= 2 ? key.substring(0, 2) : "00";
        return cacheRoot().resolve(shard).resolve(key + ".bin");
    }

    private static Path cacheRoot() {
        return BoldTextFixConfig.cacheDirectory().resolve("v2");
    }
}
