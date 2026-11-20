package com.andresq.meteorsmash.visual;

import java.util.ArrayList;
import java.util.List;

import com.andresq.meteorsmash.MeteorSmashPlugin;
import pumpkin.BlockPos;
import pumpkin.Common;
import pumpkin.Display;
import pumpkin.EntityTypes;
import pumpkin.Particles;
import pumpkin.Player;
import pumpkin.Position;
import pumpkin.ScheduledTask;
import pumpkin.Sounds;
import pumpkin.TextComponent;
import pumpkin.World;

/**
 * Drives one meteor animation from a single repeating server-tick task.
 *
 * Block displays form the readable meteor body, particles provide the heat
 * and motion, and the impact wave mutates real blocks into a supported,
 * layered crater. No gameplay state is accessed from another thread.
 */
public final class MeteorDirector {
    private static final int FALL_TICKS = 64;
    private static final int ALTITUDE = 30;
    private static final int CRATER_RADIUS = 6;
    private static final double TARGET_DISTANCE = 14.0;
    private static final double PI = 3.141592653589793;
    private static final double DEG_TO_RAD = 0.017453292519943295;

    private final MeteorSmashPlugin plugin;
    private final List<Display.BlockDisplayEntity> blockDisplays = new ArrayList<>();
    private final List<World.Entity> displayEntities = new ArrayList<>();

    private ScheduledTask task = null;
    private World world;
    private Position impact;
    private Display.TextDisplayEntity label = null;
    private int generation;
    private int age;
    private int afterImpact;
    private int carvedRadius;
    private boolean impacted;

    public MeteorDirector(MeteorSmashPlugin plugin) {
        this.plugin = plugin;
    }

    public void summon(Player player) {
        cancelAll();
        int token = ++generation;

        world = player.getWorld();
        Position playerPosition = player.getPosition();
        double yaw = player.getYaw() * DEG_TO_RAD;
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);

        int targetX = (int) Math.floor(playerPosition.x() + dx * TARGET_DISTANCE);
        int targetZ = (int) Math.floor(playerPosition.z() + dz * TARGET_DISTANCE);
        int ground = findGround(targetX, targetZ, (int) Math.floor(playerPosition.y()));

        impact = new Position(targetX + 0.5, ground + 1.0, targetZ + 0.5);
        age = 0;
        afterImpact = 0;
        carvedRadius = 0;
        impacted = false;

        spawnCore();
        spawnShell();
        spawnLabel();

        world.playCustomSound(
                "minecraft:entity.ender_dragon.growl",
                Sounds.SoundCategory.HOSTILE,
                impact,
                2.0f,
                0.55f);
        world.playCustomSound(
                "minecraft:entity.blaze.shoot",
                Sounds.SoundCategory.HOSTILE,
                new Position(impact.x(), impact.y() + ALTITUDE, impact.z()),
                1.5f,
                0.7f);

