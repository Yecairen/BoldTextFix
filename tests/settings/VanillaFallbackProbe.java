import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import dev.yecairen.boldtextfix.DilationBoldBaker;
import dev.yecairen.boldtextfix.DilationBoldGlyph;
import dev.yecairen.boldtextfix.VanillaBoldFallback;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.resources.IndexedAssetSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.repository.PackSource;

final class VanillaFallbackProbe {
    static Atlas atlas;

    static void install() throws Exception {
        Minecraft client = Minecraft.getInstance();
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeType, "theUnsafe").get(null);
        Options options = (Options) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, Options.class);
        field(Options.class, "forceUnicodeFont").set(options, OptionInstance.createBoolean("unicode", false));
        field(Options.class, "japaneseGlyphVariants").set(options, OptionInstance.createBoolean("japanese", false));
        field(Minecraft.class, "options").set(client, options);
        Path assets = ProbeEnvironment.assets();
        Path indexed = IndexedAssetSource.createIndexFs(assets, System.getProperty("boldtextfix.test.assetIndex", "34"));
        var vanilla = new VanillaPackResourcesBuilder().pushJarResources()
                .pushAssetPath(PackType.CLIENT_RESOURCES, indexed).exposeNamespace("minecraft")
                .build(new PackLocationInfo("vanilla", Component.literal("Vanilla"), PackSource.BUILT_IN, Optional.empty()));
        field(Minecraft.class, "vanillaPackResources").set(client, vanilla);
        loadAtlas();
    }

    static void loadAtlas() throws Exception {
        var ensure = VanillaBoldFallback.class.getDeclaredMethod("ensureLoaded");
        ensure.setAccessible(true);
        ensure.invoke(null);
        @SuppressWarnings("unchecked")
        List<GlyphProvider> providers = (List<GlyphProvider>) field(VanillaBoldFallback.class, "providers").get(null);
        for (int codePoint : new int[]{49, 0x4E2D, 0xAC00}) {
            if (providers.stream().noneMatch(provider -> provider.getGlyph(codePoint) != null)) {
                throw new AssertionError("Built-in assets must supply U+" + Integer.toHexString(codePoint));
            }
        }
        GlyphStitcher previous = (GlyphStitcher) field(VanillaBoldFallback.class, "atlas").get(null);
        previous.close();
        atlas = new Atlas();
        field(VanillaBoldFallback.class, "atlas").set(null, atlas);
    }

    static void fontOptions(boolean unicode, boolean japanese) throws Exception {
        Options options = Minecraft.getInstance().options;
        field(Options.class, "forceUnicodeFont").set(options, OptionInstance.createBoolean("unicode", unicode));
        field(Options.class, "japaneseGlyphVariants").set(options, OptionInstance.createBoolean("japanese", japanese));
    }

    static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static final class Atlas extends GlyphStitcher {
        int stitches;
        boolean closed;

        Atlas() {
            super(null, Identifier.fromNamespaceAndPath("boldtextfix", "probe"));
        }

        @Override
        public BakedSheetGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
            if (bitmap.getPixelWidth() <= 0 || bitmap.getPixelHeight() <= 0) {
                throw new AssertionError("Real fallback glyph must contain bitmap pixels");
            }
            stitches++;
            return new Glyph(info);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static class Glyph extends BakedSheetGlyph implements DilationBoldGlyph {
        private boolean vanilla;
        private boolean variant;
        private DilationBoldBaker.LazySource source;
        private final Map<Integer, BakedSheetGlyph> variants = new HashMap<>();

        Glyph(GlyphInfo info) {
            super(info, null, null, 0, 1, 0, 1, 0, 8, 0, 8);
        }

        public boolean boldtextfix$isVanillaFontGlyph() { return vanilla; }
        public void boldtextfix$setVanillaFontGlyph(boolean value) { vanilla = value; }
        public boolean boldtextfix$isDilationBoldVariant() { return variant; }
        public void boldtextfix$markDilationBoldVariant() { variant = true; }
        public DilationBoldBaker.LazySource boldtextfix$getDilationLazySource() { return source; }
        public void boldtextfix$setDilationLazySource(DilationBoldBaker.LazySource value) { source = value; }
        public BakedSheetGlyph boldtextfix$getDilationBoldVariant(int key) { return variants.get(key); }
        public void boldtextfix$setDilationBoldVariant(int key, BakedSheetGlyph value) { variants.put(key, value); }
    }
}
