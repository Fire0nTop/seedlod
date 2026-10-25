package dev.seedlod;

import dev.seedlod.gen.DoneMap;
import dev.seedlod.gen.ExploredChunks;
import dev.seedlod.gen.GenContext;
import dev.seedlod.gen.GenerationJob;
import dev.seedlod.gen.SeedTerrain;
import dev.seedlod.gen.VanillaWorldgen;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side owner of the running job.
 * <ul>
 *   <li>Singleplayer / LAN host: uses the integrated server's real generator (datapacks and mods included).</li>
 *   <li>Multiplayer: rebuilds vanilla worldgen from a seed the player provides (or that /seed reveals), checked
 *       against the seed hash every server sends to clients.</li>
 * </ul>
 */
public final class GenerationManager {
    public static final GenerationManager INSTANCE = new GenerationManager();
    private static final long DEBUG_REPORT_MS = 10_000;
    private static final long SEED_REPLY_TIMEOUT_MS = 10_000;

    @Nullable private GenerationJob job;
    private boolean jobIsManual;
    @Nullable private ResourceKey<Level> jobDimension;
    private final Map<Path, DoneMap> doneMaps = new HashMap<>();
    private final Map<ResourceKey<WorldPreset>, CompletableFuture<VanillaWorldgen>> worldgen = new HashMap<>();
    @Nullable private Object sessionIdentity;
    @Nullable private String lastReportedProblem;
    private long lastReport;
    private int lastCenterX, lastCenterZ;
    @Nullable private ResourceKey<Level> lastCenterDim;
    private long awaitingSeedSince;

    private GenerationManager() {}

    // ------------------------------------------------------------------ session

    @Nullable
    private static Object sessionIdentity(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        return server != null ? server : mc.getConnection();
    }

    private static boolean isMultiplayer(Minecraft mc) {
        return mc.getSingleplayerServer() == null;
    }

