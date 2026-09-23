package dev.yecairen.boldtextfix;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable dimensions and CPU-only pixel processing for one uniform dilation mask.
 * Input pixels are a tightly packed glyph copy, independent of font providers and GPU textures.
 */
final class DilationMask {
    private static final int SUBPIXEL_SCALE = 4;
    private static final int EDGE_DIRECTION_SAMPLES = 16;
    private static final float SAMPLE_EPSILON = 0.0001F;
    private static final ConcurrentHashMap<Integer, SampleOffset[]> SAMPLE_KERNELS = new ConcurrentHashMap<>();

    final int sourceWidth;
    final int sourceHeight;
    final int outputWidth;
    final int outputHeight;
    final int padding;
    final float sourceRadius;
    private final SampleOffset[] sampleOffsets;

    DilationMask(int sourceWidth, int sourceHeight, int padding, float sourceRadius) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.outputWidth = sourceWidth + 2 * padding;
        this.outputHeight = sourceHeight + 2 * padding;
        this.padding = padding;
        this.sourceRadius = sourceRadius;
        this.sampleOffsets = sampleOffsets(sourceRadius);
    }

    static void clearSampleKernels() {
        SAMPLE_KERNELS.clear();
    }

    byte[] padded(byte[] source, int components) {
        byte[] result = new byte[this.outputWidth * this.outputHeight * components];
        int rowBytes = this.sourceWidth * components;
        for (int y = 0; y < this.sourceHeight; y++) {
            System.arraycopy(source, y * rowBytes, result,
                    pixelOffset(this.padding, this.padding + y, this.outputWidth, components), rowBytes);
        }
        return result;
    }

    byte[] dilate(byte[] source, NativeImage.Format format) {
        int components = format.components();
        ByteBuffer sourcePixels = ByteBuffer.wrap(source);
        ByteBuffer outputPixels = ByteBuffer.allocate(this.outputWidth * this.outputHeight * components);
        int coverageChannel = coverageChannel(format);

        for (int outputY = 0; outputY < this.outputHeight; outputY++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            float baseY = outputY - this.padding;
            for (int outputX = 0; outputX < this.outputWidth; outputX++) {
                float baseX = outputX - this.padding;
                float bestX = baseX;
                float bestY = baseY;
                int bestCoverage = 0;

                for (SampleOffset offset : this.sampleOffsets) {
                    float sampleX = baseX - offset.x();
                    float sampleY = baseY - offset.y();
                    int coverage = sampleCoverage(
                            sourcePixels,
                            components,
                            coverageChannel,
                            sampleX,
                            sampleY
                    );
                    if (coverage > bestCoverage) {
                        bestCoverage = coverage;
                        bestX = sampleX;
                        bestY = sampleY;
                    }
                }

                int outputOffset = pixelOffset(outputX, outputY, this.outputWidth, components);
                if (bestCoverage == 0) {
                    continue;
                }

                for (int component = 0; component < components; component++) {
                    outputPixels.put(outputOffset + component, (byte) bilinearComponent(
                            sourcePixels,
                            components,
                            bestX,
                            bestY,
                            component
                    ));
                }
            }
        }

        return outputPixels.array();
    }

    private int sampleCoverage(ByteBuffer pixels, int components, int alphaChannel, float x, float y) {
        if (alphaChannel >= 0 && alphaChannel < components) {
            return bilinearComponent(pixels, components, x, y, alphaChannel);
        }
        int maximum = 0;
        for (int component = 0; component < components; component++) {
            maximum = Math.max(maximum, bilinearComponent(pixels, components, x, y, component));
        }
        return maximum == 0 ? 0 : 255;
    }

    private int bilinearComponent(ByteBuffer pixels, int components, float x, float y, int component) {
        int left = (int) Math.floor(x);
        int top = (int) Math.floor(y);
        float horizontal = x - left;
        float vertical = y - top;
        int upperLeft = componentAt(pixels, components, left, top, component);
        int upperRight = componentAt(pixels, components, left + 1, top, component);
        int lowerLeft = componentAt(pixels, components, left, top + 1, component);
        int lowerRight = componentAt(pixels, components, left + 1, top + 1, component);
        return interpolate(
                interpolate(upperLeft, upperRight, horizontal),
                interpolate(lowerLeft, lowerRight, horizontal), vertical);
    }

    private int componentAt(ByteBuffer pixels, int components, int x, int y, int component) {
        if (x < 0 || y < 0 || x >= this.sourceWidth || y >= this.sourceHeight) {
            return 0;
        }
        return Byte.toUnsignedInt(pixels.get(pixelOffset(x, y, this.sourceWidth, components) + component));
    }

    private static int interpolate(int first, int second, float fraction) {
        if (fraction <= SAMPLE_EPSILON) {
            return first;
        }
        return Math.round(first + (second - first) * fraction);
    }

    private static int coverageChannel(NativeImage.Format format) {
        if (format.hasAlpha()) {
            return format.alphaOffset() / Byte.SIZE;
        }
        if (format.hasLuminance()) {
            return format.luminanceOffset() / Byte.SIZE;
        }
        return -1;
    }

    private static int pixelOffset(int x, int y, int width, int components) {
        return (x + y * width) * components;
    }

    private static SampleOffset[] sampleOffsets(float radius) {
        int radiusTicks = Math.max(0, Math.round(radius * 1024.0F));
        return SAMPLE_KERNELS.computeIfAbsent(radiusTicks,
                ticks -> createSampleOffsets(ticks / 1024.0F));
    }

    private static SampleOffset[] createSampleOffsets(float radius) {
        List<SampleOffset> offsets = new ArrayList<>();
        offsets.add(new SampleOffset(0.0F, 0.0F));
        if (radius <= SAMPLE_EPSILON) {
            return offsets.toArray(SampleOffset[]::new);
        }

        int gridRadius = (int) Math.ceil(radius * SUBPIXEL_SCALE);
        float radiusSquared = radius * radius;
        for (int gridY = -gridRadius; gridY <= gridRadius; gridY++) {
            for (int gridX = -gridRadius; gridX <= gridRadius; gridX++) {
                if (gridX == 0 && gridY == 0) {
                    continue;
                }
                float offsetX = (float) gridX / SUBPIXEL_SCALE;
                float offsetY = (float) gridY / SUBPIXEL_SCALE;
                if (offsetX * offsetX + offsetY * offsetY < radiusSquared - SAMPLE_EPSILON) {
                    addSampleOffset(offsets, offsetX, offsetY);
                }
            }
        }

        for (int direction = 0; direction < EDGE_DIRECTION_SAMPLES; direction++) {
            double angle = Math.PI * 2.0D * direction / EDGE_DIRECTION_SAMPLES;
            addSampleOffset(
                    offsets,
                    (float) (Math.cos(angle) * radius),
                    (float) (Math.sin(angle) * radius)
            );
        }
        return offsets.toArray(SampleOffset[]::new);
    }

    private static void addSampleOffset(
            List<SampleOffset> offsets,
            float offsetX,
            float offsetY
    ) {
        float horizontal = Math.abs(offsetX) <= SAMPLE_EPSILON ? 0.0F : offsetX;
        float vertical = Math.abs(offsetY) <= SAMPLE_EPSILON ? 0.0F : offsetY;
        offsets.add(new SampleOffset(horizontal, vertical));
    }

    private record SampleOffset(float x, float y) {
    }
}
