import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.CustomBoldGlyph;
import dev.yecairen.boldtextfix.FontFixPolicy;
import dev.yecairen.boldtextfix.LocalBoldFont;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Style;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

class CustomFontProbe {
    private static int assertions;

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]).toAbsolutePath();
        check(!Files.exists(root), "Fresh isolated test directory");
        Files.createDirectories(root);
        GameProvider game = (GameProvider) Proxy.newProxyInstance(GameProvider.class.getClassLoader(),
                new Class<?>[]{GameProvider.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getLaunchDirectory")) return root;
                    throw new UnsupportedOperationException(method.getName());
                });
        FabricLoaderImpl.INSTANCE.setGameProvider(game);
        SettingsRegressionProbe.run(root);
        DilationProgressProbe.run();
        checkConfig(root);
        checkFiles(root);
        checkPolicy();
        checkVanillaPolicy();
        checkNativeGlyphIsolation();
        checkWrapper();
        Path cff = CffFixture.create(root);
        checkProvider(ProbeEnvironment.trueTypeFont(), "TrueType");
        checkProvider(cff, "CFF");
        String imported = CustomBoldFonts.importFile(cff);
        check(Files.exists(BoldFontFiles.resolve(imported)), "CFF OTF import accepted");
        CustomBoldFonts.close();
        CustomFontUiProbe.run(cff);
        System.out.println("ASSERTIONS_PASSED=" + assertions);
        System.out.println("CONFIG; FILE_IMPORT; TTF; CFF_OTF; SIZE; PRECISION; MISSING_GLYPHS; NATIVE_CLEANUP; BOLD_STYLE: PASSED");
    }

    private static void checkConfig(Path root) throws Exception {
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        Files.createDirectories(root.resolve("config"));
        Path config = root.resolve("config/boldtextfix.json");
        String previous = "{\"mode\":\"offset\",\"enabled\":false,\"dilationStrengthTicks\":12,\"offsetStrengthTicks\":7,\"dilationDirectionMask\":6,\"preserveVanillaFallback\":false}";
        Files.writeString(config, previous);
        check(BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.OFFSET, "Existing mode retained");
        check(!BoldTextFixConfig.isEnabled(), "Existing disabled retained");
        check(BoldTextFixConfig.fontSize() == 10 && BoldTextFixConfig.fontOversample() == 8, "New parameter defaults");
        check(BoldTextFixConfig.boldFont().isEmpty(), "No forced font selection");
        check(Files.readString(config).equals(previous), "Reading preserves the old config file");
        BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.CUSTOM_FONT);
        BoldTextFixConfig.setBoldFont("测试字体.BOLD.OTF");
        BoldTextFixConfig.setFontSize(14.4F);
        BoldTextFixConfig.setFontOversample(8.6F);
        check(BoldTextFixConfig.fontSize() == 14.4F && BoldTextFixConfig.fontOversample() == 8.6F, "New font values");
        var saved = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        check(saved.get("mode").getAsString().equals("custom_font"), "Custom mode saved");
        check(!saved.has("preserveVanillaFallback"), "Removed option is not saved");
        check(saved.get("boldFont").getAsString().equals("测试字体.BOLD.OTF"), "Unicode filename persisted");
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        check(BoldTextFixConfig.fontSize() == 14.4F && BoldTextFixConfig.fontOversample() == 8.6F
                && BoldTextFixConfig.boldFont().endsWith(".OTF"), "Config reload retains values inside new ranges");
        check(BoldTextFixConfig.dilationStrengthTicks() == 120 && BoldTextFixConfig.offsetStrengthTicks() == 70,
                "Other methods preserved");
        check(!saved.has("dilationDirectionMask"), "Removed direction setting is not saved");
        BoldTextFixConfig.setFontSize(Float.NaN);
        BoldTextFixConfig.setFontOversample(Float.POSITIVE_INFINITY);
        check(BoldTextFixConfig.fontSize() == 10 && BoldTextFixConfig.fontOversample() == 8, "Non-finite defaults");
        BoldTextFixConfig.setFontSize(10000);
        BoldTextFixConfig.setFontOversample(-10000);
        check(BoldTextFixConfig.fontSize() == 10 && BoldTextFixConfig.fontOversample() == 8, "Invalid values revert to defaults");
        BoldTextFixConfig.setFontOversample(10000);
        check(BoldTextFixConfig.fontOversample() == 8, "Invalid clarity reverts to default");
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        check(BoldTextFixConfig.fontSize() == 10 && BoldTextFixConfig.fontOversample() == 8, "Defaults persist");
        Files.writeString(config, "{\"fontSize\":32,\"fontOversample\":2}");
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        check(BoldTextFixConfig.fontSize() == 10 && BoldTextFixConfig.fontOversample() == 2, "Only out-of-range value falls back");
        BoldTextFixConfig.setFontSize(-10000);
        check(BoldTextFixConfig.fontSize() == 10, "Below-minimum font size reverts to default");
        BoldTextFixConfig.setBoldFont("../outside.ttf");
        check(BoldTextFixConfig.boldFont().isEmpty(), "Config cannot escape folder");
        BoldTextFixConfig.setFontSize(10);
        BoldTextFixConfig.setFontOversample(8);
        BoldTextFixConfig.setEnabled(true);
    }

    private static void checkFiles(Path root) throws Exception {
        for (String valid : new String[]{"a.ttf", "A.OTF", "中文 한글 日本語.ttf", "font-bold (2).otf"}) {
            check(BoldFontFiles.isFontName(valid), "Accept font filename");
        }
        for (String invalid : new String[]{"", "../a.ttf", "C:\\a.ttf", "a.ttf:stream", "a.woff", "a.ttc", "a.png", "a\0.ttf"}) {
            check(!BoldFontFiles.isFontName(invalid), "Reject invalid filename");
        }
        expectIOException(() -> BoldFontFiles.resolve("../outside.ttf"), "Reject traversal");
        byte[] original = BoldFontFiles.read(ProbeEnvironment.trueTypeFont());
        Path source = root.resolve("自己的粗字体.ttf");
        Files.write(source, original);
        String first = CustomBoldFonts.importFile(source);
        String second = CustomBoldFonts.importFile(source);
        check(!first.equals(second) && second.contains("(2)"), "Same-name import keeps both files");
        check(Arrays.equals(original, Files.readAllBytes(BoldFontFiles.resolve(first))), "Imported bytes identical");
        check(Arrays.equals(original, Files.readAllBytes(source)), "Original file preserved");
        check(CustomBoldFonts.importFile(BoldFontFiles.resolve(first)).equals(first), "Already-imported path reuses file");
        check(BoldFontFiles.list().size() == 2, "Directory lists imported files");
        Files.writeString(BoldFontFiles.directory().resolve("ignore.txt"), "ignored");
        Files.createDirectory(BoldFontFiles.directory().resolve("directory.ttf"));
        check(BoldFontFiles.list().size() == 2, "Ignore unrelated files and directories");
        Path fake = root.resolve("fake.ttf");
        Files.write(fake, new byte[12]);
        expectIOException(() -> CustomBoldFonts.importFile(fake), "Reject non-font signature");
        Files.write(fake, ByteBuffer.allocate(12).putInt(0x00010000).array());
        expectIOException(() -> CustomBoldFonts.importFile(fake), "Reject truncated font with valid magic");
        check(BoldFontFiles.list().size() == 2, "Rejected import leaves folder unchanged");
        Path collection = root.resolve("collection.ttf");
        Files.write(collection, ByteBuffer.allocate(12).putInt(0x74746366).array());
        expectIOException(() -> BoldFontFiles.read(collection), "Reject TTC disguised as TTF");
        var before = BoldFontFiles.list();
        Files.delete(BoldFontFiles.resolve(second));
        check(!before.equals(BoldFontFiles.list()), "Directory detects external removal");
        Files.write(BoldFontFiles.resolve(first), Arrays.copyOf(original, original.length + 4));
        check(!before.getFirst().equals(BoldFontFiles.list().getFirst()), "File fingerprint changes after replacement");
    }

    private static void checkPolicy() {
        for (var mode : BoldTextFixConfig.RepairMode.values()) {
            BoldTextFixConfig.setMode(mode);
            for (boolean enabled : new boolean[]{false, true}) {
                BoldTextFixConfig.setEnabled(enabled);
                for (boolean bold : new boolean[]{false, true}) {
                    Style style = Style.EMPTY.withBold(bold);
                    check(CustomBoldFonts.appliesTo(style) == (enabled && bold && mode == BoldTextFixConfig.RepairMode.CUSTOM_FONT), "Third-mode gate");
                    check(FontFixPolicy.shouldHandleCustomBold(style, false) == (enabled && bold && mode != BoldTextFixConfig.RepairMode.CUSTOM_FONT), "Original-mode gate and vanilla fallback");
                    check(!CustomBoldFonts.appliesTo(style.withObfuscated(true)), "Obfuscated text unchanged");
                }
            }
        }
        BoldTextFixConfig.setEnabled(true);
        check(CustomFontProbe.localAdvance(65) == null && CustomBoldFonts.glyph(65) == null, "No loaded font falls through");
    }

    private static void checkVanillaPolicy() {
        for (var mode : BoldTextFixConfig.RepairMode.values()) {
            BoldTextFixConfig.setMode(mode);
            for (boolean enabled : new boolean[]{false, true}) {
                BoldTextFixConfig.setEnabled(enabled);
                for (boolean bold : new boolean[]{false, true}) {
                    Style style = Style.EMPTY.withBold(bold);
                    boolean custom = enabled && bold && mode == BoldTextFixConfig.RepairMode.CUSTOM_FONT;
                    check(CustomBoldFonts.appliesTo(style) == custom, "Selected font takes priority when it contains the character");
                    check(!CustomBoldFonts.appliesTo(style.withObfuscated(true)), "Obfuscation remains unchanged");
                    boolean synthetic = enabled && bold && mode == BoldTextFixConfig.RepairMode.OFFSET;
                    check(FontFixPolicy.shouldHandleCustomBold(style, true) == synthetic,
                            "Only offset mode modifies vanilla glyph bold rendering");
                    check(!FontFixPolicy.shouldUseDilation(style, true), "Vanilla glyph never selects dilation");
                    check(FontFixPolicy.shouldUseDilation(style, false)
                            == (enabled && bold && mode == BoldTextFixConfig.RepairMode.DILATION),
                            "Resource-pack glyph uses dilation only in dilation mode");
                    FontFixPolicy.beginGlyphRender(synthetic, 1.0F, mode == BoldTextFixConfig.RepairMode.OFFSET);
                    FontFixPolicy.beginRenderChar();
                    check(!FontFixPolicy.isSecondaryBoldPass(bold), "First glyph draw is retained");
                    boolean suppress = FontFixPolicy.isSecondaryBoldPass(bold)
                            && !FontFixPolicy.shouldUseVanillaOffsetPass();
                    check(!suppress, "Vanilla glyph retains native second draw");
                    FontFixPolicy.endRenderChar();
                    FontFixPolicy.endGlyphRender(synthetic);
                }
            }
        }
        BoldTextFixConfig.setEnabled(true);
    }

    private static void checkNativeGlyphIsolation() {
        Style bold = Style.EMPTY.withBold(true);
        BoldTextFixConfig.setEnabled(true);
        for (int strength : new int[]{0, 11, 20}) {
            BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.DILATION);
            BoldTextFixConfig.setDilationStrength(strength * 0.025F);
            for (var mode : new BoldTextFixConfig.RepairMode[]{
                    BoldTextFixConfig.RepairMode.DILATION, BoldTextFixConfig.RepairMode.CUSTOM_FONT}) {
                BoldTextFixConfig.setMode(mode);
                check(!FontFixPolicy.shouldUseDilation(bold, true)
                        && !FontFixPolicy.shouldHandleCustomBold(bold, true),
                        "Every strength leaves native glyph rendering unchanged");
                check(FontFixPolicy.shouldUseDilation(bold, false)
                        == (mode == BoldTextFixConfig.RepairMode.DILATION && strength > 0),
                        "Resource-pack glyphs still follow dilation strength");
                BoldTextFixConfig.setEnabled(false);
                check(!FontFixPolicy.shouldUseDilation(bold, true)
                        && !FontFixPolicy.shouldHandleCustomBold(bold, false), "Master disable bypasses processing");
                BoldTextFixConfig.setEnabled(true);
            }
        }
        BoldTextFixConfig.setDilationStrength(0.275F);
    }

    private static void checkWrapper() {
        RecordingGlyph original = new RecordingGlyph(GlyphInfo.simple(8));
        BakedGlyph wrapper = new CustomBoldGlyph(original);
        check(wrapper.info().getAdvance(true) == 8, "Width has no synthetic bold increment");
        check(wrapper.info().getBoldOffset() == 0, "No second bold offset");
        Style style = Style.EMPTY.withBold(true).withItalic(true).withUnderlined(true).withColor(0xABCDEF);
        wrapper.createGlyph(4, 8, 10, 20, style, 1, 1);
        check(!original.receivedStyle.isBold() && original.receivedOffset == 0, "Disable duplicate bold pass");
        check(original.receivedStyle.isItalic() && original.receivedStyle.isUnderlined()
                && original.receivedStyle.getColor().getValue() == 0xABCDEF, "Other styling preserved");
    }

    private static void checkProvider(Path path, String expectedFormat) throws Exception {
        byte[] bytes = BoldFontFiles.read(path);
        float normalAdvance;
        int normalPixels;
        TrueTypeGlyphProvider closed;
        try (TrueTypeGlyphProvider provider = LocalBoldFont.open(bytes, 11, 1)) {
            closed = provider;
            FT_Face face = (FT_Face) field(TrueTypeGlyphProvider.class, "face").get(provider);
            check(expectedFormat.equals(FreeType.FT_Get_Font_Format(face)), "Actual outline format " + expectedFormat);
            check(provider.getGlyph(65) != null, "Provider resolves A");
            check(provider.getGlyph(0x10FFFF) == null, "Unsupported codepoint returns missing");
            check(provider.getGlyph(32).info().getAdvance() > 0, "Whitespace retains advance");
            normalAdvance = provider.getGlyph(65).info().getAdvance();
            GlyphBitmap bitmap = bitmap(provider.getGlyph(65));
            normalPixels = bitmap.getPixelWidth() * bitmap.getPixelHeight();
            check(normalPixels > 0, "Glyph has drawable dimensions");
            check(FreeType.FT_Load_Char(face, 65, FreeType.FT_LOAD_RENDER) == 0, "FreeType rasterization succeeds");
            check(face.glyph().bitmap().rows() > 0 && face.glyph().bitmap().width() > 0, "Actual bitmap generated");
        }
        check(field(TrueTypeGlyphProvider.class, "face").get(closed) == null, "Native face released");
        check(field(TrueTypeGlyphProvider.class, "fontMemory").get(closed) == null, "Native font memory released");
        try (TrueTypeGlyphProvider larger = LocalBoldFont.open(bytes, 15, 1);
                TrueTypeGlyphProvider precise = LocalBoldFont.open(bytes, 11, 2)) {
            check(larger.getGlyph(65).info().getAdvance() > normalAdvance * 1.2, "Size grows logical metrics");
            check(Math.abs(precise.getGlyph(65).info().getAdvance() - normalAdvance) < 1,
                    "Precision keeps logical size within rasterization rounding");
            GlyphBitmap detailed = bitmap(precise.getGlyph(65));
            check(detailed.getPixelWidth() * detailed.getPixelHeight() > normalPixels * 2,
                    "Precision increases pixel resolution");
        }
        try (TrueTypeGlyphProvider maximum = LocalBoldFont.open(bytes, 16, 16)) {
            GlyphBitmap detailed = bitmap(maximum.getGlyph(65));
            check(detailed.getOversample() == 16, "Maximum precision is applied by the provider");
            check(detailed.getPixelWidth() > 0 && detailed.getPixelWidth() <= 256
                    && detailed.getPixelHeight() > 0 && detailed.getPixelHeight() <= 256, "Maximum-size glyph fits vanilla atlas");
            FT_Face face = (FT_Face) field(TrueTypeGlyphProvider.class, "face").get(maximum);
            check(FreeType.FT_Load_Char(face, 65, FreeType.FT_LOAD_RENDER) == 0, "Maximum precision rasterizes successfully");
        }
        System.out.println("FONT_VERIFIED=" + expectedFormat + ", " + path.getFileName());
    }

    private static GlyphBitmap bitmap(UnbakedGlyph glyph) {
        GlyphBitmap[] result = new GlyphBitmap[1];
        glyph.bake(new UnbakedGlyph.Stitcher() {
            public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
                result[0] = bitmap;
                return new RecordingGlyph(info);
            }
            public BakedGlyph getMissing() { throw new AssertionError("Unexpected missing glyph"); }
        });
        return result[0];
    }

    static Float localAdvance(int codePoint) {
        try {
            var method = CustomBoldFonts.class.getDeclaredMethod("unbaked", int.class);
            method.setAccessible(true);
            UnbakedGlyph glyph = (UnbakedGlyph) method.invoke(null, codePoint);
            return glyph == null ? null : glyph.info().getAdvance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void expectIOException(CheckedAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            assertions++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private interface CheckedAction { void run() throws Exception; }

    private static class RecordingGlyph implements BakedGlyph {
        private final GlyphInfo info;
        private Style receivedStyle;
        private float receivedOffset;
        RecordingGlyph(GlyphInfo info) { this.info = info; }
        public GlyphInfo info() { return this.info; }
        public TextRenderable.Styled createGlyph(float positionX, float positionY, int color, int shadowColor,
                Style style, float boldOffset, float shadowOffset) {
            this.receivedStyle = style;
            this.receivedOffset = boldOffset;
            return null;
        }
    }
}
