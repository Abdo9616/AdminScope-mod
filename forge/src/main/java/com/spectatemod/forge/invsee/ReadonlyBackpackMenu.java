package com.spectatemod.forge.invsee;

import net.minecraft.server.level.ServerPlayer;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackItemMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Traveler's Backpack's own screen/menu, with edits gated by invsee.edit. */
public final class ReadonlyBackpackMenu extends BackpackItemMenu {
    private final ServerPlayer viewer;

    public ReadonlyBackpackMenu(int windowId, Inventory playerInventory, BackpackWrapper wrapper) {
        super(windowId, playerInventory, wrapper);
        this.viewer = playerInventory.player instanceof ServerPlayer player ? player : null;
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        if (canEdit()) {
            super.clicked(slotId, dragType, clickType, player);
        } else {
            // Undo the client's predicted move and tell the viewer why, like the other invsee menus.
            sendAllDataToRemote();
            InvseeService.notifyDenied(viewer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return canEdit() ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return canEdit();
    }

    @Override
    public boolean canDragTo(net.minecraft.world.inventory.Slot slot) {
        return canEdit();
    }

    @Override
    public void removed(Player player) {
        getWrapper().getPlayersUsing().remove(player);
        // This override skips the normal menu cleanup, so an item still on the cursor would
        // vanish. Hand it back to the viewer (inventory first, dropped if full).
        ItemStack carried = getCarried();
        if (!carried.isEmpty()) {
            player.getInventory().placeItemBackInInventory(carried);
            setCarried(ItemStack.EMPTY);
        }
    }

    public boolean canEdit() {
        return viewer != null && InvseePermissions.canEdit(viewer);
    }
}