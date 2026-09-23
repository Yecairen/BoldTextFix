package dev.yecairen.boldtextfix;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

public final class CustomBoldFonts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Integer, BakedGlyph> GLYPHS = new HashMap<>();
    private static final Set<Integer> FAILED_GLYPHS = new HashSet<>();
    private static List<BoldFontFiles.FontFile> files = List.of();
    private static Selection selection;
    private static TrueTypeGlyphProvider provider;
    private static GlyphStitcher atlas;
    private static Component status = Component.empty();
    private static boolean invalidated = true;
    private static int ticks;

    private CustomBoldFonts() {
    }

    public static boolean appliesTo(Style style) {
        return BoldTextFixConfig.isEnabled()
                && BoldTextFixConfig.mode() == BoldTextFixConfig.RepairMode.CUSTOM_FONT
                && style != null && style.isBold() && !style.isObfuscated()
                && style.getFont() instanceof FontDescription.Resource;
    }

    public static BakedGlyph resolveGlyph(int codePoint) {
        BakedGlyph glyph = glyph(codePoint);
        return glyph != null ? glyph : VanillaBoldFallback.glyph(codePoint);
    }

    public static boolean prefersThirdPartyGlyph(int codePoint) {
        // Digits keep the ordinary text fallback even though Unicode also allows them in keycap Emoji.
        boolean emoji = !Character.isDigit(codePoint)
                && (Character.isEmoji(codePoint) || Character.isEmojiComponent(codePoint));
        if (emoji || codePoint == 0xFE0E) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL, Character.PRIVATE_USE -> true;
            default -> false;
        };
    }

    public static synchronized void tick(Minecraft client) {
        if (!BoldTextFixConfig.isEnabled()
                || BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
            if (selection != null) {
                release();
                selection = null;
                status = Component.empty();
            }
            ticks = 0;
            return;
        }
        if (invalidated || ++ticks >= 20) {
            refresh(client);
        }
    }

    public static synchronized void invalidate() {
        invalidated = true;
    }

    public static synchronized List<BoldFontFiles.FontFile> files() {
        return files;
    }

    public static synchronized Component status() {
        if (provider != null && selection != null) {
            BoldFontFiles.Details details = BoldFontFiles.details(selection.file());
            return Component.translatable("screen.boldtextfix.font.selected", selection.file().name(),
                    details.characterCount(), details.glyphCount());
        }
        return status;
    }

    public static synchronized boolean failedToLoad(BoldFontFiles.FontFile file) {
        return selection != null && file.equals(selection.file()) && provider == null;
    }

    public static synchronized boolean ownsAtlas(GlyphStitcher stitcher) {
        return atlas == stitcher;
    }

    public static synchronized void refresh(Minecraft client) {
        ticks = 0;
        boolean reload = invalidated;
        invalidated = false;
        try {
            files = BoldFontFiles.list();
        } catch (IOException | RuntimeException failure) {
            files = List.of();
            release();
            selection = null;
            status = error("screen.boldtextfix.font.folder_error");
            return;
        }
        if (!BoldTextFixConfig.isEnabled()
                || BoldTextFixConfig.mode() != BoldTextFixConfig.RepairMode.CUSTOM_FONT) {
            release();
            selection = null;
            status = Component.empty();
            return;
        }
        String name = BoldTextFixConfig.boldFont();
        BoldFontFiles.FontFile file = files.stream().filter(entry -> entry.name().equals(name)).findFirst().orElse(null);
        Selection next = new Selection(file, BoldTextFixConfig.fontSize(), BoldTextFixConfig.fontOversample());
        if (file == null) {
            release();
            selection = next;
            status = name.isEmpty() ? Component.translatable("screen.boldtextfix.font.none")
                    : error("screen.boldtextfix.font.missing", name);
            return;
        }
        if (!reload && Objects.equals(selection, next)) {
            return;
        }
        release();
        selection = next;
        try {
            provider = LocalBoldFont.open(BoldFontFiles.read(BoldFontFiles.resolve(name)), next.size(), next.oversample());
            atlas = new GlyphStitcher(client.getTextureManager(),
                    Identifier.fromNamespaceAndPath(BoldTextFixMod.MOD_ID, "custom_bold"));
            status = Component.empty();
        } catch (IOException | RuntimeException failure) {
            release();
            status = error("screen.boldtextfix.font.load_error", name);
            LOGGER.warn("[BoldTextFix] Cannot load bold font {}", name, failure);
        }
    }

    private static Component error(String key, Object... arguments) {
        return Component.translatable(key, arguments).withStyle(Style.EMPTY.withColor(0xFF5555));
    }

    public static String importFile(Path source) throws IOException {
        byte[] bytes = BoldFontFiles.read(source);
        try (TrueTypeGlyphProvider checked = LocalBoldFont.open(bytes,
                BoldTextFixConfig.fontSize(), BoldTextFixConfig.fontOversample())) {
            return BoldFontFiles.importFile(source, bytes);
        }
    }

    public static synchronized BakedGlyph glyph(int codePoint) {
        BakedGlyph cached = GLYPHS.get(codePoint);
        if (cached != null) {
            return cached;
        }
        UnbakedGlyph glyph = unbaked(codePoint);
        if (glyph == null || atlas == null) {
            return null;
        }
        try {
            BakedGlyph baked = glyph.bake(new UnbakedGlyph.Stitcher() {
                @Override
                public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
                    return atlas.stitch(info, bitmap);
                }

                @Override
                public BakedGlyph getMissing() {
                    return null;
                }
            });
            if (baked != null) {
                BakedGlyph replacement = new CustomBoldGlyph(baked);
                GLYPHS.put(codePoint, replacement);
                return replacement;
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("[BoldTextFix] Cannot bake local bold glyph {}", codePoint, failure);
        }
        FAILED_GLYPHS.add(codePoint);
        return null;
    }

    private static UnbakedGlyph unbaked(int codePoint) {
        if (provider == null || FAILED_GLYPHS.contains(codePoint)) {
            return null;
        }
        try {
            return provider.getGlyph(codePoint);
        } catch (RuntimeException failure) {
            FAILED_GLYPHS.add(codePoint);
            LOGGER.warn("[BoldTextFix] Cannot read local bold glyph {}", codePoint, failure);
            return null;
        }
    }

    public static synchronized void close() {
        BoldFontFiles.clearDetails();
        release();
        selection = null;
        invalidated = true;
    }

    private static void release() {
        GLYPHS.clear();
        FAILED_GLYPHS.clear();
        if (atlas != null) {
            atlas.close();
            atlas = null;
        }
        if (provider != null) {
            provider.close();
            provider = null;
        }
    }

    private record Selection(BoldFontFiles.FontFile file, float size, float oversample) {
    }
}