    /** Server address used to remember a multiplayer world's seed and progress. */
    @Nullable
    public static String serverKey(Minecraft mc) {
        var data = mc.getCurrentServer();
        if (data == null) return null;
        return data.ip.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void tick(Minecraft mc) {
        Object identity = sessionIdentity(mc);
        if (identity == null || mc.player == null || mc.level == null) {
            if (this.sessionIdentity != null) this.reset();
            return;
        }
        if (this.sessionIdentity != identity) {
            this.reset();
            this.sessionIdentity = identity;
            if (isMultiplayer(mc)) this.onJoinServer(mc);
        }
        if (this.awaitingSeedSince != 0 && System.currentTimeMillis() - this.awaitingSeedSince > SEED_REPLY_TIMEOUT_MS) {
            this.awaitingSeedSince = 0;
        }

        if (this.job != null) {
            this.job.setProtectedChunks(this.playerChunks(mc), this.protectRadius(mc));
            if (this.job.isFinished()) {
                if (this.jobIsManual || SeedLodConfig.INSTANCE.debug) {
                    this.message(mc, "SeedLOD " + this.job.describe(), ChatFormatting.GRAY);
                }
                this.job = null;
            } else if (SeedLodConfig.INSTANCE.debug && System.currentTimeMillis() - this.lastReport > DEBUG_REPORT_MS) {
                this.lastReport = System.currentTimeMillis();
                this.message(mc, "SeedLOD " + this.job.describe(), ChatFormatting.DARK_GRAY);
            }
        }

        var dim = mc.level.dimension();
        if (this.job != null && this.jobDimension != dim) {
            this.stop();
        }

        if (!SeedLodConfig.INSTANCE.continuous || this.jobIsManual && this.job != null) return;

        var cfg = SeedLodConfig.INSTANCE;
        int px = mc.player.chunkPosition().x(), pz = mc.player.chunkPosition().z();
        int moveThreshold = Math.max(8, cfg.distance / 8);
        if (this.job != null) {
            // Recenter a running job only after a big move, otherwise let it finish its rings
            if (Math.max(Math.abs(this.job.centerX - px), Math.abs(this.job.centerZ - pz)) > Math.max(moveThreshold, cfg.distance / 4)) {
                this.stop();
            } else {
                return;
            }
        }
        if (this.lastCenterDim == dim && Math.max(Math.abs(this.lastCenterX - px), Math.abs(this.lastCenterZ - pz)) < moveThreshold) {
            return;
        }
        String problem = this.start(mc, px, pz, new GenerationJob.Settings(cfg.distance, cfg.detailRadius, cfg.nSized, cfg.threads), false);
        // Retried every tick; only tell the player about things they can act on, and only once each
        if (problem != null && isMultiplayer(mc) && !problem.equals(this.lastReportedProblem) && !problem.startsWith("Loading")
                && !problem.startsWith("Voxy")) {
            this.lastReportedProblem = problem;
            this.message(mc, "SeedLOD: " + problem, ChatFormatting.YELLOW);
        }
    }

    private void onJoinServer(Minecraft mc) {
        String key = serverKey(mc);
        var known = key == null ? null : SeedLodConfig.INSTANCE.servers.get(key);
        if (known != null && known.seed != null) return;
        // If we may run /seed, ask the server quietly instead of making the player type it
        var connection = mc.getConnection();
        if (connection != null && connection.getCommands().getRoot().getChild("seed") != null) {
            this.awaitingSeedSince = System.currentTimeMillis();
            connection.sendCommand("seed");
        }
    }

    /** Catches the reply to our automatic /seed. Returns false to hide the message. */
    public boolean onGameMessage(Component message) {
        if (this.awaitingSeedSince == 0) return true;
        if (!(message.getContents() instanceof TranslatableContents tc) || !tc.getKey().equals("commands.seed.success")) return true;
        if (tc.getArgs().length == 0) return true;
        Object arg = tc.getArgs()[0];
        String text = (arg instanceof Component c ? c.getString() : String.valueOf(arg)).replaceAll("[^0-9-]", "");
        this.awaitingSeedSince = 0;
        var mc = Minecraft.getInstance();
        try {
            long seed = Long.parseLong(text);
            String result = this.setSeed(mc, Long.toString(seed), false);
            this.message(mc, "SeedLOD: got this server's seed from /seed. " + result, ChatFormatting.GRAY);
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ resolving a dimension

    private record Target(GenContext ctx, @Nullable ExploredChunks explored, Path progressDir) {}

    /** Either a target or a reason why we can't generate here. */
    private record Resolved(@Nullable Target target, @Nullable String problem) {
        static Resolved problem(String why) {
            return new Resolved(null, why);
        }
    }

    private Resolved resolve(Minecraft mc, ResourceKey<Level> dim) {
        var server = mc.getSingleplayerServer();
        if (server != null) {
            ServerLevel level = server.getLevel(dim);
            if (level == null) return Resolved.problem("No server level for this dimension");
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
                return Resolved.problem("This dimension doesn't use noise-based terrain");
            }
            Path dir = server.getWorldPath(LevelResource.ROOT).normalize().resolve("seedlod");
            return new Resolved(new Target(GenContext.of(level, generator), new ExploredChunks(level), dir), null);
        }

        String key = serverKey(mc);
        if (key == null) return Resolved.problem("Can't tell which server this is");
        var known = SeedLodConfig.INSTANCE.servers.get(key);
        if (known == null || known.seed == null) {
            return Resolved.problem("No seed for this server yet. Set it with /seedlod seed <seed>");
        }
        long seed = VanillaWorldgen.parseSeed(known.seed).orElse(0);
        if (!known.force && !this.seedMatches(mc, seed)) {
            return Resolved.problem("The saved seed doesn't match this server's world. Check it, or use /seedlod seed force <seed>");
        }
        var presetKey = VanillaWorldgen.PRESETS.getOrDefault(known.preset, VanillaWorldgen.PRESETS.get("normal"));
        var future = this.worldgen.computeIfAbsent(presetKey,
                p -> CompletableFuture.supplyAsync(() -> VanillaWorldgen.load(p), Util.backgroundExecutor()));
        if (!future.isDone()) return Resolved.problem("Loading vanilla worldgen");
        VanillaWorldgen vanilla;
        try {
            vanilla = future.join();
        } catch (Exception e) {
            SeedLod.LOGGER.error("Failed to load vanilla worldgen", e);
            this.worldgen.remove(presetKey);
            return Resolved.problem("Couldn't load vanilla worldgen (see log)");
        }
        GenContext ctx = vanilla.context(dim, seed);
        if (ctx == null) return Resolved.problem("SeedLOD can only rebuild the vanilla Overworld, Nether and End on servers");
        return new Resolved(new Target(ctx, null, serverProgressDir(key)), null);
    }

    /** Servers send clients a SHA-256 hash of the world seed (for biome blending); a right seed hashes to it. */
    private boolean seedMatches(Minecraft mc, long seed) {
        var id = WorldIdentifier.of(mc.level);
        return id == null || BiomeManager.obfuscateSeed(seed) == id.biomeSeed;
    }

    private static Path serverProgressDir(String key) {
        return FabricLoader.getInstance().getConfigDir().resolve("seedlod").resolve("servers").resolve(key.replaceAll("[^a-z0-9._-]", "_"));
    }

    private DoneMap doneMap(Path dir, ResourceKey<Level> dim) {
        var id = dim.identifier();
        Path file = dir.resolve(id.getNamespace() + "_" + id.getPath().replace('/', '_') + ".bin");
        return this.doneMaps.computeIfAbsent(file, DoneMap::new);
    }

    @Nullable
    private Path progressDir(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != null) return server.getWorldPath(LevelResource.ROOT).normalize().resolve("seedlod");
        String key = serverKey(mc);
        return key == null ? null : serverProgressDir(key);
    }

    // ------------------------------------------------------------------ jobs

    /** Returns an error message, or null on success. */
    @Nullable
    public String start(Minecraft mc, int cx, int cz, GenerationJob.Settings settings, boolean manual) {
        if (mc.level == null) return "Not in a world";
        var instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning()) return "Voxy isn't running";

        var dim = mc.level.dimension();
        var resolved = this.resolve(mc, dim);
        if (resolved.target == null) return resolved.problem;
        var target = resolved.target;

        var engine = WorldIdentifier.of(mc.level).getOrCreateEngine();
        if (engine == null) return "Voxy has no world for this dimension yet";

        this.stop();
        var cfg = SeedLodConfig.INSTANCE;
        var done = this.doneMap(target.progressDir, dim);
        var terrain = new SeedTerrain(target.ctx, cfg.trees, cfg.waterFeatures);
        this.job = new GenerationJob(terrain, engine, instance, done, target.explored, cx, cz, settings);
        this.jobDimension = dim;
        this.job.setProtectedChunks(this.playerChunks(mc), this.protectRadius(mc));
        this.jobIsManual = manual;
        this.lastCenterX = cx;
        this.lastCenterZ = cz;
        this.lastCenterDim = dim;
        this.lastReport = System.currentTimeMillis();
        this.lastReportedProblem = null;
        this.job.start();
        SeedLod.LOGGER.info("Started seed generation in {} around {} {} (radius {}, {} threads, {})",
                dim.identifier(), cx, cz, settings.radius(), settings.threads(), isMultiplayer(mc) ? "server seed" : "integrated server");
        return null;
    }

