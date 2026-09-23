package dev.yecairen.boldtextfix.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/** Visuals and input behavior of configuration controls; page layout belongs to the screen. */
final class ConfigWidgets {
    static final int CONTROL_HEIGHT = 18;
    static final int SELECTED_TEXT_COLOR = 0x55FF55;
    static final int UNSELECTED_TEXT_COLOR = 0xFF5555;
    static final int CONTROL_BACKGROUND = 0xFF29353D;
    static final int CONTROL_HOVER_BACKGROUND = 0xFF3C515C;

    private ConfigWidgets() {
    }

    static class CompactButton extends Button {
        protected boolean warning;
        CompactButton(int left, int top, int width, Component message, OnPress action) {
            super(left, top, width, CONTROL_HEIGHT, message, action, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(),
                    this.warning ? (this.isHoveredOrFocused() ? 0xFFB03030 : 0xFF8C2525)
                            : (this.isHoveredOrFocused() ? CONTROL_HOVER_BACKGROUND : CONTROL_BACKGROUND));
            graphics.fill(this.getX(), this.getBottom() - 1, this.getRight(), this.getBottom(),
                    this.warning ? 0xFFFF6B6B : (this.isHoveredOrFocused() ? 0xFF70C7AF : 0xFF53616A));
            this.drawMessage(graphics);
        }

        protected void drawMessage(GuiGraphicsExtractor graphics) {
            graphics.enableScissor(this.getX() + 3, this.getY(), this.getRight() - 3, this.getBottom());
            graphics.centeredText(Minecraft.getInstance().font, this.getMessage(),
                    this.getX() + this.width / 2, this.getY() + 4, 0xFFFFFFFF);
            graphics.disableScissor();
        }
    }

    static final class FontFileButton extends CompactButton {
        private final BoldFontFiles.FontFile file;
        private BoldFontFiles.Details shownDetails;

        FontFileButton(int left, int width, BoldFontFiles.FontFile file, OnPress action) {
            super(left, 0, width, Component.literal(file.name())
                    .withStyle(Style.EMPTY.withColor(file.name().equals(BoldTextFixConfig.boldFont())
                            ? SELECTED_TEXT_COLOR : 0xE1E9ED)), action);
            this.file = file;
            this.updateDetails();
        }

        @Override
        protected void drawMessage(GuiGraphicsExtractor graphics) {
            if (!this.file.name().equals(BoldTextFixConfig.boldFont())) {
                super.drawMessage(graphics);
                return;
            }
            Font font = Minecraft.getInstance().font;
            int inset = Math.max(font.width("\u25B6"), font.width("\u25C0")) + 8;
            graphics.text(font, "\u25B6", this.getX() + 4, this.getY() + 4, 0xFF000000 | SELECTED_TEXT_COLOR);
            graphics.text(font, "\u25C0", this.getRight() - 4 - font.width("\u25C0"),
                    this.getY() + 4, 0xFF000000 | SELECTED_TEXT_COLOR);
            graphics.enableScissor(this.getX() + inset, this.getY(), this.getRight() - inset, this.getBottom());
            graphics.centeredText(font, this.getMessage(), this.getX() + this.width / 2, this.getY() + 4, 0xFFFFFFFF);
            graphics.disableScissor();
        }

        void updateDetails() {
            BoldFontFiles.Details details = BoldFontFiles.details(this.file);
            boolean invalid = details.invalid() || CustomBoldFonts.failedToLoad(this.file);
            if (details.equals(this.shownDetails) && invalid == this.warning) {
                return;
            }
            this.shownDetails = details;
            this.warning = invalid;
            boolean selected = this.file.name().equals(BoldTextFixConfig.boldFont());
            Component title = Component.translatable(selected
                    ? "screen.boldtextfix.font.in_use" : "screen.boldtextfix.font.choose", this.file.name());
            if (selected) {
                title = title.copy().withStyle(Style.EMPTY.withColor(SELECTED_TEXT_COLOR));
            }
            var message = Component.empty().append(title);
            if (invalid) {
                message.append("\n").append(Component.translatable("screen.boldtextfix.font.invalid")
                        .withStyle(Style.EMPTY.withColor(UNSELECTED_TEXT_COLOR)));
            }
            message.append("\n").append(Component.translatable("screen.boldtextfix.font.file_info",
                    String.format(Locale.ROOT, "%.2f", this.file.size() / 1048576.0),
                    details.characterCount(), details.glyphCount()));
            this.setTooltip(Tooltip.create(message));
        }
    }

    static final class RefreshButton extends Button {
        private static final Identifier SPRITE = Identifier.fromNamespaceAndPath("boldtextfix", "refresh");

