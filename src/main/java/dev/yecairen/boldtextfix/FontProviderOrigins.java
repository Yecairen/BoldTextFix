package dev.yecairen.boldtextfix;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Tracks whether a loaded glyph comes from Minecraft's built-in vanilla resource pack. */
public final class FontProviderOrigins {
    private static final String VANILLA_PACK_ID = "vanilla";

    private FontProviderOrigins() {
    }

    public static void rememberProvider(GlyphProvider provider, ResourceManager resources, Identifier file) {
        if (provider instanceof Source source) {
            source.boldtextfix$setVanillaFontSource(resources.getResource(file)
                    .map(resource -> VANILLA_PACK_ID.equals(resource.sourcePackId()))
                    .orElse(false));
        }
    }

    public interface Source {
        boolean boldtextfix$isVanillaFontSource();

        void boldtextfix$setVanillaFontSource(boolean vanilla);
    }
}
