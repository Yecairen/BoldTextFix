package dev.yecairen.boldtextfix.mixin;

import dev.yecairen.boldtextfix.CustomGlyph;
import dev.yecairen.boldtextfix.DilationBoldGlyph;
import dev.yecairen.boldtextfix.FontFixPolicy;
import dev.yecairen.boldtextfix.ShaderCompatibility;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Configures each glyph draw's bold offset and optional shader-safe display mode. */
@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$GlyphInstance")
abstract class GlyphInstanceMixin {
    @Unique private boolean boldtextfix$customBold;
    @Unique private boolean boldtextfix$offsetMode;

    @Shadow @Final private BakedSheetGlyph glyph;
    @Shadow @Final @Mutable private float boldOffset;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void boldtextfix$configureCustomBold(
            float x,
            float y,
            int color,
            int shadowColor,
            BakedSheetGlyph glyph,
            Style style,
            float boldOffset,
            float shadowOffset,
            CallbackInfo callbackInfo
    ) {
        boolean vanillaFontGlyph = glyph instanceof DilationBoldGlyph marked
                && marked.boldtextfix$isVanillaFontGlyph();
        boolean dilationVariant = FontFixPolicy.shouldUseDilation(style, vanillaFontGlyph)
                && glyph instanceof DilationBoldGlyph marked
                && marked.boldtextfix$isDilationBoldVariant();
        this.boldtextfix$customBold = dilationVariant || FontFixPolicy.shouldHandleCustomBold(style, vanillaFontGlyph);
        this.boldtextfix$offsetMode = this.boldtextfix$customBold
                && !dilationVariant
                && FontFixPolicy.usesOffsetFallback();

        if (this.boldtextfix$customBold) {
            this.boldOffset = this.boldtextfix$offsetMode ? FontFixPolicy.configuredOffset() : 0.0F;
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void boldtextfix$beginGlyphRender(CallbackInfo callbackInfo) {
        FontFixPolicy.beginGlyphRender(
                this.boldtextfix$customBold,
                this.boldOffset,
                this.boldtextfix$offsetMode
        );
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void boldtextfix$endGlyphRender(CallbackInfo callbackInfo) {
        FontFixPolicy.endGlyphRender(this.boldtextfix$customBold);
    }

    @ModifyVariable(method = "renderType", at = @At("HEAD"), argsOnly = true)
    private Font.DisplayMode boldtextfix$useShaderSafeDisplayMode(Font.DisplayMode displayMode) {
        if (FontFixPolicy.isEnabled()
                && displayMode == Font.DisplayMode.POLYGON_OFFSET
                && this.glyph instanceof CustomGlyph marked
                && marked.boldtextfix$isTrueType()
                && ShaderCompatibility.isShaderPackInUse()) {
            return Font.DisplayMode.NORMAL;
        }
        return displayMode;
    }
}
