import com.google.gson.JsonParser;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.LocalBoldFont;
import dev.yecairen.boldtextfix.client.BoldTextFixConfigScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

final class FontCatalogProbe {
    private static int assertions;

    static void run(Path fixture) throws Exception {
        byte[] original = Files.readAllBytes(fixture);
        LocalBoldFont.Statistics stats = LocalBoldFont.inspect(original);
        check(stats.characters() == 2 && stats.glyphs() == 3, "Unmapped .notdef counts as glyph, not character");
        Path mappedDir = Files.createDirectory(fixture.getParent().resolve("catalog-fixture"));
        byte[] mapped = Files.readAllBytes(CffFixture.create(mappedDir, 65, 66, 0x1F601));
        stats = LocalBoldFont.inspect(mapped);
        check(stats.characters() == 4 && stats.glyphs() == 3,
                "Unicode aliases count separately; supplementary Emoji counts once; glyph count is independent");
        var ttf = LocalBoldFont.inspect(BoldFontFiles.read(ProbeEnvironment.trueTypeFont()));
        check(ttf.characters() > 100 && ttf.glyphs() > 100, "Real TTF provides counts");
        try (var font = LocalBoldFont.open(BoldFontFiles.read(ProbeEnvironment.trueTypeFont()), 10, 8)) {
            check(ttf.characters() == font.getSupportedGlyphs().size(), "TTF count matches game Unicode coverage");
        }
        Path valid = BoldFontFiles.directory().resolve("catalog-valid.otf");
        Path broken = BoldFontFiles.directory().resolve("catalog-broken.ttf");
        Path oversized = BoldFontFiles.directory().resolve("catalog-oversized.otf");
        Files.write(valid, original);
        Files.write(broken, ByteBuffer.allocate(12).putInt(0x00010000).array());
        try (FileChannel file = FileChannel.open(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(BoldFontFiles.MAX_FILE_BYTES);
            file.write(ByteBuffer.wrap(new byte[]{0}));
        }
        BoldFontFiles.inspectFiles(BoldFontFiles.list(), false);
        awaitInspection();
        var validFile = file(valid);
        var details = BoldFontFiles.details(validFile);
        check(!details.invalid() && details.characters() == 2 && details.glyphs() == 3, "Async valid counts");
        check(BoldFontFiles.details(file(broken)).invalid(), "Broken font flagged");
        check(BoldFontFiles.details(file(oversized)).invalid(), "Oversize file flagged without loading whole payload");
        BoldFontFiles.inspectFiles(BoldFontFiles.list(), false);
        check(BoldFontFiles.details(validFile) == details, "Unchanged scan reuses cached inspection");
        Files.write(valid, mapped);
        BoldFontFiles.inspectFiles(BoldFontFiles.list(), false);
        awaitInspection();
        check(BoldFontFiles.details(file(valid)).characters() == 4, "Replaced file gets new counts");
        check(BoldFontFiles.details(validFile).characters() == -1, "Stale fingerprint evicted");

        Language previous = Language.getInstance();
        Map<String, String> translations = new HashMap<>();
        try (var input = FontCatalogProbe.class.getResourceAsStream("/assets/boldtextfix/lang/zh_cn.json")) {
            Language.loadFromJson(input, translations::put);
        }
        Language.inject(new Language() {
            public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, previous.getOrDefault(key, fallback)); }
            public boolean has(String key) { return translations.containsKey(key) || previous.has(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return previous.getVisualOrder(text); }
        });
        try {
            BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.CUSTOM_FONT);
            BoldTextFixConfig.setEnabled(true);
            BoldTextFixConfig.setBoldFont(valid.getFileName().toString());
            BoldTextFixConfigScreen screen = new BoldTextFixConfigScreen(null);
            screen.width = 480;
            screen.height = 270;
            init(screen);
            awaitInspection();
            screen.tick();
            check(CustomBoldFonts.status().getString().equals("已选用：catalog-valid.otf 渲染粗字体，字符：4。字形：3。"),
                    "Selected status uses actual counts and filename");
            AbstractWidget preview = (AbstractWidget) field(BoldTextFixConfigScreen.class, "previewLabel").get(screen);
            check(preview instanceof MultiLineTextWidget && preview.getMessage().getString().equals("预览与说明"),
                    "Preview explanation is text, not an image");
            EditBox editor = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
            check(preview.getRight() + 4 < editor.getX() && preview.getHeight() + 6 <= 24, "Preview hover label and background fit beside editor");
            String explanation = tooltip(preview).getString();
            check(explanation.equals("如果预览文本出现mc原版字体： 1.粗字体文件/字体资源包缺少对应字符。 2.未选用粗字体文件/字体资源包。 3.选用的粗字体文件/字体资源包出现问题。"),
                    "Preview explanation uses requested spaces with automatic wrapping");
            Button chosen = fontButton(screen, valid.getFileName().toString());
            check(chosen.getMessage().getString().equals(valid.getFileName().toString()),
                    "Selected filename no longer includes the old checkmark");
            Component chosenHint = tooltip(chosen);
            check(chosenHint.getString().contains("字符：4。 字形：3。"), "File tooltip displays actual counts");
            check(chosenHint.getSiblings().getFirst().getStyle().getColor().getValue() == 0x55FF55,
                    "Selected tooltip title is green");
            chosen.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            check(BoldTextFixConfig.boldFont().isEmpty() && CustomBoldFonts.status().getString().equals("待选择TTF/OTF……"),
                    "Selected file still toggles off and displays new empty selection text");
            check(tooltip(fontButton(screen, valid.getFileName().toString())).getString().startsWith("文件名： catalog-valid.otf "),
                    "Unselected tooltip identifies the filename explicitly");
            Button invalid = fontButton(screen, broken.getFileName().toString());
            check(field(invalid.getClass().getSuperclass(), "warning").getBoolean(invalid), "Invalid row uses warning background");
            check(invalid.getMessage().getStyle().getColor().getValue() != 0xFF5555,
                    "Invalid filename is not colored red");
            check(tooltip(invalid).getSiblings().stream().anyMatch(part ->
                            part.getString().equals("文件异常，可能无法加载字体！")
                                    && part.getStyle().getColor() != null
                                    && part.getStyle().getColor().getValue() == 0xFF5555),
                    "Invalid row explains file problem with a red exclamation message");
            invalid.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            check(CustomBoldFonts.status().getString().equals("无法加载catalog-broken.ttf ！")
                    && CustomBoldFonts.status().getStyle().getColor().getValue() == 0xFF5555, "Load failure is visibly red");
            Component status = ((AbstractWidget) field(BoldTextFixConfigScreen.class, "fontStatus").get(screen)).getMessage();
            check(status.getStyle().getColor().getValue() == 0xFF5555, "Status widget retains text color");

            Files.write(broken, original);
            Button refresh = refreshButton(screen);
            check(tooltip(refresh).getString().equals("刷新列表"), "Refresh icon tooltip");
            editor = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
            editor.setValue("刷新不丢失预览 Aa");
            refresh.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            awaitInspection();
            screen.tick();
            check(!field(invalid.getClass().getSuperclass(), "warning").getBoolean(fontButton(screen, broken.getFileName().toString())),
                    "Refresh clears warning after file is repaired");
            check(CustomBoldFonts.status().getString().contains("字符：2。字形：3。"), "Repaired file loads and updates status");
            check(((EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen)).getValue().equals("刷新不丢失预览 Aa"),
                    "Refresh preserves preview input");

            Files.delete(broken);
            CustomBoldFonts.refresh(Minecraft.getInstance());
            screen.tick();
            check(CustomBoldFonts.status().getString().equals("找不到catalog-broken.ttf ！")
                    && CustomBoldFonts.status().getStyle().getColor().getValue() == 0xFF5555,
                    "Deleted selected file reports missing in red");
            String existingSelection = BoldTextFixConfig.boldFont();
            Path rejected = fixture.getParent().resolve("rejected.ttf");
            Files.write(rejected, new byte[12]);
            screen.onFilesDrop(List.of(rejected));
            check(BoldTextFixConfig.boldFont().equals(existingSelection), "Bad import does not replace selection");
            screen.onFilesDrop(List.of(fixture));
            check(BoldTextFixConfig.boldFont().equals(existingSelection), "Valid drop preserves even a missing selection");
            check(screen.children().stream().filter(child -> child instanceof AbstractWidget)
                    .map(child -> ((AbstractWidget) child).getMessage().getString())
                    .noneMatch(text -> text.contains("字体导入：")), "No import count banner");
            BoldTextFixConfig.setEnabled(false);
            init(screen);
            check(!refreshButton(screen).active, "Disabled mod locks refresh icon");
            check(((Button) field(BoldTextFixConfigScreen.class, "doneButton").get(screen)).active, "Done remains usable");
            check(((Button) field(BoldTextFixConfigScreen.class, "enabledToggle").get(screen)).active, "Master toggle remains usable");
            Button master = (Button) field(BoldTextFixConfigScreen.class, "enabledToggle").get(screen);
            check(master.getMessage().getString().equals("模组已禁用")
                            && master.getMessage().getStyle().getColor().getValue() == 0xFF5555,
                    "Disabled footer switch uses the requested red status");
            check(tooltip(master).getString().equals("决定着本模组是否生效"), "Footer switch keeps the requested explanation");
            master.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            check(master.getMessage().getString().equals("模组已开启")
                            && master.getMessage().getStyle().getColor().getValue() == 0x55FF55,
                    "Enabled footer switch uses the requested green status");

            Method previewLeft = BoldTextFixConfigScreen.class.getDeclaredMethod("previewTextLeft", String.class);
            previewLeft.setAccessible(true);
            for (String language : new String[]{"zh_cn", "zh_tw", "zh_hk", "lzh", "en_us", "ja_jp", "ko_kr"}) {
                translations.clear();
                try (var stream = FontCatalogProbe.class.getResourceAsStream("/assets/boldtextfix/lang/" + language + ".json")) {
                    Language.loadFromJson(stream, translations::put);
                }
                for (var mode : BoldTextFixConfig.RepairMode.values()) {
                    BoldTextFixConfig.setMode(mode);
                    BoldTextFixConfig.setEnabled(true);
                    for (int width : new int[]{320, 480, 960}) {
                        screen.width = width;
                        init(screen);
                        CustomFontUiProbe.checkFrame(screen);
                        AbstractWidget label = (AbstractWidget) field(BoldTextFixConfigScreen.class, "previewLabel").get(screen);
                        EditBox input = (EditBox) field(BoldTextFixConfigScreen.class, "previewInput").get(screen);
                        check(label.getMessage().getString().equals(translations.get("screen.boldtextfix.preview.label"))
                                && input.getX() - (label.getRight() + 4) == 4,
                                "Every language and mode anchors the editor to the visible colored border");
                        int panelLeft = field(BoldTextFixConfigScreen.class, "panelLeft").getInt(screen);
                        int panelWidth = field(BoldTextFixConfigScreen.class, "panelWidth").getInt(screen);
                        check(input.getRight() == panelLeft + panelWidth - 14 && input.getWidth() > 0,
                                "Expanded editor keeps its right edge inside the panel");
                        check(!tooltip(label).getString().isBlank(), "Every mode retains explanation");
                        for (String key : new String[]{"screen.boldtextfix.preview.bold", "screen.boldtextfix.preview.normal"}) {
                            String text = Component.translatable(key).getString();
                            int start = (int) previewLeft.invoke(screen, text);
                            int labelEnd = panelLeft + 8 + Minecraft.getInstance().font.width(text);
                            int glyphWidth = Minecraft.getInstance().font.width(text.substring(0, text.offsetByCodePoints(0, 1)));
                            check(start - labelEnd == Math.max(1, glyphWidth),
                                    "Each translated preview row leaves exactly one label-glyph width");
                        }
                    }
                }
            }
        } finally {
            Language.inject(previous);
            CustomBoldFonts.close();
        }
        check(((Map<?, ?>) field(BoldFontFiles.class, "DETAILS").get(null)).isEmpty(), "Closing releases metadata cache");
        System.out.println("FONT_CATALOG_UI_ASSERTIONS_PASSED=" + assertions);
    }

    private static BoldFontFiles.FontFile file(Path path) throws Exception {
        return BoldFontFiles.list().stream().filter(file -> file.name().equals(path.getFileName().toString())).findFirst().orElseThrow();
    }

    private static void awaitInspection() throws Exception {
        CompletableFuture<?>[] tasks;
        synchronized (BoldFontFiles.class) {
            Map<?, ?> cache = (Map<?, ?>) field(BoldFontFiles.class, "DETAILS").get(null);
            tasks = cache.values().toArray(CompletableFuture<?>[]::new);
        }
        CompletableFuture.allOf(tasks).get(20, TimeUnit.SECONDS);
    }

    private static Button fontButton(BoldTextFixConfigScreen screen, String name) throws Exception {
        for (Object button : (List<?>) field(BoldTextFixConfigScreen.class, "fontButtons").get(screen)) {
            if (((BoldFontFiles.FontFile) field(button.getClass(), "file").get(button)).name().equals(name)) return (Button) button;
        }
        throw new AssertionError("Missing font button " + name);
    }

    private static Button refreshButton(BoldTextFixConfigScreen screen) {
        return (Button) screen.children().stream().filter(child -> child.getClass().getSimpleName().equals("RefreshButton"))
                .findFirst().orElseThrow();
    }

    private static Component tooltip(AbstractWidget widget) throws Exception {
        Object holder = field(AbstractWidget.class, "tooltip").get(widget);
        Method get = holder.getClass().getDeclaredMethod("get");
        get.setAccessible(true);
        Tooltip tooltip = (Tooltip) get.invoke(holder);
        return (Component) field(Tooltip.class, "message").get(tooltip);
    }

    private static void init(BoldTextFixConfigScreen screen) throws Exception {
        Method init = net.minecraft.client.gui.screens.Screen.class.getDeclaredMethod("rebuildWidgets");
        init.setAccessible(true);
        init.invoke(screen);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
