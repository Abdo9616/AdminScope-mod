package com.spectatemod;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerPlayer;

/** Small bridge for the few services that differ between Fabric and Forge. */
public final class PlatformHooks {
    private static volatile Path gameDirectory = Paths.get("").toAbsolutePath().normalize();
    private static volatile Predicate<String> modLoaded = id -> false;
    private static volatile BiPredicate<ServerPlayer, String> permissionChecker = (player, node) -> false;

    private PlatformHooks() {
    }

    public static void configure(Path gameDirectory, Predicate<String> modLoaded) {
        PlatformHooks.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        PlatformHooks.modLoaded = Objects.requireNonNull(modLoaded, "modLoaded");
    }

    /** Installs the loader-specific permission backend used by shared commands. */
    public static void configurePermissionChecker(BiPredicate<ServerPlayer, String> permissionChecker) {
        PlatformHooks.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    public static Path configDirectory() {
        return gameDirectory.resolve("config").resolve("spectatemod");
    }

    public static boolean isModLoaded(String modId) {
        return modLoaded.test(modId);
    }

    public static boolean hasPermission(ServerPlayer player, String node) {
        return player != null && permissionChecker.test(player, node);
    }
}
