package com.serenemobmodifier.logic.spine;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Manages segmented spine hitbox chains for elongated sea monsters / serpents (like Leviathan, Bakunawa, Abaia)
 * that render long snake-like 3D bodies via GeckoLib without native PartEntity sub-boxes.
 * Constrains each segment along an inverse-kinematics chain so sub-hitboxes follow the curve of the body as it swims.
 */
public class SpineSegmentManager {

    private static final Map<LivingEntity, SereneBodyPartEntity[]> SPINE_PARTS = new WeakHashMap<>();
    private static final int SEGMENT_COUNT = 6;

    public static boolean isSpineCandidate(LivingEntity living) {
        if (living == null || living instanceof Player || living.getType() == EntityType.ARMOR_STAND || !living.isAlive()) {
            return false;
        }

        // If the mob natively has third-party PartEntity instances (e.g. EnderDragon), do not attach spine
        PartEntity<?>[] existing = living.getParts();
        if (existing != null && existing.length > 0 && !(existing[0] instanceof SereneBodyPartEntity)) {
            return false;
        }

        String descId = living.getType().getDescriptionId().toLowerCase(Locale.ROOT);
        String className = living.getClass().getName().toLowerCase(Locale.ROOT);

        return descId.contains("leviathan") || descId.contains("serpent") || descId.contains("bakunawa")
                || descId.contains("abaia") || descId.contains("eel") || descId.contains("bake_kujira")
                || className.contains("leviathan") || className.contains("serpent") || className.contains("bakunawa")
                || className.contains("abaia") || (living instanceof WaterAnimal && living.getType().getDimensions().width() >= 1.5f && (descId.contains("sea") || descId.contains("monster")));
    }

    public static boolean hasSpineSegments(Entity entity) {
        if (entity instanceof LivingEntity living) {
            SereneBodyPartEntity[] parts = SPINE_PARTS.get(living);
            return parts != null && parts.length > 0;
        }
        return false;
    }

    public static SereneBodyPartEntity[] getSpineSegments(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return SPINE_PARTS.get(living);
        }
        return null;
    }

    public static List<SereneBodyPartEntity> getSegments(LivingEntity living) {
        SereneBodyPartEntity[] parts = getSpineSegments(living);
        if (parts == null || parts.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.asList(parts);
    }

    public static void tick(LivingEntity living) {
        if (!isSpineCandidate(living)) {
            return;
        }

        if (!living.isAlive() || living.isRemoved()) {
            SPINE_PARTS.remove(living);
            return;
        }

        float scale = living.getScale();
        EntityDimensions baseDims = living.getType().getDimensions();
        float baseWidth = baseDims.width();
        float baseHeight = baseDims.height();

        float segWidth = Math.max(1.6f, baseWidth * 0.85f) * scale;
        float segHeight = Math.max(1.5f, baseHeight * 0.80f) * scale;
        float segSpacing = Math.max(2.0f, baseWidth * 0.75f) * scale;

        SereneBodyPartEntity[] parts = SPINE_PARTS.get(living);
        float yaw = living.getYRot();
        double yawRad = Math.toRadians(yaw);
        double backX = Math.sin(yawRad);
        double backZ = -Math.cos(yawRad);

        if (parts == null) {
            parts = new SereneBodyPartEntity[SEGMENT_COUNT];
            Vec3 head = living.position();
            for (int i = 0; i < SEGMENT_COUNT; i++) {
                String name = (i == SEGMENT_COUNT - 1) ? "tail" : ("body_" + (i + 1));
                float taper = (i >= 4) ? 0.75f : 1.0f;
                parts[i] = new SereneBodyPartEntity(living, name, segWidth * taper, segHeight * taper);
                Vec3 initialPos = head.add(backX * segSpacing * (i + 1), 0, backZ * segSpacing * (i + 1));
                parts[i].setPos(initialPos.x, initialPos.y, initialPos.z);
                parts[i].setBoundingBox(parts[i].getDimensions(parts[i].getPose()).makeBoundingBox(initialPos.x, initialPos.y, initialPos.z));
            }
            SPINE_PARTS.put(living, parts);
        }

        // Inverse Kinematics Chain Update: each segment stays exactly segSpacing behind previous segment
        Vec3 prevPos = living.position();
        for (int i = 0; i < parts.length; i++) {
            SereneBodyPartEntity part = parts[i];
            Vec3 curPos = part.position();
            Vec3 diff = curPos.subtract(prevPos);
            double dist = diff.length();

            if (dist < 0.01) {
                diff = new Vec3(backX, 0, backZ).scale(segSpacing);
                curPos = prevPos.add(diff);
            } else {
                curPos = prevPos.add(diff.scale(segSpacing / dist));
            }

            part.setPos(curPos.x, curPos.y, curPos.z);
            float taper = (i >= 4) ? 0.75f : 1.0f;
            part.updateDimensions(segWidth * taper, segHeight * taper);
            part.setBoundingBox(part.getDimensions(part.getPose()).makeBoundingBox(curPos.x, curPos.y, curPos.z));

            prevPos = curPos;
        }
    }
}

