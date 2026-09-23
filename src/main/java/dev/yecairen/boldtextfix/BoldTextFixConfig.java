package dev.yecairen.boldtextfix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Persistent client settings. Strength is stored as integer ticks to avoid float drift. */
public final class BoldTextFixConfig {
    public static final int MIN_STRENGTH_TICKS = 0;
    // 400 preserves both historical 0.0125 px values and the new 0.02 px UI steps.
    private static final int STRENGTH_TICKS_PER_PIXEL = 400;
    public static final int MAX_DILATION_STRENGTH_TICKS = 200;
    public static final int MAX_OFFSET_STRENGTH_TICKS = 400;
    public static final double STRENGTH_STEP = 0.02;
    public static final int DEFAULT_DILATION_STRENGTH_TICKS = 104;
    public static final int DEFAULT_OFFSET_STRENGTH_TICKS = 200;
    public static final float MIN_FONT_SIZE = 4.0F;
    public static final float MAX_FONT_SIZE = 16.0F;
    public static final float DEFAULT_FONT_SIZE = 10.0F;
    public static final double FONT_STEP = 0.2;
    public static final float MIN_FONT_OVERSAMPLE = 2.0F;
    public static final float MAX_FONT_OVERSAMPLE = 16.0F;
    public static final float DEFAULT_FONT_OVERSAMPLE = 8.0F;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CONFIG_VERSION = 2;
    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String ENABLED_KEY = "enabled";
    private static final String MODE_KEY = "mode";
    private static final String DILATION_TICKS_KEY = "dilationStrengthTicks";
    private static final String OFFSET_TICKS_KEY = "offsetStrengthTicks";
    private static final String LEGACY_RADIUS_KEY = "dilationRadius";

    private static volatile boolean enabled = true;
    private static volatile RepairMode mode = RepairMode.DILATION;
    private static volatile int dilationStrengthTicks = DEFAULT_DILATION_STRENGTH_TICKS;
    private static volatile int offsetStrengthTicks = DEFAULT_OFFSET_STRENGTH_TICKS;
    private static volatile String boldFont = "";
    private static volatile float fontSize = DEFAULT_FONT_SIZE;
    private static volatile float fontOversample = DEFAULT_FONT_OVERSAMPLE;
    private static volatile boolean dilationLimited = true;
    private static volatile boolean dilationNotifications = true;
    private static volatile NotificationCorner notificationCorner = NotificationCorner.TOP_LEFT;
    private static volatile boolean loaded;

