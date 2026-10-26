package dev.seedlod.gen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

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
        DimensionType dimensionType,
        ResourceKey<Level> dimension,
        RegistryAccess registries,
        ChunkGeneratorStructureState structureState,
        // Null when structure templates can't be loaded; structures are then skipped
        @Nullable StructureTemplateManager templates
) {
    public static GenContext of(ServerLevel level, NoiseBasedChunkGenerator generator) {
        return new GenContext(generator, level.getChunkSource().randomState(), level.getSeed(), level,
                level.palettedContainerFactory(), level.dimensionType(), level.dimension(), level.registryAccess(),
                level.getChunkSource().getGeneratorState(), level.getServer().getStructureTemplateManager());
    }

    public boolean hasCeiling() {
        return this.dimensionType.hasCeiling();
    }

    public long biomeZoomSeed() {
        return BiomeManager.obfuscateSeed(this.seed);
    }
}
