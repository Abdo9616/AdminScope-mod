package com.spectatemod.forge;

import com.spectatemod.PlatformHooks;
import com.spectatemod.SpectateMod;
import com.spectatemod.command.SpectateCommand;
import com.spectatemod.command.SpectatePermissions;
import com.spectatemod.manager.SpectateManager;
import com.spectatemod.forge.invsee.InvseeCommand;
import com.spectatemod.forge.invsee.InvseeService;
import com.spectatemod.forge.invsee.InvseePermissions;
import com.spectatemod.forge.invsee.InvseeClickInterceptor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.PermissionAPI;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod(SpectateMod.MOD_ID)
public final class ForgeSpectateMod {
    private final SpectateManager manager;
    private final InvseeService invsee;
    private final Map<UUID, Boolean> invseeCommandVisibility = new HashMap<>();

    public ForgeSpectateMod() {
        PlatformHooks.configure(FMLPaths.GAMEDIR.get(), id -> ModList.get().isLoaded(id));
        PlatformHooks.configurePermissionChecker((player, node) ->
                SpectatePermissions.NODE.equals(node)
                        && Boolean.TRUE.equals(PermissionAPI.getPermission(player, InvseePermissions.SPECTATE)));
        InvseeNetwork.register();
        SpectateMod.initialize();
        manager = SpectateMod.getSpectateManager();
        invsee = new InvseeService();
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> ForgeClientConfig::register);
        MinecraftForge.EVENT_BUS.register(this);
        SpectateMod.LOGGER.info("AdminScope Forge integration initialized");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SpectateCommand.register(event.getDispatcher());
        InvseeCommand.register(event.getDispatcher(), invsee);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        manager.loadSpectateData(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        invsee.closeAll();
        manager.saveSpectateData();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            manager.processPendingReconnectCleanup(event.getServer());
        } else {
            manager.enforceFreecamLimits(event.getServer());
            invsee.tick(event.getServer());
            boolean commandTreeChanged = InvseeCommand.refreshIfNeeded();
            for (net.minecraft.server.level.ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                boolean visible = invsee.config().isEnabled() && InvseePermissions.canView(player);
                Boolean previous = invseeCommandVisibility.put(player.getUUID(), visible);
                if (commandTreeChanged || previous == null || previous != visible) {
                    event.getServer().getCommands().sendCommands(player);
                }
            }
            invseeCommandVisibility.keySet().removeIf(uuid -> event.getServer().getPlayerList().getPlayer(uuid) == null);
        }
    }

    @SubscribeEvent
    public void onGatherPermissions(PermissionGatherEvent.Nodes event) {
        event.addNodes(InvseePermissions.VIEW, InvseePermissions.EDIT,
                InvseePermissions.ENDER_CHEST, InvseePermissions.BACKPACK,
                InvseePermissions.SPECTATE);
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        String command = event.getParseResults().getReader().getString().trim();
        if (command.equalsIgnoreCase("spectate reload")) invsee.reload();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            manager.handlePlayerDisconnect(player, player.getServer());
            InvseeClickInterceptor.remove(player);
            invsee.onLogout(player);
            invseeCommandVisibility.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            InvseeClickInterceptor.install(player);
            manager.handlePlayerJoin(player, player.getServer());
        }
    }
}
