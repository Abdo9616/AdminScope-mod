package com.spectatemod.client.modmenu;

import com.spectatemod.SpectateMod;
import com.spectatemod.client.config.AdminScopeClothConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AdminScopeModMenuIntegration implements ModMenuApi {
    private static final Component TITLE = Component.literal("AdminScope Config");
    private static final Component BACK_BUTTON = Component.literal("Back");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (!isClothConfigPresent()) {
                return infoScreen(parent, Component.literal("Install Cloth Config API to open this screen."));
            }

            try {
                return AdminScopeClothConfigScreen.create(parent);
            } catch (Throwable error) {
                SpectateMod.LOGGER.error("Failed to create AdminScope config screen", error);
                String summary = error.getClass().getSimpleName();
                if (error.getMessage() != null && !error.getMessage().isBlank()) {
                    summary = summary + ": " + error.getMessage();
                }
                return infoScreen(parent, Component.literal("Could not open config screen. " + summary));
            }
        };
    }

    private static boolean isClothConfigPresent() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("cloth-config") || loader.isModLoaded("cloth-config2");
    }

    private static Screen infoScreen(Screen parent, Component message) {
        return new ConfirmScreen(
                accepted -> Minecraft.getInstance().setScreen(parent),
                TITLE,
                message,
                BACK_BUTTON,
                BACK_BUTTON
        );
    }
}
