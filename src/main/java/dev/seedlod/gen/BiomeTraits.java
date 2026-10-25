package dev.seedlod.gen;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-biome knowledge the coarse generator can't get from noise alone: what the surface looks like
 * and what trees grow there. Surface blocks are learned from fully generated chunks (so modded
 * surface rules are picked up), falling back to name/tag heuristics for biomes not seen yet.
 */
public final class BiomeTraits {
    public enum TreeKind { NONE, OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, DARK_OAK, CHERRY, MANGROVE, PALE_OAK }

    public record Trees(TreeKind kind, float density) {
        static final Trees NONE = new Trees(TreeKind.NONE, 0);
    }

    private static final class Surface {
        final BlockCounter top = new BlockCounter();
        final BlockCounter filler = new BlockCounter();
        final BlockCounter underwater = new BlockCounter();
    }

    // Tiny majority vote: we only need "the usual block", not exact statistics
    private static final class BlockCounter {
        private final Map<BlockState, Integer> counts = new ConcurrentHashMap<>();
        private volatile BlockState best;
        private volatile int bestCount;

        void add(BlockState state) {
            int c = this.counts.merge(state, 1, Integer::sum);
            if (c > this.bestCount) {
                this.bestCount = c;
                this.best = state;
            }
        }

        BlockState get() {
            return this.bestCount >= 8 ? this.best : null;
        }
    }

    private final Map<Holder<Biome>, Surface> surfaces = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, Trees> trees = new ConcurrentHashMap<>();
    private final BlockState defaultBlock;

    public BiomeTraits(BlockState defaultBlock) {
        this.defaultBlock = defaultBlock;
    }

    private Surface surface(Holder<Biome> biome) {
        return this.surfaces.computeIfAbsent(biome, b -> new Surface());
    }

    public void learnLand(Holder<Biome> biome, BlockState top, BlockState below) {
        var s = this.surface(biome);
        s.top.add(top);
        s.filler.add(below);
    }

    public void learnUnderwater(Holder<Biome> biome, BlockState floor) {
        this.surface(biome).underwater.add(floor);
    }

    public BlockState top(Holder<Biome> biome) {
        var learned = this.surface(biome).top.get();
        return learned != null ? learned : this.guessTop(biome);
    }

    public BlockState filler(Holder<Biome> biome) {
        var learned = this.surface(biome).filler.get();
        return learned != null ? learned : this.guessFiller(biome);
    }

    public BlockState underwater(Holder<Biome> biome) {
        var learned = this.surface(biome).underwater.get();
        return learned != null ? learned : this.guessUnderwater(biome);
    }

    private static String path(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
    }

