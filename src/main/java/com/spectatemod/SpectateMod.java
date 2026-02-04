package com.spectatemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectatemod.command.SpectateCommand;
import com.spectatemod.config.ConfigManager;
import com.spectatemod.manager.SpectateManager;

public class SpectateMod implements ModInitializer {
    public static final String MOD_ID = "adminspectator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static SpectateManager spectateManager;
    private static ConfigManager configManager;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Spectate Mod");
        
        // Initialize config manager
        configManager = new ConfigManager();
        configManager.loadConfig();
        
        // Initialize spectate manager
        spectateManager = new SpectateManager();
        
        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SpectateCommand.register(dispatcher);
        });
        
        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            spectateManager.loadSpectateData();
            LOGGER.info("Spectate Mod loaded successfully");
        });
        
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            spectateManager.saveSpectateData();
            LOGGER.info("Spectate Mod shutting down");
        });
    }
    
    public static SpectateManager getSpectateManager() {
        return spectateManager;
    }
    
    public static ConfigManager getConfigManager() {
        return configManager;
    }
}
