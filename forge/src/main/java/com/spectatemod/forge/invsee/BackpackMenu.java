package com.spectatemod.forge.invsee;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Vanilla-compatible shell around Traveler's Backpack storage slots. */
final class BackpackMenu extends ChestMenu {
    private final InvseeService service; private final ServerPlayer viewer; private final ServerPlayer target;
    private final TravelerBackpackCompat.BackpackView view;
    BackpackMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target, TravelerBackpackCompat.BackpackView view) {
        super(menuType(view.rows()), id, viewer.getInventory(), view, view.rows());
        this.service=service; this.viewer=viewer; this.target=target; this.view=view;
        int topSlots = view.rows() * 9;
        for (int i=0;i<topSlots;i++) { final int displaySlot = i; this.slots.set(i, new Slot(view, i, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return view.isControl(displaySlot) ? false : InvseePermissions.canEdit(viewer) && super.mayPlace(stack); }
            @Override public boolean mayPickup(Player player) { return !view.isControl(displaySlot) && InvseePermissions.canEdit(viewer); }
        });
        }
        for (int i=topSlots;i<this.slots.size();i++) { final int inventorySlot = i < topSlots + 27 ? i - topSlots + 9 : i - topSlots - 27; this.slots.set(i, new Slot(viewer.getInventory(), inventorySlot, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        }); }
    }
    @Override public boolean stillValid(Player player) { return player == viewer && target.isAlive(); }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        int topSlots = view.rows() * 9;
        if (slot >= 0 && slot < topSlots && view.isControl(slot)) { service.backpackPage(this, view.targetPage(slot)); return; }
        if (!(player instanceof ServerPlayer) || player != viewer || !InvseePermissions.canEdit(viewer)) { service.deniedEdit(viewer); return; }
        super.clicked(slot, button, type, player); service.edited(viewer, target, slot);
    }

    ServerPlayer viewer() { return viewer; }
    ServerPlayer target() { return target; }
    TravelerBackpackCompat.BackpackView view() { return view; }

    private static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }
}
