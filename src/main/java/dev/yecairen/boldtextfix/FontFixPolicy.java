package dev.yecairen.boldtextfix;

import net.minecraft.network.chat.Style;

/** Chooses the configured synthetic-bold path while retaining Minecraft's bold style semantics. */
public final class FontFixPolicy {
    private static final float PASS_EPSILON = 0.0001F;

    private static final ThreadLocal<RenderState> RENDER_STATE =
            ThreadLocal.withInitial(RenderState::new);

    private FontFixPolicy() {
    }

    public static boolean isEnabled() {
        return BoldTextFixConfig.isEnabled();
    }

    public static boolean shouldHandleCustomBold(Style style, boolean vanillaFallbackGlyph) {
        return isEnabled() && style != null && style.isBold()
                && (BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.OFFSET
                || (BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.DILATION && !vanillaFallbackGlyph));
    }

    public static boolean shouldUseDilation(Style style, boolean vanillaFallbackGlyph) {
        return shouldHandleCustomBold(style, vanillaFallbackGlyph)
                && BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.OFFSET
                && BoldTextFixConfig.dilationStrengthTicks() > 0;
    }

    public static int dilationStrengthTicks() {
        return BoldTextFixConfig.dilationStrengthTicks();
    }

    public static float configuredOffset() {
        if (!isEnabled() || BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.OFFSET) {
            return 0.0F;
        }
        return BoldTextFixConfig.offsetStrength();
    }

    public static boolean usesOffsetFallback() {
        return isEnabled()
                && BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.OFFSET
                && BoldTextFixConfig.offsetStrengthTicks() > 0;
    }

    /** Marks a custom-bold glyph draw and whether its native second pass should remain visible. */
    public static void beginGlyphRender(boolean customBold, float boldOffset, boolean offsetMode) {
        if (customBold) {
            RenderState state = RENDER_STATE.get();
            state.customBoldDepth++;
            state.offsetMode = offsetMode && sanitizeOffset(boldOffset) > PASS_EPSILON;
        }
    }

    public static void endGlyphRender(boolean customBold) {
        if (!customBold) {
            return;
        }

        RenderState state = RENDER_STATE.get();
        if (state.customBoldDepth <= 1) {
            RENDER_STATE.remove();
        } else {
            state.customBoldDepth--;
        }
    }

    public static void beginRenderChar() {
        RenderState state = RENDER_STATE.get();
        if (state.customBoldDepth > 0) {
            state.renderCharActive = true;
            state.renderPassIndex = 0;
        }
    }

    public static void endRenderChar() {
        RenderState state = RENDER_STATE.get();
        state.renderCharActive = false;
        state.renderPassIndex = 0;
    }

    /** Returns true only for Minecraft's second native bold invocation. */
    public static boolean isSecondaryBoldPass(boolean bold) {
        RenderState state = RENDER_STATE.get();
        if (!state.renderCharActive) {
            return false;
        }

        int pass = state.renderPassIndex++;
        return (pass & 1) != 0 && bold;
    }

    public static boolean shouldUseVanillaOffsetPass() {
        return RENDER_STATE.get().offsetMode;
    }

    private static float sanitizeOffset(float offset) {
        return Float.isFinite(offset) ? Math.max(0.0F, Math.min(1.0F, offset)) : 0.0F;
    }

    private static final class RenderState {
        private int customBoldDepth;
        private boolean renderCharActive;
        private boolean offsetMode;
        private int renderPassIndex;
    }
}
