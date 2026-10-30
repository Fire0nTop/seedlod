package dev.seedlod.gen;

import dev.seedlod.SeedLod;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fills a circle of chunks around a center, nearest first, on a small pool of worker threads.
 */
public final class GenerationJob {
    public record Settings(int radius, int detailRadius, boolean nSized, int threads) {}

    private final SeedTerrain terrain;
    private final WorldEngine engine;
    private final VoxyInstance voxy;
    private final DoneMap done;
    @org.jetbrains.annotations.Nullable private final ExploredChunks explored;// singleplayer only
    private final StoredInVoxy stored;
    private final Settings settings;
    public final int centerX, centerZ;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong visited = new AtomicLong();
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final long total;
    private long startTime;
    private volatile long endTime;
    private volatile long[] protectedChunks = new long[0];
    private volatile int protectRadius;

    // Chunks of structures that reach past the radius, generated after it (any part generated means all of it is)
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> structureOverflow = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<Long> overflowQueued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Chunks skipped for being near a player, by chunk key -> offset. The game was about to load them, but a player
    // moving fast (or just arriving through a portal) can leave before it does; retried after everything else.
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> deferred = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEFERRED_PATIENCE_MS = 20_000;
    private volatile long deferredGiveUpAt;

    // Ring iterator state (guarded by this)
    private int ring = 0;
    private int ringIndex = 0;

    public GenerationJob(SeedTerrain terrain, WorldEngine engine, VoxyInstance voxy, DoneMap done, @org.jetbrains.annotations.Nullable ExploredChunks explored,
                         int centerX, int centerZ, Settings settings) {
        this.terrain = terrain;
        this.engine = engine;
        this.voxy = voxy;
        this.done = done;
        this.explored = explored;
        this.stored = new StoredInVoxy(engine, terrain.seaLevel);
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.settings = settings;
        long t = 0;
        long r2 = (long) settings.radius * settings.radius;
        for (int dz = -settings.radius; dz <= settings.radius; dz++) {
            long w = (long) Math.floor(Math.sqrt(r2 - (long) dz * dz));
            t += 2 * w + 1;
        }
        this.total = t;
    }

    public void start() {
        this.startTime = System.currentTimeMillis();
        this.engine.acquireRef();
        var remaining = new AtomicLong(this.settings.threads);
        for (int i = 0; i < this.settings.threads; i++) {
            var thread = new Thread(() -> {
                try {
                    this.work();
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        this.endTime = System.currentTimeMillis();
                        this.done.save();
                        this.engine.releaseRef();
                    }
                }
            }, "SeedLOD worker " + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            this.workers.add(thread);
            thread.start();
        }
    }

