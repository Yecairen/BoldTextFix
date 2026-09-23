package dev.yecairen.boldtextfix.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.yecairen.boldtextfix.CustomGlyph;
import dev.yecairen.boldtextfix.DilationBoldBaker;
import dev.yecairen.boldtextfix.DilationBoldGlyph;
import dev.yecairen.boldtextfix.FontFixPolicy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph")
abstract class BakedSheetGlyphMixin implements CustomGlyph, DilationBoldGlyph {
    @Shadow
    protected abstract void render(
            boolean italic,
            float x,
            float y,
            float depth,
            Matrix4fc matrix,
            VertexConsumer vertexConsumer,
            int color,
            boolean bold,
            int light
    );

    @Unique private boolean boldtextfix$trueType;
    @Unique private final Map<Integer, BakedSheetGlyph> boldtextfix$dilationBoldVariants = new ConcurrentHashMap<>();
    @Unique private DilationBoldBaker.LazySource boldtextfix$dilationLazySource;
    @Unique private boolean boldtextfix$vanillaFontGlyph;
    @Unique private boolean boldtextfix$dilationBoldVariantGlyph;

    @Override
    public boolean boldtextfix$isTrueType() {
        return this.boldtextfix$trueType;
    }

    @Override
    public void boldtextfix$setTrueType(boolean trueType) {
        this.boldtextfix$trueType = trueType;
    }

    @Override
    public BakedSheetGlyph boldtextfix$getDilationBoldVariant(int variantKey) {
        return this.boldtextfix$dilationBoldVariants.get(variantKey);
    }

    @Override
    public void boldtextfix$setDilationBoldVariant(int variantKey, BakedSheetGlyph variant) {
        if (variant != null) {
            this.boldtextfix$dilationBoldVariants.put(variantKey, variant);
        }
    }

    @Override
    public DilationBoldBaker.LazySource boldtextfix$getDilationLazySource() {
        return this.boldtextfix$dilationLazySource;
    }

    @Override
    public void boldtextfix$setDilationLazySource(DilationBoldBaker.LazySource source) {
        this.boldtextfix$dilationLazySource = source;
    }

    @Override
    public boolean boldtextfix$isVanillaFontGlyph() {
        return this.boldtextfix$vanillaFontGlyph;
    }

    @Override
    public void boldtextfix$setVanillaFontGlyph(boolean vanillaFontGlyph) {
        this.boldtextfix$vanillaFontGlyph = vanillaFontGlyph;
    }

    @Override
    public boolean boldtextfix$isDilationBoldVariant() {
        return this.boldtextfix$dilationBoldVariantGlyph;
    }

    @Override
    public void boldtextfix$markDilationBoldVariant() {
        this.boldtextfix$dilationBoldVariantGlyph = true;
    }

    @Inject(method = "createGlyph", at = @At("HEAD"), cancellable = true)
    private void boldtextfix$useDilationBoldVariant(
            float x,
            float y,
            int color,
            int shadowColor,
            Style style,
            float boldOffset,
            float shadowOffset,
            CallbackInfoReturnable<TextRenderable.Styled> callbackInfo
    ) {
        if (!FontFixPolicy.shouldUseDilation(style, this.boldtextfix$vanillaFontGlyph)) {
            return;
        }

        BakedSheetGlyph variant = DilationBoldBaker.ensureVariant((BakedSheetGlyph) (Object) this);
        if (variant != null) {
            callbackInfo.setReturnValue(variant.createGlyph(
                    x, y, color, shadowColor, style, boldOffset, shadowOffset
            ));
        }
    }

    @Inject(method = "renderChar", at = @At("HEAD"))
    private void boldtextfix$beginRenderChar(CallbackInfo callbackInfo) {
        FontFixPolicy.beginRenderChar();
    }

    @Inject(method = "renderChar", at = @At("RETURN"))
    private void boldtextfix$endRenderChar(CallbackInfo callbackInfo) {
        FontFixPolicy.endRenderChar();
    }

    /**
     * Keeps Minecraft's complete native second glyph draw in offset mode. Dilation and a zero
     * offset consume only the duplicate call because their first draw already has the intended
     * appearance.
     */
    @Redirect(
            method = "renderChar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/font/glyphs/BakedSheetGlyph;render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V"
            )
    )
    private void boldtextfix$preserveVanillaOffsetPass(
            BakedSheetGlyph glyph,
            boolean italic,
            float x,
            float y,
            float depth,
            Matrix4fc matrix,
            VertexConsumer vertexConsumer,
            int color,
            boolean bold,
            int light
    ) {
        if (FontFixPolicy.isSecondaryBoldPass(bold)
                && !FontFixPolicy.shouldUseVanillaOffsetPass()) {
            return;
        }
        this.render(italic, x, y, depth, matrix, vertexConsumer, color, bold, light);
    }
}
