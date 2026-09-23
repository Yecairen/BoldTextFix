package dev.yecairen.boldtextfix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

public final class BoldFontFiles {
    public static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    private static final Map<FontFile, CompletableFuture<Details>> DETAILS = new HashMap<>();
    private static final ExecutorService INSPECTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "BoldTextFix font inspection");
        thread.setDaemon(true);
        return thread;
    });

    private BoldFontFiles() {
    }

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("boldtextfix/boldfonts");
    }

    public static boolean isFontName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains(":") || name.indexOf('\0') >= 0) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    public static Path resolve(String name) throws IOException {
        if (!isFontName(name)) {
            throw new IOException("Invalid font filename");
        }
        Path root = directory().toAbsolutePath().normalize();
        Path result = root.resolve(name).normalize();
        if (!root.equals(result.getParent())) {
            throw new IOException("Font must be in boldfonts");
        }
        return result;
    }

    public static List<FontFile> list() throws IOException {
        Files.createDirectories(directory());
        List<FontFile> result = new ArrayList<>();
        try (var entries = Files.list(directory())) {
            for (Path path : entries.toList()) {
                if (isFontName(path.getFileName().toString())) {
                    try {
                        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isRegularFile()) {
                            result.add(new FontFile(path.getFileName().toString(), attributes.size(),
                                    attributes.lastModifiedTime().toMillis()));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        result.sort(Comparator.comparing(FontFile::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FontFile::name));
        return List.copyOf(result);
    }

    public static byte[] read(Path path) throws IOException {
        if (!isFontName(path.getFileName().toString())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Only regular TTF and OTF files are supported");
        }
        long size = Files.size(path);
        if (size < 12 || size > MAX_FILE_BYTES) {
            throw new IOException("Font file size is out of range");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
        }
        if (bytes.length < 12 || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Font file size is out of range");
        }
        int signature = ByteBuffer.wrap(bytes).getInt();
        if (signature != 0x00010000 && signature != 0x4F54544F && signature != 0x74727565) {
            throw new IOException("Not a TTF or OTF font");
        }
        return bytes;
    }

    public static String importFile(Path source, byte[] validatedBytes) throws IOException {
        String name = source.getFileName().toString();
        Path destination = resolve(name);
        Files.createDirectories(directory());
        if (Files.exists(destination) && Files.isSameFile(source, destination)) {
            return name;
        }
        int extension = name.lastIndexOf('.');
        for (int suffix = 1; suffix <= 10000; suffix++) {
            String candidate = suffix == 1 ? name
                    : name.substring(0, extension) + " (" + suffix + ")" + name.substring(extension);
            destination = resolve(candidate);
            try {
                Files.createFile(destination);
            } catch (FileAlreadyExistsException ignored) {
                continue;
            }
            try {
                Files.write(destination, validatedBytes);
                return candidate;
            } catch (IOException failure) {
                Files.deleteIfExists(destination);
                throw failure;
            }
        }
        throw new IOException("Too many files with the same name");
    }

    /** Inspect each file fingerprint once, outside the render thread. Refresh explicitly retries it. */
    public static synchronized void inspectFiles(List<FontFile> files, boolean force) {
        var current = new HashSet<>(files);
        DETAILS.entrySet().removeIf(entry -> {
            if (force || !current.contains(entry.getKey())) {
                entry.getValue().cancel(false);
                return true;
            }
            return false;
        });
        for (FontFile file : files) {
            DETAILS.computeIfAbsent(file, key -> CompletableFuture.supplyAsync(() -> inspect(key), INSPECTOR));
        }
    }

    public static synchronized Details details(FontFile file) {
        CompletableFuture<Details> result = DETAILS.get(file);
        return result == null ? Details.PENDING : result.getNow(Details.PENDING);
    }

    public static synchronized void clearDetails() {
        DETAILS.values().forEach(task -> task.cancel(false));
        DETAILS.clear();
    }

    private static Details inspect(FontFile file) {
        try {
            LocalBoldFont.Statistics stats = LocalBoldFont.inspect(read(resolve(file.name())));
            return new Details(stats.characters(), stats.glyphs(), false);
        } catch (IOException | RuntimeException failure) {
            return Details.INVALID;
        }
    }

    public record Details(int characters, long glyphs, boolean invalid) {
        private static final Details PENDING = new Details(-1, -1, false);
        private static final Details INVALID = new Details(-1, -1, true);

        public String characterCount() {
            return this.characters >= 0 ? Integer.toString(this.characters) : this.invalid ? "—" : "…";
        }

        public String glyphCount() {
            return this.glyphs >= 0 ? Long.toString(this.glyphs) : this.invalid ? "—" : "…";
        }
    }

    public record FontFile(String name, long size, long modified) {
    }
}