    /**
     * Stops the workers and waits for them, so the progress file is written before the caller moves on.
     * Workers are daemon threads: without this, closing the game kills them before the final save.
     */
    public void stop() {
        this.stopped.set(true);
        long deadline = System.currentTimeMillis() + 5_000;
        for (var thread : this.workers) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                thread.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!this.isFinished()) {
            // A worker is stuck in a slow chunk; save what we have rather than lose it
            this.done.save();
        }
    }

    public boolean isFinished() {
        return this.endTime != 0;
    }

    public SeedTerrain terrain() {
        return this.terrain;
    }

    /** Chunks around players are left for Voxy's own ingest of the real world. */
    public void setProtectedChunks(long[] chunks, int radius) {
        this.protectedChunks = chunks;
        this.protectRadius = radius;
    }

    private boolean isProtected(int cx, int cz) {
        int r = this.protectRadius;
        for (long p : this.protectedChunks) {
            // Exactly the circle vanilla sends to the client; a square here left its corners empty forever
            if (ChunkTrackingView.isInViewDistance(ChunkPos.getX(p), ChunkPos.getZ(p), r, cx, cz)) return true;
        }
        return false;
    }

    // Next chunk offset in square rings from the center, clipped to the circle; null when exhausted
    private synchronized long nextOffset() {
        int radius = this.settings.radius;
        long r2 = (long) radius * radius;
        while (this.ring <= radius) {
            int r = this.ring;
            int len = r == 0 ? 1 : 8 * r;
            while (this.ringIndex < len) {
                int i = this.ringIndex++;
                int dx, dz;
                if (r == 0) { dx = 0; dz = 0; }
                else {
                    int side = 2 * r;
                    if (i < side) { dx = -r + i; dz = -r; }
                    else if (i < 2 * side) { dx = r; dz = -r + (i - side); }
                    else if (i < 3 * side) { dx = r - (i - 2 * side); dz = r; }
                    else { dx = -r; dz = r - (i - 3 * side); }
                }
                if ((long) dx * dx + (long) dz * dz <= r2) return ChunkPos.pack(dx, dz);
            }
            this.ring++;
            this.ringIndex = 0;
        }
        // Then the parts of structures that stick out past the radius, so no structure is cut off at the edge
        Long extra = this.structureOverflow.poll();
        if (extra != null) return ChunkPos.pack(ChunkPos.getX(extra) - this.centerX, ChunkPos.getZ(extra) - this.centerZ);
        return Long.MIN_VALUE;
    }

    private void queueRestOfStructures(int cx, int cz) {
        long r2 = (long) this.settings.radius * this.settings.radius;
        this.terrain.forEachStructureChunk(cx, cz, key -> {
            long dx = ChunkPos.getX(key) - this.centerX, dz = ChunkPos.getZ(key) - this.centerZ;
            if (dx * dx + dz * dz > r2 && this.overflowQueued.add(key)) this.structureOverflow.add(key);
        });
    }

    private int targetLevel(int dx, int dz) {
        if (!this.settings.nSized) return 0;
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        int detail = this.settings.detailRadius;
        if (d <= detail) return 0;
        int lvl = 1;
        double threshold = Math.max(16, detail) * 2.0;
        while (d > threshold && lvl < 4) {
            lvl++;
            threshold *= 2;
        }
        return lvl;
    }

    private void work() {
        try (var worker = this.terrain.newWorker()) {
            this.work(worker);
        }
    }

    private void work(SeedTerrain.Worker worker) {
        var sink = SeedTerrain.Sink.of(this.engine);
        long lastSave = System.currentTimeMillis();
        while (!this.stopped.get()) {
            long off = this.nextOffset();
            if (off == Long.MIN_VALUE) off = this.nextDeferred();
            if (off == Long.MIN_VALUE) break;
            int dx = ChunkPos.getX(off), dz = ChunkPos.getZ(off);
            int cx = this.centerX + dx, cz = this.centerZ + dz;
            long key = ChunkPos.pack(cx, cz);
            this.visited.incrementAndGet();

            int lvl = this.targetLevel(dx, dz);
            // A structure needs its real blocks; Voxy shrinks them down for the distance like any other terrain
            if (this.terrain.structuresTouch(cx, cz)) {
                lvl = 0;
                this.queueRestOfStructures(cx, cz);
            }
            byte previous = this.done.get(key);
            if (previous <= lvl) continue;
            if (this.isProtected(cx, cz)) {
                this.deferred.put(key, off);
                continue;
            }
            // Data we didn't write ourselves is real terrain; data we did write may be refined
            if ((this.explored != null && this.explored.isExplored(cx, cz))
                    || (previous == DoneMap.NONE && this.stored.has(cx, cz))) {
                this.done.put(key, DoneMap.REAL);
                continue;
            }

            // Don't outrun Voxy's saving service, it holds every dirty section in memory until written
            while (!this.voxy.savingServiceRateLimiter.getAsBoolean() && !this.stopped.get() && this.voxy.isRunning()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
            // Voxy shutting down under us: leave at once so its shutdown isn't left waiting on our world reference
            if (this.stopped.get() || !this.engine.isLive() || !this.voxy.isRunning()) break;

            try {
                if (lvl == 0) {
                    worker.generateFull(sink, cx, cz);
                } else {
                    worker.generateCoarse(sink, cx, cz, lvl);
                }
                this.done.put(key, lvl);
                this.generated.incrementAndGet();
            } catch (Exception e) {
                if (this.errors.incrementAndGet() <= 5) {
                    SeedLod.LOGGER.error("Failed to generate chunk {} {}", cx, cz, e);
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastSave > 30_000) {
                lastSave = now;
                this.done.save();
            }
        }
    }

    /**
     * A skipped chunk that is now safe to generate: the player moved away and the game never loaded it (a loaded one
     * gets marked as real terrain and is dropped). Waits a little for the player to move; gives up after a while
     * without progress, a later job will see them again.
     */
    private long nextDeferred() {
        while (!this.stopped.get() && !this.deferred.isEmpty()) {
            long now = System.currentTimeMillis();
            if (this.deferredGiveUpAt == 0) this.deferredGiveUpAt = now + DEFERRED_PATIENCE_MS;
            for (var e : this.deferred.entrySet()) {
                long key = e.getKey();
                if (this.done.get(key) == DoneMap.REAL) {
                    this.deferred.remove(key);
                } else if (!this.isProtected(ChunkPos.getX(key), ChunkPos.getZ(key)) && this.deferred.remove(key) != null) {
                    this.deferredGiveUpAt = now + DEFERRED_PATIENCE_MS;
                    return e.getValue();
                }
            }
            if (now > this.deferredGiveUpAt) return Long.MIN_VALUE;
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    public String describe() {
        long elapsed = Math.max(1, (this.endTime != 0 ? this.endTime : System.currentTimeMillis()) - this.startTime);
        long v = this.visited.get(), g = this.generated.get();
        double rate = g * 1000.0 / elapsed;
        String state = this.isFinished() ? (this.stopped.get() ? "stopped" : "done") : "running";
        String eta = "";
        if (!this.isFinished() && v > 0) {
            long etaS = (long) ((this.total - v) * (elapsed / 1000.0) / v);
            eta = String.format(", ETA %dm%02ds", etaS / 60, etaS % 60);
        }
        return String.format("%s: %d/%d chunks checked (%.0f%%), %d generated at %.0f chunks/s, %.1fs elapsed%s%s",
                state, v, this.total, 100.0 * v / Math.max(1, this.total), g, rate, elapsed / 1000.0, eta,
                this.errors.get() > 0 ? ", " + this.errors.get() + " errors (see log)" : "");
    }
}
