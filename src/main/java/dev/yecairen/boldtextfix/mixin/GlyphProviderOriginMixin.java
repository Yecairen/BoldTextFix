package dev.yecairen.boldtextfix.mixin;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import dev.yecairen.boldtextfix.FontProviderOrigins;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates each loaded glyph with the resource-pack origin of its provider. */
@Mixin({BitmapProvider.class, TrueTypeGlyphProvider.class, UnihexProvider.class})
abstract class GlyphProviderOriginMixin implements FontProviderOrigins.Source {
    @Unique private boolean boldtextfix$vanillaFontSource;

    @Override
    public boolean boldtextfix$isVanillaFontSource() {
        return this.boldtextfix$vanillaFontSource;
    }

    @Override
    public void boldtextfix$setVanillaFontSource(boolean vanilla) {
        this.boldtextfix$vanillaFontSource = vanilla;
    }

    @Inject(method = "getGlyph", at = @At("RETURN"), require = 1)
    private void boldtextfix$rememberGlyphOrigin(
            int codePoint,
            CallbackInfoReturnable<UnbakedGlyph> callbackInfo
    ) {
        if (callbackInfo.getReturnValue() instanceof FontProviderOrigins.Source source) {
            source.boldtextfix$setVanillaFontSource(this.boldtextfix$vanillaFontSource);
        }
    }
}
