import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.VanillaBoldFallback;
import dev.yecairen.boldtextfix.client.BoldTextFixConfigScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Style;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

final class SettingsUiProbe {
    private static int assertions;

    static void run(BoldTextFixConfigScreen screen) throws Exception {
        String selected = BoldTextFixConfig.boldFont();
        TrueTypeGlyphProvider provider = (TrueTypeGlyphProvider) field(CustomBoldFonts.class, "provider").get(null);
        fontButton(screen, selected).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        check(BoldTextFixConfig.boldFont().isEmpty(), "Clicking checked font deselects it");
        check(field(CustomBoldFonts.class, "provider").get(null) == null
                && field(TrueTypeGlyphProvider.class, "face").get(provider) == null,
                "Deselection immediately releases native font");
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        for (int tick = 0; tick < 45; tick++) CustomBoldFonts.tick(Minecraft.getInstance());
        method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
        check(BoldTextFixConfig.boldFont().isEmpty() && field(CustomBoldFonts.class, "provider").get(null) == null,
                "Reload, refresh and reopening keep no selection");
        checkDeselectedRendering();
        fontButton(screen, selected).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        check(BoldTextFixConfig.boldFont().equals(selected) && CustomFontProbe.localAdvance(65) != null,
                "Font can be selected again");
        TrueTypeGlyphProvider active = (TrueTypeGlyphProvider) field(CustomBoldFonts.class, "provider").get(null);
        BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.OFFSET);
        CustomBoldFonts.tick(Minecraft.getInstance());
        check(field(TrueTypeGlyphProvider.class, "face").get(active) == null,
                "Leaving custom mode releases native resources");
        var before = CustomBoldFonts.files();
        Path extra = BoldFontFiles.directory().resolve("inactive-scan.otf");
        Files.write(extra, new byte[12]);
        for (int tick = 0; tick < 45; tick++) CustomBoldFonts.tick(Minecraft.getInstance());
        check(CustomBoldFonts.files() == before, "Inactive mode does not scan the font directory");
        Files.delete(extra);
        checkSliderSteps();
        checkNotificationControls(screen);
        System.out.println("SETTINGS_UI_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkDeselectedRendering() throws Exception {
        Class<?> mixin = (Class<?>) method(CustomFontSelectionProbe.class, "concreteMixin").invoke(null);
        var constructor = mixin.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        Method select = method(mixin, "boldtextfix$selectBoldFont", int.class, Style.class, CallbackInfoReturnable.class);
        Method measure = method(mixin, "boldtextfix$measureBoldFont", int.class, Style.class, CallbackInfoReturnable.class);
        BakedGlyph thirdParty = (BakedGlyph) method(CustomFontSelectionProbe.class, "originalGlyph", boolean.class).invoke(null, false);
        mixin.getField("testSource").set(instance, new GlyphSource() {
            public BakedGlyph getGlyph(int codePoint) { return thirdParty; }
            public BakedGlyph getRandomGlyph(RandomSource random, int advance) { return thirdParty; }
        });
        VanillaFallbackProbe.loadAtlas();
        for (int codePoint : new int[]{65, 49, 0x4E2D}) {
            var glyph = new CallbackInfoReturnable<BakedGlyph>("glyph", true, thirdParty);
            var width = new CallbackInfoReturnable<Float>("width", true, thirdParty.info().getAdvance(true));
            select.invoke(instance, codePoint, Style.EMPTY.withBold(true), glyph);
            measure.invoke(instance, codePoint, Style.EMPTY.withBold(true), width);
            BakedGlyph builtin = VanillaBoldFallback.glyph(codePoint);
            check(glyph.isCancelled() && glyph.getReturnValue() == builtin && builtin != thirdParty,
                    "No selection bypasses resource-pack characters and uses vanilla glyphs");
            check(width.isCancelled() && width.getReturnValue() == builtin.info().getAdvance(true),
                    "No selection uses native bold widths");
        }
        method(CustomFontSelectionProbe.class, "checkMissingEmoji", Object.class, Method.class, Method.class,
                BakedGlyph.class, boolean.class).invoke(null, instance, select, measure, thirdParty, true);
        VanillaBoldFallback.close();
    }

