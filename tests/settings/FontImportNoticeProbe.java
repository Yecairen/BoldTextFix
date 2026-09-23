import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.yecairen.boldtextfix.BoldFontFiles;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.client.BoldTextFixConfigScreen;
import java.io.InputStreamReader;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

final class FontImportNoticeProbe {
    private static int assertions;
    private static final Class<?> NOTICE;
    static {
        try { NOTICE = Class.forName("dev.yecairen.boldtextfix.client.FontImportNotice"); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }

    static void run(Path fixture, Font font) throws Exception {
        Field inputManager = net.minecraft.client.Minecraft.class.getDeclaredField("textInputManager");
        inputManager.setAccessible(true);
        inputManager.set(net.minecraft.client.Minecraft.getInstance(),
                new com.mojang.blaze3d.platform.TextInputManager(null) {
                    @Override public void onTextInputFocusChange(Object owner, boolean focused) { }
                });
        Field gui = net.minecraft.client.Minecraft.class.getDeclaredField("gui");
        gui.setAccessible(true);
        gui.set(net.minecraft.client.Minecraft.getInstance(), allocate(net.minecraft.client.gui.Gui.class));
        Path invalid = fixture.resolveSibling("invalid-notice.ttf");
        Files.write(invalid, new byte[12]);
        String selected = BoldFontFiles.list().getFirst().name();
        for (var mode : BoldTextFixConfig.RepairMode.values()) {
            BoldTextFixConfig.setEnabled(true);
            BoldTextFixConfig.setMode(mode);
            BoldTextFixConfig.setBoldFont(selected);
            BoldTextFixConfigScreen screen = screen();
            Object notice = field(screen, "importNotice");
            int controlsBefore = screen.children().size() - ((List<?>) field(screen, "fontButtons")).size();
            var before = BoldFontFiles.list().stream().map(BoldFontFiles.FontFile::name).toList();
            screen.onFilesDrop(List.of(fixture, invalid, ProbeEnvironment.trueTypeFont()));
            var added = BoldFontFiles.list().stream().map(BoldFontFiles.FontFile::name)
                    .filter(name -> !before.contains(name)).toList();
            check(added.size() == 2, "Both valid OTF and TTF copied; invalid file excluded");
            check(BoldTextFixConfig.mode() == mode && BoldTextFixConfig.boldFont().equals(selected),
                    "Import preserves mode and selection");
            boolean showSwitchHint = mode != BoldTextFixConfig.RepairMode.CUSTOM_FONT;
            check((boolean) field(notice, "showSwitchHint") == showSwitchHint,
                    "Import chooses the message for its original mode");
            check(new HashSet<>(names(notice)).equals(new HashSet<>(added)),
                    "Notice uses actual saved filenames, including duplicate suffixes");
            check(screen.children().size() - ((List<?>) field(screen, "fontButtons")).size() == controlsBefore,
                    "Notice registers no input listeners; imported fonts only add file buttons");
            check(!(boolean) field(notice, "presented"), "Import and widget rebuild consume no display time");
            check(opacity(notice, 100L) == 1F, "First visible frame is fully opaque");
            check(opacity(notice, 4_000_000_100L) == .5F, "Half opacity after four seconds");
            var retained = names(notice);
            screen.onFilesDrop(List.of(invalid));
            check(names(notice).equals(retained) && (boolean) field(notice, "presented"),
                    "Failed import neither claims success nor restarts the notice");

            EditBox edit = (EditBox) field(screen, "previewInput");
            check(screen.getChildAt(edit.getX() + 2, edit.getY() + 2).orElse(null) == edit,
                    "Pointer targeting still reaches preview editor");
            var click = new MouseButtonEvent(edit.getX() + 4, edit.getY() + 4, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            check(screen.mouseClicked(click, false), "Preview receives mouse click with notice active");
            edit.setValue("");
            check(screen.charTyped(new CharacterEvent('测')) && edit.getValue().equals("测"),
                    "Typing reaches preview editor with notice active");
            screen.mouseReleased(click);

            for (Object slider : (List<?>) field(screen, "sliders")) {
                check(((AbstractWidget) slider).active, "Sliders remain enabled");
                call(CustomFontUiProbe.class, "checkLayout", new Class<?>[]{BoldTextFixConfigScreen.class}, screen);
            }
            List<?> modes = (List<?>) field(screen, "modeControls");
            Button next = (Button) modes.getLast();
            check(screen.getChildAt(next.getX() + 2, next.getY() + 2).orElse(null) == next,
                    "Mode button remains reachable");
            next.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            while (BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
                ((Button) ((List<?>) field(screen, "modeControls")).getLast()).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            }
            check(field(screen, "importNotice") == notice && names(notice).equals(retained)
                    && (boolean) field(notice, "showSwitchHint") == showSwitchHint,
                    "Mode changes and widget rebuilds preserve the original notice and instructions");
            call(CustomFontUiProbe.class, "checkScrolling", new Class<?>[]{BoldTextFixConfigScreen.class}, screen);
            check(opacity(notice, 7_999_000_100L) > 0F, "Fade remains active until eight seconds");
            check(opacity(notice, 8_000_000_100L) == 0F && names(notice).isEmpty(), "Expires at eight seconds");
            BoldTextFixConfig.setMode(mode);
            call(screen, "rebuildWidgets", new Class<?>[0]);
            screen.onFilesDrop(List.of(fixture));
            check(opacity(notice, 20_000_000_000L) == 1F, "Later valid import starts a fresh eight seconds");
            screen.onFilesDrop(List.of(fixture));
            check(!(boolean) field(notice, "presented") && opacity(notice, 30_000_000_000L) == 1F,
                    "New valid import replaces the notice and restarts its timer");
        }
        BoldTextFixConfig.setMode(BoldTextFixConfig.RepairMode.OFFSET);
        BoldTextFixConfig.setEnabled(false);
        BoldTextFixConfigScreen disabled = screen();
        int beforeDisabled = BoldFontFiles.list().size();
        disabled.onFilesDrop(List.of(fixture));
        check(names(field(disabled, "importNotice")).isEmpty() && BoldFontFiles.list().size() == beforeDisabled,
                "Disabled mod retains existing import behavior");
        BoldTextFixConfig.setEnabled(true);
        checkRendering(font);
        System.out.println("FONT_IMPORT_NOTICE_ASSERTIONS_PASSED=" + assertions);
    }

    private static void checkRendering(Font font) throws Exception {
        Language original = Language.getInstance();
        Constructor<ClientLanguage> ctor = ClientLanguage.class.getDeclaredConstructor(Map.class, boolean.class);
        ctor.setAccessible(true);
        try {
            for (String language : List.of("zh_cn", "zh_tw", "zh_hk", "en_us", "ja_jp", "ko_kr", "lzh")) {
                Map<String,String> strings = new HashMap<>();
                try (var input = NOTICE.getResourceAsStream("/assets/boldtextfix/lang/" + language + ".json")) {
                    check(input != null, "Packaged language exists: " + language);
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject()
                            .entrySet().forEach(entry -> strings.put(entry.getKey(), entry.getValue().getAsString()));
                }
                Language.inject(ctor.newInstance(strings, false));
                for (int[] size : new int[][]{{320,240},{427,240},{480,270},{640,360},{854,480},{960,540}}) {
                    for (List<String> filenames : List.of(List.of("自己的字体 (2).otf"),
                            List.of("甲.ttf", "Beta.otf"), List.of("很长的字体名称".repeat(150) + ".ttf"))) {
                        for (boolean showSwitchHint : new boolean[]{false, true}) {
                        Constructor<?> noticeCtor = NOTICE.getDeclaredConstructor();
                        noticeCtor.setAccessible(true);
                        Object notice = noticeCtor.newInstance();
                        call(notice, "show", new Class<?>[]{List.class, boolean.class}, filenames, showSwitchHint);
                        RecordingGraphics graphics = allocate(RecordingGraphics.class);
                        graphics.rectangles = new ArrayList<>();
                        graphics.texts = new ArrayList<>();
                        call(notice, "extract", new Class<?>[]{GuiGraphicsExtractor.class, Font.class, int.class, int.class},
                                graphics, font, size[0], size[1]);
                        int[] background = graphics.rectangles.getFirst();
                        check(Math.abs(background[0] + background[2] - size[0]) <= 1
                                && Math.abs(background[1] + background[3] - size[1]) <= 1, "Notice is centered");
                        check(background[0] >= 0 && background[1] >= 0 && background[2] <= size[0]
                                && background[3] <= size[1], "Notice stays within screen");
                        check(graphics.strata == 1 && !graphics.texts.isEmpty(), "Notice renders above settings");
                        String text = graphics.texts.stream().map(Text::value).reduce("", String::concat);
                        String destination = strings.get("screen.boldtextfix.mode.custom_font").replaceAll("\\s", "");
                        check(text.replaceAll("\\s", "").contains(destination) == showSwitchHint,
                                "Only the long notice includes the translated destination");
                        if (!showSwitchHint && filenames.getFirst().length() < 100) {
                            String expected = strings.get("screen.boldtextfix.font.imported_custom")
                                    .formatted(String.join(", ", filenames));
                            check(text.replaceAll("\\s", "").equals(expected.replaceAll("\\s", "")),
                                    "Custom-font mode uses the translated short message");
                        }
                        checkGoldText(graphics.texts, filenames);
                        check(!text.contains("%s") && !text.contains("screen.boldtextfix"), "All placeholders resolved");
                        for (Text line : graphics.texts) {
                            check(line.color() >>> 24 == 255 && !line.shadow(), "First-frame text is fully visible");
                            check(line.x() >= background[0] && line.x() + line.width() <= background[2]
                                    && line.y() + font.lineHeight <= background[3], "Text fits notification box");
                        }
                        Field start = NOTICE.getDeclaredField("firstFrameNanos");
                        start.setAccessible(true);
                        start.setLong(notice, System.nanoTime() - 4_000_000_000L);
                        graphics.rectangles.clear();
                        graphics.texts.clear();
                        call(notice, "extract", new Class<?>[]{GuiGraphicsExtractor.class, Font.class, int.class, int.class},
                                graphics, font, size[0], size[1]);
                        int alpha = graphics.texts.getFirst().color() >>> 24;
                        check(alpha >= 124 && alpha <= 128, "Real draw colors fade after four seconds");
                        checkGoldText(graphics.texts, filenames);
                        start.setLong(notice, System.nanoTime() - 8_000_000_000L);
                        graphics.rectangles.clear();
                        graphics.texts.clear();
                        call(notice, "extract", new Class<?>[]{GuiGraphicsExtractor.class, Font.class, int.class, int.class},
                                graphics, font, size[0], size[1]);
                        check(graphics.rectangles.isEmpty() && graphics.texts.isEmpty(), "No draw calls after expiry");
                        }
                    }
                }
            }
        } finally { Language.inject(original); }
    }

    private static void checkGoldText(List<Text> lines, List<String> filenames) {
        String golden = lines.stream().map(Text::golden).reduce("", String::concat).replaceAll("\\s", "");
        String expected = String.join(", ", filenames).replaceAll("\\s", "");
        if (golden.endsWith("…")) {
            check(!golden.equals("…") && expected.startsWith(golden.substring(0, golden.length() - 1)),
                    "A shortened filename remains golden through its ellipsis");
        } else {
            check(golden.equals(expected), "Exactly the filenames are golden after wrapping");
        }
        check(lines.stream().noneMatch(Text::unexpectedColor), "Surrounding text retains its default color");
    }

    private static BoldTextFixConfigScreen screen() throws Exception {
        var screen = new BoldTextFixConfigScreen(null);
        screen.width = 480; screen.height = 270;
        call(screen, "rebuildWidgets", new Class<?>[0]);
        return screen;
    }
    private static List<?> names(Object notice) throws Exception { return (List<?>) field(notice, "importedNames"); }
    private static float opacity(Object notice, long time) throws Exception {
        return (float) call(notice, "opacity", new Class<?>[]{long.class}, time);
    }
    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object target, String name, Class<?>[] signature, Object... args) throws Exception {
        for (Class<?> type = target instanceof Class<?> c ? c : target.getClass(); type != null; type = type.getSuperclass()) {
            try { Method method = type.getDeclaredMethod(name, signature); method.setAccessible(true);
                return method.invoke(target instanceof Class<?> ? null : target, args); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); assertions++;
    }
    private record Text(String value, int x, int y, int width, int color, boolean shadow,
                        String golden, boolean unexpectedColor) { }
    private static final class RecordingGraphics extends GuiGraphicsExtractor {
        List<int[]> rectangles;
        List<Text> texts;
        int strata;
        private RecordingGraphics() { super(null, null, 0, 0); }
        @Override public void nextStratum() { strata++; }
        @Override public void fill(int x1, int y1, int x2, int y2, int color) {
            rectangles.add(new int[]{x1,y1,x2,y2,color});
        }
        @Override public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
            StringBuilder value = new StringBuilder();
            StringBuilder golden = new StringBuilder();
            boolean[] unexpectedColor = {false};
            text.accept((index, style, point) -> {
                value.appendCodePoint(point);
                if (style.getColor() != null) {
                    if (style.getColor().getValue() == 0xFFD700) golden.appendCodePoint(point);
                    else unexpectedColor[0] = true;
                }
                return true;
            });
            texts.add(new Text(value.toString(), x, y, font.width(text), color, shadow,
                    golden.toString(), unexpectedColor[0]));
        }
    }
}
