package com.spectatemod.forge.invsee;

import com.mojang.brigadier.CommandDispatcher;
import com.spectatemod.SpectateMod;
import com.spectatemod.forge.InvseeNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.server.permission.PermissionAPI;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns Forge invsee sessions and keeps them tied to online player entities. */
public final class InvseeService {
    /** Minimum gap between "No permission" messages so a drag or spam-click can't flood chat and logs. */
    private static final long DENIED_MESSAGE_COOLDOWN_MS = 1000L;

    private final InvseeConfig config = new InvseeConfig();
    private final Map<UUID, InvseeMenu> openMenus = new HashMap<>();
    private final Map<UUID, BackpackMenu> openBackpacks = new HashMap<>();
    private final Map<UUID, EnderChestMenu> openEnderChests = new HashMap<>();
    private final Map<UUID, AbstractContainerMenu> nativeBackpacks = new HashMap<>();
    /** Viewer UUID -> the exact target entity whose backpack that viewer has open. */
    private final Map<UUID, ServerPlayer> nativeBackpackTargets = new HashMap<>();
    private final Map<UUID, ServerPlayer> nativeBackpackViewers = new HashMap<>();
    private final Map<UUID, TemporaryBackpackItem> temporaryBackpackItems = new HashMap<>();
    private final Map<UUID, UUID> shortcutTargets = new HashMap<>();
    private final Map<UUID, Long> lastDeniedAt = new HashMap<>();
    /** Viewer UUID -> the "show tool slots" value their open backpack screen currently displays. */
    private final Map<UUID, Boolean> toolSlotsOnClient = new HashMap<>();

    /** The running service, so menus that are not built by the service (e.g. the backpack menu) can reach it. */
    private static volatile InvseeService instance;

    public InvseeService() { instance = this; }

