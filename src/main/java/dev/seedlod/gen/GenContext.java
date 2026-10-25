package dev.seedlod.gen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Everything the generator needs about one dimension. Comes either from the integrated server
 * ({@link #of(ServerLevel, NoiseBasedChunkGenerator)}) or, on a multiplayer server, from vanilla
 * worldgen data plus a seed the player typed in ({@link VanillaWorldgen}).
 */
public record GenContext(
        NoiseBasedChunkGenerator generator,
        RandomState randomState,
        long seed,
        LevelHeightAccessor heightAccessor,
        PalettedContainerFactory containers,
        boolean hasCeiling
) {
    public static GenContext of(ServerLevel level, NoiseBasedChunkGenerator generator) {
        return new GenContext(generator, level.getChunkSource().randomState(), level.getSeed(), level,
                level.palettedContainerFactory(), level.dimensionType().hasCeiling());
    }

    public long biomeZoomSeed() {
        return BiomeManager.obfuscateSeed(this.seed);
    }
}
