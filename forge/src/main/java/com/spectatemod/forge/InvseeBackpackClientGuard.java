package com.spectatemod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;

/**
 * Disables only Traveler's Backpack's settings/gear widget when its native screen was opened
 * by invsee. Reflection is used so the client integration remains harmless when Traveler's
 * Backpack is not installed.
 */
public final class InvseeBackpackClientGuard {
    private static volatile int markedContainerId = Integer.MIN_VALUE;

    /** Called by the Forge network handler after the server opens an invsee backpack. */
    public static void markOpen(int containerId) {
        markedContainerId = containerId;
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) disableSettingsWidget(screen);
    }

    @SubscribeEvent
    public void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        // Keep the native inventory slots and every other Backpack control usable. Only the
        // settings/gear widget is blocked for an invsee screen.
        if (isInvseeSettingsClick(event.getScreen(), event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (!isInvseeBackpack(event.getScreen())) return;

        try {
            Object settingsWidget = event.getScreen().getClass().getField("settingsWidget").get(event.getScreen());
            if (settingsWidget instanceof GuiEventListener listener) event.removeListener(listener);
        } catch (Throwable ignored) {
            // The mouse guard below remains active if Traveler changes its widget internals.
        }
    }

    @SubscribeEvent
    public void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() != null
                && "com.tiviacz.travelersbackpack.client.screens.BackpackScreen".equals(
                event.getScreen().getClass().getName())) {
            markedContainerId = Integer.MIN_VALUE;
        }
    }

    private static boolean isInvseeSettingsClick(Screen screen, double mouseX, double mouseY) {
        if (!isInvseeBackpack(screen)) return false;

        try {
            Object settingsWidget = screen.getClass().getField("settingsWidget").get(screen);
            // Call the mapped Forge/Minecraft interface method directly. Reflecting on
            // isMouseOver fails in production because Minecraft renames that method.
            return settingsWidget instanceof GuiEventListener listener
                    && listener.isMouseOver(mouseX, mouseY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isInvseeBackpack(Screen screen) {
        if (screen == null || !"com.tiviacz.travelersbackpack.client.screens.BackpackScreen".equals(screen.getClass().getName())) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !isTravelerBackpackMenu(minecraft.player.containerMenu)) {
            return false;
        }

        // This marker is sent after the server has created the native menu. It does not
        // depend on the temporary synchronized item stack, which Traveler may replace.
        if (minecraft.player.containerMenu.containerId == markedContainerId) return true;

        try {
            Object wrapper = invoke(minecraft.player.containerMenu, "getWrapper");
            Object screenId = invoke(wrapper, "getScreenID");
            if (screenId instanceof Number number && number.intValue() == TravelerScreenMarker.SCREEN_ID) return true;

            Object backpackStack = invoke(wrapper, "getBackpackStack");
            Object tag = invoke(backpackStack, "getTag");
            return tag instanceof CompoundTag compoundTag
                    && compoundTag.getBoolean(TravelerScreenMarker.CLIENT_TAG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTravelerBackpackMenu(Object menu) {
        if (menu == null) return false;
        String className = menu.getClass().getName();
        if (!className.startsWith("com.tiviacz.travelersbackpack.inventory.menu.")) return false;
        try {
            menu.getClass().getMethod("getWrapper");
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void disableSettingsWidget(Screen screen) {
        try {
            Object settingsWidget = screen.getClass().getField("settingsWidget").get(screen);
            if (settingsWidget == null) return;

            if (settingsWidget instanceof GuiEventListener listener) {
                Method removeWidget = Screen.class.getDeclaredMethod("removeWidget", GuiEventListener.class);
                removeWidget.setAccessible(true);
                removeWidget.invoke(screen, listener);
            }
        } catch (Throwable ignored) {
            // Fall back to making the widget inert. The mouse guard is still authoritative.
            try {
                Object settingsWidget = screen.getClass().getField("settingsWidget").get(screen);
                if (settingsWidget == null) return;
                Method setActive = settingsWidget.getClass().getMethod("setActive", boolean.class);
                setActive.invoke(settingsWidget, false);
                Method setVisible = settingsWidget.getClass().getMethod("setVisible", boolean.class);
                setVisible.invoke(settingsWidget, false);
            } catch (Throwable ignoredAgain) {
                // Traveler's widget internals changed; the coordinate guard still blocks it.
            }
        }
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) throw new ReflectiveOperationException("Missing reflection target");
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    /** Duplicated here to keep this client-only class independent from the optional bridge. */
    private static final class TravelerScreenMarker {
        private static final int SCREEN_ID = 99;
        private static final String CLIENT_TAG = "adminscope_invsee_backpack";
    }
}
