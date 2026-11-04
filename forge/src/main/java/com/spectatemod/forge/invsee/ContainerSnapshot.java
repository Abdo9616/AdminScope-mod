package com.spectatemod.forge.invsee;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Copy of a container's contents, used to log a staff edit only when something actually changed. */
final class ContainerSnapshot {
    private final ItemStack[] stacks;

    private ContainerSnapshot(ItemStack[] stacks) {
        this.stacks = stacks;
    }

    static ContainerSnapshot of(Container container) {
        ItemStack[] copy = new ItemStack[container.getContainerSize()];
        for (int i = 0; i < copy.length; i++) copy[i] = container.getItem(i).copy();
        return new ContainerSnapshot(copy);
    }

    boolean differsFrom(Container container) {
        if (container.getContainerSize() != stacks.length) return true;
        for (int i = 0; i < stacks.length; i++) {
            if (!ItemStack.matches(stacks[i], container.getItem(i))) return true;
        }
        return false;
    }
}