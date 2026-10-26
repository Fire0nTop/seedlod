# SeedLOD for Voxy (Minecraft 26.3, Fabric)

Fills [Voxy](https://modrinth.com/mod/voxy)'s LODs straight from the world seed, so distant terrain shows up without exploring it.

**Requires:** Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Voxy 0.2.20-beta, Sodium (Voxy needs it). Java 25.

**Works in singleplayer and on servers.**
- Singleplayer / LAN host: uses the integrated server's real generator, so datapacks and worldgen mods are included.
- Multiplayer: rebuilds vanilla terrain from the world seed. If you may run `/seed`, SeedLOD asks the server for it quietly when you join; otherwise set it with `/seedlod seed <seed>`. Every server sends clients a hash of its seed, and SeedLOD checks your seed against it, so a wrong seed is refused. Only vanilla worldgen can be rebuilt; servers with custom terrain (datapacks/mods) will look different.

## How it works
- **Near the player (`detailRadius`):** runs vanilla's own biome, noise and surface-rule code on throwaway chunks off-thread. Then it adds approximate trees, snow and ice. Heights match vanilla exactly.
- **Further out (`nSized`):** samples 26.3's density function on a strided volume, with steps of 2, 4, 8 or 16 blocks depending on distance. Each LOD voxel is only computed once. Surface blocks come from what the detail tier saw for each biome, or from biome-based guesses before that.
- **Structures:** the ones you can see from a distance (villages, temples, outposts, mansions, ocean ruins, monuments, shipwrecks, ruined portals, igloos, witch huts, Nether fortresses, bastions, End cities) are placed with vanilla's own code, including the flattened ground under villages. Chunks they touch are always generated at full detail, so they keep their shape in the distance. Underground structures are left out.
- Never touches real terrain. It skips chunks around players, chunks the game has loaded, chunks Voxy already holds terrain for, and (singleplayer) full chunks in the region files. Voxy's normal ingest owns those.
- Progress is saved in `<world>/seedlod/<dimension>.bin` (singleplayer) or `config/seedlod/servers/<address>/` (servers). Areas that were generated coarsely get refined when you come closer.

## Commands
`/seedlod fill [radius] [threads]` · `/seedlod stop` · `/seedlod status` · `/seedlod reset` (regenerate everything SeedLOD made in this dimension; real terrain is kept) · `/seedlod config [option] [value]`

Servers: `/seedlod seed <seed>` (numbers or text, like the world-creation box) · `/seedlod seed` (show) · `/seedlod seed clear` · `/seedlod seed force <seed>` (for servers that send a fake seed hash) · `/seedlod preset normal|large_biomes|amplified`. Changing the seed or preset clears that server's progress.

Config options (also in `config/seedlod.json`): `distance` (16–4096, default 256), `detailRadius` (48), `continuous` (true), `nSized` (true), `threads`, `trees`, `waterFeatures`, `structures`, `debug`.

## For server owners (fair play)
SeedLOD only runs on players' clients, but you decide what it may do on your server. Put one of these markers in your server's MOTD, or send it in any chat or system message (a join message works):

| Marker | Effect |
|---|---|
| `seedlod:no-structures` | SeedLOD draws terrain but no structures, so nobody can spot villages, bastions, monuments or End cities from afar. |
| `seedlod:disable` | SeedLOD doesn't run on your server at all. |

Players' clients wait a few seconds after joining for the MOTD before generating anything. If a marker arrives later, SeedLOD stops and redraws what it already made without structures. Clients remember the rule for your server; it's lifted when your MOTD no longer contains the marker. Terrain SeedLOD drew before `seedlod:disable` arrived stays in the player's Voxy cache.

## Limitations
- No caves, ores or underground structures. The Overworld underground is solid.
- Trees are approximations (shape by biome), not the real tree features.
- Changed settings only affect new areas; run `/seedlod reset` to redo what SeedLOD already generated.

## Build
```bash
./gradlew build
```
Output: `build/libs/seedlod-<version>.jar`
