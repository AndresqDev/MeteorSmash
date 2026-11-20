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
 * Block displays form the readable meteor body, a multi-particle trail carries
 * the motion, and the post-impact surface wave repaints the crater from a
 * center-out palette while keeping fire on real support blocks.
 */
public final class MeteorDirector {
    private static final int FALL_TICKS = 44;
    private static final int ALTITUDE = 27;
    private static final int CRATER_RADIUS = 14;
    private static final int GRADIENT_DELAY = 8;
    private static final int GRADIENT_TICKS = CRATER_RADIUS * 2;
    private static final double TARGET_DISTANCE = 14.0;
    private static final double PI = 3.141592653589793;
    private static final double DEG_TO_RAD = 0.017453292519943295;
    private static final String[] PALETTE = {
            "minecraft:magma_block",
            "minecraft:netherrack",
            "minecraft:red_terracotta",
            "minecraft:orange_terracotta",
            "minecraft:yellow_terracotta",
            "minecraft:brown_terracotta",
            "minecraft:blackstone",
            "minecraft:basalt",
            "minecraft:obsidian",
            "minecraft:crying_obsidian"
    };

    private final MeteorSmashPlugin plugin;
    private final List<Display.BlockDisplayEntity> blockDisplays = new ArrayList<>();
    private final List<World.Entity> displayEntities = new ArrayList<>();
    private final int[] surfaceHeights = new int[(CRATER_RADIUS * 2 + 1) * (CRATER_RADIUS * 2 + 1)];

