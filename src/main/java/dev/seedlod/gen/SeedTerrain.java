package dev.seedlod.gen;

import dev.seedlod.SeedLod;
import dev.seedlod.mixin.ChunkGeneratorAccessor;
import dev.seedlod.mixin.NoiseBasedChunkGeneratorAccessor;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;

/**
 * Turns the world seed into Voxy sections without going through the server's chunk system.
 *
 * <p>Two paths:
 * <ul>
 *   <li>{@link #generateFull}: vanilla biomes + noise fill + surface rules on a throwaway ProtoChunk,
 *       plus approximate trees/snow/ice. Used close to the player.</li>
 *   <li>{@link #generateCoarse}: samples the final density function on a strided volume whose step is
 *       the size of the LOD voxel Voxy will draw at that distance ("N-sized" generation), then paints
 *       surfaces from {@link BiomeTraits}. Orders of magnitude cheaper per chunk.</li>
 * </ul>
 * One instance is shared by all worker threads; per-thread scratch lives in {@link Worker}.
 */
public final class SeedTerrain {
    private static final int MAX_LIGHT = 15;

    /** Where finished sections go; normally a Voxy {@link me.cortex.voxy.common.world.WorldEngine}. */
    public interface Sink {
        Mapper mapper();

        // The section is reused by the caller once this returns
        void insert(VoxelizedSection section);

        static Sink of(me.cortex.voxy.common.world.WorldEngine engine) {
            return new Sink() {
                @Override
                public Mapper mapper() {
                    return engine.getMapper();
                }

                @Override
                public void insert(VoxelizedSection section) {
                    me.cortex.voxy.common.world.WorldUpdater.insertUpdate(engine, section);
                }
            };
        }
    }

    final GenContext ctx;
    final NoiseBasedChunkGenerator generator;
    final NoiseGeneratorSettings settings;
    final RandomState randomState;
    final BiomeTraits traits;
    final long seed;
    final int seaLevel;
    final boolean hasCeiling;
    final boolean trees;
    final boolean waterFeatures;
    @org.jetbrains.annotations.Nullable final StructureIndex structures;

    public SeedTerrain(GenContext ctx, boolean trees, boolean waterFeatures, boolean structures) {
        this.ctx = ctx;
        var generator = ctx.generator();
        this.generator = generator;
        this.settings = generator.generatorSettings().value();
        this.randomState = ctx.randomState();
        this.traits = new BiomeTraits(this.settings.defaultBlock());
        this.seed = ctx.seed();
        this.seaLevel = generator.getSeaLevel();
        this.hasCeiling = ctx.hasCeiling();
        this.trees = trees;
        this.waterFeatures = waterFeatures;
        this.structures = structures && ctx.templates() != null ? new StructureIndex(ctx) : null;
    }

    public boolean hasStructures() {
        return this.structures != null;
    }

    /** Chunks a structure touches are always generated at full detail, so the structure is really there. */
    public boolean structuresTouch(int cx, int cz) {
        return this.structures != null && !this.structures.affecting(cx, cz).isEmpty();
    }

    public Worker newWorker() {
        return new Worker();
    }

    private static int light(int sky) {
        return Math.clamp(sky, 0, MAX_LIGHT);
    }

