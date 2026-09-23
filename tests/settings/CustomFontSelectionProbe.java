import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.CustomBoldGlyph;
import dev.yecairen.boldtextfix.DilationBoldGlyph;
import dev.yecairen.boldtextfix.FontFixPolicy;
import dev.yecairen.boldtextfix.LocalBoldFont;
import dev.yecairen.boldtextfix.VanillaBoldFallback;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

final class CustomFontSelectionProbe {
    private static int assertions;
    private static final int[] EMOJI_AND_SYMBOLS = {0x1F6B7, 0x1F601, 0x1F60B, 0x1F621, 0x263A, 0x2764,
            0x1F1E8, 0x1F3FB, 0x200D, 0xFE0E, 0xFE0F, 0x20E3, 0xE0067, 0xE007F,
            '#', '*', '+', '$', '^', 0x2192, 0x221E, 0x20AC, 0x2605, 0xE000, 0xF0000};

    static void run() throws Exception {
        Class<?> mixin = concreteMixin();
        var constructor = mixin.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        Method select = mixin.getDeclaredMethod("boldtextfix$selectBoldFont", int.class,
                Style.class, CallbackInfoReturnable.class);
        Method measure = mixin.getDeclaredMethod("boldtextfix$measureBoldFont", int.class,
                Style.class, CallbackInfoReturnable.class);
        select.setAccessible(true);
        measure.setAccessible(true);
        Field cached = CustomBoldFonts.class.getDeclaredField("GLYPHS");
        cached.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, BakedGlyph> glyphs = (Map<Integer, BakedGlyph>) cached.get(null);
        BakedGlyph selected = new CustomBoldGlyph(new VanillaFallbackProbe.Glyph(
                GlyphInfo.simple(CustomFontProbe.localAdvance(65))));
        glyphs.put(65, selected);
        Style bold = Style.EMPTY.withBold(true);
        check(CustomFontProbe.localAdvance(65) != null, "Fixture contains A");
        check(CustomFontProbe.localAdvance(49) == null, "Fixture does not contain digit 1");
        VanillaFallbackProbe.install();
        try {
            for (boolean vanilla : new boolean[]{false, true}) {
                BakedGlyph original = originalGlyph(vanilla);
                mixin.getField("testSource").set(instance, new GlyphSource() {
                    public BakedGlyph getGlyph(int codePoint) { return original; }
                    public BakedGlyph getRandomGlyph(RandomSource random, int advance) { return original; }
                });
                checkMissingEmoji(instance, select, measure, original, !vanilla);
                checkLocalEmoji(instance, select, measure, original, glyphs, !vanilla);
                for (boolean legacyPreference : new boolean[]{true, false}) {
                    loadLegacyPreference(legacyPreference);
                    CallbackInfoReturnable<BakedGlyph> present = glyphCallback(original);
                    select.invoke(instance, 65, bold, present);
                    check(present.isCancelled() && present.getReturnValue() == selected,
                            "Selected font must supply A with vanilla=" + vanilla + ", old setting=" + legacyPreference);
                    CallbackInfoReturnable<Float> width = widthCallback(original, true);
                    measure.invoke(instance, 65, bold, width);
                    check(width.isCancelled() && width.getReturnValue().equals(CustomFontProbe.localAdvance(65)),
                            "Selected font width must match selected glyph source");
                    for (int missing : new int[]{'.', ',', '!', 'B', 48, 49, 50, 51, 52, 53, 54, 55, 56, 57,
                            0x4E2D, 0xAC00, 0x10400, 0x20000, 0x10FFFF}) {
                        CallbackInfoReturnable<BakedGlyph> fallback = glyphCallback(original);
                        select.invoke(instance, missing, bold, fallback);
                        BakedGlyph builtin = fallback.getReturnValue();
                        check(fallback.isCancelled() && builtin != original,
                                "Missing character bypasses the resource pack even when it supplies that character");
                        check(builtin instanceof DilationBoldGlyph marked && marked.boldtextfix$isVanillaFontGlyph(),
                                "Fallback from built-in providers is marked vanilla");
                        int stitches = VanillaFallbackProbe.atlas.stitches;
                        check(VanillaBoldFallback.glyph(missing) == builtin
                                && VanillaFallbackProbe.atlas.stitches == stitches, "Fallback bitmap is cached");
                        CallbackInfoReturnable<Float> fallbackWidth = widthCallback(original, true);
                        measure.invoke(instance, missing, bold, fallbackWidth);
                        check(fallbackWidth.isCancelled()
                                && fallbackWidth.getReturnValue() == builtin.info().getAdvance(true),
                                "Fallback layout matches built-in glyph metrics");
                        if (!vanilla) {
                            check(fallbackWidth.getReturnValue() != original.info().getAdvance(true),
                                    "Resource-pack metrics cannot leak into fallback layout");
                        }
                        check(!FontFixPolicy.shouldUseDilation(bold, true),
                                "Built-in fallback glyphs never use dilation, regardless of old setting");
                        check(!FontFixPolicy.shouldHandleCustomBold(bold, true),
                                "Built-in fallback always bypasses mod synthetic-bold processing");
                    }
                    for (Style unchanged : new Style[]{Style.EMPTY, bold.withObfuscated(true)}) {
                        CallbackInfoReturnable<BakedGlyph> untouched = glyphCallback(original);
                        select.invoke(instance, 65, unchanged, untouched);
                        check(!untouched.isCancelled(), "Normal and obfuscated text retain the game font");
                    }
                    BoldTextFixConfig.setEnabled(false);
                    CallbackInfoReturnable<BakedGlyph> disabled = glyphCallback(original);
                    select.invoke(instance, 65, bold, disabled);
                    check(!disabled.isCancelled(), "Disabled mod retains the game font");
                    CallbackInfoReturnable<BakedGlyph> disabledMissing = glyphCallback(original);
                    select.invoke(instance, 49, bold, disabledMissing);
                    check(!disabledMissing.isCancelled(), "Disabled mod does not select built-in fallback");
                    BoldTextFixConfig.setEnabled(true);
                }
            }
            for (BakedGlyph absent : new BakedGlyph[]{new VanillaFallbackProbe.Glyph(SpecialGlyphs.MISSING), null}) {
                mixin.getField("testSource").set(instance, new GlyphSource() {
                    public BakedGlyph getGlyph(int codePoint) { return absent; }
                    public BakedGlyph getRandomGlyph(RandomSource random, int advance) { return absent; }
                });
                checkMissingEmoji(instance, select, measure, absent, false);
                checkLocalEmoji(instance, select, measure, absent, glyphs, false);
            }
            BakedGlyph wrapped = new CustomBoldGlyph(originalGlyph(false));
            mixin.getField("testSource").set(instance, new GlyphSource() {
                public BakedGlyph getGlyph(int codePoint) { return wrapped; }
                public BakedGlyph getRandomGlyph(RandomSource random, int advance) { return wrapped; }
            });
            CallbackInfoReturnable<BakedGlyph> alreadySinglePass = glyphCallback(wrapped);
            select.invoke(instance, 0x1F601, bold, alreadySinglePass);
            check(alreadySinglePass.getReturnValue() == wrapped, "Already single-pass Emoji is reused without nesting wrappers");
            BakedGlyph initial = VanillaBoldFallback.glyph(49);
            VanillaFallbackProbe.Atlas initialAtlas = VanillaFallbackProbe.atlas;
            VanillaBoldFallback.invalidate();
            VanillaBoldFallback.tick();
            check(initialAtlas.closed, "Resource reload closes the fallback atlas");
            VanillaFallbackProbe.loadAtlas();
            check(VanillaBoldFallback.glyph(49) != initial, "Reload discards cached glyphs");
            for (boolean unicode : new boolean[]{true, false}) {
                for (boolean japanese : new boolean[]{true, false}) {
                    VanillaFallbackProbe.fontOptions(unicode, japanese);
                    VanillaFallbackProbe.loadAtlas();
                    for (int codePoint : new int[]{49, 0x4E2D, 0xAC00}) {
                        CallbackInfoReturnable<Float> builtinWidth = widthCallback(originalGlyph(false), true);
                        measure.invoke(instance, codePoint, bold, builtinWidth);
                        check(builtinWidth.isCancelled()
                                && builtinWidth.getReturnValue() == VanillaBoldFallback.glyph(codePoint).info().getAdvance(true),
                                "Built-in Unicode and Japanese options retain consistent glyph metrics");
                    }
                }
            }
            for (var other : new BoldTextFixConfig.RepairMode[]{BoldTextFixConfig.RepairMode.DILATION,
                    BoldTextFixConfig.RepairMode.OFFSET}) {
                BoldTextFixConfig.setMode(other);
                CallbackInfoReturnable<BakedGlyph> unchanged = glyphCallback(initial);
                select.invoke(instance, 49, bold, unchanged);
                check(!unchanged.isCancelled(), "Other bold modes retain their original font selection");
            }
            VanillaBoldFallback.tick();
            check(VanillaFallbackProbe.atlas.closed, "Leaving custom mode releases fallback atlas");
        } finally {
            glyphs.remove(65);
            VanillaBoldFallback.close();
            BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.CUSTOM_FONT);
            BoldTextFixConfig.setEnabled(true);
        }
        System.out.println("FONT_SELECTION_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkMissingEmoji(Object instance, Method select, Method measure, BakedGlyph original,
            boolean thirdParty)
            throws Exception {
        Style bold = Style.EMPTY.withBold(true);
        for (int codePoint : EMOJI_AND_SYMBOLS) {
            check(CustomFontProbe.localAdvance(codePoint) == null, "Fixture lacks symbol U+" + Integer.toHexString(codePoint));
            CallbackInfoReturnable<BakedGlyph> glyph = glyphCallback(original);
            select.invoke(instance, codePoint, bold, glyph);
            CallbackInfoReturnable<Float> width = widthCallback(original, true);
            measure.invoke(instance, codePoint, bold, width);
            checkResolvedEmoji(codePoint, original, glyph.getReturnValue(), width.getReturnValue(), thirdParty);
            for (Style unchanged : new Style[]{Style.EMPTY, bold.withObfuscated(true)}) {
                CallbackInfoReturnable<BakedGlyph> untouched = glyphCallback(original);
                select.invoke(instance, codePoint, unchanged, untouched);
                check(!untouched.isCancelled(), "Plain and obfuscated fallback symbols keep the game behavior");
            }
        }
    }

