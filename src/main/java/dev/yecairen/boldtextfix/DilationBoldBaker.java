package dev.yecairen.boldtextfix;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;

/**
 * Lazily bakes a synthetic bold glyph by expanding its alpha mask before it reaches the font
 * atlas. Normal glyph requests do not allocate a bold twin or perform image processing.
 */
public final class DilationBoldBaker {
    private static final int CACHE_ALGORITHM_VERSION = 6;
    private static final int MAX_RUNTIME_VARIANTS = 4096;
    private static final long MAX_VARIANT_PIXELS = 1_048_576L;

    private static final String BITMAP_GLYPH_BITMAP =
            "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1";
    private static final String TRUE_TYPE_GLYPH_BITMAP =
            "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1";
    private static final String UNIHEX_GLYPH_BITMAP =
            "net.minecraft.client.gui.font.providers.UnihexProvider$Glyph$2";

    private static final ThreadLocal<Integer> VARIANT_CREATION_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<UploadContext> UPLOAD_CONTEXT = new ThreadLocal<>();
    private static final AtomicInteger RUNTIME_VARIANTS = new AtomicInteger();

    private static volatile Method unihexUnpackBits;

    private DilationBoldBaker() {
    }

    private static boolean canCreateVariant(GlyphBitmap source) {
        if (source == null) {
            return false;
        }
        String className = source.getClass().getName();
        return BITMAP_GLYPH_BITMAP.equals(className)
                || TRUE_TYPE_GLYPH_BITMAP.equals(className)
                || UNIHEX_GLYPH_BITMAP.equals(className);
    }

    /** Stores enough source state to defer atlas expansion until Style#isBold is actually used. */
    public static void rememberSource(
            BakedSheetGlyph regular,
            GlyphInfo info,
            GlyphBitmap source,
            GlyphStitcher stitcher
    ) {
        if (!canCreateVariant(source)
                || !(regular instanceof DilationBoldGlyph marker)) {
            return;
        }
        marker.boldtextfix$setDilationLazySource(new LazySource(info, source, stitcher));
    }

    /** Creates a variant only for a bold request and caches it by strength. */
    public static BakedSheetGlyph ensureVariant(BakedSheetGlyph regular) {
        if (!(regular instanceof DilationBoldGlyph marker)
                || marker.boldtextfix$isDilationBoldVariant()) {
            return null;
        }

        int strengthTicks = FontFixPolicy.dilationStrengthTicks();
        if (strengthTicks <= 0) {
            return null;
        }

        int variantKey = strengthTicks;
        BakedSheetGlyph existing = marker.boldtextfix$getDilationBoldVariant(variantKey);
        if (existing != null) {
            return existing;
        }

        LazySource lazySource = marker.boldtextfix$getDilationLazySource();
        if (lazySource == null) {
            return null;
        }

        if (!reserveVariantSlot()) {
            return null;
        }

        boolean retained = false;
        try {
            DilationGlyphBitmap dilationBitmap = createVariant(
                    lazySource.bitmap(),
                    BoldTextFixConfig.strengthForTicks(
                            BoldTextFixConfig.RepairMode.DILATION,
                            strengthTicks
                    )
            );
            if (dilationBitmap == null) {
                return null;
            }

            BakedSheetGlyph variant;
            beginVariantCreation();
            try {
                variant = lazySource.stitcher().stitch(lazySource.info(), dilationBitmap);
            } catch (RuntimeException ignored) {
                return null;
            } finally {
                endVariantCreation();
            }

            if (variant != null && dilationBitmap.completed()
                    && variant instanceof DilationBoldGlyph variantMarker) {
                marker.boldtextfix$setDilationBoldVariant(variantKey, variant);
                variantMarker.boldtextfix$markDilationBoldVariant();
                variantMarker.boldtextfix$setVanillaFontGlyph(marker.boldtextfix$isVanillaFontGlyph());
                if (regular instanceof CustomGlyph sourceGlyph && variant instanceof CustomGlyph variantGlyph) {
                    variantGlyph.boldtextfix$setTrueType(sourceGlyph.boldtextfix$isTrueType());
                }
                retained = true;
                return variant;
            }
            return null;
        } finally {
            if (!retained) {
                RUNTIME_VARIANTS.updateAndGet(current -> Math.max(0, current - 1));
            }
        }
    }

