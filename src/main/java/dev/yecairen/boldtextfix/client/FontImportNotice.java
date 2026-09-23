package dev.yecairen.boldtextfix.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A visual-only import confirmation; the settings screen keeps handling all input. */
final class FontImportNotice {
    private static final long DURATION_NANOS = 8_000_000_000L;
    private static final int PADDING = 10;

    private List<String> importedNames = List.of();
    private long firstFrameNanos;
    private boolean presented;
    private boolean showSwitchHint;

    void show(List<String> names, boolean showSwitchHint) {
        if (!names.isEmpty()) {
            this.importedNames = List.copyOf(names);
            this.showSwitchHint = showSwitchHint;
            this.presented = false;
        }
    }

    void extract(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight) {
        if (this.importedNames.isEmpty() || screenWidth < 48 || screenHeight < 48) {
            return;
        }
        float opacity = this.opacity(System.nanoTime());
        int textAlpha = Math.round(255 * opacity);
        if (textAlpha == 0) {
            return;
        }
        int wrapWidth = Math.min(380, screenWidth - 24) - 2 * PADDING;
        List<FormattedCharSequence> lines = font.split(this.message(font, wrapWidth), wrapWidth);
        int width = lines.stream().mapToInt(font::width).max().orElse(0) + 2 * PADDING;
        int lineHeight = font.lineHeight + 2;
        int height = lines.size() * lineHeight - 2 + 2 * PADDING;
        int left = (screenWidth - width) / 2;
        int top = (screenHeight - height) / 2;
        int textColor = (textAlpha << 24) | 0xF4F4F4;

        graphics.nextStratum();
        graphics.fill(left, top, left + width, top + height,
                (Math.round(240 * opacity) << 24) | 0x1D2024);
        graphics.fill(left, top, left + width, top + 1, (textAlpha << 24) | 0x78DBA9);
        for (int index = 0; index < lines.size(); index++) {
            graphics.text(font, lines.get(index), left + PADDING, top + PADDING + index * lineHeight,
                    textColor, false);
        }
    }

    private Component message(Font font, int wrapWidth) {
        String names = String.join(", ", this.importedNames);
        // Leave room for the instruction even when many fonts or long filenames are dropped.
        int nameWidth = wrapWidth * 2;
        if (font.width(names) > nameWidth) {
            names = font.plainSubstrByWidth(names, Math.max(1, nameWidth - font.width("…"))) + "…";
        }
        Component filename = Component.literal(names).withStyle(style -> style.withColor(0xFFD700));
        return this.showSwitchHint
                ? Component.translatable("screen.boldtextfix.font.imported", filename,
                        Component.translatable("screen.boldtextfix.mode.custom_font"))
                : Component.translatable("screen.boldtextfix.font.imported_custom", filename);
    }

    private float opacity(long now) {
        if (this.importedNames.isEmpty()) {
            return 0;
        }
        if (!this.presented) {
            this.firstFrameNanos = now;
            this.presented = true;
        }
        long elapsed = now - this.firstFrameNanos;
        if (elapsed >= DURATION_NANOS) {
            this.importedNames = List.of();
            return 0;
        }
        return 1.0F - (float) elapsed / DURATION_NANOS;
    }
}
