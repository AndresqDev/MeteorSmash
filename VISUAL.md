# MeteorSmash Visual Design

The effect uses the visual primitives available in the Pumpkin 0.1.2 WIT API.

## Composition

| Layer | Implementation |
|---|---|
| Meteor core | `BLOCK_DISPLAY` with `minecraft:magma_block` |
| Rotating shell | six `BLOCK_DISPLAY` entities using obsidian, netherrack, blackstone, crying obsidian and magma |
| Motion | `DisplayTransformation` translation and quaternion rotation updated once per tick |
| Trail | `FLAME`, `LAVA`, `FIREWORK`, `END_ROD`, `SOUL_FIRE_FLAME`, and `ELECTRIC_SPARK` particles |
| Impact | expanding particle shockwave with `EXPLOSION_EMITTER`, `EXPLOSION`, `FLAME`, `LAVA`, `FIREWORK`, `END_ROD`, `SOUL_FIRE_FLAME`, and `ELECTRIC_SPARK` |
| Crater | post-explosion surface scan and palette wave across the nearest air-exposed block in each column |
| Fire | deterministic fire placed on the nearest air-exposed support block, never in mid-air |
| Label | `TEXT_DISPLAY` with a centered gold `METEOR` billboard |
| Audio | custom Minecraft resource identifiers for dragon, blaze, explosion, thunder and fire sounds |

## Display alignment

A block display's transformation pivot is the bottom-north-west corner of the block model, not its center. The effect therefore uses:

```text
translationX = orbitX + scale / 2
translationY = orbitY - 1.0 - scale / 2
translationZ = orbitZ + scale / 2
```

The `-1.0` accounts for the impact position being one block above the ground surface. The half-scale offsets center each block around its intended orbit position. Interpolation is disabled so the core and shell cannot lag into visibly different positions.

Block changes use `BlockFlags(notifyNeighbors=true, notifyListeners=true, forceState=true)`. Without the listener flag the server changes the chunk state but does not send the client the corresponding block update.

## Why mixed composition

A pure block-display meteor gives crisp geometry but no volumetric trail. A pure particle meteor is atmospheric but loses the recognizable block silhouette. The mixed version uses block displays for the readable meteor body and real particles for motion, heat and impact.

The impact uses actual world block changes rather than only display entities, so the destroyed crater and fire are persistent and visible to the server simulation. After the initial explosion there is an 8-tick hold, then an ordered pulse repaints surface columns within radius 14. Each two ticks opens a new one-block ring through a warm palette (magma, netherrack, terracotta tones, blackstone, basalt, obsidian). The pulse does not create fire, so the material colors remain readable. A second explosion closes the sequence at tick 50. Displays are removed at impact through an explicit cleanup sweep, and a per-run generation token prevents a stale task from leaving the magma core behind.

## Timing

- fall: 44 ticks;
- impact particles: immediate burst;
- surface gradient: 8-tick hold, then one block-radius ring every 2 ticks out to radius 14;
- gradient fire: none; surface fire is cleared while the color pulse passes;
- secondary explosion: triggered 50 ticks after impact;
- residue: 110 ticks after impact before the task is cancelled;
- display interpolation: 1 tick per transform update.

The animation runs on one `everyTicks` task owned by `MeteorDirector`. The task is cancelled from `onDisable` and before a second meteor is summoned.
