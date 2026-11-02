package com.spectatemod.forge.invsee;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** A vanilla generic chest menu backed by the target's live inventory. */
public final class InvseeMenu extends ChestMenu {
    private final InvseeService service;
    private final ServerPlayer viewer;
    private final ServerPlayer target;
    private final MappedContainer mapped;
    private final int page;
    private final Map<Integer, InvseeConfig.Button> buttons = new HashMap<>();

    public InvseeMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target, int page) {
        this(service, id, viewer, target, page, new MappedContainer(target, page, service.config()));
    }

    private InvseeMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target, int page, MappedContainer mapped) {
        super(MenuType.GENERIC_9x6, id, viewer.getInventory(), mapped, 6);
        this.service = service; this.viewer = viewer; this.target = target; this.mapped = mapped; this.page = page;
        for (InvseeConfig.Button button : service.config().buttons()) {
            if ("open_backpack".equals(button.action) && !TravelerBackpackCompat.isAvailable()) continue;
            if (button.page == page && button.slot >= 0 && button.slot < 54) buttons.put(button.slot, button);
        }
        for (int slot = 0; slot < 54; slot++) {
            final int index = slot;
            if (buttons.containsKey(slot)) this.slots.set(slot, new LockedSlot(mapped, slot, 0, 0));
            else if (page != 1 || !mapped.isTargetSlot(slot)) this.slots.set(slot, new LockedSlot(mapped, slot, 0, 0));
            else this.slots.set(slot, new TargetSlot(mapped, slot, 0, 0, viewer));
        }
    }

    public ServerPlayer target() { return target; }
    ServerPlayer viewerForService() { return viewer; }
    public int page() { return page; }

    @Override public boolean stillValid(Player player) { return player == viewer && viewer.isAlive() && target.isAlive() && target.connection != null; }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer != viewer) return;
        if (slot >= 0 && slot < 54) {
            InvseeConfig.Button menuButton = buttons.get(slot);
            if (menuButton != null) { service.buttonClicked(this, menuButton); return; }
        }
        if (!InvseePermissions.canEdit(viewer)) {
            service.deniedEdit(viewer);
            return;
        }
        super.clicked(slot, button, type, player);
        service.edited(viewer, target, slot);
    }

    public void refresh() { sendAllDataToRemote(); }

    private static final class LockedSlot extends Slot {
        LockedSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    private static final class TargetSlot extends Slot {
        private final ServerPlayer viewer;
        TargetSlot(Container container, int index, int x, int y, ServerPlayer viewer) { super(container, index, x, y); this.viewer = viewer; }
        @Override public boolean mayPlace(ItemStack stack) { return InvseePermissions.canEdit(viewer) && super.mayPlace(stack); }
        @Override public boolean mayPickup(Player player) { return InvseePermissions.canEdit(viewer); }
    }

    static final class MappedContainer implements Container {
        private final ServerPlayer target; private final int page;
        MappedContainer(ServerPlayer target, int page, InvseeConfig config) { this.target = target; this.page = page; this.configButtons = config.buttons(); }
        boolean isTargetSlot(int slot) { return page == 1 && targetIndex(slot) >= 0; }
        private int targetIndex(int slot) {
            if (page != 1) return -1;
            if (slot < 4) return 39 - slot;
            if (slot == 4) return 40;
            if (slot >= 9 && slot < 36) return slot;
            if (slot >= 45 && slot < 54) return slot - 45;
            return -1;
        }
        @Override public int getContainerSize() { return 54; }
        @Override public boolean isEmpty() { for (int i=0;i<54;i++) if (!getItem(i).isEmpty()) return false; return true; }
        @Override public ItemStack getItem(int slot) {
            InvseeConfig.Button button = button(slot);
            if (button != null) return createButton(button);
            int index=targetIndex(slot); return index < 0 ? ItemStack.EMPTY : target.getInventory().getItem(index);
        }
        @Override public ItemStack removeItem(int slot, int amount) { int index=targetIndex(slot); return index < 0 ? ItemStack.EMPTY : target.getInventory().removeItem(index, amount); }
        @Override public ItemStack removeItemNoUpdate(int slot) { int index=targetIndex(slot); return index < 0 ? ItemStack.EMPTY : target.getInventory().removeItemNoUpdate(index); }
        @Override public void setItem(int slot, ItemStack stack) { if (button(slot) != null) return; int index=targetIndex(slot); if (index >= 0) target.getInventory().setItem(index, stack); }
        @Override public void setChanged() { target.getInventory().setChanged(); }
        @Override public boolean stillValid(Player player) { return player != null && target.isAlive(); }
        @Override public void clearContent() { for (int i=0;i<54;i++) setItem(i, ItemStack.EMPTY); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return isTargetSlot(slot); }

        private InvseeConfig.Button button(int slot) {
            for (InvseeConfig.Button button : configButtons) {
                if ("open_backpack".equals(button.action) && !TravelerBackpackCompat.isAvailable()) continue;
                if (button.page == page && button.slot == slot) return button;
            }
            return null;
        }
        private final java.util.List<InvseeConfig.Button> configButtons;
        private ItemStack createButton(InvseeConfig.Button button) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(button.item));
            ItemStack stack;
            if ("open_backpack".equals(button.action)) {
                stack = TravelerBackpackCompat.getWearingBackpack(target);
                if (stack.isEmpty()) {
                    Item backpack = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse("travelersbackpack:standard"));
                    stack = backpack == Items.AIR ? ItemStack.EMPTY : new ItemStack(backpack);
                }
                if (stack.isEmpty()) stack = new ItemStack(Items.CHEST);
            } else {
                stack = item == null || item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
            }
            String title = styleTitle(button, replace(button.title));
            stack.setHoverName(Component.literal(title));
            ListTag lore = new ListTag();
            for (String line : button.lines) lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(replace(line)))));
            if ("health".equals(button.id) && button.lines.stream().noneMatch(line -> line.contains("{effects}"))) {
                lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("§7Effects: " + effects()))));
            }
            if (!lore.isEmpty()) stack.getOrCreateTagElement("display").put("Lore", lore);
            return stack;
        }

        private String replace(String value) {
            return value.replace("{xp_level}", Integer.toString(target.experienceLevel))
                    .replace("{xp_total}", Integer.toString(target.totalExperience))
                    .replace("{health}", format(target.getHealth()))
                    .replace("{max_health}", format(target.getMaxHealth()))
                    .replace("{hunger}", Integer.toString(target.getFoodData().getFoodLevel()))
                    .replace("{food}", Integer.toString(target.getFoodData().getFoodLevel()))
                    .replace("Food:", "Hunger:")
                    .replace("{dimension}", target.level().dimension().location().toString())
                    .replace("{xyz}", String.format("%.1f, %.1f, %.1f", target.getX(), target.getY(), target.getZ()))
                    .replace("{effects}", effects());
        }

        private String styleTitle(InvseeConfig.Button button, String title) {
            String color = switch (button.id) {
                case "xp" -> "§e";
                case "enderchest" -> "§6";
                case "location" -> "§b";
                case "next_page" -> "§a";
                case "previous_page" -> "§c";
                default -> null;
            };
            if (color == null) return title;
            return title.length() >= 2 && title.charAt(0) == '§' ? color + title.substring(2) : color + title;
        }

        private String effects() {
            if (target.getActiveEffects().isEmpty()) return "None";
            StringBuilder result = new StringBuilder();
            target.getActiveEffects().forEach(effect -> {
                if (result.length() > 0) result.append(", ");
                result.append(effect.getEffect().getDisplayName().getString()).append(" ").append(effect.getAmplifier() + 1);
            });
            return result.toString();
        }

        private String format(float value) { return String.format("%.1f", value); }
    }
}
