package com.spectatemod;

import com.spectatemod.config.ConfigManager;
import com.spectatemod.manager.SpectateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-neutral service container shared by the Fabric and Forge entrypoints. */
public final class SpectateMod {
    public static final String MOD_ID = "adminscope";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SpectateManager spectateManager;
    private static ConfigManager configManager;

    private SpectateMod() {
    }

    public static synchronized void initialize() {
        if (spectateManager != null) {
            return;
        }

        LOGGER.info("Initializing AdminScope");
        configManager = new ConfigManager();
        configManager.loadConfig();
        spectateManager = new SpectateManager();
    }

    public static SpectateManager getSpectateManager() {
        return spectateManager;
    }

    public static ConfigManager getConfigManager() {
        return configManager;
    }
}
