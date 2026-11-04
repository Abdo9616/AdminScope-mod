package com.spectatemod.forge.invsee;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A vanilla generic chest menu backed by the target's live inventory.
 *
 * Page 1 layout (5 rows): row 0 = armor (0-3), offhand (4) and buttons (5-8), rows 1-3 = main
 * inventory, row 4 = hotbar. Other pages are 6 rows of buttons only. The row count per page
 * comes from {@link InvseeConfig#rowsForPage(int)}.
 */
public final class InvseeMenu extends ChestMenu {
    /** Display slots 0-3 show the target's head, chest, legs and feet armor in that order. */
    private static final EquipmentSlot[] ARMOR_BY_DISPLAY_SLOT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final InvseeService service;
    private final ServerPlayer viewer;
    private final ServerPlayer target;
    private final MappedContainer mapped;
    private final int page;
    private final Map<Integer, InvseeConfig.Button> buttons;

    public InvseeMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target, int page) {
        this(service, id, viewer, target, page, resolveButtons(page, service.config()));
    }

    private InvseeMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target, int page,
                       Map<Integer, InvseeConfig.Button> buttons) {
        this(service, id, viewer, target, page, buttons, new MappedContainer(target, page, buttons));
    }

    private InvseeMenu(InvseeService service, int id, ServerPlayer viewer, ServerPlayer target, int page,
                       Map<Integer, InvseeConfig.Button> buttons, MappedContainer mapped) {
        super(menuTypeFor(page), id, viewer.getInventory(), mapped, InvseeConfig.rowsForPage(page));
        this.service = service;
        this.viewer = viewer;
        this.target = target;
        this.mapped = mapped;
        this.page = page;
        this.buttons = buttons;
        int size = InvseeConfig.rowsForPage(page) * 9;
        for (int slot = 0; slot < size; slot++) {
            if (buttons.containsKey(slot) || !mapped.isTargetSlot(slot)) {
                this.slots.set(slot, new LockedSlot(mapped, slot, 0, 0));
            } else {
                this.slots.set(slot, new TargetSlot(mapped, slot, 0, 0, viewer, armorSlotFor(page, slot)));
            }
        }
    }

    /** The buttons that are actually active on this page; the single source of truth for display and clicks. */
    private static Map<Integer, InvseeConfig.Button> resolveButtons(int page, InvseeConfig config) {
        Map<Integer, InvseeConfig.Button> result = new HashMap<>();
        int size = InvseeConfig.rowsForPage(page) * 9;
        boolean backpackAvailable = TravelerBackpackCompat.isAvailable();
        for (InvseeConfig.Button button : config.buttons()) {
            if (button.page != page || button.slot < 0 || button.slot >= size) continue;
            if ("open_backpack".equals(button.action) && !backpackAvailable) continue;
            result.putIfAbsent(button.slot, button);
        }
        return Collections.unmodifiableMap(result);
    }

    private static MenuType<ChestMenu> menuTypeFor(int page) {
        return InvseeConfig.rowsForPage(page) == 5 ? MenuType.GENERIC_9x5 : MenuType.GENERIC_9x6;
    }

    private static EquipmentSlot armorSlotFor(int page, int slot) {
        return page == 1 && slot >= 0 && slot < ARMOR_BY_DISPLAY_SLOT.length ? ARMOR_BY_DISPLAY_SLOT[slot] : null;
    }

    public ServerPlayer target() { return target; }
    ServerPlayer viewerForService() { return viewer; }
    public int page() { return page; }

    /**
     * The target must still be the exact online player entity. After a logout the old entity
     * lingers with its data already saved, so editing it would duplicate or lose items.
     */
    @Override
    public boolean stillValid(Player player) {
        return player == viewer && viewer.isAlive() && InvseeService.isOnline(target) && target.isAlive();
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer != viewer) return;
        int size = InvseeConfig.rowsForPage(page) * 9;
        if (slot >= 0 && slot < size) {
            InvseeConfig.Button menuButton = buttons.get(slot);
            if (menuButton != null) {
                // Only a plain left/right click activates a button; drags, number-key swaps,
                // shift-clicks and double-click collects on a button slot do nothing.
                if (type == ClickType.PICKUP && (button == 0 || button == 1)) {
                    service.buttonClicked(this, menuButton);
                }
                return;
            }
        }
        if (!InvseePermissions.canEdit(viewer)) {
            service.deniedEdit(viewer);
            return;
        }
        ContainerSnapshot before = ContainerSnapshot.of(target.getInventory());
        super.clicked(slot, button, type, player);
        if (before.differsFrom(target.getInventory())) service.edited(viewer, target, slot);
    }

    public void refresh() { sendAllDataToRemote(); }

    private static final class LockedSlot extends Slot {
        LockedSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    private static final class TargetSlot extends Slot {
        private final ServerPlayer viewer;
        /** Non-null for the four armor display slots; only matching armor may be placed there. */
        private final EquipmentSlot armorSlot;

        TargetSlot(Container container, int index, int x, int y, ServerPlayer viewer, EquipmentSlot armorSlot) {
            super(container, index, x, y);
            this.viewer = viewer;
            this.armorSlot = armorSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (!InvseePermissions.canEdit(viewer)) return false;
            if (armorSlot != null && Mob.getEquipmentSlotForItem(stack) != armorSlot) return false;
            return super.mayPlace(stack);
        }

        @Override public boolean mayPickup(Player player) { return InvseePermissions.canEdit(viewer); }

        @Override
        public int getMaxStackSize() { return armorSlot != null ? 1 : super.getMaxStackSize(); }
    }

    static final class MappedContainer implements Container {
        /** Button items are rebuilt at most this often instead of on every menu sync. */
        private static final long BUTTON_REFRESH_MS = 500L;

        private final ServerPlayer target;
        private final int page;
        private final int rows;
        private final Map<Integer, InvseeConfig.Button> buttons;
        private final Map<Integer, CachedButton> buttonCache = new HashMap<>();

        MappedContainer(ServerPlayer target, int page, Map<Integer, InvseeConfig.Button> buttons) {
            this.target = target;
            this.page = page;
            this.rows = InvseeConfig.rowsForPage(page);
            this.buttons = buttons;
        }

        boolean isTargetSlot(int slot) { return targetIndex(slot) >= 0; }

        private int targetIndex(int slot) {
            if (page != 1 || slot < 0) return -1;
            if (slot < 4) return 39 - slot;
            if (slot == 4) return 40;
            if (slot >= 9 && slot < 36) return slot;
            int hotbarStart = (rows - 1) * 9;
            if (slot >= hotbarStart && slot < hotbarStart + 9) return slot - hotbarStart;
            return -1;
        }

        /** Target inventory index for a slot that may be modified; button slots are never editable. */
        private int editableIndex(int slot) { return buttons.containsKey(slot) ? -1 : targetIndex(slot); }

        @Override public int getContainerSize() { return rows * 9; }
        @Override public boolean isEmpty() { for (int i = 0; i < getContainerSize(); i++) if (!getItem(i).isEmpty()) return false; return true; }
        @Override public ItemStack getItem(int slot) {
            InvseeConfig.Button button = buttons.get(slot);
            if (button != null) return cachedButton(slot, button);
            int index = targetIndex(slot);
            return index < 0 ? ItemStack.EMPTY : target.getInventory().getItem(index);
        }
        @Override public ItemStack removeItem(int slot, int amount) {
            int index = editableIndex(slot);
            return index < 0 ? ItemStack.EMPTY : target.getInventory().removeItem(index, amount);
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            int index = editableIndex(slot);
            return index < 0 ? ItemStack.EMPTY : target.getInventory().removeItemNoUpdate(index);
        }
        @Override public void setItem(int slot, ItemStack stack) {
            int index = editableIndex(slot);
            if (index >= 0) target.getInventory().setItem(index, stack);
        }
        @Override public void setChanged() { target.getInventory().setChanged(); }
        @Override public boolean stillValid(Player player) { return player != null && InvseeService.isOnline(target) && target.isAlive(); }
        @Override public void clearContent() { for (int i = 0; i < getContainerSize(); i++) setItem(i, ItemStack.EMPTY); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return editableIndex(slot) >= 0; }

        private record CachedButton(ItemStack stack, long builtAt) { }

        private ItemStack cachedButton(int slot, InvseeConfig.Button button) {
            long now = System.currentTimeMillis();
            CachedButton cached = buttonCache.get(slot);
            if (cached == null || now - cached.builtAt() >= BUTTON_REFRESH_MS) {
                ItemStack built;
                try {
                    built = createButton(button);
                } catch (RuntimeException failure) {
                    built = new ItemStack(Items.BARRIER);
                    built.setHoverName(Component.literal("§cBroken button: " + button.id));
                }
                cached = new CachedButton(built, now);
                buttonCache.put(slot, cached);
            }
            return cached.stack();
        }

        private ItemStack createButton(InvseeConfig.Button button) {
            ResourceLocation itemId = ResourceLocation.tryParse(button.item);
            Item item = itemId == null ? Items.AIR : BuiltInRegistries.ITEM.get(itemId);
            ItemStack stack;
            if ("open_backpack".equals(button.action)) {
                // getWearingBackpack returns a copy, so naming it never touches the target's real item.
                stack = TravelerBackpackCompat.getWearingBackpack(target);
                if (stack.isEmpty()) {
                    Item backpack = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse("travelersbackpack:standard"));
                    stack = backpack == Items.AIR ? ItemStack.EMPTY : new ItemStack(backpack);
                }
                if (stack.isEmpty()) stack = new ItemStack(Items.CHEST);
            } else {
                stack = item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
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