package dev.yecairen.boldtextfix;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.AllMissingGlyphProvider;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

public final class VanillaBoldFallback {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final GlyphProvider MISSING = new AllMissingGlyphProvider();
    private static final Map<Integer, UnbakedGlyph> UNBAKED = new HashMap<>();
    private static final Map<Integer, BakedGlyph> GLYPHS = new HashMap<>();
    private static List<GlyphProvider> providers = List.of();
    private static Set<FontOption> options = Set.of();
    private static GlyphStitcher atlas;
    private static BakedGlyph missingGlyph;
    private static boolean invalidated = true;

    private VanillaBoldFallback() {
    }

    public static synchronized BakedGlyph glyph(int codePoint) {
        ensureLoaded();
        return GLYPHS.computeIfAbsent(codePoint, character -> {
            BakedGlyph baked = unbaked(character).bake(new UnbakedGlyph.Stitcher() {
                @Override
                public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
                    return atlas.stitch(info, bitmap);
                }

                @Override
                public BakedGlyph getMissing() {
                    if (missingGlyph == null) {
                        missingGlyph = SpecialGlyphs.MISSING.bake(atlas);
                    }
                    return missingGlyph;
                }
            });
            if (baked instanceof DilationBoldGlyph marked) {
                marked.boldtextfix$setVanillaFontGlyph(true);
            }
            return baked;
        });
    }

    private static UnbakedGlyph unbaked(int codePoint) {
        return UNBAKED.computeIfAbsent(codePoint, character -> {
            for (GlyphProvider provider : providers) {
                UnbakedGlyph glyph = provider.getGlyph(character);
                if (glyph != null) {
                    return glyph;
                }
            }
            return MISSING.getGlyph(character);
        });
    }

    private static void ensureLoaded() {
        Minecraft client = Minecraft.getInstance();
        Set<FontOption> currentOptions = EnumSet.noneOf(FontOption.class);
        if (client.options.forceUnicodeFont().get()) {
            currentOptions.add(FontOption.UNIFORM);
        }
        if (client.options.japaneseGlyphVariants().get()) {
            currentOptions.add(FontOption.JAPANESE_VARIANTS);
        }
        if (!invalidated && atlas != null && options.equals(currentOptions)) {
            return;
        }
        release();
        invalidated = false;
        options = currentOptions;
        atlas = new GlyphStitcher(client.getTextureManager(),
                Identifier.fromNamespaceAndPath(BoldTextFixMod.MOD_ID, "vanilla_bold_fallback"));
        ResourceManager resources = client.getVanillaPackResources().asResourceManager();
        try {
            providers = loadProviders(resources, currentOptions);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[BoldTextFix] Cannot load vanilla fallback fonts", failure);
        }
    }

    private static List<GlyphProvider> loadProviders(ResourceManager resources, Set<FontOption> fontOptions)
            throws IOException {
        List<GlyphProvider> loaded = new ArrayList<>();
        try {
            loadDefinitions(resources, Minecraft.DEFAULT_FONT, fontOptions, new HashSet<>(), loaded);
            return List.copyOf(loaded);
        } catch (IOException | RuntimeException failure) {
            loaded.forEach(GlyphProvider::close);
            throw failure;
        }
    }

    private static void loadDefinitions(ResourceManager resources, Identifier font, Set<FontOption> fontOptions,
            Set<Identifier> visiting, List<GlyphProvider> loaded) throws IOException {
        if (!visiting.add(font)) {
            throw new IOException("Circular vanilla font reference: " + font);
        }
        try (var reader = resources.openAsReader(font.withPrefix("font/").withSuffix(".json"))) {
            var definitions = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("providers");
            for (var entry : definitions) {
                var conditional = GlyphProviderDefinition.Conditional.CODEC.parse(JsonOps.INSTANCE, entry).getOrThrow();
                if (!conditional.filter().apply(fontOptions)) {
                    continue;
                }
                var definition = conditional.definition().unpack();
                if (definition.left().isPresent()) {
                    GlyphProvider provider = definition.left().orElseThrow().load(resources);
                    if (provider != null) {
                        loaded.add(provider);
                    }
                } else {
                    loadDefinitions(resources, definition.right().orElseThrow().id(), fontOptions, visiting, loaded);
                }
            }
        } finally {
            visiting.remove(font);
        }
    }

    public static synchronized void invalidate() {
        invalidated = true;
    }

    public static synchronized void tick() {
        if (invalidated || !BoldTextFixConfig.isEnabled()
                || BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
            release();
        }
    }

    public static synchronized void close() {
        release();
        invalidated = true;
    }

    private static void release() {
        GLYPHS.clear();
        UNBAKED.clear();
        missingGlyph = null;
        if (atlas != null) {
            atlas.close();
            atlas = null;
        }
        providers.forEach(GlyphProvider::close);
        providers = List.of();
    }
}
