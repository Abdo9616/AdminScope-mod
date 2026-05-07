package com.spectatemod.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.spectatemod.SpectateMod;
import com.spectatemod.data.SerializableSpectateState;
import com.spectatemod.data.SpectateState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;

public class SpectateManager {
    private static final String DATA_DIR = "config/spectatemod";
    private static final String DATA_FILE = "spectate_data.json";

    /**
     * How long to wait after an admin disconnects while spectating before restoring on rejoin.
     * This matches the behavior you referenced ("waits 5 seconds").
     */
    private static final long REJOIN_RESTORE_DELAY_MILLIS = 5000L;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, SpectateState> activeSpectators = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> freecamWarnings = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3d> lastAllowedPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> freecamExceedCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> freecamExceedWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cameraWarnings = new ConcurrentHashMap<>();

    // Spectators who disconnected while spectating (persisted to disk, restored on rejoin)
    private final Map<UUID, SpectateState> disconnectedSpectators = new ConcurrentHashMap<>();
    // pending apply after delay (in-memory scheduling)
    private final Map<UUID, Long> pendingApplyAt = new ConcurrentHashMap<>();

    public SpectateManager() {
    }

    public boolean canSpectate(ServerPlayerEntity admin, ServerPlayerEntity target) {
        if (JailModCompat.isPlayerJailed(admin)) {
            admin.sendMessage(Text.literal("§cYou cannot spectate while jailed."), false);
            return false;
        }

        if (isSpectating(admin.getUuid())) {
            admin.sendMessage(
                    Text.literal("§cYou are already spectating someone! Use /spectate stop first."),
                    false);
            return false;
        }

        if (admin.getUuid().equals(target.getUuid())) {
            admin.sendMessage(Text.literal("§cYou cannot spectate yourself!"), false);
            return false;
        }

        if (isOnCooldown(admin.getUuid())) {
            long remainingSeconds = getCooldownRemaining(admin.getUuid());
            admin.sendMessage(
                    Text.literal("§cYou must wait " + remainingSeconds
                            + " seconds before spectating again."),
                    false);
            return false;
        }

        if (SpectateMod.getConfigManager().getConfig().isPreventCombatSpectate()) {
            if (isInDanger(admin)) {
                admin.sendMessage(Text.literal(
                        "§cYou cannot spectate while in combat or near hostile mobs!"),
                        false);
                return false;
            }
        }

        return true;
    }

    public void startSpectating(ServerPlayerEntity admin, ServerPlayerEntity target) {
        SpectateState state = new SpectateState(admin, target);
        activeSpectators.put(admin.getUuid(), state);
        lastAllowedPositions.put(admin.getUuid(), new Vec3d(admin.getX(), admin.getY(), admin.getZ()));
        freecamExceedCounts.remove(admin.getUuid());
        freecamExceedWindows.remove(admin.getUuid());

        admin.changeGameMode(GameMode.SPECTATOR);
        admin.setCameraEntity(target);

        if (!admin.getEntityWorld().getRegistryKey().equals(target.getEntityWorld().getRegistryKey())) {
            ServerWorld targetWorld = (ServerWorld) target.getEntityWorld();
            admin.teleport(targetWorld, target.getX(), target.getY(), target.getZ(),
                    EnumSet.noneOf(PositionFlag.class), target.getYaw(), target.getPitch(), false);
        }

        if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
            saveSpectateData();
        }

        admin.sendMessage(Text.literal("§aYou are now spectating §e"
                + target.getName().getString() + "§a."), false);
        admin.sendMessage(Text.literal("§7Use §e/spectate stop §7to stop spectating."), false);