        RefreshButton(int left, OnPress action) {
            super(left, 0, CONTROL_HEIGHT, CONTROL_HEIGHT, Component.translatable("screen.boldtextfix.font.refresh"),
                    action, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (this.isHoveredOrFocused()) {
                graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), CONTROL_HOVER_BACKGROUND);
            }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE,
                    this.getX() + 2, this.getY() + 2, CONTROL_HEIGHT - 4, CONTROL_HEIGHT - 4);
        }
    }

    static final class InfoLabel extends AbstractWidget {
        private final boolean centered;

        InfoLabel(int left, int width, Component message) {
            this(left, width, message, false);
        }

        InfoLabel(int left, int width, Component message, boolean centered) {
            super(left, 0, width, CONTROL_HEIGHT, message);
            this.centered = centered;
            this.setTooltip(Tooltip.create(message));
        }

        @Override
        public void setMessage(Component message) {
            if (!message.equals(this.getMessage())) {
                super.setMessage(message);
                this.setTooltip(Tooltip.create(message));
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.enableScissor(this.getX(), this.getY(), this.getRight(), this.getBottom());
            if (this.centered) {
                graphics.centeredText(Minecraft.getInstance().font, this.getMessage(),
                        this.getX() + this.width / 2, this.getY() + 4, 0xFFFFFFFF);
            } else {
                List<FormattedCharSequence> lines = Minecraft.getInstance().font.split(this.getMessage(), this.width);
                int count = Math.min(2, lines.size());
                int top = this.getY() + (CONTROL_HEIGHT - count * Minecraft.getInstance().font.lineHeight) / 2;
                for (int index = 0; index < count; index++) {
                    graphics.text(Minecraft.getInstance().font, lines.get(index), this.getX(),
                            top + index * Minecraft.getInstance().font.lineHeight, 0xFF91A2AD);
                }
            }
            graphics.disableScissor();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    static final class PreviewHelpLabel extends MultiLineTextWidget {
        PreviewHelpLabel(int left, Component message, Font font) {
            super(left, 0, message, font);
        }

        int borderRight() {
            return this.getRight() + 4;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int left = this.getX() - 4;
            int top = this.getY() - 3;
            int right = this.borderRight();
            int bottom = this.getBottom() + 3;
            graphics.fill(left, top, right, bottom, 0xFF625B57);

            int width = right - left;
            int height = bottom - top;
            int perimeter = 2 * (width + height - 2);
            float phase = Math.floorMod(System.nanoTime(), 8_000_000_000L) / 8_000_000_000.0f;
            // Walk clockwise so the gradient stays continuous at every corner.
            for (int x = 0; x < width; x++) {
                graphics.fill(left + x, top, left + x + 1, top + 1, borderColor(phase, x, perimeter));
                graphics.fill(left + x, bottom - 1, left + x + 1, bottom,
                        borderColor(phase, width + height - 2 + width - 1 - x, perimeter));
            }
            for (int y = 1; y < height - 1; y++) {
                graphics.fill(right - 1, top + y, right, top + y + 1,
                        borderColor(phase, width - 1 + y, perimeter));
                graphics.fill(left, top + y, left + 1, top + y + 1,
                        borderColor(phase, perimeter - y, perimeter));
            }
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
        }

        private static int borderColor(float phase, int distance, int perimeter) {
            return 0xFF000000 | Mth.hsvToRgb((phase + (float) distance / perimeter) % 1.0f, 0.7f, 1.0f);
        }
    }

    static final class SettingSlider extends AbstractSliderButton {
        private final double minimum;
        private final double maximum;
        private final double step;
        private final String format;
        private final DoubleConsumer setter;
        private boolean dirty;

        SettingSlider(int left, int width, double minimum, double maximum, double step,
                double current, String format, DoubleConsumer setter) {
            super(left, 0, width, CONTROL_HEIGHT, Component.empty(), (current - minimum) / (maximum - minimum));
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.format = format;
            this.setter = setter;
            this.updateMessage();
        }

        boolean isDirty() {
            return this.dirty;
        }

        private double configuredValue() {
            double current = this.minimum + this.value * (this.maximum - this.minimum);
            if (!this.dirty) {
                return current;
            }
            return Math.max(this.minimum, Math.min(this.maximum, this.minimum
                    + Math.round((current - this.minimum) / this.step) * this.step));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(String.format(Locale.ROOT, this.format, this.configuredValue())));
        }

        @Override
        protected void applyValue() {
            this.dirty = true;
            this.updateMessage();
        }

        void commit() {
            if (this.dirty) {
                double current = this.configuredValue();
                this.dirty = false;
                this.value = (current - this.minimum) / (this.maximum - this.minimum);
                this.setter.accept(current);
                this.updateMessage();
            }
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                this.commit();
            }
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!this.active) {
                return false;
            }
            if (this.canChangeValue && (event.isLeft() || event.isRight())) {
                double next = this.configuredValue() + (event.isLeft() ? -this.step : this.step);
                this.setValue((next - this.minimum) / (this.maximum - this.minimum));
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public boolean keyReleased(KeyEvent event) {
            this.commit();
            return super.keyReleased(event);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) {
                this.commit();
            }
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(),
                    this.isHoveredOrFocused() ? CONTROL_HOVER_BACKGROUND : CONTROL_BACKGROUND);
            int filledWidth = (int) Math.round(this.value * (this.width - 2));
            graphics.fill(this.getX() + 1, this.getBottom() - 3,
                    this.getX() + 1 + filledWidth, this.getBottom() - 1, 0xFF70C7AF);
            graphics.centeredText(Minecraft.getInstance().font, this.getMessage(),
                    this.getX() + this.width / 2, this.getY() + 4, 0xFFFFFFFF);
            this.handleCursor(graphics);
        }
    }
}
