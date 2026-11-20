# MeteorSmash Visual Design

The effect uses the visual primitives available in the Pumpkin 0.1.2 WIT API.

## Composition

| Layer | Implementation |
|---|---|
| Meteor core | `BLOCK_DISPLAY` with `minecraft:magma_block` |
| Rotating shell | six `BLOCK_DISPLAY` entities using obsidian, netherrack, blackstone, crying obsidian and magma |
| Motion | center-based `DisplayTransformation` orbit and rotation updated once per tick |
| Trail | `FLAME`, `LAVA`, `FIREWORK`, `END_ROD`, `SOUL_FIRE_FLAME`, and `ELECTRIC_SPARK` particles |
| Impact | expanding particle shockwave with `EXPLOSION_EMITTER`, `EXPLOSION`, `FLAME`, `LAVA`, `FIREWORK`, `END_ROD`, `SOUL_FIRE_FLAME`, and `ELECTRIC_SPARK` |
| Crater | post-explosion surface scan and accelerating palette pulse across the nearest air-exposed block in each column |
| Fire | only the initial explosion can ignite supported surface blocks; the color pulse clears fire above each surface it reaches |
| Label | `TEXT_DISPLAY` with a centered gold `METEOR` billboard |
| Audio | custom Minecraft resource identifiers for dragon, blaze, explosion, thunder and fire sounds |

## Display alignment

A block display's transformation pivot is the bottom-north-west corner of the block model, not its center. The director therefore computes a desired visual center for every display and derives the translation from that center instead of adding half-scale offsets directly to orbit coordinates:

```text
half = scale / 2
rotatedCenterX = half * (cos(yaw) + sin(yaw))
rotatedCenterZ = half * (cos(yaw) - sin(yaw))
translationX = centerX - rotatedCenterX
translationY = centerY - half
translationZ = centerZ - rotatedCenterZ
```

The core center is `(impact.x, ground + CORE_SCALE / 2, impact.z)`. Each companion center is placed on a close orbit of radius `1.52` around that same point. The `-1.0` in the vertical center accounts for the impact position being one block above the ground surface. Interpolation is disabled so the core and companions cannot lag into visibly different positions.

Block changes use `BlockFlags(notifyNeighbors=true, notifyListeners=true, forceState=true)`. Without the listener flag the server changes the chunk state but does not send the client the corresponding block update.

## Why mixed composition

A pure block-display meteor gives crisp geometry but no volumetric trail. A pure particle meteor is atmospheric but loses the recognizable block silhouette. The mixed version uses block displays for the readable meteor body and real particles for motion, heat and impact.

The impact uses actual world block changes rather than only display entities, so the destroyed crater is visible to the server simulation. After the initial explosion there is a 3-tick hold, then an ordered pulse repaints surface columns within radius 14. The first five rings open every two ticks; from radius 5 onward one new ring opens each tick, producing a visible acceleration toward the climax. Each ring rotates through a warm palette (magma, netherrack, terracotta tones, blackstone, basalt, obsidian) and the pulse does not create fire, so the material colors remain readable. A second explosion closes the sequence at tick 24. Displays are removed at impact through an explicit cleanup sweep, and a per-run generation token prevents a stale task from leaving the magma core behind.

## Timing

- fall: 44 ticks;
- impact particles: immediate burst;
- surface gradient: 3-tick hold, then rings 0-4 every 2 ticks and rings 5-14 every tick;
- gradient fire: none; surface fire is cleared while the color pulse passes;
- secondary explosion: triggered 24 ticks after impact;
- residue: 70 ticks after impact before the task is cancelled;
- display interpolation: disabled; transforms are recomputed once per tick.

The animation runs on one `everyTicks` task owned by `MeteorDirector`. The task is cancelled from `onDisable` and before a second meteor is summoned.
