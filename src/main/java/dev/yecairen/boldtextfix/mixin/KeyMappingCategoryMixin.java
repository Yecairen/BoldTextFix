package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.BoldTextFixMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.Category.class)
abstract class KeyMappingCategoryMixin {
    @Inject(method = "label", at = @At("HEAD"), cancellable = true)
    private void boldtextfix$useCodeDefinedName(CallbackInfoReturnable<Component> callbackInfo) {
        KeyMapping.Category category = (KeyMapping.Category) (Object) this;
        if (BoldTextFixMod.MOD_ID.equals(category.id().getNamespace())
                && "debug".equals(category.id().getPath())) {
            callbackInfo.setReturnValue(Component.literal(BoldTextFixMod.MOD_NAME));
        }
    }
}
