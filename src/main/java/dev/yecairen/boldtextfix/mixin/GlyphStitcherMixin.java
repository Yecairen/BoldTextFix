package dev.yecairen.boldtextfix.mixin;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import dev.yecairen.boldtextfix.DilationBoldBaker;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers a source glyph so an expanded atlas twin is created only for an actual bold draw. */
@Mixin(GlyphStitcher.class)
abstract class GlyphStitcherMixin {
    @Inject(method = "stitch", at = @At("RETURN"))
    private void boldtextfix$rememberDilationSource(
            GlyphInfo info,
            GlyphBitmap bitmap,
            CallbackInfoReturnable<BakedSheetGlyph> callbackInfo
    ) {
        BakedSheetGlyph regular = callbackInfo.getReturnValue();
        if (regular == null || DilationBoldBaker.isCreatingVariant()
                || CustomBoldFonts.ownsAtlas((GlyphStitcher) (Object) this)) {
            return;
        }
        DilationBoldBaker.rememberSource(regular, info, bitmap, (GlyphStitcher) (Object) this);
    }
}