    private static void checkResolvedEmoji(int codePoint, BakedGlyph original, BakedGlyph resolved, float advance,
            boolean thirdParty) {
        BakedGlyph expected = thirdParty ? original : VanillaBoldFallback.glyph(codePoint);
        if (!thirdParty) {
            check(resolved == expected, "Absent third-party and local glyphs always use independent built-in fallback");
            check(resolved != original, "Current font chain cannot replace independent vanilla fallback");
        }
        checkFallbackRendering(expected, resolved, advance);
    }

    private static void checkFallbackRendering(BakedGlyph original, BakedGlyph resolved, float advance) {
        boolean vanilla = original instanceof DilationBoldGlyph marked && marked.boldtextfix$isVanillaFontGlyph();
        DrawRecordingGlyph source = original instanceof DrawRecordingGlyph recording
                ? recording : new DrawRecordingGlyph(vanilla);
        Style style = Style.EMPTY.withBold(true).withItalic(true).withUnderlined(true)
                .withStrikethrough(true).withColor(0xabcdef);
        for (int shadowColor : new int[]{0, 0xff243142}) {
            source.draws.clear();
            TextRenderable.Styled renderable = resolved.createGlyph(10, 20, 0xffabc123, shadowColor,
                    style, resolved.info().getBoldOffset(), resolved.info().getShadowOffset());
            renderable.render(new Matrix4f(), source.vertices(), 15728880, false);
            int foregroundPasses = vanilla ? 2 : 1;
            check(source.draws.stream().filter(draw -> draw.color() == 0xffabc123).count() == foregroundPasses * 4,
                    "Third-party Emoji draws once; vanilla fallback retains native bold");
            check(source.draws.size() == foregroundPasses * (shadowColor == 0 ? 4 : 8),
                    "Optional shadow is retained without adding a second bold pass");
            if (original instanceof DrawRecordingGlyph) {
                check(source.receivedStyle.isBold() == vanilla && source.receivedStyle.isItalic()
                        && source.receivedStyle.isUnderlined() && source.receivedStyle.isStrikethrough()
                        && source.receivedStyle.getColor().getValue() == 0xabcdef,
                        "Only synthetic bold changes; other styles and original font source survive");
            }
            for (Draw draw : source.draws) {
                check(draw.light() == 15728880, "Glyph light is retained");
            }
            if (!vanilla) {
                List<Draw> actual = List.copyOf(source.draws);
                source.draws.clear();
                original.createGlyph(10, 20, 0xffabc123, shadowColor, style.withBold(false), 0,
                        original.info().getShadowOffset()).render(new Matrix4f(), source.vertices(), 15728880, false);
                check(actual.equals(source.draws),
                        "Third-party Emoji emits exactly its original non-bold vertices, colors and texture coordinates");
            }
            check(advance == original.info().getAdvance(vanilla)
                    && advance == resolved.info().getAdvance(true) && renderable.activeRight() == 10 + advance,
                    "Layout and rendered bounds use the same width without extra bold spacing");
        }
        check(original.info().getBoldOffset() > 0, "Shared source glyph retains its native bold metrics");
    }

