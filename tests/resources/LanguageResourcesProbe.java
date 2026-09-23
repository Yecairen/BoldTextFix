import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.knot.Knot;

/** Exercises Minecraft's resource-pack discovery and language loader with only the packaged mod. */
public final class LanguageResourcesProbe {
    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]).toAbsolutePath();
        Path gameDirectory = Path.of(args[1]).toAbsolutePath();
        boolean expectMissing = args.length > 2 && args[2].equals("--expect-missing");
        ClassLoader client = new Knot(EnvType.CLIENT).init(new String[]{
                "--gameDir", gameDirectory.toString(), "--version", "26.3"});
        Thread.currentThread().setContextClassLoader(client);
        var loader = FabricLoader.getInstance();
        Set<String> allowed = Set.of("minecraft", "java", "fabricloader", "mixinextras", "boldtextfix",
                "fabric-api-base", "fabric-key-mapping-api-v1", "fabric-lifecycle-events-v1",
                "fabric-resource-loader-v1");
        for (var mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            if (!allowed.contains(id)) throw new AssertionError("Unexpected mod in isolated test: " + id);
        }
        System.out.println("ISOLATED_MODS=" + loader.getAllMods().stream()
                .map(mod -> mod.getMetadata().getId()).sorted().toList());
        type(client, "net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        type(client, "net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        Class<?> validatorType = type(client, "net.minecraft.world.level.validation.DirectoryValidator");
        Object validator = validatorType.getConstructor(PathMatcher.class)
                .newInstance((PathMatcher) path -> false);
        Path assets = Files.createDirectories(gameDirectory.resolve("assets"));
        Object source = type(client, "net.minecraft.client.resources.ClientPackSource")
                .getConstructor(Path.class, validatorType).newInstance(assets, validator);
        Class<?> sourceType = type(client, "net.minecraft.server.packs.repository.RepositorySource");
        Object sources = Array.newInstance(sourceType, 1);
        Array.set(sources, 0, source);
        Class<?> repositoryType = type(client, "net.minecraft.server.packs.repository.PackRepository");
        Object repository = repositoryType.getConstructor(sources.getClass()).newInstance(sources);
        Class<?> packType = type(client, "net.minecraft.server.packs.PackType");
        Object clientResources = packType.getField("CLIENT_RESOURCES").get(null);
        Class<?> managerType = type(client, "net.minecraft.server.packs.resources.MultiPackResourceManager");
        Class<?> resourceManagerType = type(client, "net.minecraft.server.packs.resources.ResourceManager");
        Class<?> languageType = type(client, "net.minecraft.client.resources.language.ClientLanguage");
        Class<?> languageBase = type(client, "net.minecraft.locale.Language");
        Class<?> componentType = type(client, "net.minecraft.network.chat.Component");
        var load = languageType.getMethod("loadFrom", resourceManagerType, List.class, boolean.class);
        var translate = languageType.getMethod("getOrDefault", String.class, String.class);
        var has = languageType.getMethod("has", String.class);
        int checked = 0;
        try (var jar = new JarFile(artifact.toFile())) {
            // Reload the repository twice and switch language through the real client loader.
            for (int reload = 1; reload <= 2; reload++) {
                repositoryType.getMethod("reload").invoke(repository);
                System.out.println("AVAILABLE_PACKS=" + repositoryType.getMethod("getAvailableIds").invoke(repository));
                List<?> packs = (List<?>) repositoryType.getMethod("openAllSelected").invoke(repository);
                Object manager = managerType.getConstructor(packType, List.class).newInstance(clientResources, packs);
                try {
                    System.out.println("RESOURCE_NAMESPACES=" + managerType.getMethod("getNamespaces").invoke(manager));
                    for (String code : expectMissing ? List.of("en_us")
                            : List.of("en_us", "zh_cn", "zh_tw", "zh_hk", "lzh", "ja_jp", "ko_kr")) {
                        Object language = load.invoke(null, manager,
                                code.equals("en_us") ? List.of(code) : List.of("en_us", code), false);
                        languageBase.getMethod("inject", languageBase).invoke(null, language);
                        Object title = componentType.getMethod("translatable", String.class)
                                .invoke(null, "screen.boldtextfix.preview.label");
                        String shown = (String) componentType.getMethod("getString").invoke(title);
                        System.out.println("TRANSLATED_PREVIEW[" + code + "]=" + shown);
                        if (expectMissing) {
                            if ((boolean) has.invoke(language, "screen.boldtextfix.preview.label")) {
                                throw new AssertionError("Expected the old artifact to miss mod translations");
                            }
                            continue;
                        }
                        var entry = jar.getJarEntry("assets/boldtextfix/lang/" + code + ".json");
                        try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                            var expected = JsonParser.parseReader(reader).getAsJsonObject();
                            for (var text : expected.entrySet()) {
                                String actual = (String) translate.invoke(language, text.getKey(), "<missing>");
                                if (!text.getValue().getAsString().equals(actual)) {
                                    throw new AssertionError(code + " " + text.getKey() + ": " + actual);
                                }
                                checked++;
                            }
                            if (!shown.equals(expected.get("screen.boldtextfix.preview.label").getAsString())) {
                                throw new AssertionError("Component did not use the active language: " + code);
                            }
                            System.out.println("LANGUAGE_OK=" + code + " keys=" + expected.size() + " reload=" + reload);
                        }
                    }
                } finally {
                    managerType.getMethod("close").invoke(manager);
                }
            }
        }
        System.out.println(expectMissing ? "MISSING_LANGUAGE_RESOURCES_REPRODUCED"
                : "LANGUAGE_RESOURCE_CHECKS_PASSED=" + checked);
    }

    private static Class<?> type(ClassLoader client, String name) throws ClassNotFoundException {
        return Class.forName(name, true, client);
    }
}
