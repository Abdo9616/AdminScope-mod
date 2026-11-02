package com.spectatemod.forge.invsee;

import com.spectatemod.SpectateMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Optional, reflection-only Traveler's Backpack bridge; AdminScope does not link to its classes. */
final class TravelerBackpackCompat {
    static final int INVSEE_SCREEN_ID = 99;
    static final String INVSEE_CLIENT_TAG = "adminscope_invsee_backpack";

    private TravelerBackpackCompat() { }

    static boolean isAvailable() {
        if (!ModList.get().isLoaded("travelersbackpack")) return false;
        try {
            Class.forName("com.tiviacz.travelersbackpack.inventory.menu.BackpackItemMenu");
            Class.forName("com.spectatemod.forge.invsee.ReadonlyBackpackMenu");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static AbstractContainerMenu createNativeReadonlyMenu(int windowId, ServerPlayer viewer, ServerPlayer target) {
        if (!isAvailable()) return null;
        try {
            Class<?> utils = Class.forName("com.tiviacz.travelersbackpack.capability.CapabilityUtils");
            Class<?> wrapperClass = Class.forName("com.tiviacz.travelersbackpack.inventory.BackpackWrapper");
            Object wrapper = utils.getMethod("getBackpackWrapper", Player.class).invoke(null, target);
            if (wrapper == null) return null;
            Class<?> menuClass = Class.forName("com.spectatemod.forge.invsee.ReadonlyBackpackMenu");
            Object menu = menuClass.getConstructor(int.class, Inventory.class, wrapperClass)
                    .newInstance(windowId, viewer.getInventory(), wrapper);
            return menu instanceof AbstractContainerMenu abstractMenu ? abstractMenu : null;
        } catch (Throwable failure) {
            SpectateMod.LOGGER.warn("Could not open Traveler's Backpack's native menu for invsee", failure);
            return null;
        }
    }

    static BackpackView find(ServerPlayer target) {
        if (!ModList.get().isLoaded("travelersbackpack")) return null;
        try {
            Class<?> utils = Class.forName("com.tiviacz.travelersbackpack.capability.CapabilityUtils");
            Method get = utils.getMethod("getBackpackWrapper", Player.class);
            Object wrapper = get.invoke(null, target);
            if (wrapper == null) return null;
            Object handler = wrapper.getClass().getField("inventory").get(wrapper);
            return new BackpackView(target, handler);
        } catch (Throwable failure) {
            SpectateMod.LOGGER.warn("Traveler's Backpack was detected, but its storage API could not be opened", failure);
            return null;
        }
    }

    static ItemStack getWearingBackpack(ServerPlayer target) {
        if (!ModList.get().isLoaded("travelersbackpack")) return ItemStack.EMPTY;
        try {
            Class<?> utils = Class.forName("com.tiviacz.travelersbackpack.capability.CapabilityUtils");
            Method get = utils.getMethod("getWearingBackpack", Player.class);
            Object stack = get.invoke(null, target);
            return stack instanceof ItemStack itemStack ? itemStack.copy() : ItemStack.EMPTY;
        } catch (Throwable failure) {
            SpectateMod.LOGGER.debug("Could not read the worn Traveler's Backpack icon", failure);
            return ItemStack.EMPTY;
        }
    }

    static final class BackpackView implements Container {
        private final ServerPlayer target;
        private final Object handler;
        private final Method slots, getStack, extract, setStack;
        private int page;

        BackpackView(ServerPlayer target, Object handler) throws ReflectiveOperationException {
            this.target = target;
            this.handler = handler;
            Class<?> type = handler.getClass();
            slots = type.getMethod("getSlots");
            getStack = type.getMethod("getStackInSlot", int.class);
            extract = type.getMethod("extractItem", int.class, int.class, boolean.class);
            setStack = type.getMethod("setStackInSlot", int.class, ItemStack.class);
        }

        int storageSize() { try { return (int) slots.invoke(handler); } catch (Exception e) { return 0; } }
        int pageCount() { return Math.max(1, (int) Math.ceil(storageSize() / 52.0)); }
        int page() { return page; }
        void setPage(int page) { this.page = Math.max(0, Math.min(page, pageCount() - 1)); }
        boolean hasControls() { return pageCount() > 1; }
        int rows() { return hasControls() ? 6 : Math.max(1, Math.min(6, (storageSize() + 8) / 9)); }

        private int storageIndex(int displaySlot) {
            if (displaySlot < 0 || displaySlot >= 54) return -1;
            if (hasControls() && (displaySlot == 45 || displaySlot == 53)) return -1;
            int logical = displaySlot;
            if (hasControls() && displaySlot > 45) logical--;
            int index = page * 52 + logical;
            return index < storageSize() ? index : -1;
        }

        private ItemStack handlerStack(int index) {
            try { return (ItemStack) getStack.invoke(handler, index); } catch (Exception e) { return ItemStack.EMPTY; }
        }

        private ItemStack controlStack(int slot) {
            if (!hasControls()) return ItemStack.EMPTY;
            if (slot == 45 && page > 0) return new ItemStack(Items.ARROW);
            if (slot == 53 && page < pageCount() - 1) return new ItemStack(Items.ARROW);
            return ItemStack.EMPTY;
        }

        boolean isControl(int slot) { return !controlStack(slot).isEmpty(); }
        String controlName(int slot) { return slot == 45 ? "Previous backpack page" : "Next backpack page"; }
        int targetPage(int slot) { return slot == 45 ? page - 1 : page + 1; }

        @Override public int getContainerSize() { return 54; }
        @Override public boolean isEmpty() { for (int i = 0; i < storageSize(); i++) if (!handlerStack(i).isEmpty()) return false; return true; }
        @Override public ItemStack getItem(int slot) {
            ItemStack control = controlStack(slot);
            if (!control.isEmpty()) return control;
            int index = storageIndex(slot);
            return index < 0 ? ItemStack.EMPTY : handlerStack(index);
        }
        @Override public ItemStack removeItem(int slot, int amount) {
            int index = storageIndex(slot);
            if (index < 0) return ItemStack.EMPTY;
            try { return (ItemStack) extract.invoke(handler, index, amount, false); } catch (Exception e) { return ItemStack.EMPTY; }
        }
        @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, getItem(slot).getCount()); }
        @Override public void setItem(int slot, ItemStack value) {
            int index = storageIndex(slot);
            if (index >= 0) try { setStack.invoke(handler, index, value); } catch (Exception ignored) { }
        }
        @Override public void setChanged() { }
        @Override public boolean stillValid(Player player) { return player == target && target.isAlive(); }
        @Override public void clearContent() { for (int i = 0; i < storageSize(); i++) try { setStack.invoke(handler, i, ItemStack.EMPTY); } catch (Exception ignored) { } }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return storageIndex(slot) >= 0; }
    }
}
