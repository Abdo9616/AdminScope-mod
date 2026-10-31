package com.spectatemod.fabric;

import com.spectatemod.PlatformHooks;
import com.spectatemod.SpectateMod;
import com.spectatemod.command.SpectateCommand;
import com.spectatemod.manager.SpectateManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricSpectateMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PlatformHooks.configure(
                FabricLoader.getInstance().getGameDir(),
                id -> FabricLoader.getInstance().isModLoaded(id));
        SpectateMod.initialize();

        SpectateManager manager = SpectateMod.getSpectateManager();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SpectateCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> manager.loadSpectateData(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> manager.saveSpectateData());
        ServerTickEvents.START_SERVER_TICK.register(manager::processPendingReconnectCleanup);
        ServerTickEvents.END_SERVER_TICK.register(manager::enforceFreecamLimits);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                manager.handlePlayerDisconnect(handler.player, server));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                manager.handlePlayerJoin(handler.player, server));

        SpectateMod.LOGGER.info("AdminScope Fabric integration initialized");
    }
}
