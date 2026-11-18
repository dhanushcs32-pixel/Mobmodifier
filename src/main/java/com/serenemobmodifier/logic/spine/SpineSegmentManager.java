package com.serenemobmodifier.logic.spine;

import com.serenemobmodifier.logic.cerbon.CerbonsApiBridge;
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
 * 
 * Spans the FULL length of the 3D model:
 * - Front Segments: Extend forward along look/body yaw from entity.position() to the tip of the head/snout.
 * - Center Segment: Positioned at entity.position(), vertically centered on the torso.
 * - Rear Segments: Form an inverse-kinematics chain trailing behind entity.position() along the body spine
 *   all the way to the tail fin, constrained to prevent lateral veering into empty water.
 */
public class SpineSegmentManager {

    private static final Map<LivingEntity, SereneBodyPartEntity[]> SPINE_PARTS = new WeakHashMap<>();

    public record SpineProfile(
            float forwardLength,
            float rearLength,
            float baseWidth,
            float baseHeight,
            float torsoCenterY
    ) {}

    public static boolean isSpineCandidate(LivingEntity living) {
        if (living == null || living instanceof Player || living.getType() == EntityType.ARMOR_STAND || !living.isAlive()) {
            return false;
        }

        // If CerbonsAPI multipart entities manage their own 3D oriented segments and has live parts, let Cerbons handle it
        if (CerbonsApiBridge.isCerbonsMultipart(living)) {
            List<?> cerbonSegs = CerbonsApiBridge.getSegments(living);
            if (cerbonSegs != null && !cerbonSegs.isEmpty()) {
                return false;
            }
        }
        if (living.getBoundingBox() != null && living.getBoundingBox().getClass().getSimpleName().contains("CompoundOrientedBox")) {
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

    public static SpineProfile getProfile(LivingEntity living, float scale) {
        String descId = living.getType().getDescriptionId().toLowerCase(Locale.ROOT);
        String className = living.getClass().getName().toLowerCase(Locale.ROOT);

        if (descId.contains("leviathan") || className.contains("leviathan")) {
            // Based on MOTS leviathan.geo.json:
            // Head tip: -174.8 px (-10.925 blocks). Pivot (0,0,0) is at body5.
            // Tail fin tip: +188.09 px (+11.755 blocks).
            // Torso cross-section: 23 px (1.44 blocks) torso + fins = ~2.8 blocks wide, ~2.6 blocks high.
            // Torso midline Y: ~17.5 px = +1.15 blocks above pivot.
            return new SpineProfile(
                    11.0f * scale,
                    11.8f * scale,
                    2.8f * scale,
                    2.6f * scale,
                    1.15f * scale
            );
        } else if (descId.contains("bakunawa") || className.contains("bakunawa")) {
            return new SpineProfile(
                    9.0f * scale,
                    10.5f * scale,
                    2.4f * scale,
                    2.2f * scale,
                    1.0f * scale
            );
        } else if (descId.contains("abaia") || className.contains("abaia")) {
            return new SpineProfile(
                    5.0f * scale,
                    8.0f * scale,
                    2.2f * scale,
                    2.0f * scale,
                    0.9f * scale
            );
        } else if (descId.contains("bake_kujira") || className.contains("bake_kujira")) {
            return new SpineProfile(
                    6.0f * scale,
                    10.0f * scale,
                    3.5f * scale,
                    3.0f * scale,
                    1.5f * scale
            );
        } else {
            EntityDimensions dims = living.getType().getDimensions();
            float w = dims.width() * scale;
            float h = dims.height() * scale;
            return new SpineProfile(
                    Math.max(3.0f * scale, w * 2.2f),
                    Math.max(4.0f * scale, w * 3.2f),
                    Math.max(1.6f * scale, w * 0.95f),
                    Math.max(1.5f * scale, h * 0.90f),
                    h * 0.5f
            );
        }
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
        SpineProfile profile = getProfile(living, scale);

        // Segment spacing ~2.0 - 2.5 blocks scaled to prevent bunching or gaps
        float segSpacing = Math.max(1.8f, Math.min(2.5f, 2.0f * Math.max(1.0f, scale * 0.8f)));

        int numFront = Math.max(2, Math.round(profile.forwardLength() / segSpacing));
        int numRear = Math.max(3, Math.round(profile.rearLength() / segSpacing));
        int totalSegments = numFront + 1 + numRear; // Front (0..numFront-1), Center (numFront), Rear (numFront+1..end)

        SereneBodyPartEntity[] parts = SPINE_PARTS.get(living);

        if (parts == null || parts.length != totalSegments) {
            parts = new SereneBodyPartEntity[totalSegments];

            // 1. Front segments (Index 0 is Head tip, numFront - 1 is neck)
            for (int i = 0; i < numFront; i++) {
                boolean isHead = (i == 0);
                String name = isHead ? "head" : ("neck_" + (numFront - i));
                float taper = isHead ? 0.9f : 1.0f;
                parts[i] = new SereneBodyPartEntity(living, name, profile.baseWidth() * taper, profile.baseHeight() * taper, isHead);
            }

            // 2. Center segment (Index numFront)
            parts[numFront] = new SereneBodyPartEntity(living, "torso_center", profile.baseWidth(), profile.baseHeight(), false);

            // 3. Rear segments (Index numFront + 1 to totalSegments - 1)
            for (int r = 1; r <= numRear; r++) {
                int idx = numFront + r;
                boolean isTail = (r == numRear);
                String name = isTail ? "tail_fin" : ("body_" + r);
                float rearTaper = 1.0f - 0.45f * ((float) r / (float) numRear);
                parts[idx] = new SereneBodyPartEntity(living, name, profile.baseWidth() * rearTaper, profile.baseHeight() * rearTaper, false);
            }

            SPINE_PARTS.put(living, parts);
        }

        // Minecraft coordinate vectors:
        // Entity forward direction for body yaw: (-sin(yBodyRot), cos(yBodyRot))
        float bodyYaw = living.yBodyRot;
        double bodyYawRad = Math.toRadians(bodyYaw);
        double bodyFwdX = -Math.sin(bodyYawRad);
        double bodyFwdZ = Math.cos(bodyYawRad);

        // Head look yaw:
        float headYaw = living.getYRot();
        double headYawRad = Math.toRadians(headYaw);
        double headFwdX = -Math.sin(headYawRad);
        double headFwdZ = Math.cos(headYawRad);

        Vec3 centerPos = living.position();

        // Step A: Update Center Segment (Index numFront)
        SereneBodyPartEntity centerPart = parts[numFront];
        double centerBoxBottomY = centerPos.y + profile.torsoCenterY() - (profile.baseHeight() / 2.0);
        centerPart.setPos(centerPos.x, centerPos.y, centerPos.z);
        centerPart.updateDimensions(profile.baseWidth(), profile.baseHeight());
        centerPart.setBoundingBox(centerPart.getDimensions(centerPart.getPose())
                .makeBoundingBox(centerPos.x, centerBoxBottomY, centerPos.z));

        // Step B: Update Front Segments (Index 0 = Head tip to numFront - 1 = Neck)
        for (int i = 0; i < numFront; i++) {
            SereneBodyPartEntity frontPart = parts[i];
            // fraction from center (0.0) forward to head tip (1.0)
            double t = (double) (numFront - i) / (double) numFront;
            double dist = t * profile.forwardLength();

            // Blend direction: near center is pure body yaw, towards head tip smoothly follows head look yaw
            double blend = t * 0.55;
            double dirX = (1.0 - blend) * bodyFwdX + blend * headFwdX;
            double dirZ = (1.0 - blend) * bodyFwdZ + blend * headFwdZ;
            double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (dirLen > 1e-4) {
                dirX /= dirLen;
                dirZ /= dirLen;
            } else {
                dirX = bodyFwdX;
                dirZ = bodyFwdZ;
            }

            double segX = centerPos.x + dirX * dist;
            double segZ = centerPos.z + dirZ * dist;

            float taper = frontPart.isHead ? 0.9f : 1.0f;
            float pWidth = profile.baseWidth() * taper;
            float pHeight = profile.baseHeight() * taper;
            double boxBottomY = centerPos.y + profile.torsoCenterY() - (pHeight / 2.0);

            frontPart.setPos(segX, centerPos.y, segZ);
            frontPart.updateDimensions(pWidth, pHeight);
            frontPart.setBoundingBox(frontPart.getDimensions(frontPart.getPose())
                    .makeBoundingBox(segX, boxBottomY, segZ));
        }

        // Step C: Update Rear Segments (Index numFront + 1 to totalSegments - 1)
        // Trailing IK chain behind centerPos, constrained within spine deflection angle
        double rearTargetSpacing = (double) profile.rearLength() / (double) numRear;
        Vec3 prevPos = centerPos;
        Vec3 backDir = new Vec3(-bodyFwdX, 0, -bodyFwdZ);

        for (int r = 1; r <= numRear; r++) {
            int idx = numFront + r;
            SereneBodyPartEntity rearPart = parts[idx];

            Vec3 curPos = rearPart.position();
            Vec3 diff = new Vec3(curPos.x - prevPos.x, 0, curPos.z - prevPos.z);
            double dist = diff.length();

            if (dist < 0.01) {
                // Initial placement or zero distance
                curPos = prevPos.add(backDir.scale(rearTargetSpacing));
            } else {
                Vec3 unitDiff = diff.scale(1.0 / dist);
                // Constrain deflection from backward vector so tail does not veer off into empty water!
                double dot = unitDiff.dot(backDir);
                if (dot < 0.25) { // more than ~75 degrees off the spine line
                    unitDiff = unitDiff.scale(0.65).add(backDir.scale(0.35)).normalize();
                }
                curPos = prevPos.add(unitDiff.scale(rearTargetSpacing));
            }

            float rearTaper = 1.0f - 0.45f * ((float) r / (float) numRear);
            float pWidth = profile.baseWidth() * rearTaper;
            float pHeight = profile.baseHeight() * rearTaper;
            double boxBottomY = centerPos.y + profile.torsoCenterY() - (pHeight / 2.0);

            rearPart.setPos(curPos.x, centerPos.y, curPos.z);
            rearPart.updateDimensions(pWidth, pHeight);
            rearPart.setBoundingBox(rearPart.getDimensions(rearPart.getPose())
                    .makeBoundingBox(curPos.x, boxBottomY, curPos.z));

            prevPos = curPos;
        }
    }
}
