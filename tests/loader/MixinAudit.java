import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.knot.Knot;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/** Applies every declared mod Mixin to the real client classes without opening a window. */
public final class MixinAudit {
    public static void main(String[] arguments) throws Exception {
        Path jarPath = Path.of(arguments[0]).toAbsolutePath();
        Path gameDirectory = Path.of(arguments[1]).toAbsolutePath();
        System.setProperty("mixin.debug.countInjections", "true");
        var targets = new TreeSet<String>();
        try (var jar = new JarFile(jarPath.toFile())) {
            var config = JsonParser.parseReader(new java.io.InputStreamReader(
                    jar.getInputStream(jar.getJarEntry("boldtextfix.mixins.json")),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var name : config.getAsJsonArray("client")) {
                ClassNode node = new ClassNode();
                new ClassReader(jar.getInputStream(jar.getJarEntry(
                        prefix + name.getAsString() + ".class"))).accept(node, ClassReader.SKIP_CODE);
                List<AnnotationNode> annotations = new ArrayList<>();
                if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
                if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
                for (var annotation : annotations) {
                    if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                    for (int i = 0; i < annotation.values.size(); i += 2) {
                        if (!List.of("value", "targets").contains(annotation.values.get(i))) continue;
                        for (Object target : (List<?>) annotation.values.get(i + 1)) {
                            targets.add(target instanceof Type type ? type.getClassName() : target.toString());
                        }
                    }
                }
            }
        }
        if (targets.isEmpty()) throw new AssertionError("No declared Mixin targets");
        Knot knot = new Knot(EnvType.CLIENT);
        ClassLoader client = knot.init(new String[]{
                "--gameDir", gameDirectory.toString(), "--version", "26.3"});
        String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        if (!version.equals("26.3")) throw new AssertionError("Wrong Minecraft target: " + version);
        for (String target : targets) {
            Class.forName(target, false, client).getDeclaredMethods();
            System.out.println("MIXIN_TARGET_OK=" + target);
        }
        Class<?> sheet = Class.forName("net.minecraft.client.gui.font.glyphs.BakedSheetGlyph", false, client);
        Class<?> marker = Class.forName("dev.yecairen.boldtextfix.DilationBoldGlyph", false, client);
        if (!marker.isAssignableFrom(sheet)) throw new AssertionError("Bold glyph marker not injected");
        System.out.println("MINECRAFT_VERSION=" + version);
        System.out.println("FULL_FABRIC_API_PRESENT=" + FabricLoader.getInstance().isModLoaded("fabric-api"));
        System.out.println("MIXIN_TARGETS_PASSED=" + targets.size());
    }
}
