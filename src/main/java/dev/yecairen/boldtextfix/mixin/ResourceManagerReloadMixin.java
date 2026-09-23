package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.DilationBoldBaker;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.VanillaBoldFallback;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clears glyph-variant runtime state when Minecraft replaces its active resources. */
@Mixin(ReloadableResourceManager.class)
abstract class ResourceManagerReloadMixin {
    @Inject(method = "createReload", at = @At("RETURN"))
    private void boldtextfix$clearGlyphRuntimeState(CallbackInfoReturnable<ReloadInstance> callbackInfo) {
        DilationBoldBaker.clearRuntimeState();
        CustomBoldFonts.invalidate();
        VanillaBoldFallback.invalidate();
    }
}
