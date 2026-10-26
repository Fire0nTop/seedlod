package dev.seedlod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class SeedLodConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("seedlod.json");

    public static SeedLodConfig INSTANCE = new SeedLodConfig();

    // Radius in chunks that gets filled around the player
    public int distance = 256;
    // Inside this radius chunks get the full vanilla pipeline (biomes, noise, surface rules, trees)
    public int detailRadius = 48;
    // Keep the radius filled as the player moves
    public boolean continuous = true;
    // Outside detailRadius, sample terrain at the resolution Voxy will draw it at instead of per block
    public boolean nSized = true;
    public int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);
    public boolean trees = true;
    // Ice sheets on cold water and snow on cold land
    public boolean waterFeatures = true;
    // Villages, temples, monuments, bastions, End cities... (only ones visible in an LOD)
    public boolean structures = true;
    public boolean debug = false;
    // Multiplayer: what we know about each server's world, keyed by server address
    public Map<String, ServerWorld> servers = new HashMap<>();

    public static class ServerWorld {
        public String seed;
        public String preset = "normal";
        // Use the seed even though it doesn't match the hash the server sent (some servers send a fake one)
        public boolean force;
        // Fair-play markers this server sent (see ServerRules); remembered for direct connects without a MOTD
        public boolean noStructures;
        public boolean disabled;
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try {
                INSTANCE = GSON.fromJson(Files.readString(PATH), SeedLodConfig.class);
            } catch (Exception e) {
                SeedLod.LOGGER.error("Failed to read {}, using defaults", PATH, e);
                INSTANCE = new SeedLodConfig();
            }
        }
        INSTANCE.clamp();
        save();
    }

    public static void save() {
        try {
            Files.writeString(PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            SeedLod.LOGGER.error("Failed to write {}", PATH, e);
        }
    }

    public void clamp() {
        this.distance = Math.clamp(this.distance, 16, 4096);
        this.detailRadius = Math.clamp(this.detailRadius, 0, this.distance);
        this.threads = Math.clamp(this.threads, 1, Runtime.getRuntime().availableProcessors());
        if (this.servers == null) this.servers = new HashMap<>();
    }
}