    private BoldTextFixConfig() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            try {
                Path path = configPath();
                if (!Files.isRegularFile(path)) {
                    return;
                }

                JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (root.isJsonObject()) {
                    JsonObject object = root.getAsJsonObject();
                    int version = (int) readNumber(object, CONFIG_VERSION_KEY, 0);
                    dilationStrengthTicks = object.has(DILATION_TICKS_KEY)
                            ? readStrengthTicks(object, DILATION_TICKS_KEY, RepairMode.DILATION, version)
                            : readLegacyRadius(object);
                    offsetStrengthTicks = readStrengthTicks(object, OFFSET_TICKS_KEY, RepairMode.OFFSET, version);
                    fontSize = readFontSetting(object, "fontSize", MIN_FONT_SIZE, MAX_FONT_SIZE, DEFAULT_FONT_SIZE);
                    fontOversample = readFontSetting(object, "fontOversample", MIN_FONT_OVERSAMPLE,
                            MAX_FONT_OVERSAMPLE, DEFAULT_FONT_OVERSAMPLE);
                    if (object.has(ENABLED_KEY)) {
                        enabled = object.get(ENABLED_KEY).getAsBoolean();
                    }
                    if (object.has(MODE_KEY)) {
                        mode = RepairMode.fromSerialized(object.get(MODE_KEY).getAsString());
                    }
                    if (object.has("boldFont")) {
                        boldFont = BoldFontFiles.isFontName(object.get("boldFont").getAsString())
                                ? object.get("boldFont").getAsString() : "";
                    }
                    JsonElement limited = object.get("dilationLimited");
                    dilationLimited = limited == null || !limited.isJsonPrimitive()
                            || !limited.getAsJsonPrimitive().isBoolean() || limited.getAsBoolean();
                    JsonElement notifications = object.get("dilationNotifications");
                    dilationNotifications = notifications == null || !notifications.isJsonPrimitive()
                            || !notifications.getAsJsonPrimitive().isBoolean() || notifications.getAsBoolean();
                    JsonElement corner = object.get("notificationCorner");
                    notificationCorner = corner != null && corner.isJsonPrimitive()
                            ? NotificationCorner.fromSerialized(corner.getAsString()) : NotificationCorner.TOP_LEFT;
                }
            } catch (IOException | RuntimeException ignored) {
                // A damaged optional config must never prevent Minecraft from starting.
            } finally {
                loaded = true;
            }
        }
    }

    public static RepairMode mode() {
        load();
        return mode;
    }

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static boolean setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            enabled = value;
            saveLocked();
            return enabled;
        }
    }

    public static RepairMode setMode(RepairMode value) {
        load();
        synchronized (LOCK) {
            mode = value == null ? RepairMode.DILATION : value;
            saveLocked();
            return mode;
        }
    }

    public static int dilationStrengthTicks() {
        load();
        return dilationStrengthTicks;
    }

    public static int offsetStrengthTicks() {
        load();
        return offsetStrengthTicks;
    }

    public static float offsetStrength() {
        return strengthForTicks(RepairMode.OFFSET, offsetStrengthTicks());
    }

    public static float setDilationStrength(float strength) {
        load();
        synchronized (LOCK) {
            dilationStrengthTicks = ticksFor(RepairMode.DILATION, strength);
            saveLocked();
            return strengthForTicks(RepairMode.DILATION, dilationStrengthTicks);
        }
    }

    public static float setOffsetStrength(float strength) {
        load();
        synchronized (LOCK) {
            offsetStrengthTicks = ticksFor(RepairMode.OFFSET, strength);
            saveLocked();
            return strengthForTicks(RepairMode.OFFSET, offsetStrengthTicks);
        }
    }

    public static int maxStrengthTicks(RepairMode repairMode) {
        return repairMode == RepairMode.OFFSET
                ? MAX_OFFSET_STRENGTH_TICKS
                : MAX_DILATION_STRENGTH_TICKS;
    }

    public static int ticksFor(RepairMode repairMode, float strength) {
        if (!Float.isFinite(strength) || strength < 0
                || strength > (float) maxStrengthTicks(repairMode) / STRENGTH_TICKS_PER_PIXEL) {
            return defaultStrengthTicks(repairMode);
        }
        return normalizeTicks(repairMode, Math.round(strength * STRENGTH_TICKS_PER_PIXEL));
    }

    public static float strengthForTicks(RepairMode repairMode, int ticks) {
        return (float) normalizeTicks(repairMode, ticks) / STRENGTH_TICKS_PER_PIXEL;
    }

    public static Path cacheDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("boldtextfix").resolve("cache");
    }

    public static String boldFont() {
        load();
        return boldFont;
    }

    public static void setBoldFont(String name) {
        load();
        synchronized (LOCK) {
            boldFont = BoldFontFiles.isFontName(name) ? name : "";
            saveLocked();
        }
    }

    public static float fontSize() {
        load();
        return fontSize;
    }

    public static void setFontSize(float value) {
        load();
        synchronized (LOCK) {
            fontSize = normalizeFontSize(value);
            saveLocked();
        }
    }

    public static float fontOversample() {
        load();
        return fontOversample;
    }

    public static void setFontOversample(float value) {
        load();
        synchronized (LOCK) {
            fontOversample = normalizeFontOversample(value);
            saveLocked();
        }
    }

    public static boolean dilationLimited() {
        load();
        return dilationLimited;
    }

    public static void setDilationLimited(boolean value) {
        load();
        synchronized (LOCK) {
            dilationLimited = value;
            saveLocked();
        }
    }

    public static boolean dilationNotifications() {
        load();
        return dilationNotifications;
    }

    public static void setDilationNotifications(boolean value) {
        load();
        synchronized (LOCK) {
            dilationNotifications = value;
            saveLocked();
        }
    }

    public static NotificationCorner notificationCorner() {
        load();
        return notificationCorner;
    }

    public static void setNotificationCorner(NotificationCorner value) {
        load();
        synchronized (LOCK) {
            notificationCorner = value == null ? NotificationCorner.TOP_LEFT : value;
            saveLocked();
        }
    }

    private static float normalizeFontSize(float value) {
        return normalizeFontSetting(value, MIN_FONT_SIZE, MAX_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    private static float normalizeFontOversample(float value) {
        return normalizeFontSetting(value, MIN_FONT_OVERSAMPLE, MAX_FONT_OVERSAMPLE, DEFAULT_FONT_OVERSAMPLE);
    }

    private static float normalizeFontSetting(float value, float minimum, float maximum, float defaultValue) {
        return Float.isFinite(value) && value >= minimum && value <= maximum ? value : defaultValue;
    }

    private static double readNumber(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            double value = element.getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float readFontSetting(JsonObject object, String key, float minimum, float maximum,
            float fallback) {
        double value = readNumber(object, key, fallback);
        return value >= minimum && value <= maximum ? (float) value : fallback;
    }

    private static int readLegacyRadius(JsonObject object) {
        double radius = readNumber(object, LEGACY_RADIUS_KEY, Double.NaN);
        return radius >= 0 && radius <= 0.5 ? ticksFor(RepairMode.DILATION, (float) radius)
                : DEFAULT_DILATION_STRENGTH_TICKS;
    }

    private static int readStrengthTicks(JsonObject object, String key, RepairMode repairMode, int version) {
        double ticks = readNumber(object, key, Double.NaN);
        // Reading never rewrites the file. A later explicit save preserves the actual pixel strengths.
        int scale = version < 1 ? 10 : version == 1 ? 5 : 1;
        double scaled = ticks * scale;
        return scaled >= MIN_STRENGTH_TICKS && scaled <= maxStrengthTicks(repairMode)
                && ticks == Math.rint(ticks) ? (int) scaled : defaultStrengthTicks(repairMode);
    }

    private static int normalizeTicks(RepairMode repairMode, int ticks) {
        return ticks >= MIN_STRENGTH_TICKS && ticks <= maxStrengthTicks(repairMode)
                ? ticks : defaultStrengthTicks(repairMode);
    }

    private static int defaultStrengthTicks(RepairMode repairMode) {
        return repairMode == RepairMode.OFFSET
                ? DEFAULT_OFFSET_STRENGTH_TICKS
                : DEFAULT_DILATION_STRENGTH_TICKS;
    }

    private static void saveLocked() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty(CONFIG_VERSION_KEY, CONFIG_VERSION);
            root.addProperty(ENABLED_KEY, enabled);
            root.addProperty(MODE_KEY, mode.serializedName());
            root.addProperty(DILATION_TICKS_KEY, dilationStrengthTicks);
            root.addProperty(OFFSET_TICKS_KEY, offsetStrengthTicks);
            root.addProperty("boldFont", boldFont);
            root.addProperty("fontSize", fontSize);
            root.addProperty("fontOversample", fontOversample);
            root.addProperty("dilationLimited", dilationLimited);
            root.addProperty("dilationNotifications", dilationNotifications);
            root.addProperty("notificationCorner", notificationCorner.serializedName());
            Files.writeString(path, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The in-memory value remains usable when a config directory is temporarily read-only.
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("boldtextfix.json");
    }

    public enum NotificationCorner {
        TOP_LEFT("top_left", false, false),
        TOP_RIGHT("top_right", true, false),
        BOTTOM_LEFT("bottom_left", false, true),
        BOTTOM_RIGHT("bottom_right", true, true);

        private final String serializedName;
        private final boolean right;
        private final boolean bottom;

        NotificationCorner(String serializedName, boolean right, boolean bottom) {
            this.serializedName = serializedName;
            this.right = right;
            this.bottom = bottom;
        }

        public String serializedName() {
            return this.serializedName;
        }

        public boolean isRight() {
            return this.right;
        }

        public boolean isBottom() {
            return this.bottom;
        }

        private static NotificationCorner fromSerialized(String value) {
            for (NotificationCorner candidate : values()) {
                if (candidate.serializedName.equals(value)) {
                    return candidate;
                }
            }
            return TOP_LEFT;
        }
    }

    public enum RepairMode {
        DILATION("dilation"),
        OFFSET("offset"),
        CUSTOM_FONT("custom_font");

        private final String serializedName;

        RepairMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        private static RepairMode fromSerialized(String value) {
            for (RepairMode candidate : values()) {
                if (candidate.serializedName.equals(value)) {
                    return candidate;
                }
            }
            return DILATION;
        }
    }

}
