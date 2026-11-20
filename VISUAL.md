# MeteorSmash Visual Design

The effect uses the visual primitives available in the Pumpkin 0.1.2 WIT API.

## Composition

| Layer | Implementation |
|---|---|
| Meteor core | `BLOCK_DISPLAY` with `minecraft:magma_block` |
| Rotating shell | six `BLOCK_DISPLAY` entities using obsidian, netherrack, blackstone, crying obsidian and magma |
| Motion | `DisplayTransformation` translation and quaternion rotation updated once per tick |
| Trail | `FLAME`, `LAVA`, `LARGE_SMOKE`, `FIREWORK`, `END_ROD`, `SOUL_FIRE_FLAME`, and `ELECTRIC_SPARK` particles |
| Impact | expanding particle shockwave with `EXPLOSION_EMITTER`, `EXPLOSION`, `FLAME`, `LAVA`, `LARGE_SMOKE`, `FIREWORK`, `END_ROD`, `SOUL_FIRE_FLAME`, and `ELECTRIC_SPARK` |
| Crater | staged six-block radial wave: magma center, netherrack mid-ring, blackstone outer rim, excavated below-layer |
| Fire | deterministic fire placed on top of a real support block, never in mid-air |
| Label | `TEXT_DISPLAY` with a centered gold `METEOR` billboard |
| Audio | custom Minecraft resource identifiers for dragon, blaze, explosion, thunder and fire sounds |

## Why mixed composition

A pure block-display meteor gives crisp geometry but no volumetric trail. A pure particle meteor is atmospheric but loses the recognizable block silhouette. The mixed version uses block displays for the readable meteor body and real particles for motion, heat and impact.

The impact uses actual world block changes rather than only display entities, so the destroyed crater and fire are persistent and visible to the server simulation. The crater opens as an expanding wave instead of one instantaneous edit, producing a heat/explosion gradient. Displays are removed at impact through an explicit cleanup sweep, and a per-run generation token prevents a stale task from leaving the magma core behind.

## Timing

- fall: 64 ticks;
- impact particles: immediate burst;
- crater wave: one radius step every two ticks up to radius 6;
- residue: 100 ticks of embers, smoke and secondary impact particles before the task is cancelled;
- display interpolation: 1 tick per transform update.

The animation runs on one `everyTicks` task owned by `MeteorDirector`. The task is cancelled from `onDisable` and before a second meteor is summoned.