    public boolean stop() {
        if (this.job == null) return false;
        this.job.stop();
        this.job = null;
        this.jobIsManual = false;
        return true;
    }

    @Nullable
    public String status() {
        return this.job == null ? null : this.job.describe();
    }

    /** A real chunk arrived: Voxy ingests it, so seed terrain must never be written over it. */
    public void onChunkLoad(Minecraft mc, ResourceKey<Level> dim, int cx, int cz) {
        // The first chunks arrive before our first tick, so don't wait for the session to be set up
        Path dir = this.progressDir(mc);
        if (dir != null) this.doneMap(dir, dim).put(ChunkPos.pack(cx, cz), 0);
    }

    /** Forget progress for the current dimension, so the next job regenerates everything not explored. */
    public boolean clearProgress(Minecraft mc) {
        if (mc.level == null) return false;
        Path dir = this.progressDir(mc);
        if (dir == null) return false;
        this.stop();
        var done = this.doneMap(dir, mc.level.dimension());
        done.clear();
        done.save();
        this.lastCenterDim = null;
        return true;
    }

    // ------------------------------------------------------------------ seed commands

    /** Stores the seed for the current server and says whether it matches. */
    public String setSeed(Minecraft mc, String text, boolean force) {
        if (!isMultiplayer(mc)) return "In singleplayer SeedLOD already uses the world's own seed.";
        String key = serverKey(mc);
        if (key == null) return "Can't tell which server this is.";
        var parsed = VanillaWorldgen.parseSeed(text);
        if (parsed.isEmpty()) return "That isn't a seed.";
        long seed = parsed.getAsLong();
        boolean matches = this.seedMatches(mc, seed);
        if (!matches && !force) {
            return "That seed doesn't match this server's world, so it wasn't saved. Double-check it; if the server is known to hide its seed hash, use /seedlod seed force " + text;
        }

        var known = SeedLodConfig.INSTANCE.servers.computeIfAbsent(key, k -> new SeedLodConfig.ServerWorld());
        boolean changed = known.seed == null || VanillaWorldgen.parseSeed(known.seed).orElse(0) != seed;
        known.seed = Long.toString(seed);
        known.force = !matches;
        SeedLodConfig.save();
        if (changed) this.forgetServerProgress(key);
        this.restartSoon();
        return matches ? "Seed " + seed + " saved for " + key + " and it matches the server's world." : "Seed " + seed + " saved for " + key + " (forced: it does NOT match the hash the server sent).";
    }

