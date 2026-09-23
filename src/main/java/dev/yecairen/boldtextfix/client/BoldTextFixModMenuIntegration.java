package dev.yecairen.boldtextfix.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Exposes the local test screen only when Mod Menu is installed. */
public final class BoldTextFixModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BoldTextFixConfigScreen::new;
    }
}
