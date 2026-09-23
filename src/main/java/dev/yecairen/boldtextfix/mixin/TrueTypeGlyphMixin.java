package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.CustomGlyph;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers both TTF and OTF files, which share Minecraft's TrueType provider. */
@Mixin(targets = "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph")
abstract class TrueTypeGlyphMixin {
    @Inject(method = "bake", at = @At("RETURN"))
    private void boldtextfix$markTrueTypeGlyph(CallbackInfoReturnable<BakedGlyph> callbackInfo) {
        BakedGlyph glyph = callbackInfo.getReturnValue();
        if (glyph instanceof CustomGlyph marked) {
            marked.boldtextfix$setTrueType(true);
        }
    }
}