    private BlockState guessTop(Holder<Biome> biome) {
        String p = path(biome);
        if (!biome.is(BiomeTags.IS_OVERWORLD)) return this.defaultBlock;
        if (biome.is(BiomeTags.IS_BADLANDS)) return Blocks.RED_SAND.defaultBlockState();
        if (biome.is(BiomeTags.IS_BEACH) || p.contains("desert")) return Blocks.SAND.defaultBlockState();
        if (p.contains("snowy") || p.contains("frozen") || p.contains("grove") || p.contains("jagged") || p.contains("ice_spikes")) return Blocks.SNOW_BLOCK.defaultBlockState();
        if (p.contains("stony") || p.contains("peaks")) return Blocks.STONE.defaultBlockState();
        if (p.contains("mushroom")) return Blocks.MYCELIUM.defaultBlockState();
        if (p.contains("mangrove")) return Blocks.MUD.defaultBlockState();
        if (p.contains("old_growth") && p.contains("taiga")) return Blocks.PODZOL.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState guessFiller(Holder<Biome> biome) {
        String p = path(biome);
        if (!biome.is(BiomeTags.IS_OVERWORLD)) return this.defaultBlock;
        if (biome.is(BiomeTags.IS_BADLANDS)) return Blocks.TERRACOTTA.defaultBlockState();
        if (biome.is(BiomeTags.IS_BEACH) || p.contains("desert")) return Blocks.SANDSTONE.defaultBlockState();
        if (p.contains("stony") || p.contains("peaks")) return Blocks.STONE.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    private BlockState guessUnderwater(Holder<Biome> biome) {
        String p = path(biome);
        if (!biome.is(BiomeTags.IS_OVERWORLD)) return this.defaultBlock;
        if (p.contains("warm") || biome.is(BiomeTags.IS_BEACH) || p.contains("desert")) return Blocks.SAND.defaultBlockState();
        if (biome.is(BiomeTags.IS_OCEAN)) return Blocks.GRAVEL.defaultBlockState();
        if (p.contains("swamp")) return Blocks.MUD.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    public Trees trees(Holder<Biome> biome, BiomeGenerationSettings settings) {
        return this.trees.computeIfAbsent(biome, b -> computeTrees(b, settings));
    }

    private static Trees computeTrees(Holder<Biome> biome, BiomeGenerationSettings settings) {
        String p = path(biome);
        // Known vanilla densities (trees per block column), roughly matching vanilla's placement counts
        float density;
        if (p.equals("dark_forest") || p.equals("pale_garden")) density = 0.06f;
        else if (p.contains("jungle") && !p.contains("sparse")) density = 0.05f;
        else if (p.equals("mangrove_swamp")) density = 0.04f;
        else if (p.contains("old_growth")) density = 0.04f;
        else if (p.contains("forest") || p.contains("taiga")) density = 0.03f;
        else if (p.equals("grove") || p.equals("sparse_jungle") || p.equals("cherry_grove")) density = 0.015f;
        else if (p.contains("swamp") || p.equals("wooded_badlands")) density = 0.01f;
        else if (p.contains("savanna")) density = 0.004f;
        else if (p.contains("windswept_hills")) density = 0.002f;
        else if (p.equals("plains") || p.equals("sunflower_plains") || p.equals("meadow")) density = 0.0008f;
        else density = hasTreeFeature(settings) ? 0.01f : 0f;

        if (density <= 0) return Trees.NONE;

        TreeKind kind;
        if (p.contains("birch")) kind = TreeKind.BIRCH;
        else if (p.contains("pale")) kind = TreeKind.PALE_OAK;
        else if (p.contains("dark_forest")) kind = TreeKind.DARK_OAK;
        else if (p.contains("cherry")) kind = TreeKind.CHERRY;
        else if (p.contains("mangrove")) kind = TreeKind.MANGROVE;
        else if (p.contains("jungle") || p.contains("bamboo") || biome.is(BiomeTags.IS_JUNGLE)) kind = TreeKind.JUNGLE;
        else if (p.contains("savanna") || biome.is(BiomeTags.IS_SAVANNA)) kind = TreeKind.ACACIA;
        else if (p.contains("taiga") || p.contains("spruce") || p.contains("grove") || p.contains("snowy") || biome.is(BiomeTags.IS_TAIGA)) kind = TreeKind.SPRUCE;
        else kind = TreeKind.OAK;
        return new Trees(kind, density);
    }

    // Modded biomes: if any placed feature is named like a tree, assume a moderately wooded biome
    private static boolean hasTreeFeature(BiomeGenerationSettings settings) {
        for (var step : settings.features()) {
            for (var feature : step) {
                String id = feature.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                if (id.contains("tree")) return true;
            }
        }
        return false;
    }

    public static BlockState log(TreeKind kind) {
        return switch (kind) {
            case BIRCH -> Blocks.BIRCH_LOG.defaultBlockState();
            case SPRUCE -> Blocks.SPRUCE_LOG.defaultBlockState();
            case JUNGLE -> Blocks.JUNGLE_LOG.defaultBlockState();
            case ACACIA -> Blocks.ACACIA_LOG.defaultBlockState();
            case DARK_OAK -> Blocks.DARK_OAK_LOG.defaultBlockState();
            case CHERRY -> Blocks.CHERRY_LOG.defaultBlockState();
            case MANGROVE -> Blocks.MANGROVE_LOG.defaultBlockState();
            case PALE_OAK -> Blocks.PALE_OAK_LOG.defaultBlockState();
            default -> Blocks.OAK_LOG.defaultBlockState();
        };
    }

    public static BlockState leaves(TreeKind kind) {
        return switch (kind) {
            case BIRCH -> Blocks.BIRCH_LEAVES.defaultBlockState();
            case SPRUCE -> Blocks.SPRUCE_LEAVES.defaultBlockState();
            case JUNGLE -> Blocks.JUNGLE_LEAVES.defaultBlockState();
            case ACACIA -> Blocks.ACACIA_LEAVES.defaultBlockState();
            case DARK_OAK -> Blocks.DARK_OAK_LEAVES.defaultBlockState();
            case CHERRY -> Blocks.CHERRY_LEAVES.defaultBlockState();
            case MANGROVE -> Blocks.MANGROVE_LEAVES.defaultBlockState();
            case PALE_OAK -> Blocks.PALE_OAK_LEAVES.defaultBlockState();
            default -> Blocks.OAK_LEAVES.defaultBlockState();
        };
    }

    public static boolean canHoldTree(BlockState ground) {
        return ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL)
                || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.MUD) || ground.is(Blocks.SNOW_BLOCK)
                || ground.is(Blocks.MYCELIUM);
    }
}