    public static boolean isCreatingVariant() {
        return VARIANT_CREATION_DEPTH.get() > 0;
    }

    /** Clears atlas-scoped accounting after a resource reload; disk and memory mask caches stay valid. */
    public static void clearRuntimeState() {
        DilationRenderQueue.INSTANCE.reset();
        RUNTIME_VARIANTS.set(0);
        DilationMask.clearSampleKernels();
    }

    private static void beginVariantCreation() {
        VARIANT_CREATION_DEPTH.set(VARIANT_CREATION_DEPTH.get() + 1);
    }

    private static void endVariantCreation() {
        int depth = VARIANT_CREATION_DEPTH.get() - 1;
        if (depth <= 0) {
            VARIANT_CREATION_DEPTH.remove();
        } else {
            VARIANT_CREATION_DEPTH.set(depth);
        }
    }

    private static DilationGlyphBitmap createVariant(GlyphBitmap source, float radius) {
        if (!canCreateVariant(source)) {
            return null;
        }

        int width = source.getPixelWidth();
        int height = source.getPixelHeight();
        float oversample = source.getOversample();
        if (width <= 0 || height <= 0 || !Float.isFinite(oversample) || oversample <= 0.0F
                || !Float.isFinite(radius) || radius <= 0.0F) {
            return null;
        }

        float sourceRadius = radius * oversample;
        int padding = (int) Math.ceil(sourceRadius);
        long outputWidth = (long) width + padding * 2L;
        long outputHeight = (long) height + padding * 2L;
        if (padding <= 0 || outputWidth > Integer.MAX_VALUE || outputHeight > Integer.MAX_VALUE
                || outputWidth * outputHeight > MAX_VARIANT_PIXELS) {
            return null;
        }

        return new DilationGlyphBitmap(source, new DilationMask(width, height, padding, sourceRadius), oversample);
    }

    /** Called after BitmapProvider uploads the unexpanded source region. */
    public static void rewriteBitmapUpload(Object bitmap, GpuTexture texture) {
        UploadContext context = UPLOAD_CONTEXT.get();
        if (context == null || !BITMAP_GLYPH_BITMAP.equals(bitmap.getClass().getName())) {
            return;
        }

        try {
            Object glyph = outerGlyph(bitmap);
            Object imageData = field(glyph.getClass(), "imageData").get(glyph);
            NativeImage image = (NativeImage) field(imageData.getClass(), "image").get(imageData);
            int sourceX = field(glyph.getClass(), "offsetX").getInt(glyph);
            int sourceY = field(glyph.getClass(), "offsetY").getInt(glyph);
            context.completed = writeCachedOrDilatedMask(image, sourceX, sourceY, texture, context);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The padded source upload remains a visually aligned fallback after an upstream change.
        }
    }

