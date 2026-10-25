package dev.seedlod.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
    // Synchronous body of createBiomes(), so we don't round-trip through the vanilla worldgen executor
    @Invoker("doCreateBiomes")
    void seedlod$doCreateBiomes(Blender blender, RandomState randomState, ChunkAccess protoChunk);
}
