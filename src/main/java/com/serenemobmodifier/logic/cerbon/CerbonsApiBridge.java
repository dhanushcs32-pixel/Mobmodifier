package com.serenemobmodifier.logic.cerbon;

import com.serenemobmodifier.SereneMobModifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust reflection bridge to CerbonsAPI (used by Myths of the Sea for
 * Leviathan, Kraken, Abaia, Bake Kujira, etc.).
 * Directly scales CerbonsAPI's internal EntityBounds, EntityPart hierarchy,
 * hierarchical local offsets,
 * dynamic pivot offsets, and rebuilds CompoundOrientedBox segments every tick.
 */
public class CerbonsApiBridge {

    public record CerbonsSegment(String name, boolean isHead, Vec3 center, Vec3 halfExtents, Vec3[] corners,
            AABB tightBox) {
    }

    private static boolean initialized = false;
    private static boolean available = false;

    // Reflection handles
    private static Class<?> multipartAwareEntityClass;
    private static Method getBoundsMethod;
    private static Method setNextDamagedPartMethod;

    private static Class<?> entityBoundsClass;
    private static Field partMapField;
    private static Field overrideBoxField;
    private static Method raycastMethod;
    private static Method getBoxMethod;

    private static Class<?> entityPartClass;
    private static Field boxField;
    private static Field offXField;
    private static Field offYField;
    private static Field offZField;
    private static Field xField;
    private static Field yField;
    private static Field zField;
    private static Field parentField;
    private static Field pxField;
    private static Field pyField;
    private static Field pzField;
    private static Field changedField;
    private static Method getPartBoxMethod;

    private static Class<?> mutableBoxClass;
    private static Field mutableBoxBoxField;

    private static Class<?> orientedBoxClass;
    private static Method getCenterMethod;
    private static Method getHalfExtentsMethod;
    private static Method getMatrixMethod;

    private static Class<?> matrix3dClass;
    private static Field m00Field, m01Field, m02Field;
    private static Field m10Field, m11Field, m12Field;
    private static Field m20Field, m21Field, m22Field;

    // Caches to prevent compounding across ticks
    private static final Map<Object, AABB> BASE_PART_BOXES = new WeakHashMap<>();
    private static final Map<Object, Vec3> BASE_PART_OFFSETS = new WeakHashMap<>();
    private static final Map<Object, Double> BASE_PART_PZ = new WeakHashMap<>();
    private static final Map<Class<?>, Boolean> CLASS_AWARE_CACHE = new ConcurrentHashMap<>();

