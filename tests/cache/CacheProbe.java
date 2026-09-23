import com.mojang.blaze3d.platform.NativeImage;
import dev.yecairen.boldtextfix.*;
import java.io.DataOutputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;

class CacheProbe {
    static int checks;
    static Path root;
    static final Map<String, byte[]> expected = new LinkedHashMap<>();
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        GameProvider game = (GameProvider) Proxy.newProxyInstance(GameProvider.class.getClassLoader(),
            new Class<?>[]{GameProvider.class}, (proxy, method, values) -> {
                if (method.getName().equals("getLaunchDirectory")) return root;
                throw new UnsupportedOperationException(method.getName());
            });
        FabricLoaderImpl.INSTANCE.setGameProvider(game);
        if (args[1].equals("read")) {
            for (String line : Files.readAllLines(root.resolve("expected.txt"))) {
                String[] item = line.split(" ");
                byte[] pixels = HexFormat.of().parseHex(item[1]);
                check(Arrays.equals(pixels, GlyphDiskCache.get(item[0], pixels.length)), "Cold read " + item[0]);
            }
            GlyphDiskCache.close();
            System.out.println("COLD_READ_CHECKS=" + checks);
            return;
        }
        byte[] sample = {0,5,32,100,(byte)200,(byte)255};
        save("1000", sample);
        flush(); clearMemory();
        check(Arrays.equals(sample, GlyphDiskCache.get("1000", sample.length)), "Disk roundtrip");
        flush();
        for (int kind = 0; kind < 5; kind++) {
            String key = "20" + kind;
            Files.createDirectories(path(key).getParent());
            try (var output = new DataOutputStream(Files.newOutputStream(path(key)))) {
                output.writeInt(kind == 0 ? 0 : 0x42544632);
                output.writeInt(kind == 1 ? -1 : 2);
                output.writeInt(kind == 2 ? sample.length + 1 : sample.length);
                output.write(kind == 3 ? Arrays.copyOf(sample, 2) : sample);
                if (kind == 4) output.write(9);
            }
            check(GlyphDiskCache.get(key, sample.length) == null, "Reject corruption " + kind);
            save(key, sample);
            flush(); clearMemory();
            check(Arrays.equals(sample, GlyphDiskCache.get(key, sample.length)), "Replace corruption " + kind);
            flush();
        }
        Files.setLastModifiedTime(path("1000"), FileTime.fromMillis(1000));
        clearMemory();
        check(Arrays.equals(sample, GlyphDiskCache.get("1000", sample.length)), "Reuse old mask");
        flush();
        check(Files.getLastModifiedTime(path("1000")).toMillis() > 1000, "Refresh retention age");
        Files.delete(path("1000"));
        check(Arrays.equals(sample, GlyphDiskCache.get("1000", sample.length)), "Memory reuse after disk removal");
        flush(); clearMemory();
        check(Arrays.equals(sample, GlyphDiskCache.get("1000", sample.length)), "Restore removed disk entry");
        flush();
        Class<?> ctx = Class.forName("dev.yecairen.boldtextfix.DilationMask");
        Constructor<?> constructor = ctx.getDeclaredConstructor(int.class,int.class,int.class,float.class);
        constructor.setAccessible(true);
        Method generate = DilationBoldBaker.class.getDeclaredMethod("generateAndCache", byte[].class,
                NativeImage.Format.class, ctx, String.class);
        generate.setAccessible(true);
        int index = 0;
        for (NativeImage.Format format : NativeImage.Format.values()) {
            if (format.components() <= 0) continue;
            byte[] input = new byte[9 * format.components()];
            Arrays.fill(input, (byte)190);
            Object context = constructor.newInstance(3,3,1,0.26F);
            String key = "30" + index++;
            byte[] pixels = (byte[])generate.invoke(null,input,format,context,key);
            expected.put(key,pixels);
            flush(); clearMemory();
            check(Arrays.equals(pixels,GlyphDiskCache.get(key,pixels.length)), "Generate and save " + format);
            flush();
        }
        for (boolean reset : new boolean[]{false,true}) {
            String key = reset ? "4001" : "4000";
            byte[] input = new byte[9];
            Arrays.fill(input,(byte)255);
            Object context = constructor.newInstance(3,3,1,0.26F);
            CountDownLatch ready = new CountDownLatch(1);
            DilationRenderQueue queue = new DilationRenderQueue(System::nanoTime);
            check(queue.enqueue(34, () -> {
                try {
                    byte[] pixels = (byte[])generate.invoke(null,input,NativeImage.Format.LUMINANCE,context,key);
                    expected.put(key,pixels);
                    return pixels;
                } catch (Exception failure) { throw new RuntimeException(failure); }
                finally { ready.countDown(); }
            }, pixels -> { throw new IllegalStateException("Simulated closed atlas"); }), "Enqueue generation");
            queue.afterFrame(false, true);
            check(ready.await(5,TimeUnit.SECONDS), "Generation complete");
            if (reset) queue.reset();
            else {
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while (queue.snapshot().failed()==0 && System.nanoTime()<deadline) {
                    queue.uploadReady(); Thread.sleep(5);
                }
                check(queue.snapshot().failed()==1, "Upload failure exercised");
            }
            queue.close(); flush(); clearMemory();
            check(Arrays.equals(expected.get(key),GlyphDiskCache.get(key,25)),
                    reset ? "Reload retains mask" : "Failed upload retains mask");
            flush();
        }
        Path blocked = path("5000");
        Files.createDirectories(blocked);
        save("5000",sample); flush();
        check(Files.isDirectory(blocked), "Write failure leaves obstacle intact");
        Files.delete(blocked);
        check(Arrays.equals(sample,GlyphDiskCache.get("5000",sample.length)), "Reuse after write failure");
        flush();
        check(Files.isRegularFile(blocked), "Retry persists entry");
        for (int i=0;i<128;i++) {
            byte[] pixels=new byte[1024];
            Arrays.fill(pixels,(byte)i);
            save("60"+i,pixels);
        }
        GlyphDiskCache.close();
        StringBuilder manifest=new StringBuilder();
        for (var entry:expected.entrySet()) {
            check(Files.size(path(entry.getKey()))==entry.getValue().length+12L, "Shutdown drained "+entry.getKey());
            manifest.append(entry.getKey()).append(' ').append(HexFormat.of().formatHex(entry.getValue())).append('\n');
        }
        try(var files=Files.walk(root.resolve("config/boldtextfix/cache/v2"))) {
            check(files.noneMatch(p->p.toString().endsWith(".tmp")), "No temporary files");
        }
        Files.writeString(root.resolve("expected.txt"),manifest);
        System.out.println("WRITE_AND_LIFECYCLE_CHECKS="+checks);
        System.out.println("PERSISTED_MASKS="+expected.size());
    }
    static void save(String key,byte[] pixels) {
        expected.put(key,pixels.clone());
        GlyphDiskCache.putAsync(key,pixels);
    }
    static Path path(String key) {
        return root.resolve("config/boldtextfix/cache/v2").resolve(key.substring(0,2)).resolve(key+".bin");
    }
    static void flush() throws Exception {
        ((ExecutorService)field(GlyphDiskCache.class,"IO").get(null)).submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    static void clearMemory() throws Exception {
        synchronized(field(GlyphDiskCache.class,"MEMORY_LOCK").get(null)) {
            ((Map<?,?>)field(GlyphDiskCache.class,"MEMORY").get(null)).clear();
            field(GlyphDiskCache.class,"memoryBytes").setInt(null,0);
        }
    }
    static Field field(Class<?> type,String name) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static void check(boolean condition,String label) {
        if(!condition) throw new AssertionError(label); checks++;
    }
}
