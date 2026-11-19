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
 * The meteor is a mixed composition: block-display entities form the falling
 * core and shell, real particles form the trail, and the impact mutates the
 * world into a layered crater. No gameplay state is accessed from another
 * thread.
 */
public final class MeteorDirector {
    private static final int FALL_TICKS = 64;
    private static final int ALTITUDE = 30;
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
    private int age;
    private int afterImpact;
    private boolean impacted;

    public MeteorDirector(MeteorSmashPlugin plugin) {
        this.plugin = plugin;
    }

    public void summon(Player player) {
        cancelAll();

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

        task = plugin.getTasks().everyTicks(1, 1, server -> tick());
    }

    public void cancelAll() {
        ScheduledTask activeTask = task;
        if (activeTask != null) {
            activeTask.cancel();
        }
        task = null;
        for (World.Entity entity : displayEntities) {
            entity.remove();
        }
        displayEntities.clear();
        blockDisplays.clear();
        label = null;
        impacted = false;
        age = 0;
        afterImpact = 0;
    }

    private void tick() {
        if (!impacted) {
            updateFallingMeteor();
            age++;
            if (age >= FALL_TICKS) {
                impact();
            }
        } else {
            afterImpact++;
            updateImpactResidue();
            if (afterImpact > 80) {
                cancelAll();
            }
        }
    }

    private void updateFallingMeteor() {
        double progress = (double) age / FALL_TICKS;
        double yOffset = (1.0 - progress) * ALTITUDE;
        double pulse = 1.0 + Math.sin(age * 0.45) * 0.08;

        for (int i = 0; i < blockDisplays.size(); i++) {
            Display.BlockDisplayEntity display = blockDisplays.get(i);
            if (display == null) {
                continue;
            }

            double angle = age * 0.22 + i * (2.0 * PI / Math.max(1, blockDisplays.size()));
            double radius = i == 0 ? 0.0 : 2.1 + Math.sin(age * 0.14 + i) * 0.25;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = yOffset + (i == 0 ? 0.0 : Math.sin(age * 0.2 + i) * 0.45);
            double scale = i == 0 ? 3.2 * pulse : 1.05 * pulse;
            double spin = age * 0.18 + i * 0.7;

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
        world.spawnParticle(Particles.Particle.FLAME, trail, new Position(0.7, 1.2, 0.7), 12, 0.04);
        world.spawnParticle(Particles.Particle.LAVA, trail, new Position(0.5, 0.8, 0.5), 5, 0.03);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, trail, new Position(1.2, 1.5, 1.2), 4, 0.02);

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

        for (World.Entity entity : displayEntities) {
            entity.remove();
        }
        displayEntities.clear();
        blockDisplays.clear();
        label = null;

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

        world.spawnParticle(Particles.Particle.EXPLOSION_EMITTER, impact, new Position(1.0, 1.0, 1.0), 4, 0.02);
        world.spawnParticle(Particles.Particle.EXPLOSION, impact, new Position(3.0, 2.0, 3.0), 24, 0.12);
        world.spawnParticle(Particles.Particle.FLAME, impact, new Position(5.0, 3.0, 5.0), 90, 0.12);
        world.spawnParticle(Particles.Particle.LAVA, impact, new Position(4.0, 2.0, 4.0), 45, 0.08);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, impact, new Position(5.0, 4.0, 5.0), 55, 0.06);
        world.spawnParticle(Particles.Particle.FIREWORK, impact, new Position(3.0, 3.0, 3.0), 30, 0.12);

        carveCrater();
    }

    private void updateImpactResidue() {
        Position ember = new Position(impact.x(), impact.y() + 0.8, impact.z());
        world.spawnParticle(Particles.Particle.FLAME, ember, new Position(2.8, 0.8, 2.8), 6, 0.02);
        world.spawnParticle(Particles.Particle.LARGE_SMOKE, ember, new Position(3.2, 1.5, 3.2), 3, 0.015);

        if (afterImpact % 12 == 0) {
            world.playCustomSound(
                    "minecraft:block.fire.ambient",
                    Sounds.SoundCategory.BLOCKS,
                    ember,
                    1.0f,
                    0.7f);
        }
    }

    private void carveCrater() {
        int centerX = (int) Math.floor(impact.x());
        int centerY = (int) Math.floor(impact.y());
        int centerZ = (int) Math.floor(impact.z());
        int radius = 6;
        int bedrockState = stateId("minecraft:bedrock");

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double distance = Math.sqrt((double) (x * x + z * z));
                if (distance > radius) {
                    continue;
                }

                for (int y = centerY - 1; y <= centerY + 1; y++) {
                    BlockPos pos = new BlockPos(centerX + x, y, centerZ + z);
                    if (bedrockState != 0 && world.getBlockStateId(pos) == bedrockState) {
                        continue;
                    }

                    if (y == centerY) {
                        if (distance <= 2.1) {
                            world.setBlockByName(pos, "minecraft:magma_block");
                        } else if (distance <= 4.8) {
                            world.setBlockByName(pos, "minecraft:air");
                        } else {
                            world.setBlockByName(pos, "minecraft:blackstone");
                        }
                    } else if (distance <= 4.8) {
                        world.setBlockByName(pos, "minecraft:air");
                    }
                }

                if (distance >= 2.0 && distance <= 5.2 && ((centerX + x + centerZ + z) & 1) == 0) {
                    BlockPos fire = new BlockPos(centerX + x, centerY + 1, centerZ + z);
                    if (world.getBlockStateId(fire) == 0) {
                        world.setBlockByName(fire, "minecraft:fire");
                    }
                }
            }
        }
    }

    private void spawnCore() {
        Display.BlockDisplayEntity core = spawnBlockDisplay("minecraft:magma_block");
        if (core != null) {
            core.getDisplay().setBrightness(0xF000F0);
            core.getDisplay().setShadowRadius(1.8f);
            core.getDisplay().setShadowStrength(0.85f);
            core.getDisplay().setViewRange(2.5f);
            core.getDisplay().setInterpolationDuration(1);
            core.getDisplay().setInterpolationStart(1);
        }
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
            if (part != null) {
                part.getDisplay().setBrightness(0xF000F0);
                part.getDisplay().setShadowRadius(1.0f);
                part.getDisplay().setShadowStrength(0.7f);
                part.getDisplay().setViewRange(2.0f);
                part.getDisplay().setInterpolationDuration(1);
                part.getDisplay().setInterpolationStart(1);
            }
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
