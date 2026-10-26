package dev.seedlod.gen;

import dev.seedlod.SeedLod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Works out from the seed which structures exist and where, using vanilla's own placement and selection rules,
 * and caches them: a village is computed once, then looked up by every chunk it overlaps.
 *
 * <p>Only structures you can see in an LOD are kept: vanilla's surface step (villages, temples, outposts, mansions,
 * ocean ruins, monuments, shipwrecks, ruined portals, igloos, witch huts, bastions, End cities) plus Nether fortresses
 * and fossils. Underground ones would cost time and stay hidden inside SeedLOD's solid underground.
 */
public final class StructureIndex {
    // Furthest a visible structure reaches from its start chunk (mansions, villages, bastions stay well inside this)
    private static final int REACH = 8;
    private static final int MAX_LOGGED_ERRORS = 5;

    private final GenContext ctx;
    private final List<Holder<StructureSet>> sets;
    private final ConcurrentHashMap<Long, List<StructureStart>> starts = new ConcurrentHashMap<>();
    private final ThreadLocal<Climate.Sampler> climate;
    private final AtomicInteger errors = new AtomicInteger();

    public StructureIndex(GenContext ctx) {
        this.ctx = ctx;
        this.climate = ThreadLocal.withInitial(() -> ctx.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build()));
        // Strongholds use concentric rings, whose positions cost seconds to compute; they're underground anyway
        this.sets = ctx.structureState().possibleStructureSets().stream()
                .filter(set -> !(set.value().placement() instanceof ConcentricRingsStructurePlacement))
                .filter(set -> set.value().structures().stream().anyMatch(e -> isVisible(e.structure())))
                .toList();
    }

    public static boolean isVisible(Holder<Structure> structure) {
        if (structure.value().step() == GenerationStep.Decoration.SURFACE_STRUCTURES) return true;
        String id = structure.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        return id.equals("fortress") || id.equals("nether_fossil");
    }

    /** Visible structures whose pieces (plus their terrain-shaping margin) overlap this chunk. */
    public List<StructureStart> affecting(int cx, int cz) {
        int minX = cx << 4, minZ = cz << 4;
        List<StructureStart> out = null;
        for (int dz = -REACH; dz <= REACH; dz++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (StructureStart start : this.startsAt(cx + dx, cz + dz)) {
                    BoundingBox box = start.getStructure().adjustBoundingBox(start.getBoundingBox());
                    if (box.intersects(minX, minZ, minX + 15, minZ + 15)) {
                        if (out == null) out = new ArrayList<>(2);
                        out.add(start);
                    }
                }
            }
        }
        return out == null ? List.of() : out;
    }

    private List<StructureStart> startsAt(int cx, int cz) {
        return this.starts.computeIfAbsent(ChunkPos.pack(cx, cz), k -> this.compute(cx, cz));
    }

    // Mirrors ChunkGenerator#createStructures for one chunk, minus the parts that need a real world
    private List<StructureStart> compute(int cx, int cz) {
        var state = this.ctx.structureState();
        List<StructureStart> found = null;
        for (var set : this.sets) {
            if (!set.value().placement().isStructureChunk(state, cx, cz)) continue;
            List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.value().structures());
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureSeed(state.getLevelSeed(), cx, cz);
            int total = 0;
            for (var option : options) total += option.weight();

            // Same weighted pick as vanilla: a failed pick (wrong biome, no room) falls through to the next option
            while (!options.isEmpty()) {
                int index = 0;
                if (options.size() > 1) {
                    int choice = random.nextInt(total);
                    for (var option : options) {
                        choice -= option.weight();
                        if (choice < 0) break;
                        index++;
                    }
                }
                var selected = options.get(index);
                StructureStart start = this.generate(selected.structure(), cx, cz);
                if (start != null && start.isValid()) {
                    if (isVisible(selected.structure())) {
                        if (found == null) found = new ArrayList<>(1);
                        found.add(start);
                    }
                    break;
                }
                options.remove(index);
                total -= selected.weight();
            }
        }
        return found == null ? List.of() : found;
    }

    private StructureStart generate(Holder<Structure> holder, int cx, int cz) {
        var ctx = this.ctx;
        var structure = holder.value();
        try {
            return structure.generate(holder, ctx.dimension(), ctx.registries(), ctx.generator(), ctx.generator().getBiomeSource(),
                    this.climate.get(), ctx.randomState(), ctx.templates(), ctx.structureState().getLevelSeed(), new ChunkPos(cx, cz),
                    0, ctx.heightAccessor(), structure.biomes()::contains);
        } catch (Exception e) {
            if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                SeedLod.LOGGER.warn("Couldn't lay out {} at chunk {} {}", holder.unwrapKey().map(Object::toString).orElse("structure"), cx, cz, e);
            }
            return null;
        }
    }

    /** Vanilla's terrain shaping around structures (villages sit on flattened ground, etc.), built from our own starts. */
    public static Beardifier beardifier(List<StructureStart> affecting, ChunkPos chunkPos) {
        if (affecting.isEmpty()) return Beardifier.EMPTY;
        int chunkStartX = chunkPos.getMinBlockX(), chunkStartZ = chunkPos.getMinBlockZ();
        List<Beardifier.Rigid> rigids = new ArrayList<>();
        List<JigsawJunction> junctions = new ArrayList<>();
        BoundingBox any = null;
        for (StructureStart start : affecting) {
            TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
            if (adjustment == TerrainAdjustment.NONE) continue;
            for (var piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunkPos, 12)) continue;
                if (piece instanceof PoolElementStructurePiece pool) {
                    if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        rigids.add(new Beardifier.Rigid(pool.getBoundingBox(), adjustment, pool.getGroundLevelDelta()));
                        any = any == null ? piece.getBoundingBox() : BoundingBox.encapsulating(any, piece.getBoundingBox());
                    }
                    for (JigsawJunction junction : pool.getJunctions()) {
                        int jx = junction.getSourceX(), jz = junction.getSourceZ();
                        if (jx > chunkStartX - 12 && jz > chunkStartZ - 12 && jx < chunkStartX + 15 + 12 && jz < chunkStartZ + 15 + 12) {
                            junctions.add(junction);
                            var box = new BoundingBox(new BlockPos(jx, junction.getSourceGroundY(), jz));
                            any = any == null ? box : BoundingBox.encapsulating(any, box);
                        }
                    }
                } else {
                    rigids.add(new Beardifier.Rigid(piece.getBoundingBox(), adjustment, 0));
                    any = any == null ? piece.getBoundingBox() : BoundingBox.encapsulating(any, piece.getBoundingBox());
                }
            }
        }
        return any == null ? Beardifier.EMPTY : new Beardifier(List.copyOf(rigids), List.copyOf(junctions), any.inflatedBy(24));
    }
}