    private static Button fontButton(BoldTextFixConfigScreen screen, String name) throws Exception {
        for (Object button : (List<?>) field(BoldTextFixConfigScreen.class, "fontButtons").get(screen)) {
            if (((BoldFontFiles.FontFile) field(button.getClass(), "file").get(button)).name().equals(name)) return (Button) button;
        }
        throw new AssertionError("Missing font button: " + name);
    }

    private static void checkSliderSteps() throws Exception {
        Class<?> type = Class.forName("dev.yecairen.boldtextfix.client.ConfigWidgets$SettingSlider");
        var constructor = type.getDeclaredConstructor(int.class, int.class, double.class, double.class,
                double.class, double.class, String.class, DoubleConsumer.class);
        constructor.setAccessible(true);
        var mouseSet = method(AbstractSliderButton.class, "setValue", double.class);
        var commit = method(type, "commit");
        var left = new KeyEvent(InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT, 0);
        var right = new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0);
        for (int width : new int[]{80, 240}) {
            for (double[] spec : new double[][]{{4,16,0.2,10}, {2,16,0.2,8},
                    {0,0.5,0.02,0.26}, {0,1,0.02,0.5}}) {
                double[] saved = {spec[3]};
                int[] writes = {0};
                String format = spec[2] == 0.2 ? "%.1f" : "%.2f";
                var slider = (AbstractSliderButton) constructor.newInstance(0, width, spec[0], spec[1], spec[2],
                        spec[3], format, (DoubleConsumer) value -> { saved[0] = value; writes[0]++; });
                field(AbstractSliderButton.class, "canChangeValue").setBoolean(slider, true);
                for (int direction : new int[]{1, -1}) {
                    var key = direction == 1 ? right : left;
                    check(slider.keyPressed(key), "Arrow adjusts active slider");
                    check(writes[0] == (direction == 1 ? 0 : 1), "Press defers expensive font reload");
                    slider.keyReleased(key);
                    double expected = spec[3] + (direction == 1 ? spec[2] : 0);
                    check(Math.abs(saved[0] - expected) < 0.0000001, "Keyboard changes exactly one step");
                    check(slider.getMessage().getString().equals(String.format(Locale.ROOT, format, expected)),
                            "Display uses the requested decimal places");
                }
                mouseSet.invoke(slider, (spec[3] + 0.6 * spec[2] - spec[0]) / (spec[1] - spec[0]));
                commit.invoke(slider);
                check(Math.abs(saved[0] - spec[3] - spec[2]) < 0.0000001, "Mouse rounds to nearest step");
                for (double endpoint : new double[]{0, 1}) {
                    mouseSet.invoke(slider, endpoint);
                    commit.invoke(slider);
                    slider.keyPressed(endpoint == 0 ? left : right);
                    slider.keyReleased(endpoint == 0 ? left : right);
                    check(saved[0] == (endpoint == 0 ? spec[0] : spec[1]), "Endpoints clamp");
                }
                slider.active = false;
                check(!slider.keyPressed(left), "Disabled slider rejects keyboard input");
                slider.keyReleased(left);
                check(saved[0] == spec[1], "Disabled value unchanged");
            }
            for (double[] spec : new double[][]{{4,16,0.2,10.125}, {2,16,0.2,8.375},
                    {0,0.5,0.02,0.2625}, {0,1,0.02,0.5375}}) {
                int[] writes = {0};
                double[] saved = {spec[3]};
                String format = spec[2] == 0.2 ? "%.1f" : "%.2f";
                var slider = (AbstractSliderButton) constructor.newInstance(0, width, spec[0], spec[1], spec[2],
                        spec[3], format, (DoubleConsumer) value -> { saved[0] = value; writes[0]++; });
                check(slider.getMessage().getString().equals(String.format(Locale.ROOT, format, spec[3])),
                        "Old off-grid values have compact display only");
                slider.setFocused(true);
                slider.setFocused(false);
                commit.invoke(slider);
                check(writes[0] == 0 && saved[0] == spec[3], "Opening, focus and closing never commit untouched values");
                field(AbstractSliderButton.class, "canChangeValue").setBoolean(slider, true);
                slider.keyPressed(right);
                slider.keyReleased(right);
                double units = (saved[0] - spec[0]) / spec[2];
                check(writes[0] == 1 && saved[0] > spec[3] && Math.abs(units - Math.rint(units)) < 0.000001,
                        "Only manual adjustment adopts the new step grid");
            }
        }
    }

    private static Button efficiencyControl(BoldTextFixConfigScreen screen) {
        return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getContents()
                        instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().startsWith("screen.boldtextfix.efficiency."))
                .findFirst().orElse(null);
    }

    private static void checkNotificationControls(BoldTextFixConfigScreen screen) throws Exception {
        BoldTextFixConfig.setDilationStrength(0.2625F);
        BoldTextFixConfig.setOffsetStrength(0.5375F);
        BoldTextFixConfig.setFontSize(10.125F);
        BoldTextFixConfig.setFontOversample(8.375F);
        BoldTextFixConfig.setDilationNotifications(true);
        for (var mode : BoldTextFixConfig.RepairMode.values()) {
            BoldTextFixConfig.setMode(mode);
            method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
            List<?> sliders = (List<?>) field(BoldTextFixConfigScreen.class, "sliders").get(screen);
            String[] expected = switch (mode) {
                case DILATION -> new String[]{"0.26 px"};
                case OFFSET -> new String[]{"0.54 px"};
                case CUSTOM_FONT -> new String[]{"10.1 px", "8.4×"};
            };
            check(sliders.size() == expected.length, "Real screen has expected slider count");
            for (int index = 0; index < sliders.size(); index++) {
                check(((AbstractSliderButton) sliders.get(index)).getMessage().getString().equals(expected[index]),
                        "Real screen rounds only displayed text to one or two decimal places");
            }
            Button efficiency = efficiencyControl(screen);
            check((efficiency != null) == (mode == BoldTextFixConfig.RepairMode.DILATION),
                    "Efficiency control exists only on dilation page");
            if (efficiency != null) {
                check(BoldTextFixConfig.dilationLimited(), "Efficiency starts with the limited default");
                String limited = efficiency.getMessage().getString();
                efficiency.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                check(!BoldTextFixConfig.dilationLimited() && !efficiency.getMessage().getString().equals(limited),
                        "Single button switches to full speed");
                check(efficiency.getMessage().getStyle().getColor() == null, "Full-speed text has no status color");
                efficiency.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                check(BoldTextFixConfig.dilationLimited() && efficiency.getMessage().getString().equals(limited),
                        "Single button switches back to limited rendering");
                check(efficiency.getMessage().getStyle().getColor() == null, "Limited text has no status color");
            }
            var positions = (List<?>) field(BoldTextFixConfigScreen.class, "notificationPositionButtons").get(screen);
            check(positions.size() == (mode == BoldTextFixConfig.RepairMode.DILATION ? 5 : 0),
                    "Notification controls appear only on the dilation page");
            if (mode != BoldTextFixConfig.RepairMode.DILATION) continue;
            int notificationRows = 0;
            for (Object row : (List<?>) field(BoldTextFixConfigScreen.class, "rows").get(screen)) {
                if (((List<?>) method(row.getClass(), "controls").invoke(row)).contains(positions.getFirst())) {
                    notificationRows++;
                    check(((List<?>) method(row.getClass(), "controls").invoke(row)).equals(positions),
                            "Off and all four corners share one row");
                }
            }
            check(notificationRows == 1, "Only one notification row is present");
            String[] symbols = {"\u2196", "\u2197", "\u2199", "\u2198"};
            for (int index = 0; index < positions.size(); index++) {
                Button button = (Button) positions.get(index);
                Object hint = method(FontCatalogProbe.class, "tooltip",
                        net.minecraft.client.gui.components.AbstractWidget.class).invoke(null, button);
                check(hint != null && button.active, "All five choices have tooltips and are usable");
                if (index > 0) {
                    Button previous = (Button) positions.get(index - 1);
                    check(previous.getRight() < button.getX() && previous.getY() == button.getY(),
                            "Five choices fit side by side without overlapping");
                    check(button.getMessage().getString().equals(symbols[index - 1]), "Corner symbols follow requested order");
                }
            }
            for (int index : new int[]{0, 1, 0, 2, 0, 3, 0, 4}) {
                ((Button) positions.get(index)).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                check(BoldTextFixConfig.dilationNotifications() == (index != 0), "Off disables and any corner enables notifications");
                if (index != 0) {
                    check(BoldTextFixConfig.notificationCorner().ordinal() == index - 1, "Each corner selects its location");
                }
                checkNotificationSelection(positions, index);
                for (Object value : positions) check(((Button) value).active, "Off leaves every location available");
            }
            field(BoldTextFixConfig.class, "loaded").set(null, false);
            method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
            positions = (List<?>) field(BoldTextFixConfigScreen.class, "notificationPositionButtons").get(screen);
            check(BoldTextFixConfig.dilationNotifications()
                    && BoldTextFixConfig.notificationCorner() == BoldTextFixConfig.NotificationCorner.BOTTOM_RIGHT,
                    "Chosen location survives saving and reopening");
            checkNotificationSelection(positions, 4);
            ((Button) positions.getFirst()).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            field(BoldTextFixConfig.class, "loaded").set(null, false);
            method(net.minecraft.client.gui.screens.Screen.class, "rebuildWidgets").invoke(screen);
            positions = (List<?>) field(BoldTextFixConfigScreen.class, "notificationPositionButtons").get(screen);
            check(!BoldTextFixConfig.dilationNotifications(), "Off survives saving and reopening");
            checkNotificationSelection(positions, 0);
            Button master = (Button) field(BoldTextFixConfigScreen.class, "enabledToggle").get(screen);
            master.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            check(master.active && ((Button) field(BoldTextFixConfigScreen.class, "doneButton").get(screen)).active,
                    "Master disable keeps master and Done usable");
            for (Object control : (List<?>) field(BoldTextFixConfigScreen.class, "modeControls").get(screen)) {
                check(!((net.minecraft.client.gui.components.AbstractWidget) control).active,
                        "Disabled mod also locks the relocated header selector");
            }
            for (Object value : positions) check(!((Button) value).active, "Master disable locks all five choices");
            check(!(efficiencyControl(screen)).active,
                    "Master disable also locks efficiency");
            master.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            for (Object control : (List<?>) field(BoldTextFixConfigScreen.class, "modeControls").get(screen)) {
                check(((net.minecraft.client.gui.components.AbstractWidget) control).active,
                        "Enabling the footer switch unlocks the header selector");
            }
            for (Object value : positions) check(((Button) value).active, "Master enable unlocks all choices even while notices are off");
            checkNotificationSelection(positions, 0);
            ((Button) positions.get(1)).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            method(BoldTextFixConfigScreen.class, "commitSliders").invoke(screen);
        }
        field(BoldTextFixConfig.class, "loaded").set(null, false);
        check(BoldTextFixConfig.dilationStrengthTicks() == 105 && BoldTextFixConfig.offsetStrengthTicks() == 215
                && BoldTextFixConfig.fontSize() == 10.125F && BoldTextFixConfig.fontOversample() == 8.375F,
                "Page rebuilds, unrelated toggles and slider commits preserve all four untouched values");
        BoldTextFixConfig.setNotificationCorner(BoldTextFixConfig.NotificationCorner.TOP_LEFT);
    }

    private static void checkNotificationSelection(List<?> positions, int selected) {
        for (int index = 0; index < positions.size(); index++) {
            var color = ((Button) positions.get(index)).getMessage().getStyle().getColor();
            check(color != null && color.getValue() == (index == selected ? 0x55FF55 : 0xFF5555),
                    "Selected notification choice is green and all others are red");
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
