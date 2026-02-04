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
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, SpectateState> activeSpectators = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

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
        SpectateState state = activeSpectators.remove(admin.getUuid());

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
        admin.sendMessage(Text.literal(
                "§aYou are no longer spectating. §7(Duration: " + duration + "s)"),
                false);

        SpectateMod.LOGGER.info("{} stopped spectating after {}s",
                admin.getName().getString(), duration);
    }

    public boolean isSpectating(UUID adminUuid) {
        return activeSpectators.containsKey(adminUuid);
    }

    public SpectateState getSpectateState(UUID adminUuid) {
        return activeSpectators.get(adminUuid);
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

    public void saveSpectateData() {
        try {
            Path dataPath = Paths.get(DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }

            List<SerializableSpectateState> serializableStates = new ArrayList<>();
            for (SpectateState state : activeSpectators.values()) {
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
            Type listType = new TypeToken<List<SerializableSpectateState>>() { }.getType();
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
                    activeSpectators.put(adminUuid, state);
                } catch (Exception e) {
                    SpectateMod.LOGGER.error("Failed to restore spectate state", e);
                }
            }

            SpectateMod.LOGGER.info("Loaded {} spectate session(s) from disk",
                    activeSpectators.size());
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
