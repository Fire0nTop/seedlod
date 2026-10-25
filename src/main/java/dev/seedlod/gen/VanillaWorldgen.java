package dev.seedlod.gen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * A multiplayer client never receives the server's worldgen settings, so we load vanilla's own worldgen
 * data out of the game jar, exactly like creating a new world does, and build a generator from a seed.
 * Only vanilla terrain can be reproduced this way; a server with worldgen datapacks or mods will differ.
 */
public final class VanillaWorldgen {
    public static final Map<String, ResourceKey<WorldPreset>> PRESETS = Map.of(
            "normal", WorldPresets.NORMAL,
            "large_biomes", WorldPresets.LARGE_BIOMES,
            "amplified", WorldPresets.AMPLIFIED);

    private final RegistryAccess.Frozen registries;
    private final ResourceKey<WorldPreset> preset;

    private VanillaWorldgen(RegistryAccess.Frozen registries, ResourceKey<WorldPreset> preset) {
        this.registries = registries;
        this.preset = preset;
    }

    /** Slow (a second or two): reads and decodes vanilla's worldgen JSON. Call off the render thread. */
    public static VanillaWorldgen load(ResourceKey<WorldPreset> preset) {
        var repository = ServerPacksSource.createVanillaTrustedRepository();
        repository.reload();
        repository.setSelected(List.of("vanilla"));
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected())) {
            var layers = RegistryLayer.createRegistryAccess();
            var staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
            var context = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), staticTags);
            var world = RegistryDataLoader.load(resources, context, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            return new VanillaWorldgen(world, preset);
        }
    }

    public ResourceKey<WorldPreset> preset() {
        return this.preset;
    }

    /** Null when the dimension isn't one vanilla generates with noise (custom dimensions, the debug world, flat). */
    @Nullable
    public GenContext context(ResourceKey<Level> dimension, long seed) {
        var stemKey = ResourceKey.create(Registries.LEVEL_STEM, dimension.identifier());
        LevelStem stem = this.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(this.preset).value()
                .createWorldDimensions().dimensions().get(stemKey);
        if (stem == null || !(stem.generator() instanceof NoiseBasedChunkGenerator generator)) return null;
        var type = stem.type().value();
        var randomState = RandomState.create(this.registries.lookupOrThrow(Registries.NOISE), seed, generator.generatorSettings().value());
        return new GenContext(generator, randomState, seed, LevelHeightAccessor.create(type.minY(), type.height()),
                PalettedContainerFactory.create(this.registries), type.hasCeiling());
    }

    /** Same parsing as the "Seed for the world generator" box: numbers as-is, anything else hashed. */
    public static OptionalLong parseSeed(String text) {
        return WorldOptions.parseSeed(text);
    }
}
