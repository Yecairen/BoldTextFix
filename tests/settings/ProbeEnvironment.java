import java.nio.file.Files;
import java.nio.file.Path;

/** External fixtures are supplied by the runner, never copied into the source tree. */
final class ProbeEnvironment {
    private ProbeEnvironment() {
    }

    static Path trueTypeFont() {
        Path font = requiredPath("boldtextfix.test.font");
        if (!Files.isRegularFile(font)) throw new IllegalArgumentException("Missing TrueType test font: " + font);
        return font;
    }

    static Path assets() {
        Path assets = requiredPath("boldtextfix.test.assets");
        if (!Files.isDirectory(assets)) throw new IllegalArgumentException("Missing Minecraft assets: " + assets);
        return assets;
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Supply -D" + property + "=<path> or use run-probe.ps1.");
        }
        return Path.of(value).toAbsolutePath();
    }
}
