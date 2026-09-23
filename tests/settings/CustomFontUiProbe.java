import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.client.BoldTextFixConfigScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.RandomSource;
import net.minecraft.util.FormattedCharSequence;

final class CustomFontUiProbe {
    private static int assertions;

    static void run(Path fixture) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeType, "theUnsafe").get(null);
        Minecraft client = (Minecraft) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, Minecraft.class);
        Font font = new Font(new Font.Provider() {
            public GlyphSource glyphs(FontDescription description) {
                return new GlyphSource() {
                    public BakedGlyph getGlyph(int codePoint) { return new PlainGlyph(); }
                    public BakedGlyph getRandomGlyph(RandomSource random, int advance) { return new PlainGlyph(); }
                };
            }
            public EffectGlyph effect() { return null; }
        });
        field(Minecraft.class, "font").set(client, font);
        field(Minecraft.class, "lastInputType").set(client, InputType.MOUSE);
        field(Minecraft.class, "instance").set(null, client);
        checkTooltipWrapping(client);
        checkManager(client, fixture);
        byte[] bytes = Files.readAllBytes(fixture);
        for (int index = 0; index < 16; index++) {
            Files.write(BoldFontFiles.directory().resolve("列表字体-" + index + ".otf"), bytes);
        }
        for (var mode : BoldTextFixConfig.RepairMode.values()) {
            BoldTextFixConfig.setMode(mode);
            BoldTextFixConfig.setBoldFont("");
            for (float size : new float[]{4, 10, 16}) {
                BoldTextFixConfig.setFontSize(size);
                for (int[] dimensions : new int[][]{{320, 240}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {960, 540}}) {
                    BoldTextFixConfigScreen screen = new BoldTextFixConfigScreen(null);
                    screen.width = dimensions[0];
                    screen.height = dimensions[1];
                    method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
                    checkRemovedFallbackControl(screen, mode);
                    checkModeControls(screen, mode);
                    checkLayout(screen);
                    if (mode == BoldTextFixConfig.RepairMode.CUSTOM_FONT) checkScrolling(screen);
                    int panelLeft = field(BoldTextFixConfigScreen.class, "panelLeft").getInt(screen);
                    for (int scroll = 0; scroll < 25; scroll++) {
                        screen.mouseScrolled(panelLeft + 20, 40, 0, -0.7);
                        checkLayout(screen);
                    }
                    EditBox edit = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
                    edit.setValue("共享预览 Test 한글 日本語");
                    check(field(BoldTextFixConfigScreen.class, "previewText").get(screen).equals(edit.getValue()), "Preview uses editor text");
                    if (mode == BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
                        check(CustomBoldFonts.files().size() >= 16, "External fonts synchronized");
                    }
                }
            }
        }
        BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.OFFSET);
        BoldTextFixConfigScreen screen = new BoldTextFixConfigScreen(null);
        screen.width = 480;
        screen.height = 270;
        method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
        EditBox edit = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
        edit.setValue("Drag preview");
        int fileCount = BoldFontFiles.list().size();
        screen.onFilesDrop(List.of(fixture));
        check(BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.OFFSET, "Drop retains rendering mode");
        check(BoldTextFixConfig.boldFont().isEmpty(), "Drop retains empty selection");
        check(BoldFontFiles.list().size() == fileCount + 1, "Drop still imports the font");
        check(field(BoldTextFixConfigScreen.class, "previewText").get(screen).equals("Drag preview"), "Drop retains preview text");
        BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.CUSTOM_FONT);
        String selected = CustomBoldFonts.files().getFirst().name();
        BoldTextFixConfig.setBoldFont(selected);
        method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
        screen.mouseScrolled(30, 40, 0, -1);
        double scrollBeforeImport = field(BoldTextFixConfigScreen.class, "scrollAmount").getDouble(screen);
        screen.onFilesDrop(List.of(fixture, fixture));
        check(BoldTextFixConfig.boldFont().equals(selected), "Multiple imports retain the selected font");
        check(field(BoldTextFixConfigScreen.class, "scrollAmount").getDouble(screen) == scrollBeforeImport,
                "Drop retains list scroll position");
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        check(BoldTextFixConfig.boldFont().equals(selected), "Import does not overwrite saved selection");
        BoldTextFixConfig.setBoldFont("");
        screen.onFilesDrop(List.of(fixture));
        check(BoldTextFixConfig.boldFont().isEmpty(), "Custom mode with no font stays unselected after import");
        BoldTextFixConfig.setBoldFont(selected);
        method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
        checkLayout(screen);
        SettingsUiProbe.run(screen);
        FontCatalogProbe.run(fixture);
        FontImportNoticeProbe.run(fixture, font);
        CustomBoldFonts.close();
        System.out.println("UI_AND_MANAGER_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkManager(Minecraft client, Path fixture) throws Exception {
        BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.CUSTOM_FONT);
        BoldTextFixConfig.setEnabled(true);
        String name = CustomBoldFonts.importFile(fixture);
        BoldTextFixConfig.setBoldFont(name);
        CustomBoldFonts.refresh(client);
        check(CustomFontProbe.localAdvance(65) != null, "Selected font supplies metrics");
        check(CustomFontProbe.localAdvance(0x4E2D) == null, "Missing CJK glyph falls back");
        CustomFontSelectionProbe.run();
        TrueTypeGlyphProvider initial = (TrueTypeGlyphProvider) field(CustomBoldFonts.class, "provider").get(null);
        CustomBoldFonts.refresh(client);
        check(initial == field(CustomBoldFonts.class, "provider").get(null), "No rebuild on unchanged scan");
        BoldTextFixConfig.setFontSize(15);
        CustomBoldFonts.refresh(client);
        check(field(TrueTypeGlyphProvider.class, "face").get(initial) == null, "Size change frees old font");
        TrueTypeGlyphProvider resized = (TrueTypeGlyphProvider) field(CustomBoldFonts.class, "provider").get(null);
        CustomBoldFonts.invalidate();
        CustomBoldFonts.tick(client);
        check(field(TrueTypeGlyphProvider.class, "face").get(resized) == null, "Resource reload frees old font");
        TrueTypeGlyphProvider reloaded = (TrueTypeGlyphProvider) field(CustomBoldFonts.class, "provider").get(null);
        Files.delete(BoldFontFiles.resolve(name));
        CustomBoldFonts.refresh(client);
        check(CustomFontProbe.localAdvance(65) == null && field(TrueTypeGlyphProvider.class, "face").get(reloaded) == null,
                "Deleting selected file falls back and frees resources");
        Files.write(BoldFontFiles.resolve(name), Files.readAllBytes(fixture));
        CustomBoldFonts.refresh(client);
        check(CustomFontProbe.localAdvance(65) != null, "Restoring file recovers automatically");
        BoldTextFixConfig.setEnabled(false);
        CustomBoldFonts.refresh(client);
        check(CustomFontProbe.localAdvance(65) == null, "Master disable releases font");
        BoldTextFixConfig.setEnabled(true);
    }

    private static void checkRemovedFallbackControl(BoldTextFixConfigScreen screen, BoldTextFixConfig.RepairMode mode) throws Exception {
        for (Object row : (List<?>) field(BoldTextFixConfigScreen.class, "rows").get(screen)) {
            Component label = (Component) method(row.getClass(), "label").invoke(row);
            check(!label.getString().equals("screen.boldtextfix.setting.vanilla_fallback"),
                    "Removed fallback control is absent in every mode");
        }
        if (mode == BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
            int sliderIndex = 0;
            for (Object slider : (List<?>) field(BoldTextFixConfigScreen.class, "sliders").get(screen)) {
                check(field(slider.getClass(), "minimum").getDouble(slider) == (sliderIndex++ == 0 ? 4 : 2),
                        "Font size starts at 4; clarity starts at 2");
                check(field(slider.getClass(), "maximum").getDouble(slider) == 16, "Both font sliders end at 16");
            }
        }
    }

    private static void checkModeControls(BoldTextFixConfigScreen screen, BoldTextFixConfig.RepairMode initial) throws Exception {
        List<?> controls = modeControls(screen);
        check(controls.size() == 3, "Mode row has two arrows and a name");
        Button previous = (Button) controls.getFirst();
        AbstractWidget name = (AbstractWidget) controls.get(1);
        Button next = (Button) controls.getLast();
        check(previous.getMessage().getString().equals("<") && next.getMessage().getString().equals(">"),
                "Separate previous and next buttons");
        check(previous.getRight() < name.getX() && name.getRight() < next.getX() && name.getWidth() > 0,
                "Mode controls do not overlap");
        check(name.getMessage().getString().equals("screen.boldtextfix.mode." + initial.serializedName()),
                "Current mode name is displayed");
        check(name.getMessage().getStyle().getColor() == null, "Mode name uses default text color");
        EditBox edit = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
        edit.setValue("Mode cycle preview");
        BoldTextFixConfig.RepairMode expected = switch (initial) {
            case DILATION -> BoldTextFixConfig.RepairMode.CUSTOM_FONT;
            case OFFSET -> BoldTextFixConfig.RepairMode.DILATION;
            case CUSTOM_FONT -> BoldTextFixConfig.RepairMode.OFFSET;
        };
        BoldTextFixConfig.RepairMode expectedNext = switch (initial) {
            case DILATION -> BoldTextFixConfig.RepairMode.OFFSET;
            case OFFSET -> BoldTextFixConfig.RepairMode.CUSTOM_FONT;
            case CUSTOM_FONT -> BoldTextFixConfig.RepairMode.DILATION;
        };
        checkModeTooltip(previous, expected);
        checkModeTooltip(next, expectedNext);
        previous.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        check(BoldTextFixConfig.mode() == expected, "Previous button wraps between all three modes");
        List<?> updated = modeControls(screen);
        check(((AbstractWidget) updated.get(1)).getMessage().getString()
                .equals("screen.boldtextfix.mode." + expected.serializedName()), "Mode name updates after switching");
        checkModeTooltip((Button) updated.getLast(), initial);
        ((Button) updated.getLast()).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        check(BoldTextFixConfig.mode() == initial, "Next button returns to the original mode");
        check(((EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen)).getValue()
                .equals("Mode cycle preview"), "Both switching directions retain preview text");
    }

    private static void checkModeTooltip(Button button, BoldTextFixConfig.RepairMode target) throws Exception {
        Object holder = field(AbstractWidget.class, "tooltip").get(button);
        Tooltip tooltip = (Tooltip) method(holder.getClass(), "get").invoke(holder);
        Component expected = Component.translatable("screen.boldtextfix.mode.switch_to",
                Component.translatable("screen.boldtextfix.mode." + target.serializedName()));
        check(expected.equals(field(Tooltip.class, "message").get(tooltip)), "Arrow tooltip names its click destination");
    }

    private static void checkTooltipWrapping(Minecraft client) {
        check(Tooltip.splitTooltip(client, Component.literal("甲 乙")).size() == 1,
                "A space alone does not force a new line");
        check(Tooltip.splitTooltip(client, Component.literal("甲\n乙")).size() == 2,
                "An explicit newline forces a line break");
        List<FormattedCharSequence> unspaced = Tooltip.splitTooltip(client, Component.literal("甲".repeat(32)));
        check(plainText(unspaced.getFirst()).equals("甲".repeat(28)), "Unspaced text wraps at 170 logical pixels");
        List<FormattedCharSequence> spaced = Tooltip.splitTooltip(client,
                Component.literal("甲".repeat(24) + " " + "乙".repeat(8)));
        check(plainText(spaced.getFirst()).equals("甲".repeat(24)), "Overflow wraps at the last ordinary space");
        check(plainText(spaced.getLast()).equals("乙".repeat(8)), "Breaking space is not retained at the next line start");
    }

    private static String plainText(FormattedCharSequence sequence) {
        StringBuilder text = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            return true;
        });
        return text.toString();
    }

    private static List<?> modeControls(BoldTextFixConfigScreen screen) throws Exception {
        return (List<?>) field(BoldTextFixConfigScreen.class, "modeControls").get(screen);
    }

    static void checkFrame(BoldTextFixConfigScreen screen) throws Exception {
        int margin = field(BoldTextFixConfigScreen.class, "SCREEN_MARGIN").getInt(null);
        int titleTop = field(BoldTextFixConfigScreen.class, "HEADER_TOP").getInt(null) + 4;
        int titleRight = margin + Minecraft.getInstance().font.width(screen.getTitle());
        int separator = field(BoldTextFixConfigScreen.class, "headerLineY").getInt(screen);
        List<?> modes = modeControls(screen);
        AbstractWidget previous = (AbstractWidget) modes.getFirst();
        AbstractWidget next = (AbstractWidget) modes.getLast();
        check(previous.getY() > titleTop + Minecraft.getInstance().font.lineHeight
                        || previous.getX() >= titleRight + 4,
                "Header selector never overlaps the full title and version");
        check(next.getRight() == screen.width - margin && next.getBottom() < separator,
                "Mode controls stay at the top right above the settings separator");
        Button master = (Button) field(BoldTextFixConfigScreen.class, "enabledToggle").get(screen);
        Button done = (Button) field(BoldTextFixConfigScreen.class, "doneButton").get(screen);
        check(master.getX() == margin && done.getRight() == screen.width - margin
                        && master.getY() == screen.height - 24 && done.getY() == master.getY(),
                "Footer buttons stay anchored to the two screen edges");
        check(master.getRight() + 8 <= done.getX() && master.active && done.active,
                "Footer buttons stay separated and usable");
        check(screen.getChildAt(master.getX() + 2, master.getY() + 2).orElse(null) == master,
                "Fixed footer toggle remains reachable outside the scroll viewport");
        check(screen.getChildAt(previous.getX() + 2, previous.getY() + 2).orElse(null) == previous,
                "Fixed header controls remain reachable outside the scroll viewport");
        for (Object row : (List<?>) field(BoldTextFixConfigScreen.class, "rows").get(screen)) {
            List<?> controls = (List<?>) method(row.getClass(), "controls").invoke(row);
            Component label = (Component) method(row.getClass(), "label").invoke(row);
            check(!controls.contains(master) && !controls.contains(previous)
                            && !label.getString().equals("screen.boldtextfix.mode.label")
                            && !label.getString().equals("screen.boldtextfix.setting.enabled"),
                    "Master and mode rows have been removed from the scrolling settings");
        }
    }

    private static void checkLayout(BoldTextFixConfigScreen screen) throws Exception {
        checkFrame(screen);
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.visible) {
                check(widget.getX() >= 0 && widget.getRight() <= screen.width
                        && widget.getY() >= 0 && widget.getBottom() <= screen.height, "Visible widget inside screen");
            }
        }
        int listTop = field(BoldTextFixConfigScreen.class, "listTop").getInt(screen);
        int listBottom = field(BoldTextFixConfigScreen.class, "listBottom").getInt(screen);
        int header = field(BoldTextFixConfigScreen.class, "headerLineY").getInt(screen);
        double offset = field(BoldTextFixConfigScreen.class, "scrollAmount").getDouble(screen);
        EditBox edit = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
        int boldTop = field(BoldTextFixConfigScreen.class, "boldPreviewTop").getInt(screen);
        int boldHeight = field(BoldTextFixConfigScreen.class, "boldPreviewHeight").getInt(screen);
        int normalTop = field(BoldTextFixConfigScreen.class, "normalPreviewTop").getInt(screen);
        int previousBottom = 0;
        int previousTop = -1;
        int visibleRows = 0;
        List<?> rows = (List<?>) field(BoldTextFixConfigScreen.class, "rows").get(screen);
        int rowIndex = 0;
        check(listTop == header + 1 && listBottom == edit.getY() - 4, "Viewport is bounded by the two separators");
        check(offset >= 0 && offset <= Math.max(0, rows.size() * 20 - (listBottom - listTop)), "Pixel scroll is clamped");
        for (Object row : rows) {
            List<?> controls = (List<?>) method(row.getClass(), "controls").invoke(row);
            AbstractWidget first = (AbstractWidget) controls.getFirst();
            check(first.getY() == listTop + rowIndex++ * 20 - (int) Math.floor(offset) + 1,
                    "Rows retain pixel scroll offset without snapping");
            check(first.visible == (first.getY() < listBottom && first.getBottom() > listTop),
                    "Any intersecting row remains visible, including partial controls");
            if (!first.visible) continue;
            visibleRows++;
            check(first.getY() > previousBottom, "Adjacent setting rows do not overlap");
            if (previousTop >= 0) check(first.getY() - previousTop <= 20, "Setting rows use compact spacing");
            previousTop = first.getY();
            for (Object value : controls) {
                AbstractWidget control = (AbstractWidget) value;
                check(control.getY() == first.getY() && control.getHeight() >= 18, "Each row preserves control alignment and height");
                check(Math.min(control.getBottom(), listBottom) < edit.getY(), "Clipped settings remain above editor");
                if (control.getBottom() > listBottom) {
                    check(screen.getChildAt(control.getX() + 1, listBottom).orElse(null) != control,
                            "Clipped lower portion cannot receive mouse events");
                }
                if (control.getY() < listTop) {
                    check(screen.getChildAt(control.getX() + 1, listTop - 1).orElse(null) != control,
                            "Clipped upper portion cannot receive mouse events");
                }
                previousBottom = Math.max(previousBottom, control.getBottom());
            }
        }
        check(visibleRows > 0, "Viewport contains settings");
        var previewLabel = (AbstractWidget) field(BoldTextFixConfigScreen.class, "previewLabel").get(screen);
        check(boldTop - edit.getBottom() >= 1 && boldTop - edit.getBottom() <= 4,
                "Editor is within one to four pixels of bold preview, allowing wrapped label padding");
        check(previewLabel.getBottom() + 3 <= boldTop, "Help-label border does not overlap bold text");
        check(edit.getX() - (previewLabel.getRight() + 4) == 4,
                "Editor follows the actual colored border with a four-pixel gap");
        check(edit.getWidth() >= field(BoldTextFixConfigScreen.class, "controlWidth").getInt(screen),
                "Editor expands into the previously unused horizontal space");
        check(boldTop + boldHeight == normalTop, "Bold and normal preview regions are adjacent");
        int normalHeight = field(BoldTextFixConfigScreen.class, "PREVIEW_HEIGHT").getInt(null);
        int baselineGap = boldHeight - boldHeight / 2 + normalHeight / 2;
        if (BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.CUSTOM_FONT || BoldTextFixConfig.fontSize() <= 10) {
            check(baselineGap <= 13, "Normal-size previews have at most 13 pixels between text baselines");
        } else {
            check(boldHeight > 14, "Larger custom fonts automatically expand the preview upward");
        }
        check(normalTop + normalHeight <= screen.height - 24, "Previews above footer");
        check(normalTop == screen.height - 46, "All modes use the reclaimed direction-row space");
    }

    private static void checkScrolling(BoldTextFixConfigScreen screen) throws Exception {
        Field offset = field(BoldTextFixConfigScreen.class, "scrollAmount");
        int listTop = field(BoldTextFixConfigScreen.class, "listTop").getInt(screen);
        int listBottom = field(BoldTextFixConfigScreen.class, "listBottom").getInt(screen);
        int left = field(BoldTextFixConfigScreen.class, "panelLeft").getInt(screen);
        int width = field(BoldTextFixConfigScreen.class, "panelWidth").getInt(screen);
        int maximum = (int) method(BoldTextFixConfigScreen.class, "maxScroll").invoke(screen);
        if (maximum == 0) return;
        offset.setDouble(screen, 0);
        method(BoldTextFixConfigScreen.class, "positionWidgets").invoke(screen);
        check(screen.mouseScrolled(left + 20, listTop + 8, 0, -0.1), "Fractional wheel input is accepted");
        check(offset.getDouble(screen) == 1, "Fractional wheel moves one pixel");
        screen.mouseScrolled(left + 20, listTop + 8, 0, -0.9);
        check(offset.getDouble(screen) == 10, "One wheel notch moves half a row");
        Object firstRow = ((List<?>) field(BoldTextFixConfigScreen.class, "rows").get(screen)).getFirst();
        AbstractWidget firstControl = (AbstractWidget) ((List<?>) method(firstRow.getClass(), "controls").invoke(firstRow)).getFirst();
        check(firstControl.visible && firstControl.getY() < listTop && firstControl.getBottom() > listTop,
                "A partially exposed setting remains visible");
        check(screen.getChildAt(firstControl.getX() + 1, listTop).orElse(null) == firstControl,
                "Visible portion remains interactive");
        checkLayout(screen);
        int thumbTop = (int) method(BoldTextFixConfigScreen.class, "scrollbarTop").invoke(screen);
        MouseButtonInfo leftButton = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);
        var press = new MouseButtonEvent(left + width - 5, thumbTop + 2, leftButton);
        check(screen.mouseClicked(press, false), "Scrollbar starts dragging");
        double beforeDrag = offset.getDouble(screen);
        check(Math.abs(beforeDrag - 10) < 2, "Grabbing scrollbar does not center-jump");
        screen.mouseDragged(new MouseButtonEvent(press.x(), press.y() + 1, leftButton), 0, 1);
        double afterDrag = offset.getDouble(screen);
        check(afterDrag > beforeDrag && afterDrag - beforeDrag < 20, "One-pixel scrollbar movement does not jump a row");
        screen.mouseReleased(new MouseButtonEvent(press.x(), press.y() + 1, leftButton));
        check(!field(BoldTextFixConfigScreen.class, "draggingScrollbar").getBoolean(screen), "Scrollbar stops on release");
        check(offset.getDouble(screen) == afterDrag, "Releasing scrollbar does not snap to a row");
        screen.mouseScrolled(left + 20, listTop + 8, 0, -10000);
        check(offset.getDouble(screen) == maximum, "Wheel reaches exact content bottom");
        checkLayout(screen);
        double atBottom = offset.getDouble(screen);
        screen.mouseScrolled(left + 20, listBottom + 1, 0, 1);
        check(offset.getDouble(screen) == atBottom, "Wheel below viewport does not move settings");
        screen.mouseScrolled(left + 20, listTop + 8, 0, 10000);
        check(offset.getDouble(screen) == 0, "Wheel reaches exact content top");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name) throws Exception {
        Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static final class PlainGlyph implements BakedGlyph {
        public GlyphInfo info() { return GlyphInfo.simple(6); }
        public TextRenderable.Styled createGlyph(float positionX, float positionY, int color, int shadowColor,
                Style style, float boldOffset, float shadowOffset) { return null; }
    }
}
