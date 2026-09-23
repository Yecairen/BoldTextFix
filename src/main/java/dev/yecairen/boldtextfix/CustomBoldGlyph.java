package dev.yecairen.boldtextfix;

import com.mojang.blaze3d.font.GlyphInfo;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Style;

public final class CustomBoldGlyph implements BakedGlyph {
    private final BakedGlyph delegate;
    private final GlyphInfo info;

    public CustomBoldGlyph(BakedGlyph delegate) {
        this.delegate = delegate;
        this.info = new GlyphInfo() {
            @Override
            public float getAdvance() {
                return delegate.info().getAdvance();
            }

            @Override
            public float getBoldOffset() {
                return 0.0F;
            }

            @Override
            public float getShadowOffset() {
                return delegate.info().getShadowOffset();
            }
        };
    }

    @Override
    public GlyphInfo info() {
        return this.info;
    }

    @Override
    public TextRenderable.Styled createGlyph(float positionX, float positionY, int color, int shadowColor,
            Style style, float boldOffset, float shadowOffset) {
        return this.delegate.createGlyph(positionX, positionY, color, shadowColor, style.withBold(false), 0.0F, shadowOffset);
    }
}
