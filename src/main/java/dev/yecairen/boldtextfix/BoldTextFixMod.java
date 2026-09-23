package dev.yecairen.boldtextfix;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BoldTextFixMod implements ModInitializer {
    public static final String MOD_ID = "boldtextfix";
    public static final String MOD_NAME = "BoldTextFix";
    private static final String FALLBACK_DISPLAY_VERSION = "1.3.0+mc26.3";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static String displayName() {
        return MOD_NAME + " " + displayVersion();
    }

    private static String displayVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse(FALLBACK_DISPLAY_VERSION);
    }

    @Override
    public void onInitialize() {
        BoldTextFixConfig.load();
        LOGGER.info("Initialized {}", displayName());
    }
}
