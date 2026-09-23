package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.CustomBoldGlyph;
import dev.yecairen.boldtextfix.DilationBoldGlyph;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Font.class)
abstract class FontMixin {
    @Shadow
    private GlyphSource getGlyphSource(FontDescription description) {
        throw new AssertionError();
    }

    @Inject(method = "getGlyph", at = @At("HEAD"), cancellable = true)
    private void boldtextfix$selectBoldFont(int codePoint, Style style,
            CallbackInfoReturnable<BakedGlyph> callbackInfo) {
        if (CustomBoldFonts.appliesTo(style)) {
            BakedGlyph glyph = this.boldtextfix$resolveGlyph(codePoint, style);
            if (glyph != null) {
                callbackInfo.setReturnValue(glyph);
            }
        }
    }

    @Inject(method = "lambda$new$0", at = @At("HEAD"), cancellable = true)
    private void boldtextfix$measureBoldFont(int codePoint, Style style,
            CallbackInfoReturnable<Float> callbackInfo) {
        if (CustomBoldFonts.appliesTo(style)) {
            BakedGlyph glyph = this.boldtextfix$resolveGlyph(codePoint, style);
            if (glyph != null) {
                callbackInfo.setReturnValue(glyph.info().getAdvance(true));
            }
        }
    }

    @Unique
    private BakedGlyph boldtextfix$resolveGlyph(int codePoint, Style style) {
        if (CustomBoldFonts.prefersThirdPartyGlyph(codePoint)) {
            BakedGlyph glyph = this.getGlyphSource(style.getFont()).getGlyph(codePoint);
            if (glyph != null && glyph.info() != SpecialGlyphs.MISSING
                    && !(glyph instanceof DilationBoldGlyph marked && marked.boldtextfix$isVanillaFontGlyph())) {
                return glyph instanceof CustomBoldGlyph ? glyph : new CustomBoldGlyph(glyph);
            }
        }
        return CustomBoldFonts.resolveGlyph(codePoint);
    }
}
