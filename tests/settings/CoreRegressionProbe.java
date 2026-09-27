import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.yecairen.boldtextfix.BoldTextFixConfig;
import dev.yecairen.boldtextfix.FontFixPolicy;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.minecraft.network.chat.Style;

/** No game window or synthetic screenshots: packaged policy/config/queue regression tests. */
public final class CoreRegressionProbe {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path evidence = Path.of(args[0]);
        Files.createDirectories(evidence);
        Path isolated = Files.createDirectory(evidence.resolve("isolated-" + UUID.randomUUID()));
        GameProvider provider = (GameProvider) Proxy.newProxyInstance(GameProvider.class.getClassLoader(),
                new Class<?>[]{GameProvider.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getLaunchDirectory")) return isolated;
                    throw new UnsupportedOperationException(method.getName());
                });
        FabricLoaderImpl.INSTANCE.setGameProvider(provider);
        try (var version = CoreRegressionProbe.class.getClassLoader().getResourceAsStream("version.json")) {
            check(version != null, "Minecraft version descriptor available");
            check(JsonParser.parseString(new String(version.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().get("id").getAsString().equals("26.3"), "Tests use Minecraft 26.3");
        }
        SettingsRegressionProbe.run(isolated);
        DilationProgressProbe.run();
        checkVanillaPolicy();
        checkTranslations(Path.of(args[1]));
        dev.yecairen.boldtextfix.GlyphDiskCache.close();
        String report = "PASS Minecraft 26.3 packaged core regression\n"
                + "PASS fixed 50 ms completion-to-next-start cooldown and reset/close behavior\n"
                + "PASS completion timer, 8-second notice and hidden-notice behavior\n"
                + "PASS legacy dilationLimited values ignored, removed only on explicit save\n"
                + "PASS vanilla bold bypass in offset/dilation at all tested strengths\n"
                + "PASS third-party glyphs retain configured synthetic-bold behavior\n"
                + "PASS all seven language files packaged with six lines and yellow emphasis\n"
                + "PASS version 1.3.1+mc26.3 and Minecraft dependency 26.3\n"
                + "Policy/resource assertions: " + assertions + "\n"
                + "This is a standalone regression test, not a launched Minecraft client or visual verification.\n";
        Files.writeString(evidence.resolve("result.txt"), report);
        System.out.print(report);
    }
    private static void checkVanillaPolicy() {
        for (var mode : BoldTextFixConfig.RepairMode.values()) {
            BoldTextFixConfig.setMode(mode);
            for (float strength : new float[]{0, 0.26F, 0.5F, 1.0F}) {
                BoldTextFixConfig.setOffsetStrength(strength);
                for (boolean enabled : new boolean[]{true, false}) {
                    BoldTextFixConfig.setEnabled(enabled);
                    for (boolean bold : new boolean[]{true, false}) {
                        Style style = Style.EMPTY.withBold(bold);
                        boolean handle = FontFixPolicy.shouldHandleCustomBold(style, true);
                        check(!handle, "Vanilla glyph bypasses synthetic bold");
                        check(!FontFixPolicy.shouldUseDilation(style, true), "Vanilla never uses dilation");
                        FontFixPolicy.beginGlyphRender(handle, strength, mode == BoldTextFixConfig.RepairMode.OFFSET);
                        FontFixPolicy.beginRenderChar();
                        check(!FontFixPolicy.isSecondaryBoldPass(bold), "Vanilla first draw unmodified");
                        check(!FontFixPolicy.isSecondaryBoldPass(bold), "Mod does not intercept vanilla second draw");
                        FontFixPolicy.endRenderChar();
                        FontFixPolicy.endGlyphRender(handle);
                        check(FontFixPolicy.shouldHandleCustomBold(style, false)
                                == (enabled && bold && mode != BoldTextFixConfig.RepairMode.CUSTOM_FONT),
                                "Resource-pack glyph handling retained");
                    }
                    check(!FontFixPolicy.shouldHandleCustomBold(null, true), "Null style safe");
                }
            }
        }
    }
    private static void checkTranslations(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            JsonObject metadata = read(zip, "fabric.mod.json");
            check(metadata.get("version").getAsString().equals("1.3.1+mc26.3"), "Release version");
            check(metadata.getAsJsonObject("depends").get("minecraft").getAsString().equals("26.3"), "Game dependency");
            for (String locale : List.of("zh_cn", "zh_tw", "zh_hk", "en_us", "ja_jp", "ko_kr", "lzh")) {
                JsonObject language = read(zip, "assets/boldtextfix/lang/" + locale + ".json");
                check(language.keySet().stream().noneMatch(k -> k.startsWith("screen.boldtextfix.efficiency")),
                        "Removed efficiency options in " + locale);
                String tooltip = language.get("screen.boldtextfix.preview.missing_glyph.tooltip").getAsString();
                String[] lines = tooltip.split("\\n", -1);
                check(lines.length == 6, "Six explicit lines in " + locale);
                for (int i = 1; i <= 3; i++) check(lines[i].startsWith(i + "."), "Numbered reason in " + locale);
                check(lines[4].startsWith("§e") && lines[5].startsWith("§e")
                        && lines[5].endsWith("§r"), "Yellow emphasis and reset in " + locale);
                if (!locale.equals("zh_cn")) continue;
                String expected = "如果预览文本出现mc原版字符：\n1.粗字体文件/字体资源包缺少对应字符。\n"
                        + "2.未选用粗字体文件/字体资源包。\n3.选用的粗字体文件/字体资源包出现问题。\n"
                        + "§e强调说明：\n§eBoldTextFix的配置调节不会影响mc原版粗字体。§r";
                check(language.get("screen.boldtextfix.preview.missing_glyph.tooltip").getAsString().equals(expected),
                        "Explicit newlines, both yellow lines, reset and punctuation packaged");
                check(language.get("screen.boldtextfix.slider.dilation.tooltip").getAsString().equals(
                        "强度越高字越粗，强度为0px时只显示字体原始字重。 每个粗体字形首次渲染时会消耗少量性能，后续会复用本地缓存。"),
                        "Glyph/local-cache wording packaged");
            }
        }
    }
    private static JsonObject read(ZipFile zip, String path) throws Exception {
        var entry = zip.getEntry(path);
        check(entry != null, "Missing " + path);
        try (var stream = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