    private static void checkLocalEmoji(Object instance, Method select, Method measure, BakedGlyph original,
            Map<Integer, BakedGlyph> glyphs, boolean thirdParty) throws Exception {
        Field providerField = VanillaFallbackProbe.field(CustomBoldFonts.class, "provider");
        Field atlasField = VanillaFallbackProbe.field(CustomBoldFonts.class, "atlas");
        TrueTypeGlyphProvider previousProvider = (TrueTypeGlyphProvider) providerField.get(null);
        Object previousAtlas = atlasField.get(null);
        Map<Integer, BakedGlyph> previousGlyphs = new HashMap<>(glyphs);
        @SuppressWarnings("unchecked")
        Set<Integer> failed = (Set<Integer>) VanillaFallbackProbe.field(CustomBoldFonts.class, "FAILED_GLYPHS").get(null);
        var directory = Files.createTempDirectory(FabricLoader.getInstance().getConfigDir(), "emoji-priority-");
        byte[] bytes = Files.readAllBytes(CffFixture.create(directory, EMOJI_AND_SYMBOLS));
        Style bold = Style.EMPTY.withBold(true);
        try (TrueTypeGlyphProvider provider = LocalBoldFont.open(bytes, 10, 8);
                VanillaFallbackProbe.Atlas atlas = new VanillaFallbackProbe.Atlas()) {
            providerField.set(null, provider);
            atlasField.set(null, atlas);
            for (boolean measureFirst : new boolean[]{true, false}) {
                glyphs.clear();
                for (int codePoint : EMOJI_AND_SYMBOLS) {
                    check(provider.getGlyph(codePoint) != null, "Local OTF contains symbol U+" + Integer.toHexString(codePoint));
                    float expectedWidth = thirdParty ? original.info().getAdvance()
                            : provider.getGlyph(codePoint).info().getAdvance();
                    int before = atlas.stitches;
                    CallbackInfoReturnable<BakedGlyph> glyph = glyphCallback(original);
                    CallbackInfoReturnable<Float> width = widthCallback(original, true);
                    if (measureFirst) measure.invoke(instance, codePoint, bold, width);
                    select.invoke(instance, codePoint, bold, glyph);
                    if (!measureFirst) measure.invoke(instance, codePoint, bold, width);
                    check(glyph.isCancelled() && glyph.getReturnValue() instanceof CustomBoldGlyph
                            && glyph.getReturnValue() != original,
                            "Resolved symbol uses a single-pass glyph U+" + Integer.toHexString(codePoint));
                    check(width.isCancelled() && width.getReturnValue() == expectedWidth
                            && width.getReturnValue() == glyph.getReturnValue().info().getAdvance(true),
                            "Selected symbol rendering and layout agree, measurement first=" + measureFirst);
                    check(glyph.getReturnValue().info().getBoldOffset() == 0,
                            "Selected font is not synthetically bolded a second time");
                    check(atlas.stitches == before + (thirdParty ? 0 : 1),
                            "Third-party Emoji wins without baking a local glyph; local fallback bakes only once");
                    if (thirdParty) {
                        check(!glyphs.containsKey(codePoint), "Third-party Emoji does not populate the local font cache");
                        checkFallbackRendering(original, glyph.getReturnValue(), width.getReturnValue());
                    }
                    for (Style unchanged : new Style[]{Style.EMPTY, bold.withObfuscated(true)}) {
                        CallbackInfoReturnable<BakedGlyph> untouched = glyphCallback(original);
                        select.invoke(instance, codePoint, unchanged, untouched);
                        check(!untouched.isCancelled(), "Plain and obfuscated symbols keep their original source");
                    }
                }
            }
            for (boolean measureFirst : new boolean[]{true, false}) {
                glyphs.clear();
                failed.clear();
                try (FailingAtlas failingAtlas = new FailingAtlas()) {
                    atlasField.set(null, failingAtlas);
                    CallbackInfoReturnable<BakedGlyph> glyph = glyphCallback(original);
                    CallbackInfoReturnable<Float> width = widthCallback(original, true);
                    if (measureFirst) measure.invoke(instance, 0x1F601, bold, width);
                    select.invoke(instance, 0x1F601, bold, glyph);
                    if (!measureFirst) measure.invoke(instance, 0x1F601, bold, width);
                    checkResolvedEmoji(0x1F601, original, glyph.getReturnValue(), width.getReturnValue(), thirdParty);
                    check(failingAtlas.attempts == (thirdParty ? 0 : 1),
                            "Third-party Emoji avoids local generation; failed local glyphs are not retried");
                }
            }
            failed.clear();
            providerField.set(null, null);
            atlasField.set(null, null);
            glyphs.clear();
            checkMissingEmoji(instance, select, measure, original, thirdParty);
        } finally {
            providerField.set(null, previousProvider);
            atlasField.set(null, previousAtlas);
            glyphs.clear();
            glyphs.putAll(previousGlyphs);
            failed.clear();
        }
    }

