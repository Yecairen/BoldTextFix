package dev.yecairen.boldtextfix.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.yecairen.boldtextfix.BoldTextFixMod;
import dev.yecairen.boldtextfix.CustomBoldFonts;
import dev.yecairen.boldtextfix.DilationRenderQueue;
import dev.yecairen.boldtextfix.GlyphDiskCache;
import dev.yecairen.boldtextfix.VanillaBoldFallback;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Registers an intentionally unbound shortcut that opens the same screen as Mod Menu. */
public final class BoldTextFixClient implements ClientModInitializer {
    private static final KeyMapping.Category DEBUG_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(BoldTextFixMod.MOD_ID, "debug")
    );
    private static final KeyMapping OPEN_DEBUG = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.boldtextfix.open_debug",
            InputConstants.Type.KEYBOARD,
            InputConstants.UNKNOWN.getValue(),
            DEBUG_CATEGORY
    ));

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            DilationRenderQueue.INSTANCE.close();
            GlyphDiskCache.close();
            CustomBoldFonts.close();
            VanillaBoldFallback.close();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CustomBoldFonts.tick(client);
            VanillaBoldFallback.tick();
            while (OPEN_DEBUG.consumeClick()) {
                this.openDebugScreen(client);
            }
        });
    }

    private void openDebugScreen(Minecraft client) {
        if (client.gui.screen() instanceof BoldTextFixConfigScreen) {
            return;
        }
        client.gui.setScreen(new BoldTextFixConfigScreen(client.gui.screen()));
    }
}
