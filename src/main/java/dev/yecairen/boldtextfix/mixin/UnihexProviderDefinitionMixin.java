package dev.yecairen.boldtextfix.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.yecairen.boldtextfix.FontProviderOrigins;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.font.providers.UnihexProvider$Definition")
abstract class UnihexProviderDefinitionMixin {
    @Shadow @Final private Identifier hexFile;

    @Inject(method = "load", at = @At("RETURN"), require = 1)
    private void boldtextfix$rememberFontFile(
            ResourceManager resources,
            CallbackInfoReturnable<GlyphProvider> callbackInfo
    ) {
        FontProviderOrigins.rememberProvider(callbackInfo.getReturnValue(), resources, this.hexFile);
    }
}
