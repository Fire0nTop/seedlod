package dev.seedlod.gen;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.SectionPos;

/**
 * Does Voxy already hold terrain for a chunk we never wrote? Then it came from the real world (an earlier visit,
 * or an import) and must be kept. On a multiplayer server this is the only record of where the player has been.
 */
public final class StoredInVoxy {
    private final WorldEngine engine;
    private final int[] sectionYs;

    public StoredInVoxy(WorldEngine engine, int seaLevel) {
        this.engine = engine;
        // Level 0 world sections are 32 blocks tall (two chunk sections). Real terrain almost always has blocks
        // at or just above sea level; two layers also cover the End, whose islands sit above its sea level of 0.
        int low = SectionPos.blockToSectionCoord(seaLevel) >> 1;
        this.sectionYs = new int[]{low, low + 1};
    }

    public boolean has(int cx, int cz) {
        for (int sy : this.sectionYs) {
            if (this.hasBlocks(cx, sy, cz)) return true;
        }
        return false;
    }

    private boolean hasBlocks(int cx, int sy, int cz) {
        WorldSection section = this.engine.acquireIfExists(0, cx >> 1, sy, cz >> 1);
        if (section == null) return false;
        try {
            // A level 0 section spans 2x2 chunks and only this chunk's quarter counts. Voxy fills parts nobody wrote
            // with sky-lit air (not zero), so look for actual blocks.
            long[] data = section._unsafeGetRawDataArray();
            int bx = (cx & 1) << 4, bz = (cz & 1) << 4;
            for (int y = 0; y < 32; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (!Mapper.isAir(data[WorldSection.getIndex(bx + x, y, bz + z)])) return true;
            return false;
        } finally {
            section.release();
        }
    }
}
