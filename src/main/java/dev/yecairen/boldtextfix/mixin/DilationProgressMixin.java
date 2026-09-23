package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.DilationRenderQueue;
import dev.yecairen.boldtextfix.client.DilationProgressOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 500)
abstract class DilationProgressMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GameRenderState gameRenderState;

    @Inject(method = "extract", at = @At("HEAD"))
    private void boldtextfix$uploadReadyMasks(CallbackInfo callbackInfo) {
        DilationRenderQueue.INSTANCE.uploadReady();
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void boldtextfix$extractTopmostNotice(CallbackInfo callbackInfo) {
        DilationProgressOverlay.extract(this.minecraft, this.gameRenderState.guiRenderState);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void boldtextfix$startAfterNotice(CallbackInfo callbackInfo) {
        DilationRenderQueue.INSTANCE.afterFrame(DilationProgressOverlay.canDisplay(this.minecraft),
                BoldTextFixConfig.dilationLimited());
    }
}