    public String clearSeed(Minecraft mc) {
        String key = serverKey(mc);
        if (key == null || SeedLodConfig.INSTANCE.servers.remove(key) == null) return "No seed saved for this server.";
        SeedLodConfig.save();
        this.stop();
        this.forgetServerProgress(key);
        return "Forgot the seed for " + key + ".";
    }

    public String describeSeed(Minecraft mc) {
        if (!isMultiplayer(mc)) return "Singleplayer: using the world's own seed.";
        String key = serverKey(mc);
        var known = key == null ? null : SeedLodConfig.INSTANCE.servers.get(key);
        if (known == null || known.seed == null) return "No seed saved for " + key + ". Use /seedlod seed <seed>.";
        return "Seed for " + key + ": " + known.seed + " (preset " + known.preset + (known.force ? ", forced" : "") + ")";
    }

    public String setPreset(Minecraft mc, String preset) {
        if (!VanillaWorldgen.PRESETS.containsKey(preset)) return "Unknown preset. Use normal, large_biomes or amplified.";
        if (!isMultiplayer(mc)) return "In singleplayer SeedLOD already uses the world's own settings.";
        String key = serverKey(mc);
        if (key == null) return "Can't tell which server this is.";
        var known = SeedLodConfig.INSTANCE.servers.computeIfAbsent(key, k -> new SeedLodConfig.ServerWorld());
        if (!preset.equals(known.preset)) {
            known.preset = preset;
            SeedLodConfig.save();
            this.forgetServerProgress(key);
            this.restartSoon();
        }
        return "World type for " + key + " set to " + preset + ".";
    }

    // A different seed or preset makes everything we generated for this server wrong
    private void forgetServerProgress(String key) {
        this.stop();
        Path dir = serverProgressDir(key);
        this.doneMaps.keySet().removeIf(p -> p.startsWith(dir));
        try (var files = Files.list(dir)) {
            files.forEach(f -> f.toFile().delete());
        } catch (Exception ignored) {
            // Nothing saved yet
        }
    }

    private void restartSoon() {
        this.lastCenterDim = null;
        this.lastReportedProblem = null;
    }

    // ------------------------------------------------------------------ helpers

    private void reset() {
        this.stop();
        this.doneMaps.values().forEach(DoneMap::save);
        this.doneMaps.clear();
        this.sessionIdentity = null;
        this.lastCenterDim = null;
        this.lastReportedProblem = null;
        this.awaitingSeedSince = 0;
    }

    private long[] playerChunks(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != null && this.jobDimension != null) {
            var level = server.getLevel(this.jobDimension);
            if (level != null) {
                var players = level.players();
                long[] out = new long[players.size()];
                for (int i = 0; i < out.length; i++) {
                    ChunkPos p = players.get(i).chunkPosition();
                    out[i] = ChunkPos.pack(p.x(), p.z());
                }
                return out;
            }
        }
        // On a server we only know where we are; other players' chunks get ingested only if we see them anyway
        if (mc.player == null) return new long[0];
        var p = mc.player.chunkPosition();
        return new long[]{ChunkPos.pack(p.x(), p.z())};
    }

    private int protectRadius(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        int render = mc.options.getEffectiveRenderDistance();
        // Same clamp ChunkMap applies to the client's requested distance before sending chunks
        return server == null ? render : Math.clamp(render, 2, server.getPlayerList().getViewDistance());
    }

    private void message(Minecraft mc, String text, ChatFormatting color) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text).withStyle(color));
    }
}
