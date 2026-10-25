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
 * Remembers the finest LOD level already written for each chunk of a dimension, so restarts and
 * continuous mode don't redo work, and so far-away coarse chunks get refined when the player comes closer.
 */
public final class DoneMap {
    public static final byte NONE = Byte.MAX_VALUE;
    private static final int MAGIC = 0x53454C44;// "SELD"
    private static final int VERSION = 1;

    private final Path file;
    private final Long2ByteOpenHashMap levels = new Long2ByteOpenHashMap();
    private final Object saveLock = new Object();
    private boolean dirty;

    public DoneMap(Path file) {
        this.file = file;
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

    public synchronized void clear() {
        this.levels.clear();
        this.dirty = true;
    }

    private void load() {
        if (!Files.exists(this.file)) return;
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(this.file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                SeedLod.LOGGER.warn("Ignoring unknown progress file {}", this.file);
                return;
            }
            int count = in.readInt();
            int skipped = 0;
            for (int i = 0; i < count; i++) {
                long chunk = in.readLong();
                byte lvl = in.readByte();
                // A damaged entry must not mark chunks as done, at worst they get generated again
                if (lvl < 0 || lvl > 4) {
                    skipped++;
                    continue;
                }
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
