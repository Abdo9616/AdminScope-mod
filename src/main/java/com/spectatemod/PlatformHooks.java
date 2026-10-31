package com.spectatemod;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Predicate;

/** Small bridge for the few services that differ between Fabric and Forge. */
public final class PlatformHooks {
    private static volatile Path gameDirectory = Paths.get("").toAbsolutePath().normalize();
    private static volatile Predicate<String> modLoaded = id -> false;

    private PlatformHooks() {
    }

    public static void configure(Path gameDirectory, Predicate<String> modLoaded) {
        PlatformHooks.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        PlatformHooks.modLoaded = Objects.requireNonNull(modLoaded, "modLoaded");
    }

    public static Path configDirectory() {
        return gameDirectory.resolve("config").resolve("spectatemod");
    }

    public static boolean isModLoaded(String modId) {
        return modLoaded.test(modId);
    }
}