    private static synchronized void init() {
        if (initialized)
            return;
        initialized = true;
        try {
            multipartAwareEntityClass = Class
                    .forName("com.cerbon.cerbons_api.api.multipart_entities.entity.MultipartAwareEntity");
            getBoundsMethod = multipartAwareEntityClass.getMethod("getBounds");
            setNextDamagedPartMethod = multipartAwareEntityClass.getMethod("setNextDamagedPart", String.class);

            entityBoundsClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.entity.EntityBounds");
            partMapField = entityBoundsClass.getDeclaredField("partMap");
            partMapField.setAccessible(true);
            overrideBoxField = entityBoundsClass.getDeclaredField("overrideBox");
            overrideBoxField.setAccessible(true);
            raycastMethod = entityBoundsClass.getMethod("raycast", Vec3.class, Vec3.class);
            getBoxMethod = entityBoundsClass.getMethod("getBox", AABB.class);

            entityPartClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.entity.EntityPart");
            boxField = entityPartClass.getDeclaredField("box");
            boxField.setAccessible(true);
            offXField = entityPartClass.getDeclaredField("offX");
            offXField.setAccessible(true);
            offYField = entityPartClass.getDeclaredField("offY");
            offYField.setAccessible(true);
            offZField = entityPartClass.getDeclaredField("offZ");
            offZField.setAccessible(true);
            xField = entityPartClass.getDeclaredField("x");
            xField.setAccessible(true);
            yField = entityPartClass.getDeclaredField("y");
            yField.setAccessible(true);
            zField = entityPartClass.getDeclaredField("z");
            zField.setAccessible(true);
            parentField = entityPartClass.getDeclaredField("parent");
            parentField.setAccessible(true);
            pxField = entityPartClass.getDeclaredField("px");
            pxField.setAccessible(true);
            pyField = entityPartClass.getDeclaredField("py");
            pyField.setAccessible(true);
            pzField = entityPartClass.getDeclaredField("pz");
            pzField.setAccessible(true);
            changedField = entityPartClass.getDeclaredField("changed");
            changedField.setAccessible(true);
            getPartBoxMethod = entityPartClass.getMethod("getBox");

            mutableBoxClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.entity.MutableBox");
            mutableBoxBoxField = mutableBoxClass.getDeclaredField("box");
            mutableBoxBoxField.setAccessible(true);

            orientedBoxClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.util.OrientedBox");
            getCenterMethod = orientedBoxClass.getMethod("getCenter");
            getHalfExtentsMethod = orientedBoxClass.getMethod("getHalfExtents");
            getMatrixMethod = orientedBoxClass.getMethod("getMatrix");

            matrix3dClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.util.Matrix3d");
            m00Field = matrix3dClass.getField("m00");
            m01Field = matrix3dClass.getField("m01");
            m02Field = matrix3dClass.getField("m02");
            m10Field = matrix3dClass.getField("m10");
            m11Field = matrix3dClass.getField("m11");
            m12Field = matrix3dClass.getField("m12");
            m20Field = matrix3dClass.getField("m20");
            m21Field = matrix3dClass.getField("m21");
            m22Field = matrix3dClass.getField("m22");

            available = true;
            SereneMobModifier.LOGGER
                    .info("[SereneMobModifier] Successfully linked CerbonsAPI multipart reflection bridge.");
        } catch (Throwable t) {
            available = false;
            SereneMobModifier.LOGGER.error("[SereneMobModifier] Failed to link CerbonsAPI multipart reflection bridge",
                    t);
        }
    }

    public static boolean isCerbonsMultipart(LivingEntity entity) {
        if (!initialized)
            init();
        if (!available || entity == null)
            return false;
        return CLASS_AWARE_CACHE.computeIfAbsent(entity.getClass(), clazz -> {
            try {
                return multipartAwareEntityClass.isAssignableFrom(clazz);
            } catch (Throwable t) {
                return false;
            }
        });
    }

    /**
     * Scales CerbonsAPI's internal EntityPart local boxes, hierarchical offsets,
     * pivot offsets,
     * and updates the broad-phase overrideBox to tightly encompass the scaled 3D
     * body.
     */
    public static void scaleEntityBounds(LivingEntity entity, float scale) {
        if (!isCerbonsMultipart(entity))
            return;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null)
                return;

            Object partMapObj = partMapField.get(bounds);
            if (!(partMapObj instanceof Map<?, ?> partMap))
                return;

            for (Map.Entry<?, ?> entry : partMap.entrySet()) {
                Object part = entry.getValue();
                if (part == null)
                    continue;

                Object parent = parentField != null ? parentField.get(part) : null;
                boolean isChild = (parent != null);

                // 1. Scale local segment box AABB
                AABB baseBox = BASE_PART_BOXES.computeIfAbsent(part, p -> {
                    try {
                        return (AABB) boxField.get(p);
                    } catch (Exception e) {
                        return new AABB(-1, -1, -1, 1, 1, 1);
                    }
                });

                if (baseBox.getXsize() > 0.01 || baseBox.getYsize() > 0.01 || baseBox.getZsize() > 0.01) {
                    AABB scaledBox = new AABB(
                            baseBox.minX * scale, baseBox.minY * scale, baseBox.minZ * scale,
                            baseBox.maxX * scale, baseBox.maxY * scale, baseBox.maxZ * scale);
                    boxField.set(part, scaledBox);
                }

                // 2. Scale hierarchical offsets (offX, offY, offZ) and local coordinates (x, y, z)
                // for CHILD parts. The root part holds world coordinates (entity.getX, etc.) which must NOT be scaled!
                if (isChild) {
                    Vec3 baseOff = BASE_PART_OFFSETS.computeIfAbsent(part, p -> {
                        try {
                            return new Vec3(offXField.getDouble(p), offYField.getDouble(p), offZField.getDouble(p));
                        } catch (Exception e) {
                            return Vec3.ZERO;
                        }
                    });
                    double scaledOffX = baseOff.x * scale;
                    double scaledOffY = baseOff.y * scale;
                    double scaledOffZ = baseOff.z * scale;

                    offXField.setDouble(part, scaledOffX);
                    offYField.setDouble(part, scaledOffY);
                    offZField.setDouble(part, scaledOffZ);

                    if (xField != null) xField.setDouble(part, scaledOffX);
                    if (yField != null) yField.setDouble(part, scaledOffY);
                    if (zField != null) zField.setDouble(part, scaledOffZ);
                }

                // 3. Scale dynamic pivot offset (pz, px, py)
                // LeviathanHitboxes.updatePosition sets unscaled pz (-1.15625d or -2.25d) on
                // every tick.
                double curPz = pzField.getDouble(part);
                Double basePz = BASE_PART_PZ.get(part);
                if (basePz == null || (curPz != 0 && Math.abs(curPz - basePz * scale) > 1e-4
                        && Math.abs(curPz) < Math.abs(basePz * scale))) {
                    basePz = curPz;
                    BASE_PART_PZ.put(part, basePz);
                }
                pzField.setDouble(part, basePz * scale);
                pxField.setDouble(part, pxField.getDouble(part) * scale);
                pyField.setDouble(part, pyField.getDouble(part) * scale);

                // Mark part changed so EntityBounds recomputes its OrientedBox cache
                changedField.setBoolean(part, true);
            }