    private static long hash(long seed, int x, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    // Uniform float in [0,1) for a column
    private static float chance(long seed, int x, int z, int salt) {
        return (hash(seed, x, z, salt) >>> 40) / (float) (1L << 24);
    }

    public final class Worker implements AutoCloseable {
        private final VoxelizedSection section = VoxelizedSection.createEmpty();
        private net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool coarsePool;
        private net.minecraft.world.level.levelgen.densityfunction.DensitySampler.Bound coarseDensity;

        @Override
        public void close() {
            if (this.coarsePool != null) {
                SeedTerrain.this.randomState.releaseDensityBufferPool(this.coarsePool);
                this.coarsePool = null;
                this.coarseDensity = null;
            }
        }
        private final BiomeResolver climate = SeedTerrain.this.generator.getBiomeSource()
                .createResolver(SeedTerrain.this.randomState.createClimateSampler(SamplerContext.builder().build()));

        // ---------------------------------------------------------------- full detail

        public void generateFull(Sink sink, int cx, int cz) {
            var ctx = SeedTerrain.this.ctx;
            var pos = new ChunkPos(cx, cz);
            var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, ctx.heightAccessor(), ctx.containers(), null);

            ((ChunkGeneratorAccessor) (Object) SeedTerrain.this.generator).seedlod$doCreateBiomes(Blender.empty(), SeedTerrain.this.randomState, chunk);
            chunk.setPersistedStatus(ChunkStatus.BIOMES);

            NoiseSettings noiseSettings = SeedTerrain.this.settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
            if (noiseSettings.height() <= 0) return;

            var accessor = (NoiseBasedChunkGeneratorAccessor) (Object) SeedTerrain.this.generator;
            int minX = pos.getMinBlockX();
            int minZ = pos.getMinBlockZ();
            int qMinX = QuartPos.fromBlock(minX), qMinZ = QuartPos.fromBlock(minZ);
            // Surface rules look at neighbouring biomes through the zoomer, answer those from the climate sampler
            BiomeResolver resolver = (qx, qy, qz) -> {
                int rx = qx - qMinX, rz = qz - qMinZ;
                if (rx >= 0 && rx < 4 && rz >= 0 && rz < 4) {
                    return chunk.getNoiseBiome(qx, qy, qz);
                }
                return this.climate.getNoiseBiome(qx, qy, qz);
            };
            BiomeManager biomeManager = new BiomeManager(resolver, ctx.biomeZoomSeed());
            var structureStarts = SeedTerrain.this.structures == null ? List.<StructureStart>of() : SeedTerrain.this.structures.affecting(cx, cz);

            try (NoiseChunk noiseChunk = new NoiseChunk(
                    SeedTerrain.this.randomState, StructureIndex.beardifier(structureStarts, pos), SeedTerrain.this.settings,
                    accessor.seedlod$globalFluidPicker().get(), Blender.empty(),
                    new DensityVolume(16, noiseSettings.height(), 16, minX, noiseSettings.minY(), minZ))) {
                var sections = chunk.getSections();
                for (var s : sections) s.acquire();
                try {
                    accessor.seedlod$doFill(noiseChunk, chunk);
                } finally {
                    for (var s : sections) s.release();
                }
                accessor.seedlod$buildSurface(chunk, noiseChunk, SeedTerrain.this.randomState, biomeManager, null,
                        SeedTerrain.this.settings.materialRule().value());
            }

            boolean[] built = structureStarts.isEmpty() ? null : this.placeStructures(chunk, structureStarts, resolver);
            this.decorateAndWrite(sink, chunk, built);
        }

        /** Places every visible structure overlapping this chunk; returns the columns they cover (no trees there). */
        private boolean[] placeStructures(ProtoChunk chunk, List<StructureStart> starts, BiomeResolver biomes) {
            var t = SeedTerrain.this;
            ChunkPos pos = chunk.getPos();
            int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
            var chunkBox = new BoundingBox(minX, chunk.getMinY(), minZ, minX + 15, chunk.getMaxY(), minZ + 15);
            // Seeded like vanilla decoration, so the same seed always gives the same structure details
            var random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decorationSeed = random.setDecorationSeed(t.seed, minX, minZ);
            var level = StructureLevel.create(chunk, t.ctx, biomes, random);
            boolean[] built = new boolean[256];
            for (int i = 0; i < starts.size(); i++) {
                StructureStart start = starts.get(i);
                random.setFeatureSeed(decorationSeed, i, start.getStructure().step().ordinal());
                try {
                    start.placeInChunk(level, null, t.generator, random, chunkBox, pos);
                } catch (Exception e) {
                    // A piece wanting something only a real world has; the rest of the structure still stands
                    SeedLod.LOGGER.debug("Structure piece failed in chunk {}", pos, e);
                }
                for (var piece : start.getPieces()) {
                    var box = piece.getBoundingBox();
                    if (!box.intersects(chunkBox)) continue;
                    for (int z = Math.max(box.minZ(), minZ); z <= Math.min(box.maxZ(), minZ + 15); z++)
                        for (int x = Math.max(box.minX(), minX); x <= Math.min(box.maxX(), minX + 15); x++)
                            built[(x - minX) | ((z - minZ) << 4)] = true;
                }
            }
            return built;
        }

        private void decorateAndWrite(Sink sink, ProtoChunk chunk, @org.jetbrains.annotations.Nullable boolean[] built) {
            int minY = chunk.getMinY();
            int maxY = chunk.getMaxY();
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            var mpos = new BlockPos.MutableBlockPos();

            int[] opaqueTop = new int[256];
            int[] waterTop = new int[256];
            int lowestSurface = Integer.MAX_VALUE;
            int highestSurface = Integer.MIN_VALUE;

            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int col = x | (z << 4);
                    int wx = minX + x, wz = minZ + z;
                    int surfaceY = Integer.MIN_VALUE;
                    int fluidY = Integer.MIN_VALUE;
                    BlockState surface = null;

                    // Topmost non-air block, skipping a fluid layer if there is one
                    for (int y = maxY; y >= minY; y--) {
                        var state = chunk.getBlockState(mpos.set(wx, y, wz));
                        if (state.isAir()) continue;
                        if (!state.getFluidState().isEmpty() && !state.canOcclude()) {
                            if (fluidY == Integer.MIN_VALUE) fluidY = y;
                            continue;
                        }
                        surfaceY = y;
                        surface = state;
                        break;
                    }

                    if (surface == null) {
                        opaqueTop[col] = minY - 1;
                        waterTop[col] = fluidY;
                        continue;
                    }

                    lowestSurface = Math.min(lowestSurface, surfaceY);
                    highestSurface = Math.max(highestSurface, Math.max(surfaceY, fluidY));
                    Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(wz));

                    // Columns under a structure tell us nothing about the biome's natural ground, and grow no trees
                    boolean natural = built == null || !built[col];
                    if (natural && !SeedTerrain.this.hasCeiling) {
                        if (fluidY != Integer.MIN_VALUE) {
                            SeedTerrain.this.traits.learnUnderwater(biome, surface);
                        } else if (surfaceY > minY) {
                            SeedTerrain.this.traits.learnLand(biome, surface, chunk.getBlockState(mpos.set(wx, surfaceY - 1, wz)));
                        }
                    }

                    int top = surfaceY;
                    if (natural && fluidY == Integer.MIN_VALUE && SeedTerrain.this.trees && x >= 2 && x <= 13 && z >= 2 && z <= 13
                            && BiomeTraits.canHoldTree(surface)) {
                        var t = SeedTerrain.this.traits.trees(biome, biome.value().getGenerationSettings());
                        if (t.density() > 0 && chance(SeedTerrain.this.seed, wx, wz, 1) < t.density()) {
                            top = this.placeTree(chunk, t.kind(), x, surfaceY + 1, z, maxY, wx, wz);
                            highestSurface = Math.max(highestSurface, top);
                        }
                    }

                    if (SeedTerrain.this.waterFeatures && !SeedTerrain.this.hasCeiling) {
                        boolean cold = biome.value().coldEnoughToSnow(mpos.set(wx, Math.max(top, fluidY), wz), SeedTerrain.this.seaLevel);
                        if (cold) {
                            if (fluidY != Integer.MIN_VALUE) {
                                chunk.setBlockState(mpos.set(wx, fluidY, wz), Blocks.ICE.defaultBlockState(), 0);
                            } else if (top < maxY) {
                                chunk.setBlockState(mpos.set(wx, top + 1, wz), Blocks.SNOW.defaultBlockState(), 0);
                            }
                        }
                    }

                    opaqueTop[col] = surfaceY;
                    waterTop[col] = fluidY;
                }
            }

            if (lowestSurface == Integer.MAX_VALUE) return;// Void chunk (End), nothing for Voxy to draw

            int bottomSection, topSection;
            if (SeedTerrain.this.hasCeiling) {
                bottomSection = chunk.getMinSectionY();
                topSection = chunk.getMaxSectionY();
            } else {
                // Nobody sees deep underground in an LOD; stop a bit below the lowest surface so cliffs stay closed
                bottomSection = Math.max(chunk.getMinSectionY(), SectionPos.blockToSectionCoord(Math.min(SeedTerrain.this.seaLevel - 24, lowestSurface - 8)));
                topSection = Math.min(chunk.getMaxSectionY(), SectionPos.blockToSectionCoord(highestSurface) + 1);
            }

            Mapper mapper = sink.mapper();
            boolean ceiling = SeedTerrain.this.hasCeiling;
            for (int sy = bottomSection; sy <= topSection; sy++) {
                LevelChunkSection s = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                int baseY = SectionPos.sectionToBlockCoord(sy);
                var vs = WorldConversionFactory.convert(
                        this.section.setPosition(chunk.getPos().x(), sy, chunk.getPos().z()),
                        mapper, s.getStates(), s.getBiomes(),
                        (lx, ly, lz) -> {
                            if (ceiling) return 0;
                            int col = lx | (lz << 4);
                            int y = baseY + ly;
                            if (y <= opaqueTop[col]) return 0;
                            int w = waterTop[col];
                            return (byte) (w != Integer.MIN_VALUE && y <= w ? light(MAX_LIGHT - (w - y)) : MAX_LIGHT);
                        });
                WorldVoxilizedSectionMipper.mipSection(vs, mapper);
                sink.insert(vs);
            }
        }

        // Returns the highest y the tree occupies
        private int placeTree(ProtoChunk chunk, BiomeTraits.TreeKind kind, int x, int y, int z, int maxY, int wx, int wz) {
            var log = BiomeTraits.log(kind);
            var leaves = BiomeTraits.leaves(kind);
            int variance = (int) (hash(SeedTerrain.this.seed, wx, wz, 2) & 3);
            int height = switch (kind) {
                case SPRUCE -> 6 + variance * 2;
                case JUNGLE -> 8 + variance * 2;
                case DARK_OAK, PALE_OAK, ACACIA -> 5 + (variance & 1);
                default -> 4 + variance;
            };
            int top = Math.min(maxY - 1, y + height);
            var mpos = new BlockPos.MutableBlockPos();
            int wxBase = chunk.getPos().getMinBlockX(), wzBase = chunk.getPos().getMinBlockZ();

            for (int ty = y; ty < top; ty++) {
                chunk.setBlockState(mpos.set(wxBase + x, ty, wzBase + z), log, 0);
            }

            int canopyBottom = kind == BiomeTraits.TreeKind.SPRUCE ? y + 2 : top - 2;
            for (int ty = canopyBottom; ty <= Math.min(maxY, top + 1); ty++) {
                int radius;
                if (kind == BiomeTraits.TreeKind.SPRUCE) {
                    radius = Math.min(2, (top + 1 - ty + 1) / 2);
                } else if (kind == BiomeTraits.TreeKind.ACACIA) {
                    radius = ty >= top ? 2 : 0;
                } else {
                    radius = ty > top ? 1 : 2;
                }
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;// round the corners
                        mpos.set(wxBase + x + dx, ty, wzBase + z + dz);
                        if (chunk.getBlockState(mpos).isAir()) {
                            chunk.setBlockState(mpos, leaves, 0);
                        }
                    }
                }
            }
            return Math.min(maxY, top + 1);
        }

        // ---------------------------------------------------------------- N-sized

        /**
         * Generates one chunk at LOD level {@code lvl}: every voxel is {@code 1<<lvl} blocks wide, and the
         * density function is only evaluated once per voxel. The result is replicated into block detail so
         * Voxy's normal insert path (and its mip/child bookkeeping) stays intact.
         */
        public void generateCoarse(Sink sink, int cx, int cz, int lvl) {
            final int step = 1 << lvl;
            final int n = 16 >> lvl;// voxels per chunk edge
            var t = SeedTerrain.this;
            NoiseSettings noiseSettings = t.settings.noiseSettings().clampToHeightAccessor(t.ctx.heightAccessor());
            if (noiseSettings.height() <= 0) return;
            final int minY = noiseSettings.minY();
            final int ny = (noiseSettings.height() + step - 1) / step;
            final int minX = cx << 4, minZ = cz << 4;

            boolean[] solid = new boolean[n * n * ny];
            var volume = new DensityVolume(n, ny, n, minX, minY, minZ, step, step, step);
            if (this.coarseDensity == null) {
                // Building the sampler graph is the expensive part, so each worker keeps one for its lifetime
                this.coarsePool = t.randomState.acquireDensityBufferPool();
                this.coarseDensity = t.randomState.samplersWithContext(SamplerContext.builder()
                        .setUserFields(ContextMap.builder().set(Beardifier.CONTEXT_KEY, Beardifier.EMPTY).build())
                        .useBufferArena(this.coarsePool)
                        .enableCaches()
                        .build()).get(t.settings.noiseRouter().finalDensity());
            }
            try (var buffer = this.coarseDensity.sampleVolume(volume)) {
                for (int z = 0; z < n; z++)
                    for (int x = 0; x < n; x++)
                        for (int y = 0; y < ny; y++)
                            solid[(x + z * n) * ny + y] = buffer.get(volume.indexUnchecked(x, y, z)) > 0;
            }

            Mapper mapper = sink.mapper();
            int stoneId = mapper.getIdForBlockState(t.settings.defaultBlock());
            int fluidId = mapper.getIdForBlockState(t.settings.defaultFluid());
            int iceId = mapper.getIdForBlockState(Blocks.ICE.defaultBlockState());

            int[] blocks = new int[n * n * ny];
            byte[] lights = new byte[n * n * ny];
            int[] biomes = new int[n * n];
            int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
            var mpos = new BlockPos.MutableBlockPos();

            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int col = x + z * n;
                    int base = col * ny;
                    int wx = minX + x * step + step / 2, wz = minZ + z * step + step / 2;

                    int topVoxel = -1;
                    for (int y = ny - 1; y >= 0; y--) if (solid[base + y]) { topVoxel = y; break; }
                    int surfaceBlockY = topVoxel < 0 ? t.seaLevel : minY + topVoxel * step;
                    Holder<Biome> biome = this.climate.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(surfaceBlockY), QuartPos.fromBlock(wz));
                    biomes[col] = mapper.getIdForBiome(biome);

                    int topId = mapper.getIdForBlockState(t.traits.top(biome));
                    int fillerId = mapper.getIdForBlockState(t.traits.filler(biome));
                    int underwaterId = mapper.getIdForBlockState(t.traits.underwater(biome));
                    boolean cold = t.waterFeatures && !t.hasCeiling
                            && biome.value().coldEnoughToSnow(mpos.set(wx, surfaceBlockY, wz), t.seaLevel);

                    int sky = t.hasCeiling ? 0 : MAX_LIGHT;
                    int depth = -1;// blocks below the last exposed surface, -1 = in the open
                    boolean aboveIsFluid = false;
                    int colHighest = Integer.MIN_VALUE;

                    for (int y = ny - 1; y >= 0; y--) {
                        int idx = base + y;
                        int blockY = minY + y * step;
                        if (solid[idx]) {
                            if (depth < 0) {
                                blocks[idx] = aboveIsFluid ? underwaterId : topId;
                                depth = 0;
                                colHighest = Math.max(colHighest, blockY + step - 1);
                            } else {
                                depth += step;
                                blocks[idx] = depth < 4 ? fillerId : stoneId;
                            }
                            lights[idx] = 0;
                            sky = 0;
                            aboveIsFluid = false;
                        } else {
                            depth = -1;
                            if (blockY < t.seaLevel) {
                                boolean surfaceWater = !aboveIsFluid && sky > 0;
                                blocks[idx] = surfaceWater && cold ? iceId : fluidId;
                                colHighest = Math.max(colHighest, blockY + step - 1);
                                aboveIsFluid = true;
                                lights[idx] = (byte) light(sky);
                                sky = Math.max(0, sky - step);
                            } else {
                                blocks[idx] = 0;
                                lights[idx] = (byte) sky;
                                aboveIsFluid = false;
                            }
                        }
                    }

                    if (topVoxel >= 0) {
                        lowest = Math.min(lowest, surfaceBlockY);
                        int idx = base + topVoxel;
                        boolean dry = blocks[idx] == topId && topVoxel + 1 < ny && blocks[idx + 1] == 0;
                        if (dry && t.trees && BiomeTraits.canHoldTree(t.traits.top(biome))) {
                            var trees = t.traits.trees(biome, biome.value().getGenerationSettings());
                            if (trees.density() > 0) {
                                // Chance that a tree lands somewhere in this step x step column
                                float p = 1 - (float) Math.pow(1 - trees.density(), step * step);
                                if (chance(t.seed, wx, wz, 3) < p) {
                                    int leavesId = mapper.getIdForBlockState(BiomeTraits.leaves(trees.kind()));
                                    if (step >= 8) {
                                        blocks[idx] = leavesId;
                                    } else if (step == 4) {
                                        blocks[idx + 1] = leavesId;
                                        colHighest = Math.max(colHighest, surfaceBlockY + 2 * step - 1);
                                    } else {
                                        blocks[idx + 1] = mapper.getIdForBlockState(BiomeTraits.log(trees.kind()));
                                        if (topVoxel + 3 < ny) {
                                            blocks[idx + 2] = leavesId;
                                            blocks[idx + 3] = leavesId;
                                            colHighest = Math.max(colHighest, surfaceBlockY + 4 * step - 1);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    highest = Math.max(highest, colHighest);
                }
            }

            if (highest == Integer.MIN_VALUE) return;// void

            int bottomBlock = t.hasCeiling ? minY : Math.max(minY, Math.min(t.seaLevel - 24, (lowest == Integer.MAX_VALUE ? t.seaLevel : lowest) - 8));
            int topBlock = Math.min(minY + noiseSettings.height() - 1, highest + 16);
            int bottomSection = SectionPos.blockToSectionCoord(bottomBlock);
            int topSection = SectionPos.blockToSectionCoord(topBlock);

            long[] data = this.section.section;
            for (int sy = bottomSection; sy <= topSection; sy++) {
                this.section.setPosition(cx, sy, cz);
                int nonAir = 0;
                int baseY = SectionPos.sectionToBlockCoord(sy);
                for (int ly = 0; ly < 16; ly++) {
                    int vy = (baseY + ly - minY) / step;
                    boolean inRange = vy >= 0 && vy < ny;
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            int i = (ly << 8) | (lz << 4) | lx;
                            int col = (lx >> lvl) + (lz >> lvl) * n;
                            if (!inRange) {
                                data[i] = Mapper.airWithLight(t.hasCeiling ? 0 : MAX_LIGHT);
                                continue;
                            }
                            int idx = col * ny + vy;
                            int b = blocks[idx];
                            nonAir += b != 0 ? 1 : 0;
                            data[i] = Mapper.composeMappingId(lights[idx], b, biomes[col]);
                        }
                    }
                }
                this.section.lvl0NonAirCount = nonAir;
                WorldVoxilizedSectionMipper.mipSection(this.section, mapper);
                sink.insert(this.section);
            }
        }
    }
}
