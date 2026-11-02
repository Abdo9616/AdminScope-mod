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

    @Override
    public boolean stillValid(Player player) {
        return player == viewer && target.isAlive();
    }

    // quickMoveStack is intentionally not overridden: vanilla ChestMenu handles
    // shift-click between the ender chest and the viewer's inventory.

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer) || player != viewer || !InvseePermissions.canEdit(viewer)) {
            service.deniedEdit(viewer);
            return;
        }
        super.clicked(slot, button, type, player);
        service.edited(viewer, target, slot);
    }
}