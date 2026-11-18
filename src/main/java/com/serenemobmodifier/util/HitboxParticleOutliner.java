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
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual particle hitbox wireframe outliner.
 * Solves the issue where shader mods like Veil break vanilla's F3 + B hitbox rendering.
 * Renders particle outlines along the edges of an entity's live hitbox and all sub-hitboxes.
 * For CerbonsAPI multipart entities (e.g. Leviathan), renders 3D oriented segment wireframes
 * (head in white END_ROD, body in orange FLAME) and avoids rendering the outer broad-phase box.
 */
public class HitboxParticleOutliner {

    private static final Map<UUID, Integer> OUTLINE_TICKS = new ConcurrentHashMap<>();

    // 12 edges connecting 8 binary-indexed corners
    private static final int[][] EDGES = {
            // Edges along Z (bit 0 toggles)
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            // Edges along Y (bit 1 toggles)
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            // Edges along X (bit 2 toggles)
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

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
            // Case 1: CerbonsAPI Multipart Entities (Leviathan, Kraken, Abaia, etc.)
            if (CerbonsApiBridge.isCerbonsMultipart(living)) {
                List<CerbonsApiBridge.CerbonsSegment> segments = CerbonsApiBridge.getSegments(living);
                if (!segments.isEmpty()) {
                    for (CerbonsApiBridge.CerbonsSegment seg : segments) {
                        ParticleOptions particle = seg.isHead() ? ParticleTypes.END_ROD : ParticleTypes.FLAME;
                        renderOrientedBoxOutline(serverLevel, seg.corners(), particle, 0.4);
                    }
                    return;
                }
            }

            // Case 2: Serene IK Spine Segments (elongated sea monsters, serpents, leviathans)
            List<SereneBodyPartEntity> spineSegs = SpineSegmentManager.getSegments(living);
            if (!spineSegs.isEmpty()) {
                for (SereneBodyPartEntity seg : spineSegs) {
                    if (seg != null) {
                        ParticleOptions particle = seg.isHead ? ParticleTypes.END_ROD : ParticleTypes.FLAME;
                        renderBoxOutline(serverLevel, seg.getBoundingBox(), particle, 0.4);
                    }
                }
                return;
            }

            // Case 3: Standard LivingEntity and Multi-Part entities
            // Render parent bounding box with glowing END_ROD particles
            renderBoxOutline(serverLevel, living.getBoundingBox(), ParticleTypes.END_ROD, 0.4);

            // Render all discovered sub-hitboxes with FLAME particles to distinguish body from parts
            List<Entity> subParts = MobModifierApplier.getAllSubParts(living);
            for (Entity part : subParts) {
                if (part != null && part != living) {
                    renderBoxOutline(serverLevel, part.getBoundingBox(), ParticleTypes.FLAME, 0.4);
                }
            }
        }
    }

    public static void renderOrientedBoxOutline(ServerLevel level, Vec3[] corners, ParticleOptions particle, double step) {
        if (corners == null || corners.length < 8) return;
        for (int[] edge : EDGES) {
            Vec3 p1 = corners[edge[0]];
            Vec3 p2 = corners[edge[1]];
            renderLine(level, p1.x, p2.x, p1.y, p2.y, p1.z, p2.z, step, particle);
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
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= 0.001) {
            spawnParticle(level, particle, x1, y1, z1);
            return;
        }

        int steps = Math.max(1, (int) Math.ceil(dist / step));
        for (int i = 0; i <= steps; i++) {
            double fraction = (double) i / steps;
            double px = x1 + dx * fraction;
            double py = y1 + dy * fraction;
            double pz = z1 + dz * fraction;
            spawnParticle(level, particle, px, py, pz);
        }
    }

    private static void spawnParticle(ServerLevel level, ParticleOptions particle, double x, double y, double z) {
        // Broadcast within 128 blocks
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) < 128.0 * 128.0) {
                level.sendParticles(player, particle, true, x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }
}