    private ScheduledTask task = null;
    private World world;
    private Position impact;
    private Display.TextDisplayEntity label = null;
    private int generation;
    private int age;
    private int afterImpact;
    private boolean impacted;
    private boolean secondaryExplosion;

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
        impacted = false;
        secondaryExplosion = false;

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
        secondaryExplosion = false;
        age = 0;
        afterImpact = 0;
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
            if (!secondaryExplosion && afterImpact >= 50) {
                secondaryExplosion = true;
                triggerSecondaryExplosion();
            }
            if (afterImpact > 110) {
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
                scale = 2.35 * pulse;
                x = scale * 0.5;
                y = yOffset - 1.0;
                z = scale * 0.5;
                spin = age * 0.18;
            } else {
                int shellIndex = i - 1;
                double angle = age * 0.22 + shellIndex * (2.0 * PI / 6.0);
                double radius = 1.55 + Math.sin(age * 0.14 + shellIndex) * 0.08;
                scale = 0.95 * pulse;
                x = Math.cos(angle) * radius + scale * 0.5;
                y = yOffset - 1.0 + Math.sin(age * 0.2 + shellIndex) * 0.16 - scale * 0.5;
                z = Math.sin(angle) * radius + scale * 0.5;
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
                    new Display.Vector3f(0.0f, (float) yOffset + 4.0f, 0.0f),
                    new Display.Vector3f(2.1f, 2.1f, 2.1f),
                    quaternionY(0.0),
                    quaternionY(0.0)));
        }

        double currentY = impact.y() + yOffset;
        Position trail = new Position(impact.x(), currentY, impact.z());
        spawnFallingTrail(trail, rawProgress);
    }

    private void spawnFallingTrail(Position trail, double progress) {
        world.spawnParticle(Particles.Particle.FLAME, trail, new Position(0.9, 1.6, 0.9), 18, 0.05);
        world.spawnParticle(Particles.Particle.LAVA, trail, new Position(0.7, 1.1, 0.7), 8, 0.03);
        world.spawnParticle(Particles.Particle.FIREWORK, trail, new Position(0.9, 0.9, 0.9), 3, 0.03);
        world.spawnParticle(Particles.Particle.END_ROD, trail, new Position(0.6, 0.6, 0.6), 2, 0.01);

        for (int i = 0; i < 8; i++) {
            double angle = age * 0.55 + i * (2.0 * PI / 8.0);
            double radius = 0.45 + (i % 3) * 0.3;
            double x = trail.x() + Math.cos(angle) * radius;
            double y = trail.y() + Math.sin(age * 0.4 + i) * 0.8;
            double z = trail.z() + Math.sin(angle) * radius;
            Position irregular = new Position(x, y, z);

            world.spawnParticle(Particles.Particle.FLAME, irregular, new Position(0.25, 0.25, 0.25), 1, 0.02);
            if (i % 2 == 0) {
                world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, irregular, new Position(0.25, 0.25, 0.25), 1, 0.01);
            }
            if (i % 3 == 0) {
                world.spawnParticle(Particles.Particle.ELECTRIC_SPARK, irregular, new Position(0.25, 0.25, 0.25), 1, 0.01);
            }
        }

        for (int i = 0; i < 6; i++) {
            double angle = age * 0.3 + i * (2.0 * PI / 6.0);
            double radius = 2.0;
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
                    0.65f + (float) progress * 0.6f);
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

        world.spawnParticle(Particles.Particle.EXPLOSION_EMITTER, impact, new Position(1.0, 1.0, 1.0), 6, 0.02);
        world.spawnParticle(Particles.Particle.EXPLOSION, impact, new Position(3.0, 2.0, 3.0), 34, 0.12);
        world.spawnParticle(Particles.Particle.FLAME, impact, new Position(5.0, 3.0, 5.0), 140, 0.12);
        world.spawnParticle(Particles.Particle.LAVA, impact, new Position(4.0, 2.0, 4.0), 70, 0.08);
        world.spawnParticle(Particles.Particle.FIREWORK, impact, new Position(3.0, 3.0, 3.0), 50, 0.12);
        world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, impact, new Position(4.0, 3.0, 4.0), 35, 0.08);
        world.spawnParticle(Particles.Particle.ELECTRIC_SPARK, impact, new Position(4.0, 3.0, 4.0), 30, 0.06);
        world.spawnParticle(Particles.Particle.END_ROD, impact, new Position(4.0, 4.0, 4.0), 26, 0.08);
        initSurfaceCache();
    }

    private void updateImpactResidue() {
        // Hold the initial flash before the ordered surface pulse begins.
        if (afterImpact >= GRADIENT_DELAY && afterImpact <= GRADIENT_DELAY + GRADIENT_TICKS) {
            int elapsed = afterImpact - GRADIENT_DELAY;
            if (elapsed % 2 == 0) {
                int radius = Math.min(CRATER_RADIUS, elapsed / 2);
                paintSurfaceWave(radius);
            }
        }

        Position ember = new Position(impact.x(), impact.y() + 0.8, impact.z());
        world.spawnParticle(Particles.Particle.FLAME, ember, new Position(3.4, 0.8, 3.4), 8, 0.02);
        world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, ember, new Position(2.8, 0.8, 2.8), 4, 0.015);

        if (afterImpact % 12 == 0) {
            world.playCustomSound(
                    "minecraft:block.fire.ambient",
                    Sounds.SoundCategory.BLOCKS,
                    ember,
                    1.0f,
                    0.7f);
        }
    }

    private void paintSurfaceWave(int radius) {
        int centerX = (int) Math.floor(impact.x());
        int centerY = (int) Math.floor(impact.y());
        int centerZ = (int) Math.floor(impact.z());
        int bedrockState = stateId("minecraft:bedrock");
        int fireState = stateId("minecraft:fire");

        for (int x = -CRATER_RADIUS; x <= CRATER_RADIUS; x++) {
            for (int z = -CRATER_RADIUS; z <= CRATER_RADIUS; z++) {
                double distance = Math.sqrt((double) (x * x + z * z));
                if (distance > radius || (radius > 0 && distance <= radius - 1.0)) {
                    continue;
                }

                int surface = surfaceHeights[(x + CRATER_RADIUS) * (CRATER_RADIUS * 2 + 1) + (z + CRATER_RADIUS)];
                if (surface < -64) {
                    continue;
                }

                int blockX = centerX + x;
                int blockZ = centerZ + z;
                BlockPos support = new BlockPos(blockX, surface, blockZ);
                BlockPos above = new BlockPos(blockX, surface + 1, blockZ);
                if (bedrockState != 0 && world.getBlockStateId(support) == bedrockState) {
                    continue;
                }

                int colorIndex = (radius + 1) % PALETTE.length;
                world.setBlockByName(support, PALETTE[colorIndex]);
                world.setBlockByName(above, "minecraft:air");

                if ((x & 1) == 0 && (z & 1) == 0) {
                    Position top = new Position(blockX + 0.5, surface + 1.2, blockZ + 0.5);
                    world.spawnParticle(Particles.Particle.FLAME, top, new Position(0.25, 0.3, 0.25), 2, 0.02);
                    world.spawnParticle(Particles.Particle.LAVA, top, new Position(0.2, 0.25, 0.2), 1, 0.02);
                    world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, top, new Position(0.2, 0.2, 0.2), 1, 0.01);
                    if (distance >= 3.0) {
                        world.spawnParticle(Particles.Particle.EXPLOSION, top, new Position(0.4, 0.4, 0.4), 1, 0.02);
                    }
                }
            }
        }
    }

    private void triggerSecondaryExplosion() {
        world.playCustomSound(
                "minecraft:entity.generic.explode",
                Sounds.SoundCategory.BLOCKS,
                impact,
                2.5f,
                0.7f);
        world.createExplosion(impact, 3.0f, false);
        world.spawnParticle(Particles.Particle.EXPLOSION_EMITTER, impact, new Position(3.0, 2.0, 3.0), 8, 0.04);
        world.spawnParticle(Particles.Particle.EXPLOSION, impact, new Position(5.0, 3.0, 5.0), 40, 0.12);
        world.spawnParticle(Particles.Particle.FIREWORK, impact, new Position(5.0, 4.0, 5.0), 50, 0.12);
        initSurfaceCache();

        int centerX = (int) Math.floor(impact.x());
        int centerY = (int) Math.floor(impact.y());
        int centerZ = (int) Math.floor(impact.z());
        int samples = 36;
        for (int i = 0; i < samples; i++) {
            double angle = i * (2.0 * PI / samples);
            Position point = new Position(
                    centerX + 0.5 + Math.cos(angle) * 6.0,
                    centerY + 1.0,
                    centerZ + 0.5 + Math.sin(angle) * 6.0);
            world.spawnParticle(Particles.Particle.FLAME, point, new Position(0.4, 0.5, 0.4), 3, 0.03);
            world.spawnParticle(Particles.Particle.LAVA, point, new Position(0.3, 0.4, 0.3), 2, 0.02);
            world.spawnParticle(Particles.Particle.END_ROD, point, new Position(0.3, 0.3, 0.3), 1, 0.01);
        }
    }

    private void initSurfaceCache() {
        int centerX = (int) Math.floor(impact.x());
        int centerY = (int) Math.floor(impact.y());
        int centerZ = (int) Math.floor(impact.z());
        int fireState = stateId("minecraft:fire");
        for (int x = -CRATER_RADIUS; x <= CRATER_RADIUS; x++) {
            for (int z = -CRATER_RADIUS; z <= CRATER_RADIUS; z++) {
                int index = (x + CRATER_RADIUS) * (CRATER_RADIUS * 2 + 1) + (z + CRATER_RADIUS);
                surfaceHeights[index] = findSurface(centerX + x, centerZ + z, centerY, fireState);
            }
        }
    }

    private int findSurface(int x, int z, int centerY, int fireState) {
        for (int y = centerY + 10; y >= -64; y--) {
            int state = world.getBlockStateId(new BlockPos(x, y, z));
            int above = world.getBlockStateId(new BlockPos(x, y + 1, z));
            if (state != 0 && (above == 0 || above == fireState)) {
                return y;
            }
        }
        return -9999;
    }

    private void spawnCore() {
        Display.BlockDisplayEntity core = spawnBlockDisplay("minecraft:magma_block");
        core.getDisplay().setBrightness(0xF000F0);
        core.getDisplay().setShadowRadius(1.8f);
        core.getDisplay().setShadowStrength(0.85f);
        core.getDisplay().setViewRange(2.5f);
        core.getDisplay().setInterpolationDuration(0);
        core.getDisplay().setInterpolationStart(0);
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
            part.getDisplay().setInterpolationDuration(0);
            part.getDisplay().setInterpolationStart(0);
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
        text.getDisplay().setInterpolationDuration(0);
        text.getDisplay().setInterpolationStart(0);
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
