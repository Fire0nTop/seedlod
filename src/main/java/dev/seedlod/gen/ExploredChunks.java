package dev.seedlod.gen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Answers "has the player really been here?" so seed terrain never overwrites chunks Voxy got from
 * the real world. A region file's header says which chunks exist at all (cheap); only for those do we
 * ask the server's IO worker for the chunk's status.
 */
public final class ExploredChunks {
    private static final long HEADER_TTL_MS = 30_000;
    private record Header(int[] offsets, long readAt) {}

    private final ServerLevel level;
    private final Path regionDir;
    private final ConcurrentHashMap<Long, Header> headers = new ConcurrentHashMap<>();

    public ExploredChunks(ServerLevel level) {
        this.level = level;
        this.regionDir = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
    }

    public boolean isExplored(int cx, int cz) {
        if (!this.existsInRegion(cx, cz)) return false;
        try {
            var tag = this.level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).get(10, TimeUnit.SECONDS);
            // Partially generated border chunks don't have real terrain Voxy could have shown, so only full ones count
            return tag.map(t -> t.getStringOr("Status", "").endsWith("full")).orElse(false);
        } catch (Exception e) {
            return true;// When unsure, leave the chunk alone
        }
    }

    private boolean existsInRegion(int cx, int cz) {
        int rx = cx >> 5, rz = cz >> 5;
        long key = ChunkPos.pack(rx, rz);
        long now = System.currentTimeMillis();
        Header h = this.headers.get(key);
        if (h == null || now - h.readAt > HEADER_TTL_MS) {
            h = new Header(this.readHeader(rx, rz), now);
            this.headers.put(key, h);
        }
        return h.offsets != null && h.offsets[(cx & 31) + (cz & 31) * 32] != 0;
    }

    private int[] readHeader(int rx, int rz) {
        Path file = this.regionDir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.exists(file)) return null;
        try (var raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 4096) return null;
            int[] offsets = new int[1024];
            for (int i = 0; i < 1024; i++) offsets[i] = raf.readInt();
            return offsets;
        } catch (IOException e) {
            // Can't tell, so treat the whole region as explored
            int[] all = new int[1024];
            java.util.Arrays.fill(all, 1);
            return all;
        }
    }
}