        SpectateMod.LOGGER.info("{} started spectating {}", admin.getName().getString(),
                target.getName().getString());
    }

    public void stopSpectating(ServerPlayerEntity admin) {
        stopSpectating(admin, null);
    }

    private void stopSpectating(ServerPlayerEntity admin, String reason) {
        SpectateState state = activeSpectators.remove(admin.getUuid());
        lastAllowedPositions.remove(admin.getUuid());
        freecamExceedCounts.remove(admin.getUuid());
        freecamExceedWindows.remove(admin.getUuid());
        freecamWarnings.remove(admin.getUuid());
        cameraWarnings.remove(admin.getUuid());

        if (state == null) {
            admin.sendMessage(Text.literal("§cYou are not currently spectating anyone!"), false);
            return;
        }

        admin.setCameraEntity(admin);

        ServerWorld adminWorld = (ServerWorld) admin.getEntityWorld();
        MinecraftServer server = adminWorld.getServer();
        if (server != null) {
            ServerWorld originalWorld = server.getWorld(state.getDimension());

            if (originalWorld != null) {
                Vec3d pos = state.getPosition();
                admin.teleport(originalWorld, pos.x, pos.y, pos.z,
                        EnumSet.noneOf(PositionFlag.class), state.getYaw(), state.getPitch(), false);
            }
        }

        admin.changeGameMode(state.getGameMode());

        int cooldownSeconds = SpectateMod.getConfigManager().getConfig().getSpectateCooldown();
        if (cooldownSeconds > 0) {
            cooldowns.put(admin.getUuid(), System.currentTimeMillis()
                    + (cooldownSeconds * 1000L));
        }

        if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
            saveSpectateData();
        }

        long duration = state.getDurationSeconds();
        if (reason != null && !reason.isBlank()) {
            admin.sendMessage(Text.literal(reason), false);
        } else {
            admin.sendMessage(Text.literal(
                    "§aYou are no longer spectating. §7(Duration: " + duration + "s)"),
                    false);
        }

        SpectateMod.LOGGER.info("{} stopped spectating after {}s",
                admin.getName().getString(), duration);
    }

    public boolean isSpectating(UUID adminUuid) {
        return activeSpectators.containsKey(adminUuid);
    }

    public SpectateState getSpectateState(UUID adminUuid) {
        return activeSpectators.get(adminUuid);
    }

    /**
     * Called on any disconnect. If the disconnecting player was spectating,
     * we persist a pending resume record and schedule delayed restoration
     * on rejoin.
     */
    public void handlePlayerDisconnect(ServerPlayerEntity player, MinecraftServer server) {
        UUID playerId = player.getUuid();

        // If player is an active spectating admin: persist pending resume data.
        SpectateState state = activeSpectators.get(playerId);
        if (state != null) {
            activeSpectators.remove(playerId);

            lastAllowedPositions.remove(playerId);
            freecamExceedCounts.remove(playerId);
            freecamExceedWindows.remove(playerId);
            freecamWarnings.remove(playerId);
            cameraWarnings.remove(playerId);

            if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
                disconnectedSpectators.put(playerId, state);
                saveSpectateData();
                pendingApplyAt.put(playerId, System.currentTimeMillis() + REJOIN_RESTORE_DELAY_MILLIS);
            }
            return;
        }

        // Otherwise, if someone else is spectating this player as a target, stop those sessions.
        List<UUID> toStop = new ArrayList<>();
        for (Map.Entry<UUID, SpectateState> entry : activeSpectators.entrySet()) {
            if (entry.getValue().getTargetUuid().equals(playerId)) {
                toStop.add(entry.getKey());
            }
        }

        for (UUID adminId : toStop) {
            ServerPlayerEntity admin = server.getPlayerManager().getPlayer(adminId);
            if (admin != null) {
                stopSpectating(admin, "§cSpectating ended: player left the server.");
            } else {
                activeSpectators.remove(adminId);
                lastAllowedPositions.remove(adminId);
                freecamExceedCounts.remove(adminId);
                freecamExceedWindows.remove(adminId);
                freecamWarnings.remove(adminId);
                cameraWarnings.remove(adminId);
            }
        }
    }

    /**
     * Called on join. We schedule applying pending resume after delay.
     */
    public void handlePlayerJoin(ServerPlayerEntity player, MinecraftServer server) {
        UUID id = player.getUuid();
        if (!SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
            return;
        }

        if (disconnectedSpectators.containsKey(id) && !pendingApplyAt.containsKey(id)) {
            pendingApplyAt.put(id, System.currentTimeMillis() + REJOIN_RESTORE_DELAY_MILLIS);
        }
    }

    /**
     * Runs each server tick. Applies pending resume when the delay expires.
     */
    public void processPendingReconnectCleanup(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<UUID> toApply = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : pendingApplyAt.entrySet()) {
            if (now >= e.getValue()) {
                toApply.add(e.getKey());
            }
        }

        for (UUID adminId : toApply) {
            pendingApplyAt.remove(adminId);

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(adminId);
            if (player == null) {
                // Player not present; keep pending data for next join.
                continue;
            }

            SpectateState savedState = disconnectedSpectators.remove(adminId);
            if (savedState == null) {
                continue;
            }

            applyDisconnectedResume(player, server, savedState);
        }
    }

    private void applyDisconnectedResume(ServerPlayerEntity player, MinecraftServer server, SpectateState savedState) {
        try {
            // Force correct POV/camera first.
            player.setCameraEntity(player);

            // Teleport the player back to their original pre-spectate position.
            ServerWorld originalWorld = server.getWorld(savedState.getDimension());
            if (originalWorld != null) {
                Vec3d pos = savedState.getPosition();
                player.teleport(
                        originalWorld,
                        pos.x, pos.y, pos.z,
                        EnumSet.noneOf(PositionFlag.class),
                        savedState.getYaw(),
                        savedState.getPitch(),
                        false
                );
            }

            player.changeGameMode(savedState.getGameMode());

            // Re-attach spectate state to target so camera POV matches.
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(savedState.getTargetUuid());
            if (target != null) {
                player.setCameraEntity(target);
                player.changeGameMode(GameMode.SPECTATOR);

                // Rehydrate server-side tracking so /spectate stop returns to the original position.
                activeSpectators.put(savedState.getAdminUuid(), savedState);
            }

            saveSpectateData();

        } catch (Exception e) {
            SpectateMod.LOGGER.error("Failed to restore spectate state after reconnect", e);
        }
    }

    public void enforceFreecamLimits(MinecraftServer server) {
        double limit = SpectateMod.getConfigManager().getConfig().getFreecamDistanceLimit();
        if (limit <= 0) {
            return;
        }

        double limitSq = limit * limit;
        for (Map.Entry<UUID, SpectateState> entry : activeSpectators.entrySet()) {
            ServerPlayerEntity admin = server.getPlayerManager().getPlayer(entry.getKey());
            if (admin == null) {
                continue;
            }

            SpectateState state = entry.getValue();
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(state.getTargetUuid());
            if (target == null) {
                stopSpectating(admin, "§cSpectating ended: player left the server.");
                continue;
            }

            if (admin.interactionManager.getGameMode() != GameMode.SPECTATOR) {
                continue;
            }

            if (admin.getCameraEntity() != target && admin.getCameraEntity() != admin) {
                admin.setCameraEntity(target);
                warnCameraLimit(admin);
            }

            ServerWorld targetWorld = (ServerWorld) target.getEntityWorld();
            if (!admin.getEntityWorld().getRegistryKey().equals(targetWorld.getRegistryKey())) {
                admin.teleport(targetWorld, target.getX(), target.getY(), target.getZ(),
                        EnumSet.noneOf(PositionFlag.class), admin.getYaw(), admin.getPitch(), false);
                lastAllowedPositions.put(admin.getUuid(), new Vec3d(admin.getX(), admin.getY(), admin.getZ()));
                warnFreecamLimit(admin, limit);
                continue;
            }

            Vec3d adminPos = new Vec3d(admin.getX(), admin.getY(), admin.getZ());
            double dx = adminPos.x - target.getX();
            double dy = adminPos.y - target.getY();
            double dz = adminPos.z - target.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= limitSq) {
                lastAllowedPositions.put(admin.getUuid(), adminPos);
                freecamExceedCounts.remove(admin.getUuid());
                freecamExceedWindows.remove(admin.getUuid());
                continue;
            }

            if (distSq > limitSq * 9) {
                admin.teleport(targetWorld, target.getX(), target.getY(), target.getZ(),
                        EnumSet.noneOf(PositionFlag.class), admin.getYaw(), admin.getPitch(), false);
                admin.setCameraEntity(target);
                lastAllowedPositions.put(admin.getUuid(), new Vec3d(admin.getX(), admin.getY(), admin.getZ()));
                freecamExceedCounts.remove(admin.getUuid());
                freecamExceedWindows.remove(admin.getUuid());
                warnFreecamReset(admin);
                continue;
            }

            if (registerExceedAttempt(admin)) {
                admin.teleport(targetWorld, target.getX(), target.getY(), target.getZ(),
                        EnumSet.noneOf(PositionFlag.class), admin.getYaw(), admin.getPitch(), false);
                admin.setCameraEntity(target);
                lastAllowedPositions.put(admin.getUuid(), new Vec3d(admin.getX(), admin.getY(), admin.getZ()));
                freecamExceedCounts.remove(admin.getUuid());
                freecamExceedWindows.remove(admin.getUuid());
                warnFreecamReset(admin);
                continue;
            }

            Vec3d lastAllowed = lastAllowedPositions.get(admin.getUuid());
            if (lastAllowed == null) {
                lastAllowed = adminPos;
            }

            double lax = lastAllowed.x - target.getX();
            double lay = lastAllowed.y - target.getY();
            double laz = lastAllowed.z - target.getZ();
            double lastDistSq = lax * lax + lay * lay + laz * laz;
            Vec3d safePos = lastAllowed;
            if (lastDistSq > limitSq) {
                double lastDist = Math.sqrt(lastDistSq);
                if (lastDist > 1e-6) {
                    double scale = limit / lastDist;
                    safePos = new Vec3d(
                            target.getX() + lax * scale,
                            target.getY() + lay * scale,
                            target.getZ() + laz * scale);
                } else {
                    safePos = new Vec3d(target.getX(), target.getY(), target.getZ());
                }
            }

            admin.teleport(targetWorld, safePos.x, safePos.y, safePos.z,
                    EnumSet.noneOf(PositionFlag.class), admin.getYaw(), admin.getPitch(), false);
            lastAllowedPositions.put(admin.getUuid(), safePos);
            warnFreecamLimit(admin, limit);
        }
    }

    private boolean isOnCooldown(UUID adminUuid) {
        Long cooldownExpiry = cooldowns.get(adminUuid);
        if (cooldownExpiry == null) {
            return false;
        }

        if (System.currentTimeMillis() >= cooldownExpiry) {
            cooldowns.remove(adminUuid);
            return false;
        }
        return true;
    }

    private long getCooldownRemaining(UUID adminUuid) {
        Long cooldownExpiry = cooldowns.get(adminUuid);
        if (cooldownExpiry == null) {
            return 0;
        }
        return Math.max(0, (cooldownExpiry - System.currentTimeMillis()) / 1000);
    }

    private boolean isInDanger(ServerPlayerEntity player) {
        if (player.getLastAttackTime() > player.age - 100) {
            return true;
        }

        double radius = SpectateMod.getConfigManager().getConfig().getCombatCheckRadius();
        Box searchBox = player.getBoundingBox().expand(radius);
        List<Entity> nearbyEntities = player.getEntityWorld().getOtherEntities(player, searchBox);

        for (Entity entity : nearbyEntities) {
            if (entity instanceof HostileEntity) {
                return true;
            }
        }
        return false;
    }

    private void warnFreecamLimit(ServerPlayerEntity admin, double limit) {
        long now = System.currentTimeMillis();
        long nextAllowed = freecamWarnings.getOrDefault(admin.getUuid(), 0L);
        if (now < nextAllowed) {
            return;
        }

        freecamWarnings.put(admin.getUuid(), now + 1500);
        int rounded = (int) Math.round(limit);
        admin.sendMessage(Text.literal("§cYou cannot go further than " + rounded
                + " blocks from the player you're spectating."), true);
    }

    private void warnFreecamReset(ServerPlayerEntity admin) {
        long now = System.currentTimeMillis();
        long nextAllowed = freecamWarnings.getOrDefault(admin.getUuid(), 0L);
        if (now < nextAllowed) {
            return;
        }

        freecamWarnings.put(admin.getUuid(), now + 1500);
        admin.sendMessage(Text.literal("§cFreecam limit reached. Returning to player POV."), true);
    }

    private boolean registerExceedAttempt(ServerPlayerEntity admin) {
        long now = System.currentTimeMillis();
        long windowStart = freecamExceedWindows.getOrDefault(admin.getUuid(), 0L);
        if (now - windowStart > 2000) {
            freecamExceedWindows.put(admin.getUuid(), now);
            freecamExceedCounts.put(admin.getUuid(), 1);
            return false;
        }

        int count = freecamExceedCounts.getOrDefault(admin.getUuid(), 0) + 1;
        freecamExceedCounts.put(admin.getUuid(), count);
        return count >= 5;
    }

    private void warnCameraLimit(ServerPlayerEntity admin) {
        long now = System.currentTimeMillis();
        long nextAllowed = cameraWarnings.getOrDefault(admin.getUuid(), 0L);
        if (now < nextAllowed) {
            return;
        }

        cameraWarnings.put(admin.getUuid(), now + 1500);
        admin.sendMessage(Text.literal("§cYou can only spectate the target player's POV."), true);
    }

    public void saveSpectateData() {
        try {
            Path dataPath = Paths.get(DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }

            List<SerializableSpectateState> serializableStates = new ArrayList<>();
            // Save both active and disconnected spectators so state survives server restarts
            List<SpectateState> allStates = new ArrayList<>(activeSpectators.values());
            allStates.addAll(disconnectedSpectators.values());
            for (SpectateState state : allStates) {
                SerializableSpectateState serializable = new SerializableSpectateState(
                        state.getAdminUuid().toString(),
                        state.getTargetUuid().toString(),
                        state.getPosition().x,
                        state.getPosition().y,
                        state.getPosition().z,
                        state.getYaw(),
                        state.getPitch(),
                        state.getGameMode().getId(),
                        state.getDimension().getValue().toString(),
                        state.getStartTime());
                serializableStates.add(serializable);
            }

            File dataFile = new File(DATA_DIR, DATA_FILE);
            try (FileWriter writer = new FileWriter(dataFile)) {
                gson.toJson(serializableStates, writer);
            }
        } catch (IOException e) {
            SpectateMod.LOGGER.error("Failed to save spectate data", e);
        }
    }

    public void loadSpectateData() {
        File dataFile = new File(DATA_DIR, DATA_FILE);
        if (!dataFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(dataFile)) {
            Type listType = new TypeToken<List<SerializableSpectateState>>() {}.getType();
            List<SerializableSpectateState> serializableStates = gson.fromJson(reader, listType);

            if (serializableStates == null || serializableStates.isEmpty()) {
                return;
            }

            for (SerializableSpectateState serializable : serializableStates) {
                try {
                    UUID adminUuid = UUID.fromString(serializable.getAdminUuid());
                    UUID targetUuid = UUID.fromString(serializable.getTargetUuid());
                    Vec3d position = new Vec3d(serializable.getPositionX(),
                            serializable.getPositionY(),
                            serializable.getPositionZ());
                    float yaw = serializable.getYaw();
                    float pitch = serializable.getPitch();
                    GameMode gameMode = GameMode.byId(serializable.getGameMode(),
                            GameMode.SURVIVAL);
                    RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD,
                            Identifier.of(serializable.getDimension()));

                    SpectateState state = new SpectateState(adminUuid, targetUuid, position,
                            yaw, pitch, gameMode, dimension, serializable.getStartTime());
                    // On server start no players are online yet, so all loaded states
                    // go into disconnectedSpectators. They will be moved to activeSpectators
                    // when the player joins and the resume is applied.
                    disconnectedSpectators.put(adminUuid, state);
                } catch (Exception e) {
                    SpectateMod.LOGGER.error("Failed to restore spectate state", e);
                }
            }

            SpectateMod.LOGGER.info("Loaded {} spectate session(s) from disk",
                    disconnectedSpectators.size());
        } catch (Exception e) {
            SpectateMod.LOGGER.error("Failed to load spectate data", e);
        }
    }

    private static final class JailModCompat {
        private static boolean checked;
        private static Method isPlayerInJail;

        private static boolean isPlayerJailed(ServerPlayerEntity player) {
            if (!FabricLoader.getInstance().isModLoaded("jailmod")) {
                return false;
            }

            if (!checked) {
                checked = true;
                try {
                    Class<?> jailModClass = Class.forName("com.example.jailmod.JailMod");
                    isPlayerInJail = jailModClass.getMethod("isPlayerInJail", ServerPlayerEntity.class);
                } catch (Exception e) {
                    SpectateMod.LOGGER.warn("JailMod detected but compatibility hook failed", e);
                    isPlayerInJail = null;
                }
            }

            if (isPlayerInJail == null) {
                return false;
            }

            try {
                return (boolean) isPlayerInJail.invoke(null, player);
            } catch (Exception e) {
                SpectateMod.LOGGER.warn("JailMod compatibility check failed", e);
                return false;
            }
        }
    }
}