    /** Called after TrueTypeGlyphProvider uploads the unexpanded source region. */
    public static void rewriteTrueTypeUpload(Object bitmap, GpuTexture texture) {
        UploadContext context = UPLOAD_CONTEXT.get();
        if (context == null || !TRUE_TYPE_GLYPH_BITMAP.equals(bitmap.getClass().getName())) {
            return;
        }

        try {
            Object glyph = outerGlyph(bitmap);
            Object provider = field(glyph.getClass(), "this$0").get(glyph);
            FT_Face face = (FT_Face) field(provider.getClass(), "face").get(provider);
            int glyphIndex = field(glyph.getClass(), "index").getInt(glyph);
            if (face == null) {
                return;
            }

            try (NativeImage image = new NativeImage(
                    NativeImage.Format.LUMINANCE,
                    context.mask.sourceWidth,
                    context.mask.sourceHeight,
                    false
            )) {
                if (!image.copyFromFont(face, glyphIndex)) {
                    return;
                }
                context.completed = writeCachedOrDilatedMask(image, 0, 0, texture, context);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The padded source upload remains a visually aligned fallback after an upstream change.
        }
    }

    /** Called after UnihexProvider uploads the unpacked source region. */
    public static void rewriteUnihexUpload(Object bitmap, GpuTexture texture) {
        UploadContext context = UPLOAD_CONTEXT.get();
        if (context == null || !UNIHEX_GLYPH_BITMAP.equals(bitmap.getClass().getName())) {
            return;
        }

        IntBuffer pixels = null;
        try {
            Object glyph = outerGlyph(bitmap);
            Object contents = field(glyph.getClass(), "contents").get(glyph);
            int left = field(glyph.getClass(), "left").getInt(glyph);
            int right = field(glyph.getClass(), "right").getInt(glyph);
            pixels = MemoryUtil.memAllocInt(context.mask.sourceWidth * context.mask.sourceHeight);
            unihexUnpackBits(contents).invoke(null, pixels, contents, left, right);
            pixels.rewind();

            try (NativeImage image = new NativeImage(
                    NativeImage.Format.RGBA,
                    context.mask.sourceWidth,
                    context.mask.sourceHeight,
                    false
            )) {
                ByteBuffer sourceBytes = MemoryUtil.memByteBuffer(pixels);
                ByteBuffer imageBytes = image.getPixelBytes();
                for (int index = 0; index < sourceBytes.capacity(); index++) {
                    imageBytes.put(index, sourceBytes.get(index));
                }
                context.completed = writeCachedOrDilatedMask(image, 0, 0, texture, context);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The padded source upload remains a visually aligned fallback after an upstream change.
        } finally {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private static Object outerGlyph(Object bitmap) throws ReflectiveOperationException {
        for (Field candidate : bitmap.getClass().getDeclaredFields()) {
            if (candidate.getName().startsWith("this$")) {
                candidate.setAccessible(true);
                return candidate.get(bitmap);
            }
        }
        throw new NoSuchFieldException("glyph bitmap outer instance");
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method unihexUnpackBits(Object contents) throws ReflectiveOperationException {
        Method cached = unihexUnpackBits;
        if (cached != null) {
            return cached;
        }

        synchronized (DilationBoldBaker.class) {
            cached = unihexUnpackBits;
            if (cached != null) {
                return cached;
            }

            Class<?> provider = Class.forName("net.minecraft.client.gui.font.providers.UnihexProvider");
            for (Method candidate : provider.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals("unpackBitsToBytes")
                        && parameters.length == 4
                        && IntBuffer.class.isAssignableFrom(parameters[0])
                        && parameters[1].isInstance(contents)) {
                    candidate.setAccessible(true);
                    unihexUnpackBits = candidate;
                    return candidate;
                }
            }
        }
        throw new NoSuchMethodException("UnihexProvider.unpackBitsToBytes");
    }

    private static boolean writeCachedOrDilatedMask(
            NativeImage source,
            int sourceX,
            int sourceY,
            GpuTexture texture,
            UploadContext context
    ) {
        NativeImage.Format format = source.format();
        int components = format.components();
        if (components <= 0) {
            return false;
        }

        int byteCount = context.mask.outputWidth * context.mask.outputHeight * components;
        String cacheKey = cacheKey(source, sourceX, sourceY, context, components);
        byte[] cached = GlyphDiskCache.get(cacheKey, byteCount);
        if (cached != null) {
            return writeCachedMask(format, cached, texture, context);
        }

        // Copy only this glyph. Font providers and NativeImages must never escape to the worker.
        byte[] sourceCopy = new byte[context.mask.sourceWidth * context.mask.sourceHeight * components];
        ByteBuffer pixels = source.getPixelBytes();
        int rowBytes = context.mask.sourceWidth * components;
        for (int y = 0; y < context.mask.sourceHeight; y++) {
            int start = pixelOffset(sourceX, sourceY + y, source.getWidth(), components);
            pixels.get(start, sourceCopy, y * rowBytes, rowBytes);
        }
        // Until the worker finishes, show the original glyph with transparent padding.
        if (!writeCachedMask(format, context.mask.padded(sourceCopy, components), texture, context)) {
            return false;
        }
        return DilationRenderQueue.INSTANCE.enqueue(sourceCopy.length + byteCount,
                () -> generateAndCache(sourceCopy, format, context.mask, cacheKey), result -> {
                    if (texture.isClosed() || !writeCachedMask(format, result, texture, context)) {
                        throw new IllegalStateException("Glyph atlas is no longer available");
                    }
                });
    }

    /** Persist completed pixels even if a reload closes the atlas before its upload. */
    private static byte[] generateAndCache(byte[] source, NativeImage.Format format,
            DilationMask mask, String cacheKey) {
        byte[] result = mask.dilate(source, format);
        GlyphDiskCache.putAsync(cacheKey, result);
        return result;
    }

    private static boolean writeCachedMask(
            NativeImage.Format format,
            byte[] cached,
            GpuTexture texture,
            UploadContext context
    ) {
        try (NativeImage image = new NativeImage(format, context.mask.outputWidth, context.mask.outputHeight, false)) {
            ByteBuffer destination = image.getPixelBytes();
            if (destination.capacity() < cached.length) {
                return false;
            }
            for (int index = 0; index < cached.length; index++) {
                destination.put(index, cached[index]);
            }
            writeTexture(texture, image, context);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void writeTexture(GpuTexture texture, NativeImage image, UploadContext context) {
        RenderSystem.getDevice()
                .createCommandEncoder()
                .writeToTexture(texture, image, 0, 0, context.targetX, context.targetY);
    }

    private static String cacheKey(
            NativeImage source,
            int sourceX,
            int sourceY,
            UploadContext context,
            int components
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, CACHE_ALGORITHM_VERSION);
            updateInt(digest, source.format().ordinal());
            updateInt(digest, components);
            updateInt(digest, context.mask.sourceWidth);
            updateInt(digest, context.mask.sourceHeight);
            updateInt(digest, context.mask.outputWidth);
            updateInt(digest, context.mask.outputHeight);
            updateInt(digest, context.mask.padding);
            updateInt(digest, Float.floatToIntBits(context.mask.sourceRadius));

            ByteBuffer pixels = source.getPixelBytes();
            for (int y = 0; y < context.mask.sourceHeight; y++) {
                int rowStart = pixelOffset(sourceX, sourceY + y, source.getWidth(), components);
                for (int x = 0; x < context.mask.sourceWidth * components; x++) {
                    digest.update(pixels.get(rowStart + x));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for glyph caching", unavailable);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static int pixelOffset(int x, int y, int width, int components) {
        return (x + y * width) * components;
    }

    private static boolean reserveVariantSlot() {
        while (true) {
            int current = RUNTIME_VARIANTS.get();
            if (current >= MAX_RUNTIME_VARIANTS) {
                return false;
            }
            if (RUNTIME_VARIANTS.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public record LazySource(GlyphInfo info, GlyphBitmap bitmap, GlyphStitcher stitcher) {
    }

    /** Allocates a padded atlas region while retaining the source glyph's advance metrics. */
    private static final class DilationGlyphBitmap implements GlyphBitmap {
        private final GlyphBitmap source;
        private final DilationMask mask;
        private final float oversample;
        private boolean completed;

        private DilationGlyphBitmap(GlyphBitmap source, DilationMask mask, float oversample) {
            this.source = source;
            this.mask = mask;
            this.oversample = oversample;
        }

        @Override
        public int getPixelWidth() {
            return this.mask.outputWidth;
        }

        @Override
        public int getPixelHeight() {
            return this.mask.outputHeight;
        }

        @Override
        public void upload(int x, int y, GpuTexture texture) {
            UploadContext context = new UploadContext(this.mask, x, y);
            UPLOAD_CONTEXT.set(context);
            try {
                this.source.upload(x + this.mask.padding, y + this.mask.padding, texture);
                this.completed = context.completed;
            } finally {
                UPLOAD_CONTEXT.remove();
            }
        }

        @Override
        public boolean isColored() {
            return this.source.isColored();
        }

        @Override
        public float getOversample() {
            return this.oversample;
        }

        @Override
        public float getLeft() {
            return this.source.getLeft() - this.mask.padding / this.oversample;
        }

        @Override
        public float getRight() {
            return this.source.getRight() + this.mask.padding / this.oversample;
        }

        @Override
        public float getTop() {
            return this.source.getTop() - this.mask.padding / this.oversample;
        }

        @Override
        public float getBottom() {
            return this.source.getBottom() + this.mask.padding / this.oversample;
        }

        @Override
        public float getBearingLeft() {
            return this.source.getBearingLeft();
        }

        @Override
        public float getBearingTop() {
            return this.source.getBearingTop();
        }

        public boolean completed() {
            return this.completed;
        }
    }

    private static final class UploadContext {
        private final DilationMask mask;
        private final int targetX;
        private final int targetY;
        private boolean completed;

        private UploadContext(DilationMask mask, int targetX, int targetY) {
            this.mask = mask;
            this.targetX = targetX;
            this.targetY = targetY;
        }
    }
}
