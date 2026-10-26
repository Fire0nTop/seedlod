package dev.seedlod.gen;

import dev.seedlod.SeedLod;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Remembers, per chunk of a dimension, either that it holds real terrain ({@link #REAL}, never to be written over)
 * or the finest LOD level SeedLOD wrote there (0-4), so restarts and continuous mode don't redo work and far-away
 * coarse chunks get refined when the player comes closer.
 */
public final class DoneMap {
    public static final byte NONE = Byte.MAX_VALUE;
    /** Real terrain (explored, or loaded by the game): Voxy got it from the world, SeedLOD must leave it alone. */
    public static final byte REAL = -1;
    /** SeedLOD wrote this chunk but should write it again (after a reset); coarser than any real level. */
    public static final byte REDO = 5;
    private static final int MAGIC = 0x53454C44;// "SELD"
    // Version 1 stored real terrain as level 0, indistinguishable from SeedLOD's own full-detail chunks
    private static final int VERSION = 2;

    private final Path file;
    private final Long2ByteOpenHashMap levels = new Long2ByteOpenHashMap();
    private final Object saveLock = new Object();
    private boolean dirty;

    private final boolean regionChecked;

    /**
     * @param regionChecked whether explored chunks are also caught by reading region files (singleplayer); decides
     *                      how much an old version 1 file can be trusted
     */
    public DoneMap(Path file, boolean regionChecked) {
        this.file = file;
        this.regionChecked = regionChecked;
        this.levels.defaultReturnValue(NONE);
        this.load();
    }

    public synchronized byte get(long chunk) {
        return this.levels.get(chunk);
    }

    public synchronized void put(long chunk, int lvl) {
        if (lvl < this.levels.get(chunk)) {
            this.levels.put(chunk, (byte) lvl);
            this.dirty = true;
        }
    }

    public synchronized int size() {
        return this.levels.size();
    }

    /** Regenerate everything SeedLOD wrote, keeping the record of real terrain. */
    public synchronized void redoGenerated() {
        for (var e : this.levels.long2ByteEntrySet()) {
            if (e.getByteValue() != REAL) e.setValue(REDO);
        }
        this.dirty = true;
    }

    private void load() {
        if (!Files.exists(this.file)) return;
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(this.file)))) {
            int magic = in.readInt(), version = in.readInt();
            if (magic != MAGIC || version < 1 || version > VERSION) {
                SeedLod.LOGGER.warn("Ignoring unknown progress file {}", this.file);
                return;
            }
            int count = in.readInt();
            int skipped = 0;
            for (int i = 0; i < count; i++) {
                long chunk = in.readLong();
                byte lvl = in.readByte();
                // A damaged entry must not mark chunks as done, at worst they get generated again
                if (lvl < REAL || lvl > REDO) {
                    skipped++;
                    continue;
                }
                // Old files: level 0 may be real terrain. Without region files to re-check (servers) keep it safe as
                // real; with them, a later reset may regenerate it because explored chunks are caught anyway.
                if (version == 1 && lvl == 0 && !this.regionChecked) lvl = REAL;
                this.levels.put(chunk, lvl);
            }
            if (skipped > 0) {
                SeedLod.LOGGER.warn("Skipped {} damaged entries in progress file {}", skipped, this.file);
            }
        } catch (IOException e) {
            SeedLod.LOGGER.error("Failed to read progress file {}", this.file, e);
        }
    }

    public void save() {
        // Workers all hit their periodic save around the same time; only one may touch the files at once
        synchronized (this.saveLock) {
            this.save0();
        }
    }

    private void save0() {
        Long2ByteMap snapshot;
        synchronized (this) {
            if (!this.dirty) return;
            this.dirty = false;
            snapshot = new Long2ByteOpenHashMap(this.levels);
        }
        try {
            Files.createDirectories(this.file.getParent());
            Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(snapshot.size());
                for (var e : snapshot.long2ByteEntrySet()) {
                    out.writeLong(e.getLongKey());
                    out.writeByte(e.getByteValue());
                }
            }
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SeedLod.LOGGER.error("Failed to write progress file {}", this.file, e);
        }
    }
}
