package com.spectatemod.forge;

import com.spectatemod.client.config.AdminScopeClothConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;

/** Client-only Forge integration for the configuration button in the Mods screen. */
public final class ForgeClientConfig {
    private ForgeClientConfig() {
    }

    public static void register() {
        if (!ModList.get().isLoaded("cloth_config")) {
            return;
        }

        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(AdminScopeClothConfigScreen::create));
    }
}
