package com.spectatemod.forge.invsee;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class EnderChestMenu extends ChestMenu {
    private final InvseeService service; private final ServerPlayer viewer; private final ServerPlayer target;
    EnderChestMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target) {
        super(MenuType.GENERIC_9x3, id, viewer.getInventory(), target.getEnderChestInventory(), 3);
        this.service=service; this.viewer=viewer; this.target=target;
        for (int i=27;i<slots.size();i++) { final int inventorySlot = i < 51 ? i - 27 + 9 : i - 51; slots.set(i, new Slot(viewer.getInventory(), inventorySlot, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        }); }
    }
    ServerPlayer viewerForService() { return viewer; }
    @Override public boolean stillValid(Player player) { return player == viewer && target.isAlive(); }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer) || player != viewer || !InvseePermissions.canEdit(viewer)) { service.deniedEdit(viewer); return; }
        super.clicked(slot, button, type, player); service.edited(viewer, target, slot);
    }
}