            // 4. Extract live scaled segments and compute tight enclosure encompassing all segments
            List<CerbonsSegment> segments = getSegments(entity);
            AABB tightEnclosure = null;
            if (!segments.isEmpty()) {
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY,
                        minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY,
                        maxZ = Double.NEGATIVE_INFINITY;
                for (CerbonsSegment seg : segments) {
                    AABB tb = seg.tightBox();
                    if (tb.minX < minX)
                        minX = tb.minX;
                    if (tb.minY < minY)
                        minY = tb.minY;
                    if (tb.minZ < minZ)
                        minZ = tb.minZ;
                    if (tb.maxX > maxX)
                        maxX = tb.maxX;
                    if (tb.maxY > maxY)
                        maxY = tb.maxY;
                    if (tb.maxZ > maxZ)
                        maxZ = tb.maxZ;
                }
                tightEnclosure = new AABB(minX - 2.0, minY - 2.0, minZ - 2.0, maxX + 2.0, maxY + 2.0, maxZ + 2.0);
                Object overrideBox = overrideBoxField.get(bounds);
                if (overrideBox != null) {
                    mutableBoxBoxField.set(overrideBox, tightEnclosure);
                }
            }

            // 5. Invalidate and re-trigger getBox to rebuild CompoundOrientedBox with
            // scaled OrientedBoxes and tight enclosure
            AABB baseEnclosure = (tightEnclosure != null) ? tightEnclosure : entity.getBoundingBox();
            Object compoundBox = getBoxMethod.invoke(bounds, baseEnclosure);
            if (compoundBox instanceof AABB scaledCompoundAABB) {
                entity.setBoundingBox(scaledCompoundAABB);
            }

        } catch (Throwable t) {
            SereneMobModifier.LOGGER.error("[SereneMobModifier] Error scaling CerbonsAPI bounds for entity "
                    + entity.getType().getDescriptionId(), t);
        }
    }

    /**
     * Extracts all live oriented segments with their exact 3D world corners and
     * tight world AABBs.
     */
    public static List<CerbonsSegment> getSegments(LivingEntity entity) {
        List<CerbonsSegment> list = new ArrayList<>();
        if (!isCerbonsMultipart(entity))
            return list;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null)
                return list;

            Object partMapObj = partMapField.get(bounds);
            if (!(partMapObj instanceof Map<?, ?> partMap))
                return list;

            for (Map.Entry<?, ?> entry : partMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if ("rootYaw".equalsIgnoreCase(name))
                    continue;

                Object part = entry.getValue();
                if (part == null)
                    continue;

                Object orientedBox = getPartBoxMethod.invoke(part);
                if (orientedBox == null)
                    continue;

                Vec3 center = (Vec3) getCenterMethod.invoke(orientedBox);
                Vec3 halfExtents = (Vec3) getHalfExtentsMethod.invoke(orientedBox);
                if (center == null || halfExtents == null || halfExtents.lengthSqr() < 1e-4)
                    continue;

                Object matrix = getMatrixMethod.invoke(orientedBox);
                if (matrix == null)
                    continue;

                // Compute 8 rotated 3D corners in world space
                Vec3[] corners = new Vec3[8];
                int idx = 0;
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY,
                        minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY,
                        maxZ = Double.NEGATIVE_INFINITY;

                for (int sx : new int[] { -1, 1 }) {
                    for (int sy : new int[] { -1, 1 }) {
                        for (int sz : new int[] { -1, 1 }) {
                            Vec3 localCorner = new Vec3(sx * halfExtents.x, sy * halfExtents.y, sz * halfExtents.z);
                            Vec3 rotatedCorner = transformMatrix(matrix, localCorner);
                            Vec3 worldCorner = rotatedCorner.add(center);
                            corners[idx++] = worldCorner;

                            if (worldCorner.x < minX)
                                minX = worldCorner.x;
                            if (worldCorner.y < minY)
                                minY = worldCorner.y;
                            if (worldCorner.z < minZ)
                                minZ = worldCorner.z;
                            if (worldCorner.x > maxX)
                                maxX = worldCorner.x;
                            if (worldCorner.y > maxY)
                                maxY = worldCorner.y;
                            if (worldCorner.z > maxZ)
                                maxZ = worldCorner.z;
                        }
                    }
                }

                AABB tightBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
                boolean isHead = "head".equalsIgnoreCase(name) || name.toLowerCase(Locale.ROOT).contains("head");
                list.add(new CerbonsSegment(name, isHead, center, halfExtents, corners, tightBox));
            }
        } catch (Throwable t) {
            SereneMobModifier.LOGGER.error("[SereneMobModifier] Error extracting CerbonsAPI segments for "
                    + entity.getType().getDescriptionId(), t);
        }

        return list;
    }

    private static Vec3 transformMatrix(Object matrix, Vec3 v) throws Exception {
        double m00 = m00Field.getDouble(matrix);
        double m01 = m01Field.getDouble(matrix);
        double m02 = m02Field.getDouble(matrix);
        double m10 = m10Field.getDouble(matrix);
        double m11 = m11Field.getDouble(matrix);
        double m12 = m12Field.getDouble(matrix);
        double m20 = m20Field.getDouble(matrix);
        double m21 = m21Field.getDouble(matrix);
        double m22 = m22Field.getDouble(matrix);

        double x = m00 * v.x + m01 * v.y + m02 * v.z;
        double y = m10 * v.x + m11 * v.y + m12 * v.z;
        double z = m20 * v.x + m21 * v.y + m22 * v.z;
        return new Vec3(x, y, z);
    }

    /**
     * Extracts the live tight physical AABBs of all CerbonsAPI segments.
     */
    public static List<AABB> getCerbonsPartBoxes(LivingEntity entity) {
        List<AABB> result = new ArrayList<>();
        for (CerbonsSegment seg : getSegments(entity)) {
            result.add(seg.tightBox());
        }
        return result;
    }

    /**
     * Raycasts against CerbonsAPI's scaled EntityBounds and sets the damaged part
     * if hit.
     */
    public static boolean checkRaycastHit(LivingEntity entity, Vec3 from, Vec3 to) {
        if (!isCerbonsMultipart(entity))
            return false;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null)
                return false;

            Object hitPart = raycastMethod.invoke(bounds, from, to);
            if (hitPart instanceof String partName && !partName.isEmpty()) {
                setNextDamagedPartMethod.invoke(entity, partName);
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * Diagnostic helper for /mobmodifier inspect to return all CerbonsAPI parts and
     * their status.
     */
    public static List<String> getCerbonsPartDetails(LivingEntity entity) {
        List<String> list = new ArrayList<>();
        for (CerbonsSegment seg : getSegments(entity)) {
            AABB b = seg.tightBox();
            list.add(String.format(Locale.ROOT, "%s (%s): center=(%.1f, %.1f, %.1f), size=%.1fx%.1fx%.1f",
                    seg.name(), seg.isHead() ? "Head" : "Body",
                    seg.center().x, seg.center().y, seg.center().z,
                    b.getXsize(), b.getYsize(), b.getZsize()));
        }
        return list;
    }
}
