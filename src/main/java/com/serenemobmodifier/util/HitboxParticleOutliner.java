package com.serenemobmodifier.util;

import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.logic.cerbon.CerbonsApiBridge;
import com.serenemobmodifier.logic.spine.SereneBodyPartEntity;
import com.serenemobmodifier.logic.spine.SpineSegmentManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.entity.PartEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual particle hitbox wireframe outliner.
 * Solves the issue where shader mods like Veil break vanilla's F3 + B hitbox rendering.
 * Renders particle outlines along the 12 edges of an entity's live AABB and all of its PartEntity sub-hitboxes.
 */
public class HitboxParticleOutliner {

    private static final Map<UUID, Integer> OUTLINE_TICKS = new ConcurrentHashMap<>();

    public static void startOutline(Entity entity, int ticks) {
        if (entity != null) {
            OUTLINE_TICKS.put(entity.getUUID(), ticks);
        }
    }

    public static boolean isOutlineActive(UUID uuid) {
        return OUTLINE_TICKS.containsKey(uuid);
    }

    public static void tickEntity(LivingEntity living) {
        if (!(living.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Integer ticksLeft = OUTLINE_TICKS.get(living.getUUID());
        if (ticksLeft == null) {
            return;
        }

        if (ticksLeft <= 0 || !living.isAlive()) {
            OUTLINE_TICKS.remove(living.getUUID());
            return;
        }

        OUTLINE_TICKS.put(living.getUUID(), ticksLeft - 1);

        // Render every 2 ticks to give a crisp, continuous wireframe without particle lag
        if (ticksLeft % 2 == 0) {
            // Render parent bounding box with glowing END_ROD particles
            renderBoxOutline(serverLevel, living.getBoundingBox(), ParticleTypes.END_ROD, 0.4);

            // Render all discovered sub-hitboxes with FLAME particles to distinguish body from parts
            List<Entity> subParts = MobModifierApplier.getAllSubParts(living);
            for (Entity part : subParts) {
                if (part != null && part != living) {
                    renderBoxOutline(serverLevel, part.getBoundingBox(), ParticleTypes.FLAME, 0.4);
                }
            }

            // Render CerbonsAPI segment boxes (e.g. Myths of the Sea Leviathan body segments)
            List<AABB> cerbonsBoxes = CerbonsApiBridge.getCerbonsPartBoxes(living);
            for (AABB box : cerbonsBoxes) {
                if (box != null) {
                    renderBoxOutline(serverLevel, box, ParticleTypes.FLAME, 0.4);
                }
            }

            // Render Serene IK spine segments if active
            List<SereneBodyPartEntity> spineSegs = SpineSegmentManager.getSegments(living);
            for (SereneBodyPartEntity seg : spineSegs) {
                if (seg != null) {
                    renderBoxOutline(serverLevel, seg.getBoundingBox(), ParticleTypes.FLAME, 0.4);
                }
            }
        }
    }

    public static void renderBoxOutline(ServerLevel level, AABB box, ParticleOptions particle, double step) {
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;

        // 4 edges parallel to X axis
        renderLine(level, minX, maxX, minY, minY, minZ, minZ, step, particle);
        renderLine(level, minX, maxX, maxY, maxY, minZ, minZ, step, particle);
        renderLine(level, minX, maxX, minY, minY, maxZ, maxZ, step, particle);
        renderLine(level, minX, maxX, maxY, maxY, maxZ, maxZ, step, particle);

        // 4 edges parallel to Y axis
        renderLine(level, minX, minX, minY, maxY, minZ, minZ, step, particle);
        renderLine(level, maxX, maxX, minY, maxY, minZ, minZ, step, particle);
        renderLine(level, minX, minX, minY, maxY, maxZ, maxZ, step, particle);
        renderLine(level, maxX, maxX, minY, maxY, maxZ, maxZ, step, particle);

        // 4 edges parallel to Z axis
        renderLine(level, minX, minX, minY, minY, minZ, maxZ, step, particle);
        renderLine(level, maxX, maxX, minY, minY, minZ, maxZ, step, particle);
        renderLine(level, minX, minX, maxY, maxY, minZ, maxZ, step, particle);
        renderLine(level, maxX, maxX, maxY, maxY, minZ, maxZ, step, particle);
    }

    private static void renderLine(ServerLevel level, double x1, double x2, double y1, double y2, double z1, double z2, double step, ParticleOptions particle) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return;

        int steps = (int) Math.max(1, Math.ceil(len / step));
        List<ServerPlayer> players = level.players();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x1 + dx * t;
            double py = y1 + dy * t;
            double pz = z1 + dz * t;
            for (ServerPlayer player : players) {
                level.sendParticles(player, particle, true, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}