        task = plugin.getTasks().everyTicks(1, 1, server -> tick(token));
    }

    public void cancelAll() {
        generation++;
        ScheduledTask activeTask = task;
        if (activeTask != null) {
            activeTask.cancel();
        }
        task = null;
        removeDisplays();
        impacted = false;
        age = 0;
        afterImpact = 0;
        carvedRadius = 0;
    }

    private void tick(int token) {
        if (token != generation) {
            return;
        }

        if (!impacted) {
            updateFallingMeteor();
            age++;
            if (age >= FALL_TICKS) {
                impact();
            }
        } else {
            afterImpact++;
            updateImpactResidue();
            if (afterImpact > 100) {
                cancelAll();
            }
        }
    }

    private void updateFallingMeteor() {
        double rawProgress = (double) age / FALL_TICKS;
        double progress = rawProgress * rawProgress;
        double yOffset = (1.0 - progress) * ALTITUDE;
        double pulse = 1.0 + Math.sin(age * 0.45) * 0.08;

        for (int i = 0; i < blockDisplays.size(); i++) {
            Display.BlockDisplayEntity display = blockDisplays.get(i);
            if (display == null) {
                continue;
            }

            double x;
            double y;
            double z;
            double scale;
            double spin;

            if (i == 0) {
                x = 0.0;
                y = yOffset;
                z = 0.0;
                scale = 3.45 * pulse;
                spin = age * 0.18;
            } else {
                int shellIndex = i - 1;
                double angle = age * 0.22 + shellIndex * (2.0 * PI / 6.0);
                double radius = 1.82 + Math.sin(age * 0.14 + shellIndex) * 0.14;
                x = Math.cos(angle) * radius;
                y = yOffset + Math.sin(age * 0.2 + shellIndex) * 0.32;
                z = Math.sin(angle) * radius;
                scale = 1.22 * pulse;
                spin = age * 0.18 + shellIndex * 0.7;
            }

            display.getDisplay().setTransformation(new Display.DisplayTransformation(
                    new Display.Vector3f((float) x, (float) y, (float) z),
                    new Display.Vector3f((float) scale, (float) scale, (float) scale),
                    quaternionY(spin),
                    quaternionY(-spin * 0.6)));
        }

        Display.TextDisplayEntity activeLabel = label;
        if (activeLabel != null) {
            activeLabel.getDisplay().setTransformation(new Display.DisplayTransformation(
                    new Display.Vector3f(0.0f, (float) yOffset + 4.5f, 0.0f),
                    new Display.Vector3f(2.4f, 2.4f, 2.4f),
                    quaternionY(0.0),
                    quaternionY(0.0)));
        }

        double currentY = impact.y() + yOffset;
        Position trail = new Position(impact.x(), currentY, impact.z());
        world.spawnParticle(Particles.Particle.FLAME, trail, new Position(0.8, 1.4, 0.8), 16, 0.045);
        world.spawnParticle(Particles.Particle.LAVA, trail, new Position(0.6, 1.0, 0.6), 7, 0.03);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, trail, new Position(1.6, 2.0, 1.6), 5, 0.02);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, trail, new Position(0.8, 1.0, 0.8), 3, 0.015);
        world.spawnParticle(Particles.Particle.FIREWORK, trail, new Position(0.8, 0.8, 0.8), 2, 0.03);
        world.spawnParticle(Particles.Particle.END_ROD, trail, new Position(0.5, 0.5, 0.5), 1, 0.01);

        for (int i = 0; i < 6; i++) {
            double angle = age * 0.3 + i * (2.0 * PI / 6.0);
            double radius = 1.7;
            Position ember = new Position(
                    trail.x() + Math.cos(angle) * radius,
                    trail.y() + 0.2 + Math.sin(age * 0.2 + i) * 0.4,
                    trail.z() + Math.sin(angle) * radius);
            world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, ember, new Position(0.2, 0.2, 0.2), 1, 0.01);
            world.spawnParticle(Particles.Particle.ELECTRIC_SPARK, ember, new Position(0.3, 0.3, 0.3), 1, 0.01);
        }

        if (age % 8 == 0) {
            world.playCustomSound(
                    "minecraft:entity.blaze.shoot",
                    Sounds.SoundCategory.HOSTILE,
                    trail,
                    0.8f,
                    0.65f + (float) rawProgress * 0.6f);
        }
    }

    private void impact() {
        impacted = true;
        age = FALL_TICKS;
        removeDisplays();

        world.playCustomSound(
                "minecraft:entity.generic.explode",
                Sounds.SoundCategory.BLOCKS,
                impact,
                3.0f,
                0.55f);
        world.playCustomSound(
                "minecraft:entity.lightning_bolt.thunder",
                Sounds.SoundCategory.WEATHER,
                impact,
                2.0f,
                0.8f);
        world.createExplosion(impact, 5.5f, true);

        world.spawnParticle(Particles.Particle.EXPLOSION_EMITTER, impact, new Position(1.0, 1.0, 1.0), 5, 0.02);
        world.spawnParticle(Particles.Particle.EXPLOSION, impact, new Position(3.0, 2.0, 3.0), 30, 0.12);
        world.spawnParticle(Particles.Particle.FLAME, impact, new Position(5.0, 3.0, 5.0), 110, 0.12);
        world.spawnParticle(Particles.Particle.LAVA, impact, new Position(4.0, 2.0, 4.0), 55, 0.08);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, impact, new Position(5.0, 4.0, 5.0), 65, 0.06);
        world.spawnParticle(Particles.Particle.FIREWORK, impact, new Position(3.0, 3.0, 3.0), 36, 0.12);
        world.spawnParticle(Particles.Particle.END_ROD, impact, new Position(4.0, 4.0, 4.0), 20, 0.08);

        carveWave(0);
        carvedRadius = 1;
    }

    private void updateImpactResidue() {
        if (carvedRadius <= CRATER_RADIUS && afterImpact % 2 == 0) {
            carveWave(carvedRadius);
            carvedRadius++;
        }

        Position ember = new Position(impact.x(), impact.y() + 0.8, impact.z());
        world.spawnParticle(Particles.Particle.FLAME, ember, new Position(2.8, 0.8, 2.8), 7, 0.02);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, ember, new Position(3.2, 1.5, 3.2), 4, 0.015);
        world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, ember, new Position(2.4, 0.8, 2.4), 3, 0.015);

        if (afterImpact % 12 == 0) {
            world.playCustomSound(
                    "minecraft:block.fire.ambient",
                    Sounds.SoundCategory.BLOCKS,
                    ember,
                    1.0f,
                    0.7f);
        }
    }

    private void carveWave(int radius) {
        int centerX = (int) Math.floor(impact.x());
        int centerY = (int) Math.floor(impact.y()) - 1;
        int centerZ = (int) Math.floor(impact.z());
        int bedrockState = stateId("minecraft:bedrock");

        for (int x = -CRATER_RADIUS; x <= CRATER_RADIUS; x++) {
            for (int z = -CRATER_RADIUS; z <= CRATER_RADIUS; z++) {
                double distance = Math.sqrt((double) (x * x + z * z));
                if (distance > radius || distance < radius - 1.0) {
                    continue;
                }

                BlockPos support = new BlockPos(centerX + x, centerY, centerZ + z);
                BlockPos above = new BlockPos(centerX + x, centerY + 1, centerZ + z);
                BlockPos below = new BlockPos(centerX + x, centerY - 1, centerZ + z);

                setBlockSafe(below, "minecraft:air", bedrockState);
                setBlockSafe(above, "minecraft:air", bedrockState);

                if (distance <= 2.2) {
                    setBlockSafe(support, "minecraft:magma_block", bedrockState);
                } else if (distance <= 4.8) {
                    setBlockSafe(support, "minecraft:netherrack", bedrockState);
                } else {
                    setBlockSafe(support, "minecraft:blackstone", bedrockState);
                }

                if (distance >= 2.0 && distance <= 5.2 && ((centerX + x + centerZ + z) & 1) == 0) {
                    setBlockSafe(above, "minecraft:fire", bedrockState);
                }
            }
        }

        spawnWaveParticles(radius);
    }

    private void spawnWaveParticles(int radius) {
        int samples = 28;
        for (int i = 0; i < samples; i++) {
            double angle = i * (2.0 * PI / samples);
            Position point = new Position(
                    impact.x() + Math.cos(angle) * radius,
                    impact.y() + 0.35,
                    impact.z() + Math.sin(angle) * radius);
            world.spawnParticle(Particles.Particle.FLAME, point, new Position(0.25, 0.35, 0.25), 2, 0.02);
            world.spawnParticle(Particles.Particle.LAVA, point, new Position(0.2, 0.3, 0.2), 1, 0.02);
            if (radius >= 3) {
                world.spawnParticle(Particles.Particle.EXPLOSION, point, new Position(0.5, 0.5, 0.5), 1, 0.02);
                world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, point, new Position(0.3, 0.3, 0.3), 1, 0.01);
            }
        }

        if (radius == 3 || radius == 5) {
            world.playCustomSound(
                    "minecraft:entity.generic.explode",
                    Sounds.SoundCategory.BLOCKS,
                    new Position(impact.x(), impact.y() + 1.0, impact.z()),
                    1.2f,
                    0.65f);
        }
    }

    private void setBlockSafe(BlockPos position, String block, int bedrockState) {
        if (bedrockState != 0 && world.getBlockStateId(position) == bedrockState) {
            return;
        }
        world.setBlockByName(position, block);
    }

    private void spawnCore() {
        Display.BlockDisplayEntity core = spawnBlockDisplay("minecraft:magma_block");
        core.getDisplay().setBrightness(0xF000F0);
        core.getDisplay().setShadowRadius(1.8f);
        core.getDisplay().setShadowStrength(0.85f);
        core.getDisplay().setViewRange(2.5f);
        core.getDisplay().setInterpolationDuration(1);
        core.getDisplay().setInterpolationStart(1);
        blockDisplays.add(core);
    }

    private void spawnShell() {
        String[] blocks = {
                "minecraft:obsidian",
                "minecraft:netherrack",
                "minecraft:blackstone",
                "minecraft:crying_obsidian",
                "minecraft:magma_block",
                "minecraft:obsidian"
        };
        for (int i = 0; i < blocks.length; i++) {
            Display.BlockDisplayEntity part = spawnBlockDisplay(blocks[i]);
            part.getDisplay().setBrightness(0xF000F0);
            part.getDisplay().setShadowRadius(1.0f);
            part.getDisplay().setShadowStrength(0.7f);
            part.getDisplay().setViewRange(2.0f);
            part.getDisplay().setInterpolationDuration(1);
            part.getDisplay().setInterpolationStart(1);
            blockDisplays.add(part);
        }
    }

    private void spawnLabel() {
        World.Entity entity = world.spawnEntity(EntityTypes.EntityType.TEXT_DISPLAY, impact);
        displayEntities.add(entity);
        Display.TextDisplayEntity text = Display.TextDisplayEntity.fromEntity(entity);
        if (text == null) {
            return;
        }
        TextComponent textComponent = TextComponent.text("METEOR");
        textComponent.colorNamed(Common.NamedColor.GOLD);
        text.setText(textComponent);
        text.getDisplay().setBillboard(Display.BillboardMode.CENTER);
        text.setSeeThrough(false);
        text.setShadow(true);
        text.setBackground(0x40000000);
        text.setLineWidth(180);
        text.getDisplay().setViewRange(4.0f);
        text.getDisplay().setInterpolationDuration(1);
        text.getDisplay().setInterpolationStart(1);
        label = text;
    }

    private Display.BlockDisplayEntity spawnBlockDisplay(String blockName) {
        World.Entity entity = world.spawnEntity(EntityTypes.EntityType.BLOCK_DISPLAY, impact);
        displayEntities.add(entity);
        Display.BlockDisplayEntity display = Display.BlockDisplayEntity.fromEntity(entity);
        if (display == null) {
            entity.remove();
            displayEntities.remove(entity);
            throw new IllegalStateException("Could not create block display entity");
        }
        display.setBlockStateId(stateId(blockName));
        return display;
    }

    private void removeDisplays() {
        List<World.Entity> entities = new ArrayList<>(displayEntities);
        for (World.Entity entity : entities) {
            entity.remove();
        }
        displayEntities.clear();
        blockDisplays.clear();
        label = null;
    }

    private int findGround(int x, int z, int startY) {
        int y = startY + 1;
        while (y > -64) {
            int state = world.getBlockStateId(new BlockPos(x, y, z));
            if (state != 0) {
                return y;
            }
            y--;
        }
        return startY;
    }

    private int stateId(String blockName) {
        World.Block block = world.getBlockByName(blockName);
        return block == null ? 0 : block.defaultStateId;
    }

    private Display.Quaternionf quaternionY(double angle) {
        double half = angle * 0.5;
        return new Display.Quaternionf(0.0f, (float) Math.sin(half), 0.0f, (float) Math.cos(half));
    }
}
