package dev.yecairen.boldtextfix;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

public final class LocalBoldFont {
    private LocalBoldFont() {
    }

    public static TrueTypeGlyphProvider open(byte[] bytes, float size, float oversample) throws IOException {
        try (NativeFont font = openFace(bytes)) {
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                TrueTypeGlyphProvider provider = new TrueTypeGlyphProvider(
                        font.memory, font.face, size, oversample, 0.0F, 0.0F, "");
                font.transferred = true;
                return provider;
            }
        } catch (RuntimeException failure) {
            throw new IOException("Cannot load local bold font", failure);
        }
    }

    /** Counts mapped Unicode scalar values separately from all glyphs, including unmapped alternates. */
    public static Statistics inspect(byte[] bytes) throws IOException {
        try (NativeFont font = openFace(bytes); MemoryStack stack = MemoryStack.stackPush()) {
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                long glyphs = font.face.num_glyphs();
                var index = stack.mallocInt(1);
                long codePoint = FreeType.FT_Get_First_Char(font.face, index);
                int characters = 0;
                while (index.get(0) != 0 && codePoint <= Character.MAX_CODE_POINT) {
                    if (index.get(0) < 0 || index.get(0) >= glyphs) {
                        throw new IOException("Character map points outside the glyph table");
                    }
                    if (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE) {
                        characters++;
                    }
                    long next = FreeType.FT_Get_Next_Char(font.face, codePoint, index);
                    if (index.get(0) != 0 && next <= codePoint) {
                        throw new IOException("Invalid character map order");
                    }
                    codePoint = next;
                }
                if (characters == 0 || glyphs <= 0) {
                    throw new IOException("Font has no usable Unicode characters");
                }
                return new Statistics(characters, glyphs);
            }
        } catch (RuntimeException failure) {
            throw new IOException("Cannot inspect local bold font", failure);
        }
    }

    public record Statistics(int characters, long glyphs) {
    }

    private static NativeFont openFace(byte[] bytes) throws IOException {
        ByteBuffer memory = MemoryUtil.memAlloc(bytes.length);
        FT_Face face = null;
        boolean transferred = false;
        try {
            memory.put(bytes).flip();
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pointer = stack.mallocPointer(1);
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(
                            FreeTypeUtil.getLibrary(), memory, 0L, pointer), "Opening local bold font");
                    face = FT_Face.create(pointer.get());
                }
                String format = FreeType.FT_Get_Font_Format(face);
                if (!"TrueType".equals(format) && !"CFF".equals(format)) {
                    throw new IOException("Unsupported font outline format: " + format);
                }
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE),
                        "Selecting Unicode charmap");
                NativeFont font = new NativeFont(memory, face);
                transferred = true;
                return font;
            }
        } catch (RuntimeException failure) {
            throw new IOException("Cannot load local bold font", failure);
        } finally {
            if (!transferred) {
                synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                    if (face != null) {
                        FreeType.FT_Done_Face(face);
                    }
                }
                MemoryUtil.memFree(memory);
            }
        }
    }

    private static final class NativeFont implements AutoCloseable {
        private final ByteBuffer memory;
        private final FT_Face face;
        private boolean transferred;

        private NativeFont(ByteBuffer memory, FT_Face face) {
            this.memory = memory;
            this.face = face;
        }

        @Override
        public void close() {
            if (!this.transferred) {
                synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                    FreeType.FT_Done_Face(this.face);
                }
                MemoryUtil.memFree(this.memory);
            }
        }
    }
}
