package dev.yecairen.boldtextfix;

import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;

/** Links a regular baked glyph to lazily-created, single-pass mask-dilation variants. */
public interface DilationBoldGlyph {
    BakedSheetGlyph boldtextfix$getDilationBoldVariant(int variantKey);

    void boldtextfix$setDilationBoldVariant(int variantKey, BakedSheetGlyph variant);

    DilationBoldBaker.LazySource boldtextfix$getDilationLazySource();

    void boldtextfix$setDilationLazySource(DilationBoldBaker.LazySource source);

    boolean boldtextfix$isVanillaFontGlyph();

    void boldtextfix$setVanillaFontGlyph(boolean vanillaFontGlyph);

    boolean boldtextfix$isDilationBoldVariant();

    void boldtextfix$markDilationBoldVariant();
}
