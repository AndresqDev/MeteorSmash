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
 * the motion, and an accelerating ground-charge sequence prepares the impact:
 * rotating surface rings and radial spokes run outward before the meteor lands.
 */
public final class MeteorDirector {
    private static final int FALL_TICKS = 44;
    private static final int ALTITUDE = 27;
    private static final int CHARGE_RADIUS = 14;
    private static final int CHARGE_START_AGE = 6;
    private static final int CHARGE_RING_COUNT = 3;
    private static final double CHARGE_RING_GAP = 4.5;
    private static final int SECONDARY_EXPLOSION_TICK = 24;
    private static final double CORE_SCALE = 2.1;
    private static final double SHELL_SCALE = 0.78;
    private static final double SHELL_RADIUS = 1.52;
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
            updateChargeEffect();
            age++;
            if (age >= FALL_TICKS) {
                impact();
            }
        } else {
            afterImpact++;
            updateImpactResidue();
            if (!secondaryExplosion && afterImpact >= SECONDARY_EXPLOSION_TICK) {
                secondaryExplosion = true;
                triggerSecondaryExplosion();
            }
            if (afterImpact > 70) {
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

            double scale;
            double centerX;
            double centerY;
            double centerZ;
            double spin;

            if (i == 0) {
                scale = CORE_SCALE * pulse;
                centerX = 0.0;
                centerY = yOffset + CORE_SCALE * 0.5 - 1.0;
                centerZ = 0.0;
                spin = age * 0.18;
            } else {
                int shellIndex = i - 1;
                double angle = age * 0.22 + shellIndex * (2.0 * PI / 6.0);
                double radius = SHELL_RADIUS + Math.sin(age * 0.14 + shellIndex) * 0.06;
                scale = SHELL_SCALE * pulse;
                centerX = Math.cos(angle) * radius;
                centerY = yOffset + CORE_SCALE * 0.5 - 1.0
                        + Math.sin(age * 0.2 + shellIndex) * 0.14;
                centerZ = Math.sin(angle) * radius;
                spin = age * 0.18 + shellIndex * 0.7;
            }

            // The block model is anchored at its bottom-north-west corner. Build
            // the transformation from the desired visual center instead of
            // adding a scale offset to each orbit coordinate; this keeps every
            // companion centered on the same orbit around the core.
            Display.Vector3f translation = centeredTranslation(
                    centerX, centerY, centerZ, scale, spin);
            display.getDisplay().setTransformation(new Display.DisplayTransformation(
                    translation,
                    new Display.Vector3f((float) scale, (float) scale, (float) scale),
                    quaternionY(spin),
                    quaternionY(0.0)));
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
    }

    private void updateImpactResidue() {
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

    /**
     * Draws the orbital-charge style ground field while the meteor is falling.
     * The wave is purely visual: it never changes terrain blocks. Rings are
     * anchored to the impact ground plane and expand with an accelerating
     * radius so the build-up reaches the target exactly when the meteor lands.
     */
    private void updateChargeEffect() {
        if (age < CHARGE_START_AGE) {
            return;
        }
        double raw = (age - CHARGE_START_AGE + 1.0) / (FALL_TICKS - CHARGE_START_AGE);
        double progress = Math.pow(Math.min(1.0, raw), 1.45);
        double outerRadius = CHARGE_RADIUS * progress;

        if (outerRadius < 0.8) {
            drawChargeCenter();
            return;
        }

        for (int ring = 0; ring < CHARGE_RING_COUNT; ring++) {
            double radius = outerRadius - ring * CHARGE_RING_GAP;
            if (radius >= 0.8) {
                drawChargeRing(radius, ring);
            }
        }
        drawChargeSpokes(outerRadius);
        drawChargeCenter();
    }

    private void drawChargeRing(double radius, int ringIndex) {
        int samples = Math.max(20, Math.min(72, (int) (radius * 6.0)));
        double rotation = age * 0.16 + ringIndex * 0.8;

        for (int i = 0; i < samples; i++) {
            double angle = rotation + i * (2.0 * PI / samples);
            if (Math.sin(angle * 3.0 - age * 0.28) < -0.55) {
                continue;
            }

            int dx = (int) Math.round(Math.cos(angle) * radius);
            int dz = (int) Math.round(Math.sin(angle) * radius);
            Position point = chargePoint(dx, dz, 1.25);
            if (point == null) {
                continue;
            }

            world.spawnParticle(
                    chargeParticle(ringIndex, false),
                    point,
                    new Position(0.06, 0.12, 0.06),
                    1,
                    0.01);
            if ((i + ringIndex) % 3 == 0) {
                world.spawnParticle(
                        chargeParticle(ringIndex, true),
                        point,
                        new Position(0.05, 0.10, 0.05),
                        1,
                        0.005);
            }
        }
    }

    private void drawChargeSpokes(double outerRadius) {
        int spokes = 6;
        int steps = Math.max(2, (int) Math.ceil(outerRadius));
        double baseAngle = age * 0.12;

        for (int spoke = 0; spoke < spokes; spoke++) {
            double angle = baseAngle + spoke * (2.0 * PI / spokes);
            for (int step = 1; step <= steps; step++) {
                double radius = outerRadius * step / steps;
                int dx = (int) Math.round(Math.cos(angle) * radius);
                int dz = (int) Math.round(Math.sin(angle) * radius);
                Position point = chargePoint(dx, dz, 1.10);
                if (point == null) {
                    continue;
                }

                world.spawnParticle(
                        Particles.Particle.ELECTRIC_SPARK,
                        point,
                        new Position(0.05, 0.10, 0.05),
                        1,
                        0.005);
                if (step % 3 == 0) {
                    world.spawnParticle(
                            Particles.Particle.END_ROD,
                            point,
                            new Position(0.04, 0.08, 0.04),
                            1,
                            0.004);
                }
            }
        }
    }

    private void drawChargeCenter() {
        Position center = chargePoint(0, 0, 1.5);
        if (center == null) {
            return;
        }
        world.spawnParticle(Particles.Particle.END_ROD, center, new Position(0.25, 0.18, 0.25), 2, 0.01);
        world.spawnParticle(Particles.Particle.ELECTRIC_SPARK, center, new Position(0.35, 0.22, 0.35), 2, 0.01);
        world.spawnParticle(Particles.Particle.SOUL_FIRE_FLAME, center, new Position(0.25, 0.16, 0.25), 1, 0.01);
    }

    private Particles.Particle chargeParticle(int ringIndex, boolean secondary) {
        if (ringIndex == 0) {
            return secondary ? Particles.Particle.ELECTRIC_SPARK : Particles.Particle.END_ROD;
        }
        if (ringIndex == 1) {
            return secondary ? Particles.Particle.SOUL_FIRE_FLAME : Particles.Particle.ELECTRIC_SPARK;
        }
        return secondary ? Particles.Particle.LAVA : Particles.Particle.FLAME;
    }

    private Position chargePoint(int dx, int dz, double lift) {
        int centerX = (int) Math.floor(impact.x());
        int centerZ = (int) Math.floor(impact.z());
        int groundY = (int) Math.floor(impact.y()) - 1;
        return new Position(centerX + dx + 0.5, groundY + lift, centerZ + dz + 0.5);
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

    private Display.Vector3f centeredTranslation(
            double centerX,
            double centerY,
            double centerZ,
            double scale,
            double yaw) {
        double half = scale * 0.5;
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        // The display matrix applies the left rotation before the final
        // translation. Rotate the model center around the block-display pivot
        // so the final translation can place that center exactly at the orbit
        // coordinate.
        double rotatedX = half * (cos + sin);
        double rotatedZ = half * (cos - sin);
        return new Display.Vector3f(
                (float) (centerX - rotatedX),
                (float) (centerY - half),
                (float) (centerZ - rotatedZ));
    }

    private Display.Quaternionf quaternionY(double angle) {
        double half = angle * 0.5;
        return new Display.Quaternionf(0.0f, (float) Math.sin(half), 0.0f, (float) Math.cos(half));
    }
}