    /** Sends the shared, rate-limited "No permission" message to a viewer who tried a blocked edit. */
    static void notifyDenied(ServerPlayer viewer) {
        if (viewer == null) return;
        InvseeService service = instance;
        if (service != null) service.deniedEdit(viewer);
        else viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION);
    }

    public InvseeConfig config() { return config; }

    /** True only while {@code target} is the exact entity currently in the server's player list. */
    static boolean isOnline(ServerPlayer target) {
        MinecraftServer server = target.getServer();
        return server != null && server.getPlayerList().getPlayer(target.getUUID()) == target;
    }

    public void open(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null || !config.isEnabled()) return;
        if (!InvseePermissions.canView(viewer)) { viewer.sendSystemMessage(InvseeMessages.VIEW_PERMISSION); return; }
        if (viewer == target && !config.allowSelfView()) { viewer.sendSystemMessage(InvseeMessages.SELF_VIEW_DISABLED); return; }
        if (rejectUnavailableTarget(viewer, target)) return;
        close(viewer);
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                        new InvseeMenu(this, id, viewer, target, 1),
                Component.literal(target.getName().getString() + "'s inventory")));
        if (viewer.containerMenu instanceof InvseeMenu menu) openMenus.put(viewer.getUUID(), menu);
        log("{} opened {}'s inventory", viewer, target);
    }

    public void openSpectated(ServerPlayer viewer, ServerPlayer target) { open(viewer, target); }

    public void buttonClicked(InvseeMenu menu, InvseeConfig.Button button) {
        ServerPlayer viewer = menu.viewerForService();
        if ("next_page".equals(button.action)) { openPage(viewer, menu.target(), 2); return; }
        if ("previous_page".equals(button.action)) { openPage(viewer, menu.target(), 1); return; }
        if ("open_enderchest".equals(button.action)) { openEnderChest(viewer, menu.target()); return; }
        if ("open_backpack".equals(button.action)) openBackpack(viewer, menu.target());
    }

    /**
     * Tells the viewer when a target is offline or dead. Returns true when the action should stop.
     * Used when a viewer tries to open something; forced closes use {@link #targetClosedReason}.
     */
    private boolean rejectUnavailableTarget(ServerPlayer viewer, ServerPlayer target) {
        if (!isOnline(target)) { viewer.sendSystemMessage(InvseeMessages.TARGET_OFFLINE); return true; }
        if (!target.isAlive()) { viewer.sendSystemMessage(InvseeMessages.TARGET_DEAD); return true; }
        return false;
    }

    private void openPage(ServerPlayer viewer, ServerPlayer target, int page) {
        if (rejectUnavailableTarget(viewer, target)) { close(viewer); return; }
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                        new InvseeMenu(this, id, viewer, target, page),
                Component.literal(target.getName().getString() + "'s inventory")));
        if (viewer.containerMenu instanceof InvseeMenu menu) openMenus.put(viewer.getUUID(), menu);
    }

    private void openEnderChest(ServerPlayer viewer, ServerPlayer target) {
        if (!InvseePermissions.canOpenEnderChest(viewer)) { viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION); return; }
        if (rejectUnavailableTarget(viewer, target)) { close(viewer); return; }
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                        new EnderChestMenu(this, id, viewer, target),
                Component.literal(target.getName().getString() + "'s ender chest")));
        openMenus.remove(viewer.getUUID());
        if (viewer.containerMenu instanceof EnderChestMenu menu) openEnderChests.put(viewer.getUUID(), menu);
        log("{} opened {}'s ender chest", viewer, target);
    }

    private void openBackpack(ServerPlayer viewer, ServerPlayer target) {
        if (!InvseePermissions.canOpenBackpack(viewer)) { viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION); return; }
        if (rejectUnavailableTarget(viewer, target)) { close(viewer); return; }
        if (!TravelerBackpackCompat.isAvailable()) {
            viewer.sendSystemMessage(InvseeMessages.BACKPACK_OPEN_FAILED);
            return;
        }
        if (TravelerBackpackCompat.getWearingBackpack(target).isEmpty()) {
            SpectateMod.LOGGER.info("Invsee found no worn Traveler's Backpack on {} (target entity {}); if they are wearing one, check the console for a lookup warning",
                    target.getGameProfile().getName(), System.identityHashCode(target));
            viewer.sendSystemMessage(InvseeMessages.NO_BACKPACK);
            return;
        }
        close(viewer);
        restoreTemporaryBackpackItem(viewer);
        int clientSlot = stageBackpackForClient(viewer, TravelerBackpackCompat.getWearingBackpack(target));
        NetworkHooks.openScreen(viewer, new SimpleMenuProvider((id, inventory, player) ->
                        TravelerBackpackCompat.createNativeReadonlyMenu(id, viewer, target),
                Component.literal(target.getName().getString() + "'s backpack")), buffer -> {
                    // Screen id 99 makes the client factory read the synchronized backpack copy
                    // and marks this as an invsee-only screen for the client interaction guard.
                    // This works even when the target is outside the viewer's client tracking range.
                    buffer.writeInt(TravelerBackpackCompat.INVSEE_SCREEN_ID);
                    buffer.writeInt(clientSlot);
                });
        if (!isReadonlyBackpackMenu(viewer.containerMenu)) {
            restoreTemporaryBackpackItem(viewer);
            viewer.sendSystemMessage(InvseeMessages.BACKPACK_OPEN_FAILED);
            SpectateMod.LOGGER.warn("Could not open the read-only Traveler's Backpack menu for {} viewing {}", viewer.getGameProfile().getName(), target.getGameProfile().getName());
            return;
        }
        if (isReadonlyBackpackMenu(viewer.containerMenu)) {
            nativeBackpacks.put(viewer.getUUID(), viewer.containerMenu);
            nativeBackpackTargets.put(viewer.getUUID(), target);
            nativeBackpackViewers.put(viewer.getUUID(), viewer);
            // Traveler's Backpack's tabs, sort buttons and filters are custom packets, not container
            // clicks, so the connection guard must be present even for non-spectating viewers.
            InvseeClickInterceptor.install(viewer);
            InvseeClickInterceptor.beginBackpackSession(viewer, InvseePermissions.canEdit(viewer));
            InvseeNetwork.markBackpackScreen(viewer, viewer.containerMenu.containerId);
            Boolean toolSlots = TravelerBackpackCompat.showToolSlots(target);
            if (toolSlots != null) toolSlotsOnClient.put(viewer.getUUID(), toolSlots);
        }
        log("{} opened {}'s backpack", viewer, target);
    }

    void backpackPage(BackpackMenu current, int page) {
        openBackpackPage(current.viewer(), current.target(), current.view(), page);
    }

    private void openBackpackPage(ServerPlayer viewer, ServerPlayer target,
                                  TravelerBackpackCompat.BackpackView view, int page) {
        close(viewer);
        view.setPage(page);
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                        new BackpackMenu(this, id, viewer, target, view),
                Component.literal(target.getName().getString() + "'s backpack")));
        if (viewer.containerMenu instanceof BackpackMenu menu) openBackpacks.put(viewer.getUUID(), menu);
    }

    public void edited(ServerPlayer viewer, ServerPlayer target, int slot) { log("{} edited {}'s inventory slot {}", viewer, target, slot); }

    public void deniedEdit(ServerPlayer viewer) {
        long now = System.currentTimeMillis();
        Long last = lastDeniedAt.get(viewer.getUUID());
        if (last != null && now - last < DENIED_MESSAGE_COOLDOWN_MS) return;
        lastDeniedAt.put(viewer.getUUID(), now);
        viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION);
        log("{} was denied an invsee edit", viewer);
    }

    /**
     * Why a viewer session on {@code expected} must end because of the target, or null if the
     * target is still the same online, living entity. A different entity with the same UUID means
     * the target reconnected (or respawned), and the old session's data is stale either way.
     */
    private static Component targetClosedReason(MinecraftServer server, ServerPlayer expected) {
        ServerPlayer current = server.getPlayerList().getPlayer(expected.getUUID());
        if (current == null) return InvseeMessages.TARGET_LEFT;
        if (current != expected) return expected.isAlive() ? InvseeMessages.TARGET_LEFT : InvseeMessages.TARGET_DEAD;
        if (!current.isAlive()) return InvseeMessages.TARGET_DEAD;
        return null;
    }

    /**
     * Ends a viewer session when it is no longer valid and returns true if it did. Silent when the
     * viewer closed it or left; tells the viewer why when the target caused it.
     */
    private boolean endSessionIfInvalid(MinecraftServer server, ServerPlayer viewer, AbstractContainerMenu menu,
                                        ServerPlayer expectedTarget) {
        if (viewer == null || !viewer.isAlive() || viewer.containerMenu != menu) {
            if (viewer != null && viewer.containerMenu == menu) viewer.closeContainer();
            return true;
        }
        Component reason = expectedTarget == null ? null : targetClosedReason(server, expectedTarget);
        if (reason == null) return false;
        viewer.closeContainer();
        viewer.sendSystemMessage(reason);
        return true;
    }

    public void tick(MinecraftServer server) {
        openMenus.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            InvseeMenu menu = entry.getValue();
            if (endSessionIfInvalid(server, viewer, menu, menu.target())) return true;
            menu.refresh();
            return false;
        });
        openBackpacks.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            BackpackMenu menu = entry.getValue();
            if (endSessionIfInvalid(server, viewer, menu, menu.target())) return true;
            menu.broadcastChanges();
            return false;
        });
        openEnderChests.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            EnderChestMenu menu = entry.getValue();
            if (endSessionIfInvalid(server, viewer, menu, menu.target())) return true;
            menu.broadcastChanges();
            return false;
        });
        nativeBackpacks.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (endSessionIfInvalid(server, viewer, entry.getValue(), nativeBackpackTargets.get(entry.getKey()))) {
                nativeBackpackTargets.remove(entry.getKey());
                nativeBackpackViewers.remove(entry.getKey());
                InvseeClickInterceptor.endBackpackSession(entry.getKey());
                toolSlotsOnClient.remove(entry.getKey());
                return true;
            }
            // Keep the Netty-side guard in step with permission changes made while the menu is open.
            InvseeClickInterceptor.beginBackpackSession(viewer, InvseePermissions.canEdit(viewer));
            syncToolSlots(viewer, nativeBackpackTargets.get(entry.getKey()));
            entry.getValue().broadcastChanges();
            return false;
        });
        for (UUID viewerId : Set.copyOf(temporaryBackpackItems.keySet())) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
            if (viewer == null) temporaryBackpackItems.remove(viewerId);
            else restoreTemporaryBackpackItem(viewer);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID target = SpectateMod.getSpectateManager().getSpectatedTargetUuid(player.getUUID());
            if (target == null || shortcutTargets.get(player.getUUID()) == target) continue;
            ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
            if (targetPlayer != null) {
                MutableComponent message = Component.literal("[Open Inventory]").withStyle(style -> style.withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/invsee " + targetPlayer.getGameProfile().getName()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Open " + targetPlayer.getGameProfile().getName() + "'s inventory"))));
                player.sendSystemMessage(message); shortcutTargets.put(player.getUUID(), target);
            }
        }
    }

    /**
     * The backpack screen shows a private copy of the target's backpack, so when the target's real
     * "show tool slots" setting changes (an editor pressed the button, or the target did) the
     * viewer's screen has to be told. Tool slots only accept clicks while the real setting is on.
     * This is done with Traveler's own sync packet, so it works for every viewer without any
     * AdminScope code on their client.
     */
    private void syncToolSlots(ServerPlayer viewer, ServerPlayer target) {
        if (target == null) return;
        Boolean current = TravelerBackpackCompat.showToolSlots(target);
        if (current == null || current.equals(toolSlotsOnClient.get(viewer.getUUID()))) return;
        toolSlotsOnClient.put(viewer.getUUID(), current);
        boolean sent = TravelerBackpackCompat.syncShowToolSlotsToScreen(
                viewer, TravelerBackpackCompat.getWearingBackpack(target), current);
        SpectateMod.LOGGER.info("Invsee: {}'s tool slots are now {}; {} {}'s backpack screen",
                target.getGameProfile().getName(), current ? "shown" : "hidden",
                sent ? "refreshed" : "could not refresh", viewer.getGameProfile().getName());
    }

    public void close(ServerPlayer viewer) {
        if (viewer != null) {
            boolean hasNativeBackpack = nativeBackpacks.containsKey(viewer.getUUID());
            openMenus.remove(viewer.getUUID());
            openBackpacks.remove(viewer.getUUID());
            openEnderChests.remove(viewer.getUUID());
            nativeBackpacks.remove(viewer.getUUID());
            nativeBackpackTargets.remove(viewer.getUUID());
            nativeBackpackViewers.remove(viewer.getUUID());
            InvseeClickInterceptor.endBackpackSession(viewer.getUUID());
            toolSlotsOnClient.remove(viewer.getUUID());
            restoreTemporaryBackpackItem(viewer);
            if (viewer.containerMenu instanceof InvseeMenu || viewer.containerMenu instanceof BackpackMenu
                    || viewer.containerMenu instanceof EnderChestMenu || hasNativeBackpack) viewer.closeContainer();
        }
    }
    public void closeAll() {
        Set<ServerPlayer> viewers = new HashSet<>();
        openMenus.values().forEach(menu -> viewers.add(menu.viewerForService()));
        openBackpacks.values().forEach(menu -> viewers.add(menu.viewer()));
        openEnderChests.values().forEach(menu -> viewers.add(menu.viewerForService()));
        nativeBackpackViewers.values().forEach(viewers::add);
        toolSlotsOnClient.clear();
        for (ServerPlayer viewer : viewers) {
            InvseeClickInterceptor.endBackpackSession(viewer.getUUID());
            restoreTemporaryBackpackItem(viewer);
            viewer.closeContainer();
        }
        temporaryBackpackItems.clear();
        openMenus.clear(); openBackpacks.clear(); openEnderChests.clear(); nativeBackpacks.clear(); nativeBackpackTargets.clear(); nativeBackpackViewers.clear();
    }

    /**
     * Called while {@code player} is leaving. Closes the player's own viewer sessions, and also
     * closes and alerts every staff member who has this player's inventory, ender chest or
     * backpack open. The logout event fires before the player entity is removed, so waiting for
     * the menus' own validity checks would leave a window to edit data that was already saved.
     */
    public void onLogout(ServerPlayer player) {
        close(player);
        closeSessionsViewing(player);
        lastDeniedAt.remove(player.getUUID());
        shortcutTargets.remove(player.getUUID());
    }

    private void closeSessionsViewing(ServerPlayer leaving) {
        UUID leavingId = leaving.getUUID();
        Map<UUID, ServerPlayer> viewers = new HashMap<>();
        openMenus.values().forEach(menu -> {
            if (menu.target().getUUID().equals(leavingId)) viewers.put(menu.viewerForService().getUUID(), menu.viewerForService());
        });
        openBackpacks.values().forEach(menu -> {
            if (menu.target().getUUID().equals(leavingId)) viewers.put(menu.viewer().getUUID(), menu.viewer());
        });
        openEnderChests.values().forEach(menu -> {
            if (menu.target().getUUID().equals(leavingId)) viewers.put(menu.viewerForService().getUUID(), menu.viewerForService());
        });
        nativeBackpackTargets.forEach((viewerId, target) -> {
            if (!target.getUUID().equals(leavingId)) return;
            ServerPlayer viewer = nativeBackpackViewers.get(viewerId);
            if (viewer != null) viewers.put(viewerId, viewer);
        });
        viewers.remove(leavingId);
        for (ServerPlayer viewer : viewers.values()) {
            close(viewer);
            viewer.sendSystemMessage(InvseeMessages.TARGET_LEFT);
        }
    }

    public void reload() { closeAll(); config.load(); }

    private void log(String message, Object... args) { if (config.logStaffActions()) SpectateMod.LOGGER.info(message, args); }

    private int stageBackpackForClient(ServerPlayer viewer, ItemStack backpack) {
        Inventory inventory = viewer.getInventory();
        int slot = 0;
        for (int i = 0; i < inventory.items.size(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                slot = i;
                break;
            }
        }
        temporaryBackpackItems.put(viewer.getUUID(), new TemporaryBackpackItem(slot, inventory.getItem(slot).copy()));
        ItemStack clientBackpack = backpack.copy();
        // This marker survives the client-side BackpackWrapper construction and lets the
        // client guard identify invsee even when the viewer is wearing another backpack.
        clientBackpack.getOrCreateTag().putBoolean(TravelerBackpackCompat.INVSEE_CLIENT_TAG, true);
        inventory.setItem(slot, clientBackpack);
        viewer.containerMenu.broadcastChanges();
        return slot;
    }

    private void restoreTemporaryBackpackItem(ServerPlayer viewer) {
        TemporaryBackpackItem temporary = temporaryBackpackItems.remove(viewer.getUUID());
        if (temporary == null) return;
        viewer.getInventory().setItem(temporary.slot(), temporary.original().copy());
        viewer.containerMenu.broadcastChanges();
    }

    private static boolean isReadonlyBackpackMenu(AbstractContainerMenu menu) {
        return "com.spectatemod.forge.invsee.ReadonlyBackpackMenu".equals(menu.getClass().getName());
    }

    private record TemporaryBackpackItem(int slot, ItemStack original) { }
}