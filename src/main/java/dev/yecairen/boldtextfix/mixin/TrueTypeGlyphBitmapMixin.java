package dev.yecairen.boldtextfix.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.yecairen.boldtextfix.DilationBoldBaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rewrites only the temporary dilation atlas upload for TrueType and OpenType glyphs. */
@Mixin(targets = "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1")
abstract class TrueTypeGlyphBitmapMixin {
    @Inject(method = "upload", at = @At("RETURN"))
    private void boldtextfix$writeDilationMask(int x, int y, GpuTexture texture, CallbackInfo callbackInfo) {
        DilationBoldBaker.rewriteTrueTypeUpload(this, texture);
    }
}
