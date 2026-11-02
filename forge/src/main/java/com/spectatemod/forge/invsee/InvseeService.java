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
    private final InvseeConfig config = new InvseeConfig();
    private final Map<UUID, InvseeMenu> openMenus = new HashMap<>();
    private final Map<UUID, BackpackMenu> openBackpacks = new HashMap<>();
    private final Map<UUID, EnderChestMenu> openEnderChests = new HashMap<>();
    private final Map<UUID, AbstractContainerMenu> nativeBackpacks = new HashMap<>();
    private final Map<UUID, UUID> nativeBackpackTargets = new HashMap<>();
    private final Map<UUID, ServerPlayer> nativeBackpackViewers = new HashMap<>();
    private final Map<UUID, TemporaryBackpackItem> temporaryBackpackItems = new HashMap<>();
    private final Map<UUID, UUID> shortcutTargets = new HashMap<>();

    public InvseeConfig config() { return config; }

    public void open(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null || !config.isEnabled()) return;
        if (!InvseePermissions.canView(viewer)) { viewer.sendSystemMessage(InvseeMessages.VIEW_PERMISSION); return; }
        if (viewer == target && !config.allowSelfView()) { viewer.sendSystemMessage(InvseeMessages.SELF_VIEW_DISABLED); return; }
        if (!target.isAlive()) { viewer.sendSystemMessage(InvseeMessages.TARGET_DEAD); return; }
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

    private void openPage(ServerPlayer viewer, ServerPlayer target, int page) {
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                        new InvseeMenu(this, id, viewer, target, page),
                Component.literal(target.getName().getString() + "'s inventory")));
        if (viewer.containerMenu instanceof InvseeMenu menu) openMenus.put(viewer.getUUID(), menu);
    }

    private void openEnderChest(ServerPlayer viewer, ServerPlayer target) {
        if (!InvseePermissions.canOpenEnderChest(viewer)) { viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION); return; }
        if (!target.isAlive()) { viewer.sendSystemMessage(InvseeMessages.TARGET_DEAD); return; }
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                        new EnderChestMenu(this, id, viewer, target),
                Component.literal(target.getName().getString() + "'s ender chest")));
        openMenus.remove(viewer.getUUID());
        if (viewer.containerMenu instanceof EnderChestMenu menu) openEnderChests.put(viewer.getUUID(), menu);
        log("{} opened {}'s ender chest", viewer, target);
    }

    private void openBackpack(ServerPlayer viewer, ServerPlayer target) {
        if (!InvseePermissions.canOpenBackpack(viewer)) { viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION); return; }
        if (!target.isAlive()) { viewer.sendSystemMessage(InvseeMessages.TARGET_DEAD); return; }
        if (!TravelerBackpackCompat.isAvailable()) {
            viewer.sendSystemMessage(InvseeMessages.BACKPACK_OPEN_FAILED);
            return;
        }
        if (TravelerBackpackCompat.getWearingBackpack(target).isEmpty()) {
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
            nativeBackpackTargets.put(viewer.getUUID(), target.getUUID());
            nativeBackpackViewers.put(viewer.getUUID(), viewer);
            InvseeNetwork.markBackpackScreen(viewer, viewer.containerMenu.containerId);
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
    public void deniedEdit(ServerPlayer viewer) { viewer.sendSystemMessage(InvseeMessages.NO_PERMISSION); log("{} was denied an invsee edit", viewer); }

    public void tick(MinecraftServer server) {
        openMenus.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            InvseeMenu menu = entry.getValue();
            ServerPlayer target = server.getPlayerList().getPlayer(menu.target().getUUID());
            if (viewer == null || target == null || !viewer.isAlive() || !target.isAlive() || viewer.containerMenu != menu) {
                if (viewer != null && viewer.containerMenu == menu) viewer.closeContainer();
                return true;
            }
            menu.refresh(); return false;
        });
        openBackpacks.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            BackpackMenu menu = entry.getValue();
            ServerPlayer target = server.getPlayerList().getPlayer(menu.target().getUUID());
            if (viewer == null || target == null || !viewer.isAlive() || !target.isAlive() || viewer.containerMenu != menu) {
                if (viewer != null && viewer.containerMenu == menu) viewer.closeContainer();
                return true;
            }
            menu.broadcastChanges(); return false;
        });
        openEnderChests.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            EnderChestMenu menu = entry.getValue();
            if (viewer == null || !viewer.isAlive() || viewer.containerMenu != menu) {
                if (viewer != null && viewer.containerMenu == menu) viewer.closeContainer();
                return true;
            }
            menu.broadcastChanges(); return false;
        });
        nativeBackpacks.entrySet().removeIf(entry -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            ServerPlayer target = server.getPlayerList().getPlayer(nativeBackpackTargets.get(entry.getKey()));
            if (viewer == null || target == null || !viewer.isAlive() || !target.isAlive() || viewer.containerMenu != entry.getValue()) {
                if (viewer != null && viewer.containerMenu == entry.getValue()) viewer.closeContainer();
                nativeBackpackTargets.remove(entry.getKey());
                nativeBackpackViewers.remove(entry.getKey());
                return true;
            }
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

    public void close(ServerPlayer viewer) {
        if (viewer != null) {
            boolean hasNativeBackpack = nativeBackpacks.containsKey(viewer.getUUID());
            openMenus.remove(viewer.getUUID());
            openBackpacks.remove(viewer.getUUID());
            openEnderChests.remove(viewer.getUUID());
            nativeBackpacks.remove(viewer.getUUID());
            nativeBackpackTargets.remove(viewer.getUUID());
            nativeBackpackViewers.remove(viewer.getUUID());
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
        for (ServerPlayer viewer : viewers) {
            restoreTemporaryBackpackItem(viewer);
            viewer.closeContainer();
        }
        temporaryBackpackItems.clear();
        openMenus.clear(); openBackpacks.clear(); openEnderChests.clear(); nativeBackpacks.clear(); nativeBackpackTargets.clear(); nativeBackpackViewers.clear();
    }
    public void onLogout(ServerPlayer player) {
        close(player);
        openMenus.values().removeIf(menu -> menu.target() == player);
        openBackpacks.values().removeIf(menu -> menu.target() == player);
        openEnderChests.remove(player.getUUID());
        nativeBackpackTargets.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(player.getUUID())) return false;
            nativeBackpacks.remove(entry.getKey());
            nativeBackpackViewers.remove(entry.getKey());
            return true;
        });
        shortcutTargets.remove(player.getUUID());
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
