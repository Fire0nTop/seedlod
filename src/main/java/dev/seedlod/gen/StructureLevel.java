package dev.seedlod.gen;

import dev.seedlod.SeedLod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The "world" structure pieces write into. Vanilla hands them a WorldGenRegion backed by the server's live chunk
 * system, which we don't have, so this stands in for one chunk: blocks go into our throwaway ProtoChunk, heights
 * outside it come from the noise generator, and everything a structure might do to a live world (spawn mobs,
 * fill chests, schedule ticks) is quietly dropped.
 *
 * <p>WorldGenLevel is a very wide interface; a proxy lets us answer the handful of calls pieces actually make and
 * fall back to the interface's own default methods for the rest.
 */
final class StructureLevel implements InvocationHandler {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final ProtoChunk chunk;
    private final GenContext ctx;
    private final BiomeResolver biomes;
    private final RandomSource random;
    private final int minX, minZ;

    private StructureLevel(ProtoChunk chunk, GenContext ctx, BiomeResolver biomes, RandomSource random) {
        this.chunk = chunk;
        this.ctx = ctx;
        this.biomes = biomes;
        this.random = random;
        this.minX = chunk.getPos().getMinBlockX();
        this.minZ = chunk.getPos().getMinBlockZ();
    }

    static WorldGenLevel create(ProtoChunk chunk, GenContext ctx, BiomeResolver biomes, RandomSource random) {
        return (WorldGenLevel) Proxy.newProxyInstance(StructureLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                new StructureLevel(chunk, ctx, biomes, random));
    }

    private boolean inside(BlockPos pos) {
        int dx = pos.getX() - this.minX, dz = pos.getZ() - this.minZ;
        return dx >= 0 && dx < 16 && dz >= 0 && dz < 16 && !this.chunk.isOutsideBuildHeight(pos.getY());
    }

    private BlockState blockState(BlockPos pos) {
        if (this.inside(pos)) return this.chunk.getBlockState(pos);
        // Outside our chunk: a rough answer from the noise is enough for the few pieces that peek over the edge
        int surface = this.height(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ());
        return pos.getY() < surface ? this.ctx.generator().generatorSettings().value().defaultBlock() : Blocks.AIR.defaultBlockState();
    }

    private int height(Heightmap.Types type, int x, int z) {
        // Only the worldgen heightmaps exist this early; the others would read as empty
        type = switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> Heightmap.Types.OCEAN_FLOOR_WG;
            default -> Heightmap.Types.WORLD_SURFACE_WG;
        };
        if (x - this.minX >= 0 && x - this.minX < 16 && z - this.minZ >= 0 && z - this.minZ < 16) {
            return this.chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(x & 15, z & 15);
        }
        return this.ctx.generator().getBaseHeight(x, z, type, this.ctx.heightAccessor(), this.ctx.randomState());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        int n = args == null ? 0 : args.length;
        switch (method.getName()) {
            case "getBlockState":
                if (n == 1 && args[0] instanceof BlockPos pos) return this.blockState(pos);
                break;
            case "getFluidState":
                if (n == 1 && args[0] instanceof BlockPos pos) return this.blockState(pos).getFluidState();
                break;
            case "setBlock":
                if (n >= 2 && args[0] instanceof BlockPos pos && args[1] instanceof BlockState state) {
                    if (!this.inside(pos)) return false;
                    this.chunk.setBlockState(pos, state, 0);
                    return true;
                }
                break;
            case "removeBlock", "destroyBlock":
                if (n >= 1 && args[0] instanceof BlockPos pos && this.inside(pos)) {
                    this.chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
                    return true;
                }
                return false;
            case "isStateAtPosition":
                if (n == 2 && args[0] instanceof BlockPos pos) return ((Predicate<BlockState>) args[1]).test(this.blockState(pos));
                break;
            case "isFluidAtPosition":
                if (n == 2 && args[0] instanceof BlockPos pos) return ((Predicate<Object>) args[1]).test(this.blockState(pos).getFluidState());
                break;
            case "getHeight":
                if (n == 0) return this.ctx.heightAccessor().getHeight();
                if (n == 3 && args[0] instanceof Heightmap.Types type) return this.height(type, (Integer) args[1], (Integer) args[2]);
                break;
            case "getMinY":
                return this.ctx.heightAccessor().getMinY();
            case "getSeaLevel":
                return this.ctx.generator().getSeaLevel();
            case "getSeed":
                return this.ctx.seed();
            case "getRandom":
                return this.random;
            case "registryAccess":
                return this.ctx.registries();
            case "enabledFeatures":
                return FeatureFlags.DEFAULT_FLAGS;
            case "dimensionType":
                return this.ctx.dimensionType();
            case "getNoiseBiome":
                if (n == 3) return this.biomes.getNoiseBiome((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                break;
            case "getBiome":
                if (n == 1 && args[0] instanceof BlockPos pos) {
                    return this.biomes.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
                }
                break;
            case "getChunk": {
                // Only our own chunk exists; handing it out for a neighbour's coordinates would misplace blocks
                int cx, cz;
                if (n >= 1 && args[0] instanceof BlockPos pos) {
                    cx = pos.getX() >> 4;
                    cz = pos.getZ() >> 4;
                } else if (n >= 2 && args[0] instanceof Integer x && args[1] instanceof Integer z) {
                    cx = x;
                    cz = z;
                } else {
                    return null;
                }
                return cx == this.chunk.getPos().x() && cz == this.chunk.getPos().z() ? this.chunk : null;
            }
            case "ensureCanWrite":
                return n == 1 && args[0] instanceof BlockPos pos && this.inside(pos);
            case "getBlockEntity", "getLevel", "getServer", "getChunkSource", "getLightEngine", "getWorldBorder":
                return null;
            case "addFreshEntity":
                return false;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "SeedLOD structure level for " + this.chunk.getPos();
            default:
                break;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        // Something a structure would do to a live world: there's none, so do nothing
        if (WARNED.add(method.getName())) {
            SeedLod.LOGGER.debug("Structure placement called {} on the stand-in level; ignored", method.getName());
        }
        return defaultValue(method.getReturnType());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
