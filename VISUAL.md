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

## Why mixed composition

A pure block-display meteor gives crisp geometry but no volumetric trail. A pure particle meteor is atmospheric but loses the recognizable block silhouette. The mixed version uses block displays for the readable meteor body and real particles for motion, heat and impact.

The impact uses actual world block changes rather than only display entities, so the destroyed crater and fire are persistent and visible to the server simulation. After the initial explosion, every surface column within radius 8 is repainted through a warm palette (magma, netherrack, terracotta tones, blackstone, obsidian). The wave changes colors rapidly from the center outward, then a second explosion finishes the beat. Displays are removed at impact through an explicit cleanup sweep, and a per-run generation token prevents a stale task from leaving the magma core behind.

## Timing

- fall: 44 ticks;
- impact particles: immediate burst;
- surface gradient: 26 ticks, repainting the current surface of every column in the radius with a shifting palette;
- secondary explosion: triggered 20 ticks after impact;
- residue: 80 ticks after impact before the task is cancelled;
- display interpolation: 1 tick per transform update.

The animation runs on one `everyTicks` task owned by `MeteorDirector`. The task is cancelled from `onDisable` and before a second meteor is summoned.
