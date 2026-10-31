package com.spectatemod.forge;

import com.spectatemod.PlatformHooks;
import com.spectatemod.SpectateMod;
import com.spectatemod.command.SpectateCommand;
import com.spectatemod.manager.SpectateManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@Mod(SpectateMod.MOD_ID)
public final class ForgeSpectateMod {
    private final SpectateManager manager;

    public ForgeSpectateMod() {
        PlatformHooks.configure(FMLPaths.GAMEDIR.get(), id -> ModList.get().isLoaded(id));
        SpectateMod.initialize();
        manager = SpectateMod.getSpectateManager();
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> ForgeClientConfig::register);
        MinecraftForge.EVENT_BUS.register(this);
        SpectateMod.LOGGER.info("AdminScope Forge integration initialized");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SpectateCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        manager.loadSpectateData(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        manager.saveSpectateData();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            manager.processPendingReconnectCleanup(event.getServer());
        } else {
            manager.enforceFreecamLimits(event.getServer());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            manager.handlePlayerDisconnect(player, player.getServer());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            manager.handlePlayerJoin(player, player.getServer());
        }
    }
}
