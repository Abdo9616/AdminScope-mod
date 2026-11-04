package com.spectatemod.forge.invsee;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;

/**
 * A vanilla 9x3 chest menu backed by the target's ender chest.
 * The viewer's own inventory slots are left as normal vanilla slots so items can be
 * moved from the ender chest into the viewer's inventory (drag, click, shift-click).
 */
final class EnderChestMenu extends ChestMenu {
    private final InvseeService service;
    private final ServerPlayer viewer;
    private final ServerPlayer target;

    EnderChestMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target) {
        super(MenuType.GENERIC_9x3, id, viewer.getInventory(), target.getEnderChestInventory(), 3);
        this.service = service;
        this.viewer = viewer;
        this.target = target;
    }

    ServerPlayer viewerForService() {
        return viewer;
    }

    ServerPlayer target() {
        return target;
    }

    /**
     * The target must still be the exact online player entity. After a logout the old entity
     * lingers with its data already saved, so editing it would duplicate or lose items.
     */
    @Override
    public boolean stillValid(Player player) {
        return player == viewer && viewer.isAlive() && InvseeService.isOnline(target) && target.isAlive();
    }

    // quickMoveStack is intentionally not overridden: vanilla ChestMenu handles
    // shift-click between the ender chest and the viewer's inventory.

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer) || player != viewer || !InvseePermissions.canEdit(viewer)) {
            service.deniedEdit(viewer);
            return;
        }
        ContainerSnapshot before = ContainerSnapshot.of(target.getEnderChestInventory());
        super.clicked(slot, button, type, player);
        if (before.differsFrom(target.getEnderChestInventory())) service.edited(viewer, target, slot);
    }
}