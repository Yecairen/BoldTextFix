package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.DilationBoldGlyph;
import dev.yecairen.boldtextfix.FontProviderOrigins;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = {
        "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph",
        "net.minecraft.client.gui.font.providers.UnihexProvider$Glyph",
        "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph"
})
abstract class UnbakedGlyphOriginMixin implements FontProviderOrigins.Source {
    @Unique private boolean boldtextfix$vanillaFontSource;

    @Override
    public boolean boldtextfix$isVanillaFontSource() {
        return this.boldtextfix$vanillaFontSource;
    }

    @Override
    public void boldtextfix$setVanillaFontSource(boolean vanilla) {
        this.boldtextfix$vanillaFontSource = vanilla;
    }

    @Inject(method = "bake", at = @At("RETURN"), require = 1)
    private void boldtextfix$transferFontOrigin(CallbackInfoReturnable<BakedGlyph> callbackInfo) {
        if (callbackInfo.getReturnValue() instanceof DilationBoldGlyph glyph) {
            glyph.boldtextfix$setVanillaFontGlyph(this.boldtextfix$vanillaFontSource);
        }
    }
}
