# MeteorSmash

A cinematic Pumpkin plugin that summons a falling meteor made from block-display entities, particle trails, custom sounds, and a layered burning impact crater.

## Command

```text
/meteor
```

Permission:

```text
meteorsmash:command.meteor
```

Default permission level: OP.

The command is available to players only. It targets a point in the player's horizontal look direction and finds the first solid block below that point.

## Visual composition

The meteor uses a mixed approach:

- block-display entities for the falling core and rotating shell;
- display transformations for motion and rotation;
- real Pumpkin particles for flame, lava, smoke, explosion and firework trails;
- custom sound identifiers for the dragon growl, blaze shots, explosion, thunder and fire ambience;
- real block mutations for the crater, magma core, blackstone rim and fire ring;
- a text display for the meteor label.

The animation is driven by one `everyTicks` task. There are no gameplay threads or blocking calls.

## Compatibility

| Component | Version |
|---|---|
| Pumpkin Abs | 0.2.1 |
| Pumpkin Stub | 0.2.0 |
| J2KT Pumpkin | 0.1.5 |
| Pumpkin Kotlin/Wasm API | 0.1.2 |
| Pumpkin plugin WIT | `pumpkin:plugin@0.1.0` (synchronous) |

Stub 0.2.0 and J2KT 0.1.5 provide the particle, custom-sound, block-state, explosion, entity-teleport and entity-removal APIs used by the meteor.

## Build

Requirements:

- JDK 21;
- Gradle wrapper included in this repository;
- Maven Local/Gradle access for the Pumpkin plugin toolchain.

```bash
./gradlew cleanBuildWasm
```

Output:

```text
target/wasm/meteorsmash.wasm
```

The build translates:

```text
abs-src/
java-plugin/src/main/java/
```

to Kotlin/Wasm and then packages the component.

Install into Pumpkin:

```bash
cp target/wasm/meteorsmash.wasm <server>/plugins/meteorsmash.wasm
```

## Runtime validation

Validated against Pumpkin nightly `0.2.2+26.3-26.63`:

- plugin loads as `meteorsmash (0.1.0)`;
- OP player can execute `/meteor`;
- command returns the inbound message;
- animation and impact execute without a Wasm trap;
- custom sound calls, particle calls, block writes and display creation return normally.

## Visual design

See [VISUAL.md](VISUAL.md) for the composition, public particle/sound identifiers and timing choices.

## Known notes

- Block display support depends on the Pumpkin host version.
- Custom sound playback depends on the client's resource/sound registry; unknown sounds are client-side no-ops.
- The meteor intentionally changes the world. It does not restore the crater automatically.
- True asynchronous gameplay is not available in the current synchronous WIT `0.1.0` toolchain; the animation uses server ticks.

## Release

Published at:

```text
https://github.com/AndresqDev/MeteorSmash/releases/tag/v0.1.4
```

## License

Apache License 2.0. See `LICENSE`.