    private static final class FailingAtlas extends GlyphStitcher {
        private int attempts;

        FailingAtlas() {
            super(null, Identifier.fromNamespaceAndPath("boldtextfix", "failed_symbol_probe"));
        }

        @Override
        public BakedSheetGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
            attempts++;
            throw new IllegalStateException("Expected probe glyph upload failure");
        }

        @Override
        public void close() {
        }
    }

    private static void loadLegacyPreference(boolean value) throws Exception {
        var config = FabricLoader.getInstance().getConfigDir().resolve("boldtextfix.json");
        var root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        root.addProperty("preserveVanillaFallback", value);
        Files.writeString(config, root.toString());
        Field loaded = BoldTextFixConfig.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
    }

    private static CallbackInfoReturnable<BakedGlyph> glyphCallback(BakedGlyph original) {
        return new CallbackInfoReturnable<>("getGlyph", true, original);
    }

    private static CallbackInfoReturnable<Float> widthCallback(BakedGlyph original, boolean bold) {
        return new CallbackInfoReturnable<>("width", true, original == null ? 0.0F : original.info().getAdvance(bold));
    }

    private static BakedGlyph originalGlyph(boolean vanilla) {
        return new DrawRecordingGlyph(vanilla);
    }

    private static final class DrawRecordingGlyph extends VanillaFallbackProbe.Glyph {
        private final List<Draw> draws = new ArrayList<>();
        private Style receivedStyle;
        private float positionX;
        private float positionY;
        private float depth;
        private float textureU;
        private float textureV;
        private int color;

        DrawRecordingGlyph(boolean vanilla) {
            super(GlyphInfo.simple(vanilla ? 6 : 31));
            boldtextfix$setVanillaFontGlyph(vanilla);
        }

        @Override
        public TextRenderable.Styled createGlyph(float positionX, float positionY, int color, int shadowColor,
                Style style, float boldOffset, float shadowOffset) {
            receivedStyle = style;
            return super.createGlyph(positionX, positionY, color, shadowColor, style, boldOffset, shadowOffset);
        }

        VertexConsumer vertices() {
            return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                    new Class<?>[]{VertexConsumer.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "addVertex" -> {
                                positionX = (float) arguments[1];
                                positionY = (float) arguments[2];
                                depth = (float) arguments[3];
                            }
                            case "setColor" -> color = (int) arguments[0];
                            case "setUv" -> {
                                textureU = (float) arguments[0];
                                textureV = (float) arguments[1];
                            }
                            case "setLight" -> draws.add(new Draw(positionX, positionY, depth, textureU, textureV,
                                    color, (int) arguments[0]));
                            default -> throw new AssertionError("Unexpected vertex operation: " + method.getName());
                        }
                        return proxy;
                    });
        }
    }

    private record Draw(float positionX, float positionY, float depth, float textureU, float textureV, int color, int light) {
    }

    private static Class<?> concreteMixin() throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = CustomBoldFonts.class.getResourceAsStream("mixin/FontMixin.class")) {
            new ClassReader(stream).accept(node, 0);
        }
        node.access = (node.access | Opcodes.ACC_PUBLIC) & ~Opcodes.ACC_ABSTRACT;
        String sourceType = "Lnet/minecraft/client/gui/GlyphSource;";
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "testSource", sourceType, null, null));
        for (var method : node.methods) {
            if (method.name.equals("getGlyphSource")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "testSource", sourceType));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(CustomBoldFonts.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
