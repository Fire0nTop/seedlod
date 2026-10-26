package dev.seedlod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.seedlod.gen.GenerationJob;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class SeedLodCommands {
    private SeedLodCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        int cores = Runtime.getRuntime().availableProcessors();
        dispatcher.register(literal("seedlod")
                .then(literal("fill")
                        .executes(ctx -> fill(ctx, SeedLodConfig.INSTANCE.distance, SeedLodConfig.INSTANCE.threads))
                        .then(argument("radius", IntegerArgumentType.integer(16, 4096))
                                .executes(ctx -> fill(ctx, IntegerArgumentType.getInteger(ctx, "radius"), SeedLodConfig.INSTANCE.threads))
                                .then(argument("threads", IntegerArgumentType.integer(1, cores))
                                        .executes(ctx -> fill(ctx, IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "threads"))))))
                .then(literal("stop").executes(ctx -> {
                    boolean stopped = GenerationManager.INSTANCE.stop();
                    ctx.getSource().sendFeedback(Component.literal(stopped ? "SeedLOD stopped" : "SeedLOD isn't running"));
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    String status = GenerationManager.INSTANCE.status();
                    ctx.getSource().sendFeedback(Component.literal(status == null ? "SeedLOD is idle" : "SeedLOD " + status));
                    return 1;
                }))
                .then(literal("reset").executes(ctx -> {
                    boolean ok = GenerationManager.INSTANCE.clearProgress(ctx.getSource().getClient());
                    ctx.getSource().sendFeedback(Component.literal(ok
                            ? "SeedLOD progress cleared for this dimension; unexplored areas will be regenerated"
                            : "Nothing to reset"));
                    return 1;
                }))
                .then(literal("seed")
                        .executes(ctx -> feedback(ctx, GenerationManager.INSTANCE.describeSeed(ctx.getSource().getClient())))
                        .then(literal("clear").executes(ctx -> feedback(ctx, GenerationManager.INSTANCE.clearSeed(ctx.getSource().getClient()))))
                        .then(literal("force").then(argument("seed", StringArgumentType.greedyString()).executes(ctx ->
                                feedback(ctx, GenerationManager.INSTANCE.setSeed(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "seed"), true)))))
                        .then(argument("seed", StringArgumentType.greedyString()).executes(ctx ->
                                feedback(ctx, GenerationManager.INSTANCE.setSeed(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "seed"), false)))))
                .then(literal("preset")
                        .then(literal("normal").executes(ctx -> feedback(ctx, GenerationManager.INSTANCE.setPreset(ctx.getSource().getClient(), "normal"))))
                        .then(literal("large_biomes").executes(ctx -> feedback(ctx, GenerationManager.INSTANCE.setPreset(ctx.getSource().getClient(), "large_biomes"))))
                        .then(literal("amplified").executes(ctx -> feedback(ctx, GenerationManager.INSTANCE.setPreset(ctx.getSource().getClient(), "amplified")))))
                .then(literal("config")
                        .executes(SeedLodCommands::showConfig)
                        .then(intOption("distance", 16, 4096, (c, v) -> c.distance = v, c -> c.distance))
                        .then(intOption("detailRadius", 0, 4096, (c, v) -> c.detailRadius = v, c -> c.detailRadius))
                        .then(intOption("threads", 1, cores, (c, v) -> c.threads = v, c -> c.threads))
                        .then(boolOption("continuous", (c, v) -> c.continuous = v, c -> c.continuous))
                        .then(boolOption("nSized", (c, v) -> c.nSized = v, c -> c.nSized))
                        .then(boolOption("trees", (c, v) -> c.trees = v, c -> c.trees))
                        .then(boolOption("waterFeatures", (c, v) -> c.waterFeatures = v, c -> c.waterFeatures))
                        .then(boolOption("structures", (c, v) -> c.structures = v, c -> c.structures))
                        .then(boolOption("debug", (c, v) -> c.debug = v, c -> c.debug))));
    }

    private static int fill(CommandContext<FabricClientCommandSource> ctx, int radius, int threads) {
        var mc = ctx.getSource().getClient();
        var cfg = SeedLodConfig.INSTANCE;
        var pos = ctx.getSource().getPlayer().chunkPosition();
        String error = GenerationManager.INSTANCE.start(mc, pos.x(), pos.z(),
                new GenerationJob.Settings(radius, Math.min(cfg.detailRadius, radius), cfg.nSized, threads), true);
        if (error != null) {
            ctx.getSource().sendError(Component.literal(error));
            return 0;
        }
        ctx.getSource().sendFeedback(Component.literal("SeedLOD filling " + radius + " chunks around you with " + threads + " threads"));
        return 1;
    }

    private static int showConfig(CommandContext<FabricClientCommandSource> ctx) {
        var c = SeedLodConfig.INSTANCE;
        ctx.getSource().sendFeedback(Component.literal(String.format(
                "SeedLOD: distance=%d detailRadius=%d threads=%d continuous=%b nSized=%b trees=%b waterFeatures=%b structures=%b debug=%b",
                c.distance, c.detailRadius, c.threads, c.continuous, c.nSized, c.trees, c.waterFeatures, c.structures, c.debug)));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> intOption(
            String name, int min, int max, BiConsumer<SeedLodConfig, Integer> set, Function<SeedLodConfig, Integer> get) {
        return literal(name)
                .executes(ctx -> report(ctx, name, get.apply(SeedLodConfig.INSTANCE)))
                .then(argument("value", IntegerArgumentType.integer(min, max)).executes(ctx -> {
                    set.accept(SeedLodConfig.INSTANCE, IntegerArgumentType.getInteger(ctx, "value"));
                    return applied(ctx, name, get);
                }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> boolOption(
            String name, BiConsumer<SeedLodConfig, Boolean> set, Function<SeedLodConfig, Boolean> get) {
        return literal(name)
                .executes(ctx -> report(ctx, name, get.apply(SeedLodConfig.INSTANCE)))
                .then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                    set.accept(SeedLodConfig.INSTANCE, BoolArgumentType.getBool(ctx, "value"));
                    return applied(ctx, name, get);
                }));
    }

    private static int feedback(CommandContext<FabricClientCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Component.literal("SeedLOD: " + text));
        return 1;
    }

    private static int report(CommandContext<FabricClientCommandSource> ctx, String name, Object value) {
        ctx.getSource().sendFeedback(Component.literal("SeedLOD " + name + " = " + value));
        return 1;
    }

    private static int applied(CommandContext<FabricClientCommandSource> ctx, String name, Function<SeedLodConfig, ?> get) {
        SeedLodConfig.INSTANCE.clamp();
        SeedLodConfig.save();
        ctx.getSource().sendFeedback(Component.literal("SeedLOD " + name + " set to " + get.apply(SeedLodConfig.INSTANCE) + " (applies to areas generated from now on)"));
        return 1;
    }
}
