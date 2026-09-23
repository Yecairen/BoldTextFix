package dev.yecairen.boldtextfix.client;

import static dev.yecairen.boldtextfix.client.ConfigWidgets.CONTROL_HEIGHT;
import static dev.yecairen.boldtextfix.client.ConfigWidgets.SELECTED_TEXT_COLOR;
import static dev.yecairen.boldtextfix.client.ConfigWidgets.UNSELECTED_TEXT_COLOR;

import com.mojang.blaze3d.platform.InputConstants;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.BoldTextFixMod;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.client.ConfigWidgets.CompactButton;
import dev.yecairen.boldtextfix.client.ConfigWidgets.FontFileButton;
import dev.yecairen.boldtextfix.client.ConfigWidgets.InfoLabel;
import dev.yecairen.boldtextfix.client.ConfigWidgets.PreviewHelpLabel;
import dev.yecairen.boldtextfix.client.ConfigWidgets.RefreshButton;
import dev.yecairen.boldtextfix.client.ConfigWidgets.SettingSlider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.Blaze3D;

public final class BoldTextFixConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_INSET = (ROW_HEIGHT - CONTROL_HEIGHT) / 2;
    private static final int PREVIEW_HEIGHT = 12;
    private static final int SCREEN_MARGIN = 12;
    private static final int HEADER_TOP = 6;
    private static final int MAX_PREVIEW_BYTES = 100;

    private final Screen parent;
    private final FontImportNotice importNotice = new FontImportNotice();
    private final List<OptionRow> rows = new ArrayList<>();
    private final List<SettingSlider> sliders = new ArrayList<>();
    private final List<FontFileButton> fontButtons = new ArrayList<>();
    private final List<Button> notificationPositionButtons = new ArrayList<>();
    private List<AbstractWidget> modeControls = List.of();
    private List<BoldFontFiles.FontFile> displayedFiles = List.of();
    private Component folderError = Component.empty();
    private String previewText;
    private boolean limitingPreviewText;
    private boolean draggingScrollbar;
    private int panelLeft;
    private int panelWidth;
    private int controlLeft;
    private int controlWidth;
    private double scrollAmount;
    private double scrollbarGrabOffset;
    private int headerLineY;
    private int listTop;
    private int listBottom;
    private int boldPreviewTop;
    private int boldPreviewHeight;
    private int normalPreviewTop;
    private EditBox previewInput;
    private Button enabledToggle;
    private Button doneButton;
    private InfoLabel fontStatus;
    private PreviewHelpLabel previewLabel;

    public BoldTextFixConfigScreen(Screen parent) {
        super(Component.literal(BoldTextFixMod.displayName()));
        this.parent = parent;
        this.previewText = Component.translatable("screen.boldtextfix.preview.default").getString();
    }

    @Override
    protected void init() {
        this.draggingScrollbar = false;
        this.rows.clear();
        this.sliders.clear();
        this.fontButtons.clear();
        this.notificationPositionButtons.clear();
        this.fontStatus = null;
        this.panelWidth = Math.max(1, Math.min(620, this.width - 24));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.controlWidth = Math.max(1, Math.min(240, this.panelWidth / 2));
        this.controlLeft = this.panelLeft + this.panelWidth - this.controlWidth - 14;
        CustomBoldFonts.refresh(this.minecraft);
        this.displayedFiles = CustomBoldFonts.files();
        if (!this.folderError.getString().isEmpty()) {
            this.addRow(null, new InfoLabel(this.panelLeft + 8, this.panelWidth - 24, this.folderError));
        }

        int toggleWidth = Math.min((this.width - 2 * SCREEN_MARGIN - 8) / 2,
                Math.max(96, Math.max(this.font.width(stateMessage(true)), this.font.width(stateMessage(false))) + 16));
        this.enabledToggle = this.addRenderableWidget(new CompactButton(SCREEN_MARGIN, this.height - 24,
                toggleWidth, stateMessage(BoldTextFixConfig.isEnabled()), button -> {
                    BoldTextFixConfig.setEnabled(!BoldTextFixConfig.isEnabled());
                    button.setMessage(stateMessage(BoldTextFixConfig.isEnabled()));
                    this.settingsChanged();
                }));
        this.enabledToggle.setTooltip(tooltip("screen.boldtextfix.enabled.tooltip"));

        BoldTextFixConfig.RepairMode mode = BoldTextFixConfig.mode();
        this.addModeControls(mode);

        if (mode == BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
            this.addFontControls();
        } else {
            int ticks = mode == BoldTextFixConfig.RepairMode.DILATION
                    ? BoldTextFixConfig.dilationStrengthTicks() : BoldTextFixConfig.offsetStrengthTicks();
            this.addSlider("screen.boldtextfix.setting.strength", 0,
                    BoldTextFixConfig.strengthForTicks(mode, BoldTextFixConfig.maxStrengthTicks(mode)),
                    BoldTextFixConfig.STRENGTH_STEP, BoldTextFixConfig.strengthForTicks(mode, ticks), "%.2f px",
                    "screen.boldtextfix.slider." + mode.serializedName() + ".tooltip", value -> {
                        if (mode == BoldTextFixConfig.RepairMode.DILATION) {
                            BoldTextFixConfig.setDilationStrength((float) value);
                        } else {
                            BoldTextFixConfig.setOffsetStrength((float) value);
                        }
                    });
        }

        if (mode == BoldTextFixConfig.RepairMode.DILATION) {
            Button efficiencyToggle = new CompactButton(this.controlLeft, 0, this.controlWidth,
                    efficiencyMessage(), button -> {
                        BoldTextFixConfig.setDilationLimited(!BoldTextFixConfig.dilationLimited());
                        button.setMessage(efficiencyMessage());
                    });
            efficiencyToggle.setTooltip(tooltip("screen.boldtextfix.efficiency.tooltip"));
            this.addRow("screen.boldtextfix.efficiency", efficiencyToggle);
            this.addNotificationControls();
        }

        this.previewInput = this.addRenderableWidget(new EditBox(this.font, this.controlLeft, 0,
                this.controlWidth, CONTROL_HEIGHT, Component.translatable("screen.boldtextfix.preview.input")));
        this.previewInput.setMaxLength(MAX_PREVIEW_BYTES);
        this.previewInput.setValue(this.previewText);
        this.previewInput.setResponder(this::setPreviewText);
        this.previewLabel = this.addRenderableWidget(new PreviewHelpLabel(this.panelLeft + 8,
                Component.translatable("screen.boldtextfix.preview.label")
                        .withStyle(Style.EMPTY.withColor(0xE1E9ED)), this.font));
        this.previewLabel.setMaxWidth(Math.max(1, this.controlLeft - this.panelLeft - 16)).setMaxRows(2);
        this.previewLabel.setTooltip(tooltip("screen.boldtextfix.preview.missing_glyph.tooltip"));
        int doneWidth = Math.min((this.width - 2 * SCREEN_MARGIN - 8) / 2,
                Math.max(96, this.font.width(Component.translatable("gui.done")) + 16));
        this.doneButton = this.addRenderableWidget(new CompactButton(this.width - SCREEN_MARGIN - doneWidth,
                this.height - 24, doneWidth, Component.translatable("gui.done"), button -> this.onClose()));
        this.positionWidgets();
    }

    private void addModeControls(BoldTextFixConfig.RepairMode mode) {
        int nameWidth = 0;
        for (var candidate : BoldTextFixConfig.RepairMode.values()) {
            nameWidth = Math.max(nameWidth,
                    this.font.width(Component.translatable("screen.boldtextfix.mode." + candidate.serializedName())));
        }
        int width = Math.min(this.width - 2 * SCREEN_MARGIN,
                Math.max(112, nameWidth + 2 * (CONTROL_HEIGHT + 4) + 8));
        int left = this.width - SCREEN_MARGIN - width;
        int top = SCREEN_MARGIN + this.font.width(this.title) + 12 <= left
                ? HEADER_TOP : HEADER_TOP + ROW_HEIGHT;
        this.headerLineY = top + CONTROL_HEIGHT + 4;
        this.listTop = this.headerLineY + 1;
        String modeKey = "screen.boldtextfix.mode." + mode.serializedName();
        Button previousMode = new CompactButton(left, top, CONTROL_HEIGHT,
                Component.literal("<"), button -> this.changeMode(-1));
        InfoLabel modeName = new InfoLabel(left + CONTROL_HEIGHT + 4,
                width - 2 * (CONTROL_HEIGHT + 4),
                Component.translatable(modeKey), true);
        modeName.setY(top);
        Button nextMode = new CompactButton(left + width - CONTROL_HEIGHT, top,
                CONTROL_HEIGHT, Component.literal(">"), button -> this.changeMode(1));
        Tooltip modeTooltip = Tooltip.create(Component.translatable(modeKey + ".tooltip.line1")
                .append("\n").append(Component.translatable("screen.boldtextfix.mode.cycle")));
        previousMode.setTooltip(modeSwitchTooltip(-1));
        modeName.setTooltip(modeTooltip);
        nextMode.setTooltip(modeSwitchTooltip(1));
        this.modeControls = List.of(previousMode, modeName, nextMode);
        this.modeControls.forEach(this::addRenderableWidget);
    }

    private static Component efficiencyMessage() {
        return Component.translatable(BoldTextFixConfig.dilationLimited()
                ? "screen.boldtextfix.efficiency.limited" : "screen.boldtextfix.efficiency.full_speed");
    }

    private void addNotificationControls() {
        var corners = BoldTextFixConfig.NotificationCorner.values();
        String[] symbols = {"\u2196", "\u2197", "\u2199", "\u2198"};
        int width = Math.max(1, (this.controlWidth - 4 * corners.length) / (corners.length + 1));
        Button off = new CompactButton(this.controlLeft, 0, width,
                Component.translatable("screen.boldtextfix.notifications.off"), button -> {
                    BoldTextFixConfig.setDilationNotifications(false);
                    this.updateNotificationPositions();
                });
        off.setTooltip(tooltip("screen.boldtextfix.notifications.tooltip"));
        this.notificationPositionButtons.add(off);
        for (int index = 0; index < corners.length; index++) {
            var corner = corners[index];
            Button button = new CompactButton(this.controlLeft + (index + 1) * (width + 4), 0, width,
                    Component.literal(symbols[index]), pressed -> {
                        BoldTextFixConfig.setNotificationCorner(corner);
                        BoldTextFixConfig.setDilationNotifications(true);
                        this.updateNotificationPositions();
                    });
            button.setTooltip(tooltip("screen.boldtextfix.notifications." + corner.serializedName()));
            this.notificationPositionButtons.add(button);
        }
        this.addRow("screen.boldtextfix.notifications.position", this.notificationPositionButtons.toArray(Button[]::new));
    }

    private void updateNotificationPositions() {
        int selected = BoldTextFixConfig.dilationNotifications() ? BoldTextFixConfig.notificationCorner().ordinal() + 1 : 0;
        for (int index = 0; index < this.notificationPositionButtons.size(); index++) {
            Button button = this.notificationPositionButtons.get(index);
            button.active = BoldTextFixConfig.isEnabled();
            button.setMessage(button.getMessage().copy().withStyle(Style.EMPTY.withColor(
                    selected == index ? SELECTED_TEXT_COLOR : UNSELECTED_TEXT_COLOR)));
        }
    }

    private void addFontControls() {
        this.addSlider("screen.boldtextfix.font.size", BoldTextFixConfig.MIN_FONT_SIZE,
                BoldTextFixConfig.MAX_FONT_SIZE, BoldTextFixConfig.FONT_STEP, BoldTextFixConfig.fontSize(), "%.1f px",
                "screen.boldtextfix.font.size.tooltip", value -> BoldTextFixConfig.setFontSize((float) value));
        this.addSlider("screen.boldtextfix.font.oversample", BoldTextFixConfig.MIN_FONT_OVERSAMPLE,
                BoldTextFixConfig.MAX_FONT_OVERSAMPLE, BoldTextFixConfig.FONT_STEP, BoldTextFixConfig.fontOversample(), "%.1f×",
                "screen.boldtextfix.font.oversample.tooltip", value -> BoldTextFixConfig.setFontOversample((float) value));

        BoldFontFiles.inspectFiles(this.displayedFiles, false);
        int folderWidth = this.controlWidth - CONTROL_HEIGHT - 4;
        Button folder = new CompactButton(this.controlLeft, 0, folderWidth,
                Component.translatable("screen.boldtextfix.font.open_folder"), button -> this.openFolder());
        folder.setTooltip(tooltip("screen.boldtextfix.font.import_hint"));
        Button refresh = new RefreshButton(this.controlLeft + folderWidth + 4, button -> {
            this.commitSliders();
            CustomBoldFonts.invalidate();
            CustomBoldFonts.refresh(this.minecraft);
            BoldFontFiles.inspectFiles(CustomBoldFonts.files(), true);
            this.rebuildWidgets();
        });
        refresh.setTooltip(tooltip("screen.boldtextfix.font.refresh"));
        this.addRow("screen.boldtextfix.font.files", folder, refresh);
        this.fontStatus = new InfoLabel(this.panelLeft + 8, this.panelWidth - 24, CustomBoldFonts.status());
        this.addRow(null, this.fontStatus);
        if (this.displayedFiles.isEmpty()) {
            this.addRow(null, new InfoLabel(this.panelLeft + 8, this.panelWidth - 24,
                    Component.translatable("screen.boldtextfix.font.empty")));
        }
        for (BoldFontFiles.FontFile file : this.displayedFiles) {
            FontFileButton fontButton = new FontFileButton(this.panelLeft + 8, this.panelWidth - 24, file, button -> {
                this.commitSliders();
                BoldTextFixConfig.setBoldFont(file.name().equals(BoldTextFixConfig.boldFont()) ? "" : file.name());
                this.rebuildWidgets();
            });
            this.fontButtons.add(fontButton);
            this.addRow(null, fontButton);
        }
    }

    private void addSlider(String label, double minimum, double maximum, double step, double current,
            String format, String tooltipKey, DoubleConsumer setter) {
        SettingSlider slider = new SettingSlider(this.controlLeft, this.controlWidth, minimum, maximum,
                step, current, format, value -> {
                    setter.accept(value);
                    this.settingsChanged();
                });
        slider.setTooltip(tooltip(tooltipKey));
        this.sliders.add(slider);
        this.addRow(label, slider);
    }

    private void addRow(String key, AbstractWidget... controls) {
        this.rows.add(new OptionRow(key == null ? Component.empty() : Component.translatable(key), List.of(controls)));
        for (AbstractWidget control : controls) {
            this.addWidget(control);
        }
    }

    private void positionWidgets() {
        boolean custom = BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.CUSTOM_FONT;
        // Keep the normal-text baseline fixed; reclaim the padding above it for the settings viewport.
        this.normalPreviewTop = this.height - 46;
        int desiredHeight = custom ? (int) Math.ceil(BoldTextFixConfig.fontSize() * 2) - 6 : PREVIEW_HEIGHT;
        int previewGap = Math.max(1, (this.previewLabel.getHeight() - CONTROL_HEIGHT) / 2 + 4);
        int availableHeight = this.normalPreviewTop - this.listTop - CONTROL_HEIGHT - previewGap - 4 - ROW_HEIGHT;
        this.boldPreviewHeight = Math.max(PREVIEW_HEIGHT, Math.min(desiredHeight, availableHeight));
        this.boldPreviewTop = this.normalPreviewTop - this.boldPreviewHeight;
        this.previewInput.setX(this.previewLabel.borderRight() + 4);
        this.previewInput.setWidth(Math.max(1, this.panelLeft + this.panelWidth - 14 - this.previewInput.getX()));
        this.previewInput.setY(this.boldPreviewTop - CONTROL_HEIGHT - previewGap);
        this.listBottom = this.previewInput.getY() - 4;
        this.scrollAmount = Mth.clamp(this.scrollAmount, 0.0, this.maxScroll());
        for (int index = 0; index < this.rows.size(); index++) {
            for (AbstractWidget control : this.rows.get(index).controls()) {
                control.setY(this.rowTop(index) + ROW_INSET);
                control.visible = control.getBottom() > this.listTop && control.getY() < this.listBottom;
                if (!control.visible) {
                    control.setFocused(false);
                }
            }
        }
        this.previewLabel.setY(this.previewInput.getY() + (CONTROL_HEIGHT - this.previewLabel.getHeight()) / 2);
        this.updateInteractionState();
    }

    private void settingsChanged() {
        CustomBoldFonts.refresh(this.minecraft);
        if (this.fontStatus != null) {
            this.fontStatus.setMessage(CustomBoldFonts.status());
        }
        this.positionWidgets();
    }

    private void updateInteractionState() {
        boolean unlocked = BoldTextFixConfig.isEnabled();
        for (OptionRow row : this.rows) {
            for (AbstractWidget control : row.controls()) {
                control.active = unlocked;
            }
        }
        this.previewInput.active = unlocked;
        this.previewLabel.active = unlocked;
        this.modeControls.forEach(control -> control.active = unlocked);
        this.enabledToggle.active = true;
        this.doneButton.active = true;
        this.updateNotificationPositions();
        if (!unlocked) {
            this.draggingScrollbar = false;
            this.clearFocus();
        }
    }

    private void changeMode(int direction) {
        this.commitSliders();
        BoldTextFixConfig.setMode(adjacentMode(direction));
        this.scrollAmount = 0;
        this.rebuildWidgets();
    }

    private static BoldTextFixConfig.RepairMode adjacentMode(int direction) {
        BoldTextFixConfig.RepairMode[] modes = BoldTextFixConfig.RepairMode.values();
        return modes[Math.floorMod(BoldTextFixConfig.mode().ordinal() + direction, modes.length)];
    }

    private static Tooltip modeSwitchTooltip(int direction) {
        return Tooltip.create(Component.translatable("screen.boldtextfix.mode.switch_to",
                Component.translatable("screen.boldtextfix.mode." + adjacentMode(direction).serializedName())));
    }

    private void openFolder() {
        try {
            Files.createDirectories(BoldFontFiles.directory());
            Blaze3D.openPath(BoldFontFiles.directory());
            if (!this.folderError.getString().isEmpty()) {
                this.folderError = Component.empty();
                this.rebuildWidgets();
            }
        } catch (IOException | RuntimeException failure) {
            this.folderError = Component.translatable("screen.boldtextfix.font.folder_error")
                    .withStyle(Style.EMPTY.withColor(UNSELECTED_TEXT_COLOR));
            this.rebuildWidgets();
        }
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (!BoldTextFixConfig.isEnabled()) {
            return;
        }
        this.commitSliders();
        List<String> importedNames = new ArrayList<>();
        for (Path path : paths) {
            try {
                importedNames.add(CustomBoldFonts.importFile(path));
            } catch (IOException | RuntimeException ignored) {
                // Invalid imports do not replace the existing selection or copy unusable files.
            }
        }
        this.importNotice.show(importedNames,
                BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.CUSTOM_FONT);
        this.rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
            if (!this.displayedFiles.equals(CustomBoldFonts.files())
                    && this.sliders.stream().noneMatch(SettingSlider::isDirty)) {
                boolean editing = this.previewInput.isFocused();
                this.rebuildWidgets();
                if (editing) {
                    this.setFocused(this.previewInput);
                }
            } else if (this.fontStatus != null) {
                this.fontStatus.setMessage(CustomBoldFonts.status());
            }
            this.fontButtons.forEach(FontFileButton::updateDetails);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (BoldTextFixConfig.isEnabled() && vertical != 0 && this.insideList(mouseX, mouseY)) {
            this.scrollAmount -= vertical * ROW_HEIGHT / 2.0;
            this.clearFocus();
            this.positionWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (BoldTextFixConfig.isEnabled() && event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.maxScroll() > 0
                && this.insideList(event.x(), event.y())
                && event.x() >= this.panelLeft + this.panelWidth - 10) {
            this.draggingScrollbar = true;
            int thumbTop = this.scrollbarTop();
            int thumbHeight = this.scrollbarHeight();
            this.scrollbarGrabOffset = event.y() >= thumbTop && event.y() < thumbTop + thumbHeight
                    ? event.y() - thumbTop : thumbHeight / 2.0;
            this.scrollTo(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        boolean inside = this.insideList(mouseX, mouseY);
        for (GuiEventListener child : this.children()) {
            if (child.isMouseOver(mouseX, mouseY)
                    && (inside || this.rows.stream().noneMatch(row -> row.controls().contains(child)))) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.scrollTo(event.y());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (BoldTextFixConfig.isEnabled()
                && (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN)) {
            this.scrollAmount += event.key() == InputConstants.KEY_PAGEUP ? -this.listHeight() : this.listHeight();
            this.clearFocus();
            this.positionWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean insideList(double mouseX, double mouseY) {
        return mouseX >= this.panelLeft && mouseX < this.panelLeft + this.panelWidth
                && mouseY >= this.listTop && mouseY < this.listBottom;
    }

    private int rowTop(int index) {
        return this.listTop + index * ROW_HEIGHT - Mth.floor(this.scrollAmount);
    }

    private int listHeight() {
        return Math.max(1, this.listBottom - this.listTop);
    }

    private int maxScroll() {
        return Math.max(0, this.rows.size() * ROW_HEIGHT - this.listHeight());
    }

    private int scrollbarHeight() {
        return Math.min(this.listHeight(), Math.max(8,
                this.listHeight() * this.listHeight() / Math.max(1, this.rows.size() * ROW_HEIGHT)));
    }

    private int scrollbarTop() {
        return this.listTop + (int) Math.round((this.listHeight() - this.scrollbarHeight())
                * this.scrollAmount / Math.max(1, this.maxScroll()));
    }

    private void scrollTo(double mouseY) {
        double fraction = (mouseY - this.listTop - this.scrollbarGrabOffset)
                / Math.max(1, this.listHeight() - this.scrollbarHeight());
        this.scrollAmount = fraction * this.maxScroll();
        this.clearFocus();
        this.positionWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xF010161C);
        graphics.text(this.font, this.title, SCREEN_MARGIN, HEADER_TOP + 4, 0xFFFFFFFF);
        graphics.fill(this.panelLeft, this.headerLineY, this.panelLeft + this.panelWidth, this.listTop, 0xFF3C525B);
        graphics.enableScissor(this.panelLeft, this.listTop, this.panelLeft + this.panelWidth, this.listBottom);
        graphics.fill(this.panelLeft, this.listTop, this.panelLeft + this.panelWidth, this.listBottom, 0xFF172129);
        for (int index = 0; index < this.rows.size(); index++) {
            OptionRow row = this.rows.get(index);
            int top = this.rowTop(index);
            if (top + ROW_HEIGHT <= this.listTop || top >= this.listBottom) {
                continue;
            }
            graphics.fill(this.panelLeft, top, this.panelLeft + this.panelWidth - 10, top + ROW_HEIGHT - 1,
                    index % 2 == 0 ? 0xFF1B252D : 0xFF172129);
            if (!row.label().getString().isEmpty()) {
                this.drawLabel(graphics, row.label(), row.controls().getFirst());
            }
            for (AbstractWidget control : row.controls()) {
                control.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();
        this.drawScrollbar(graphics);
        graphics.fill(this.panelLeft, this.listBottom,
                this.panelLeft + this.panelWidth, this.listBottom + 1, 0xFF3C525B);
        this.drawPreview(graphics, true, this.boldPreviewTop, this.boldPreviewHeight);
        this.drawPreview(graphics, false, this.normalPreviewTop, PREVIEW_HEIGHT);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (!BoldTextFixConfig.isEnabled()) {
            // Dim settings and the header selector, leaving the title and footer controls clear.
            int dimBottom = this.doneButton.getY() - 4;
            graphics.fill(0, this.headerLineY, this.width, dimBottom, 0x50000000);
            AbstractWidget firstModeControl = this.modeControls.getFirst();
            AbstractWidget lastModeControl = this.modeControls.getLast();
            graphics.fill(firstModeControl.getX(), firstModeControl.getY(),
                    lastModeControl.getRight(), lastModeControl.getBottom(), 0x50000000);
        }
        this.importNotice.extract(graphics, this.font, this.width, this.height);
    }

    private void drawPreview(GuiGraphicsExtractor graphics, boolean bold, int top, int height) {
        int baseline = top + height / 2 - 4;
        Component label = Component.translatable(bold ? "screen.boldtextfix.preview.bold" : "screen.boldtextfix.preview.normal");
        String labelText = this.font.plainSubstrByWidth(label.getString(), Math.max(1, this.panelWidth / 3));
        int textLeft = this.previewTextLeft(labelText);
        graphics.text(this.font, labelText, this.panelLeft + 8, baseline, 0xFF91A2AD);
        graphics.enableScissor(textLeft, top, this.panelLeft + this.panelWidth - 8, top + height);
        Component text = Component.literal(this.previewText).withStyle(Style.EMPTY.withBold(bold));
        graphics.text(this.font, text, textLeft, baseline, 0xFFFFFFFF);
        graphics.disableScissor();
    }

    private int previewTextLeft(String label) {
        String firstGlyph = label.isEmpty() ? " " : label.substring(0, label.offsetByCodePoints(0, 1));
        int gap = Math.max(1, this.font.width(firstGlyph));
        return this.panelLeft + 8 + this.font.width(label) + gap;
    }

    private void drawLabel(GuiGraphicsExtractor graphics, Component label, AbstractWidget control) {
        int left = this.panelLeft + 8;
        int available = Math.max(1, control.getX() - left - 8);
        List<FormattedCharSequence> lines = this.font.split(label, available);
        int count = Math.min(2, lines.size());
        int top = control.getY() + (CONTROL_HEIGHT - count * this.font.lineHeight) / 2;
        for (int index = 0; index < count; index++) {
            graphics.text(this.font, lines.get(index), left, top + index * this.font.lineHeight, 0xFFE1E9ED);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics) {
        if (this.maxScroll() == 0) {
            return;
        }
        int thumbHeight = this.scrollbarHeight();
        int thumbTop = this.scrollbarTop();
        int left = this.panelLeft + this.panelWidth - 6;
        graphics.fill(left, this.listTop, left + 3, this.listBottom, 0xFF27363E);
        graphics.fill(left, thumbTop, left + 3, thumbTop + thumbHeight, 0xFF70B9A8);
    }

    private static Component stateMessage(boolean enabled) {
        return Component.translatable(enabled ? "screen.boldtextfix.enabled" : "screen.boldtextfix.disabled")
                .withStyle(Style.EMPTY.withColor(enabled ? SELECTED_TEXT_COLOR : UNSELECTED_TEXT_COLOR));
    }

    private static Tooltip tooltip(String key) {
        return Tooltip.create(Component.translatable(key));
    }

    private void setPreviewText(String value) {
        if (this.limitingPreviewText) {
            return;
        }
        String limited = truncateUtf8(value, MAX_PREVIEW_BYTES);
        this.previewText = limited;
        if (!limited.equals(value)) {
            this.limitingPreviewText = true;
            this.previewInput.setValue(limited);
            this.limitingPreviewText = false;
        }
    }

    private static String truncateUtf8(String value, int maximumBytes) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
            return value == null ? "" : value;
        }
        StringBuilder result = new StringBuilder();
        int usedBytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int byteCount = character.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + byteCount > maximumBytes) {
                break;
            }
            result.appendCodePoint(codePoint);
            usedBytes += byteCount;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private void commitSliders() {
        for (SettingSlider slider : this.sliders) {
            slider.commit();
        }
    }

    @Override
    public void onClose() {
        this.commitSliders();
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void removed() {
        this.commitSliders();
        super.removed();
    }

    private record OptionRow(Component label, List<AbstractWidget> controls) {
    }

}
