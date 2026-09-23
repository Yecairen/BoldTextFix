import com.google.gson.JsonParser;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.GlyphDiskCache;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class SettingsRegressionProbe {
    private static int assertions;

    static void run(Path root) throws Exception {
        check(BoldTextFixConfig.dilationStrengthTicks() == 104
                && BoldTextFixConfig.offsetStrengthTicks() == 200, "New default strength ticks");
        check(BoldTextFixConfig.strengthForTicks(BoldTextFixConfig.RepairMode.DILATION, 104) == 0.26F
                && BoldTextFixConfig.offsetStrength() == 0.5F, "Exact new defaults");
        check(BoldTextFixConfig.STRENGTH_STEP == 0.02 && BoldTextFixConfig.FONT_STEP == 0.2,
                "New UI step sizes");
        check(BoldTextFixConfig.dilationNotifications()
                && BoldTextFixConfig.notificationCorner() == BoldTextFixConfig.NotificationCorner.TOP_LEFT,
                "Notifications default to enabled, top left");
        check(BoldTextFixConfig.fontSize() == 10 && BoldTextFixConfig.fontOversample() == 8,
                "Font defaults retained");
        check(BoldTextFixConfig.dilationLimited(), "New configs default to limited rendering");
        Path path = root.resolve("config/boldtextfix.json");
        Files.createDirectories(path.getParent());
        for (int version : new int[]{0, 1}) {
        for (int oldOffset = 0; oldOffset <= (version == 0 ? 40 : 80); oldOffset++) {
            int oldDilation = Math.min(version == 0 ? 20 : 40, oldOffset);
            int multiplier = version == 0 ? 10 : 5;
            String original = "{" + (version == 0 ? "" : "\"configVersion\":1,")
                    + "\"dilationStrengthTicks\":" + oldDilation
                    + ",\"offsetStrengthTicks\":" + oldOffset + ",\"dilationDirectionMask\":6}";
            Files.writeString(path, original);
            reload();
            check(BoldTextFixConfig.dilationStrengthTicks() == oldDilation * multiplier
                    && BoldTextFixConfig.offsetStrengthTicks() == oldOffset * multiplier, "Legacy strength preserved");
            check(Files.readString(path).equals(original), "Reading does not rewrite legacy config");
            BoldTextFixConfig.setDilationNotifications(false);
            check(!JsonParser.parseString(Files.readString(path)).getAsJsonObject().has("dilationDirectionMask"),
                    "Explicit saves remove the obsolete direction setting");
            reload();
            check(BoldTextFixConfig.dilationStrengthTicks() == oldDilation * multiplier
                    && BoldTextFixConfig.offsetStrengthTicks() == oldOffset * multiplier,
                    "Unrelated toggle and reload preserve actual strength");
        }
        }
        for (String oldMask : new String[]{"0", "1", "6", "15", "null", "{}"}) {
            Files.writeString(path, "{\"configVersion\":2,\"enabled\":true,\"mode\":\"dilation\","
                    + "\"dilationDirectionMask\":" + oldMask + ",\"boldFont\":\"retained.ttf\"}");
            reload();
            check(dev.yecairen.boldtextfix.FontFixPolicy.shouldUseDilation(
                    net.minecraft.network.chat.Style.EMPTY.withBold(true), false),
                    "Obsolete directions cannot disable uniform dilation");
            check(BoldTextFixConfig.boldFont().equals("retained.ttf"),
                    "Even malformed obsolete directions cannot interrupt remaining settings");
        }
        Files.writeString(path, "{\"dilationRadius\":0.375}");
        reload();
        check(BoldTextFixConfig.dilationStrengthTicks() == 150, "Legacy radius remains in pixels");
        for (var mode : new BoldTextFixConfig.RepairMode[]{
                BoldTextFixConfig.RepairMode.DILATION, BoldTextFixConfig.RepairMode.OFFSET}) {
            int limit = mode == BoldTextFixConfig.RepairMode.DILATION ? 40 : 80;
            for (int tick = 0; tick <= limit; tick++) {
                float strength = tick / 80.0F;
                if (mode == BoldTextFixConfig.RepairMode.DILATION) BoldTextFixConfig.setDilationStrength(strength);
                else BoldTextFixConfig.setOffsetStrength(strength);
                reload();
                int actual = mode == BoldTextFixConfig.RepairMode.DILATION
                        ? BoldTextFixConfig.dilationStrengthTicks() : BoldTextFixConfig.offsetStrengthTicks();
                check(actual == tick * 5 && BoldTextFixConfig.strengthForTicks(mode, actual) == strength,
                        "Every 0.0125 px setting persists without drift");
            }
            for (int step = 0; step <= (mode == BoldTextFixConfig.RepairMode.DILATION ? 25 : 50); step++) {
                float strength = (float) (step * 0.02);
                if (mode == BoldTextFixConfig.RepairMode.DILATION) BoldTextFixConfig.setDilationStrength(strength);
                else BoldTextFixConfig.setOffsetStrength(strength);
                reload();
                int ticks = mode == BoldTextFixConfig.RepairMode.DILATION
                        ? BoldTextFixConfig.dilationStrengthTicks() : BoldTextFixConfig.offsetStrengthTicks();
                check(BoldTextFixConfig.strengthForTicks(mode, ticks) == strength,
                        "Every new 0.02 px setting persists without drift");
            }
        }
        for (float size : new float[]{4, 4.125F, 10, 10.125F, 15.875F, 16}) {
            for (float clarity : new float[]{2, 2.125F, 8, 8.125F, 15.875F, 16}) {
                BoldTextFixConfig.setFontSize(size);
                BoldTextFixConfig.setFontOversample(clarity);
                BoldTextFixConfig.setBoldFont("");
                reload();
                check(BoldTextFixConfig.fontSize() == size && BoldTextFixConfig.fontOversample() == clarity,
                        "Fractional font settings survive reload");
                check(BoldTextFixConfig.boldFont().isEmpty(), "Empty selection persists");
            }
        }
        checkInvalidSettings(path);
        checkEfficiency(path);
        for (var corner : BoldTextFixConfig.NotificationCorner.values()) {
            BoldTextFixConfig.setNotificationCorner(corner);
            BoldTextFixConfig.setDilationNotifications(false);
            reload();
            check(BoldTextFixConfig.notificationCorner() == corner && !BoldTextFixConfig.dilationNotifications(),
                    "Notification setting and all four corners persist");
        }
        BoldTextFixConfig.setDilationNotifications(true);
        BoldTextFixConfig.setNotificationCorner(BoldTextFixConfig.NotificationCorner.TOP_LEFT);
        BoldTextFixConfig.setFontSize(10);
        BoldTextFixConfig.setFontOversample(8);
        BoldTextFixConfig.setDilationStrength(0.26F);
        BoldTextFixConfig.setOffsetStrength(0.5F);
        checkCacheCleanup();
        System.out.println("SETTINGS_AND_MIGRATION_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkEfficiency(Path path) throws Exception {
        for (boolean limited : new boolean[]{true, false}) {
            BoldTextFixConfig.setDilationLimited(limited);
            reload();
            check(BoldTextFixConfig.dilationLimited() == limited, "Efficiency state survives restart");
        }
        for (String value : new String[]{"null", "[]", "{}", "0", "1", "\"true\"", "\"bad\""}) {
            Files.writeString(path, "{\"dilationLimited\":" + value + ",\"fontSize\":10.125}");
            reload();
            check(BoldTextFixConfig.dilationLimited(), "Invalid efficiency value defaults to limited rendering");
            check(BoldTextFixConfig.fontSize() == 10.125F, "Invalid efficiency does not disturb other settings");
        }
        Files.writeString(path, "{\"fontSize\":10.125}");
        reload();
        check(BoldTextFixConfig.dilationLimited(), "Legacy config without efficiency uses limited rendering");
        BoldTextFixConfig.setDilationLimited(true);
        reload();
        check(BoldTextFixConfig.dilationLimited() && BoldTextFixConfig.fontSize() == 10.125F,
                "Changing efficiency preserves exact legacy numeric values");
        BoldTextFixConfig.setDilationLimited(false);
    }

    private static void checkInvalidSettings(Path path) throws Exception {
        String[] keys = {"dilationStrengthTicks", "offsetStrengthTicks", "fontSize", "fontOversample"};
        double[] valid = {105, 215, 10.125, 8.375};
        double[] defaults = {104, 200, 10, 8};
        String[][] invalid = {
                {"-1", "201", "-0.00001", "200.00001", "1.5"},
                {"-1", "401", "-0.00001", "400.00001", "1.5"},
                {"3.999999999", "16.000000001", "0", "999999"},
                {"1.999999999", "16.000000001", "0", "999999"}
        };
        for (int target = 0; target < keys.length; target++) {
            var values = new java.util.ArrayList<String>(java.util.List.of(invalid[target]));
            values.addAll(java.util.List.of("null", "[]", "{}", "true", "\"bad\"", "1e1000"));
            for (String bad : values) {
                var object = new com.google.gson.JsonObject();
                object.addProperty("configVersion", 2);
                for (int index = 0; index < keys.length; index++) object.addProperty(keys[index], valid[index]);
                object.add(keys[target], JsonParser.parseString(bad));
                String original = object.toString();
                Files.writeString(path, original);
                reload();
                double[] actual = {BoldTextFixConfig.dilationStrengthTicks(), BoldTextFixConfig.offsetStrengthTicks(),
                        BoldTextFixConfig.fontSize(), BoldTextFixConfig.fontOversample()};
                for (int index = 0; index < keys.length; index++) {
                    check(actual[index] == (index == target ? defaults[index] : valid[index]),
                            "Only invalid setting falls back: " + keys[target] + "=" + bad);
                }
                check(Files.readString(path).equals(original), "Load never rewrites values");
                BoldTextFixConfig.setEnabled(true);
                reload();
                check(BoldTextFixConfig.fontSize() == (target == 2 ? 10 : 10.125F)
                        && BoldTextFixConfig.fontOversample() == (target == 3 ? 8 : 8.375F),
                        "Unrelated save preserves valid off-grid font settings");
            }
        }
        for (int version : new int[]{0, 1}) {
            Files.writeString(path, "{\"configVersion\":" + version + ",\"dilationStrengthTicks\":"
                    + (version == 0 ? 21 : 41) + ",\"offsetStrengthTicks\":" + (version == 0 ? 41 : 81) + "}");
            reload();
            check(BoldTextFixConfig.dilationStrengthTicks() == 104 && BoldTextFixConfig.offsetStrengthTicks() == 200,
                    "Legacy out-of-range strengths revert to defaults");
        }
    }

    private static void checkCacheCleanup() throws Exception {
        var write = GlyphDiskCache.class.getDeclaredMethod("write", String.class, byte[].class);
        write.setAccessible(true);
        String failed = "aa_failed_serialization";
        write.invoke(null, failed, null); // Inject an exception after the temporary file has been opened.
        Path shard = BoldTextFixConfig.cacheDirectory().resolve("v2/aa");
        try (var files = Files.list(shard)) {
            check(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")),
                    "Failed serialization leaves no temporary files");
        }
        String success = "aa_valid_mask";
        byte[] data = {1, 3, 5, 7};
        write.invoke(null, success, data);
        check(Arrays.equals(GlyphDiskCache.get(success, data.length), data), "Cache disk round trip");
        try (var files = Files.list(shard)) {
            check(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")),
                    "Successful cache write leaves no temporary files");
        }
    }

    private static void reload() throws Exception {
        Field loaded = BoldTextFixConfig.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(null, false);
        BoldTextFixConfig.load();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
