# MeteorSmash Visual Design

The effect uses the visual primitives available in the Pumpkin 0.1.2 WIT API.

## Composition

| Layer | Implementation |
|---|---|
| Meteor core | `BLOCK_DISPLAY` with `minecraft:magma_block` |
| Rotating shell | six `BLOCK_DISPLAY` entities using obsidian, netherrack, blackstone, crying obsidian and magma |
| Motion | `DisplayTransformation` translation and quaternion rotation updated once per tick |
| Trail | `FLAME`, `LAVA`, and `LARGE_SMOKE` particles |
| Impact | `EXPLOSION_EMITTER`, `EXPLOSION`, `FLAME`, `LAVA`, `LARGE_SMOKE`, and `FIREWORK` particles |
| Crater | layered real block replacement: magma center, air excavation, blackstone rim |
| Fire | deterministic surface fire placement in the outer crater ring |
| Label | `TEXT_DISPLAY` with a centered gold `METEOR` billboard |
| Audio | custom Minecraft resource identifiers for dragon, blaze, explosion, thunder and fire sounds |

## Why mixed composition

A pure block-display meteor gives crisp geometry but no volumetric trail. A pure particle meteor is atmospheric but loses the recognizable block silhouette. The mixed version uses block displays for the readable meteor body and real particles for motion, heat and impact.

The impact uses actual world block changes rather than only display entities, so the destroyed crater and fire are persistent and visible to the server simulation. Displays are removed after the impact to avoid leaving orphan visual entities.

## Timing

- fall: 64 ticks;
- impact particles: immediate burst;
- residue: 80 ticks of embers and smoke before the task is cancelled;
- display interpolation: 1 tick per transform update.

The animation runs on one `everyTicks` task owned by `MeteorDirector`. The task is cancelled from `onDisable` and before a second meteor is summoned.
