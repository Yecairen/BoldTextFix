import com.mojang.blaze3d.platform.NativeImage;
import dev.yecairen.boldtextfix.DilationBoldBaker;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

/**
 * Run with two builds in separate JVMs, then compare pixel and cache-key digests separately.
 * Covers actual pixels, transparent padding and persistent cache keys without a game or GPU.
 */
class DilationCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        Class<?> contextType = Class.forName(DilationBoldBaker.class.getName() + "$UploadContext");
        Constructor<?> contextConstructor = contextType.getDeclaredConstructors()[0];
        contextConstructor.setAccessible(true);
        Class<?> maskType = null;
        try {
            maskType = Class.forName("dev.yecairen.boldtextfix.DilationMask");
        } catch (ClassNotFoundException baseline) {
            // The saved baseline keeps CPU processing inside the baker.
        }
        Constructor<?> maskConstructor = maskType == null ? null
                : maskType.getDeclaredConstructor(int.class, int.class, int.class, float.class);
        if (maskConstructor != null) maskConstructor.setAccessible(true);
        Method dilate = maskType == null
                ? DilationBoldBaker.class.getDeclaredMethod("dilateMask", byte[].class, NativeImage.Format.class, contextType)
                : maskType.getDeclaredMethod("dilate", byte[].class, NativeImage.Format.class);
        Method pad = maskType == null
                ? DilationBoldBaker.class.getDeclaredMethod("paddedMask", byte[].class, int.class, contextType)
                : maskType.getDeclaredMethod("padded", byte[].class, int.class);
        Method cacheKey = DilationBoldBaker.class.getDeclaredMethod("cacheKey", NativeImage.class,
                int.class, int.class, contextType, int.class);
        dilate.setAccessible(true);
        pad.setAccessible(true);
        cacheKey.setAccessible(true);
        MessageDigest pixelDigest = MessageDigest.getInstance("SHA-256");
        MessageDigest cacheKeyDigest = MessageDigest.getInstance("SHA-256");
        Random random = new Random(20260920L);
        int fixtures = 0;
        for (NativeImage.Format format : NativeImage.Format.values()) {
            int components = format.components();
            if (components <= 0) continue;
            for (int[] dimensions : new int[][]{{1,1},{3,5},{7,2},{9,11}}) {
                int width = dimensions[0], height = dimensions[1];
                for (float radius : new float[]{0.0125F,0.125F,0.26F,0.275F,0.3F,0.5F,1.0F,2.0F}) {
                    int padding = (int)Math.ceil(radius);
                    Object mask = maskConstructor == null ? null : maskConstructor.newInstance(width,height,padding,radius);
                    Object context = mask == null
                            ? contextConstructor.newInstance(width,height,width+2*padding,height+2*padding,padding,radius,0,0)
                            : contextConstructor.newInstance(mask,0,0);
                    for (int pattern = 0; pattern < 4; pattern++) {
                        byte[] pixels = new byte[width * height * components];
                        if (pattern == 1) Arrays.fill(pixels, (byte)255);
                        if (pattern == 2) Arrays.fill(pixels, (width*height/2)*components,
                                (width*height/2+1)*components, (byte)255);
                        if (pattern == 3) random.nextBytes(pixels);
                        byte[] original = pixels.clone();
                        byte[] padded = (byte[])(mask == null ? pad.invoke(null,pixels,components,context)
                                : pad.invoke(mask,pixels,components));
                        byte[] result = (byte[])(mask == null ? dilate.invoke(null,pixels,format,context)
                                : dilate.invoke(mask,pixels,format));
                        if (!Arrays.equals(pixels,original)) throw new AssertionError("Source pixels mutated");
                        pixelDigest.update(padded);
                        pixelDigest.update(result);
                        // Include a nonzero source origin and wider stride, as in a bitmap font sheet.
                        try (NativeImage image = new NativeImage(format,width+4,height+5,true)) {
                            var buffer = image.getPixelBytes();
                            for (int row = 0; row < height; row++) {
                                buffer.put(((row+3)*(width+4)+2)*components,
                                        pixels,row*width*components,width*components);
                            }
                            String key = (String)cacheKey.invoke(null,image,2,3,context,components);
                            cacheKeyDigest.update(key.getBytes(StandardCharsets.US_ASCII));
                        }
                        fixtures++;
                    }
                }
            }
        }
        System.out.println("PIXEL_AND_CACHE_FIXTURES=" + fixtures);
        System.out.println("PIXEL_DIGEST=" + HexFormat.of().formatHex(pixelDigest.digest()));
        System.out.println("CACHE_KEY_DIGEST=" + HexFormat.of().formatHex(cacheKeyDigest.digest()));
    }
}
