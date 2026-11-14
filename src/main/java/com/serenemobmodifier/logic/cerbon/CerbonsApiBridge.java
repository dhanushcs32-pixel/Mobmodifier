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
 * Safe reflection bridge to CerbonsAPI (used by Myths of the Sea for Leviathan, Kraken, Abaia, Bake Kujira, etc.).
 * Scales CerbonsAPI's EntityBounds and EntityPart segment sizes, pivot offsets, and override collision boxes
 * by Attributes.SCALE every tick so that sub-hitboxes properly encompass the scaled 3D body.
 */
public class CerbonsApiBridge {

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
    private static Field pxField;
    private static Field pyField;
    private static Field pzField;
    private static Field changedField;

    private static Class<?> mutableBoxClass;
    private static Field mutableBoxBoxField;

    private static Class<?> orientedBoxClass;
    private static Method getExtentsMethod;

    // Caches to prevent compounding across ticks
    private static final Map<Object, AABB> BASE_PART_BOXES = new WeakHashMap<>();
    private static final Map<LivingEntity, AABB> BASE_COLLISION_BOXES = new WeakHashMap<>();
    private static final Map<Class<?>, Boolean> CLASS_AWARE_CACHE = new ConcurrentHashMap<>();

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            multipartAwareEntityClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.entity.MultipartAwareEntity");
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
            pxField = entityPartClass.getDeclaredField("px");
            pxField.setAccessible(true);
            pyField = entityPartClass.getDeclaredField("py");
            pyField.setAccessible(true);
            pzField = entityPartClass.getDeclaredField("pz");
            pzField.setAccessible(true);
            changedField = entityPartClass.getDeclaredField("changed");
            changedField.setAccessible(true);

            mutableBoxClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.entity.MutableBox");
            mutableBoxBoxField = mutableBoxClass.getDeclaredField("box");
            mutableBoxBoxField.setAccessible(true);

            orientedBoxClass = Class.forName("com.cerbon.cerbons_api.api.multipart_entities.util.OrientedBox");
            getExtentsMethod = orientedBoxClass.getMethod("getExtents");

            available = true;
            SereneMobModifier.LOGGER.info("[SereneMobModifier] Successfully linked CerbonsAPI multipart reflection bridge.");
        } catch (Throwable t) {
            available = false;
        }
    }

    public static boolean isCerbonsMultipart(LivingEntity entity) {
        if (!initialized) init();
        if (!available || entity == null) return false;
        return CLASS_AWARE_CACHE.computeIfAbsent(entity.getClass(), clazz -> {
            try {
                return multipartAwareEntityClass.isAssignableFrom(clazz);
            } catch (Throwable t) {
                return false;
            }
        });
    }

    /**
     * Scales CerbonsAPI's internal EntityPart local boxes, pivot offsets, and override collision box.
     * Called at the end of each tick (EntityTickEvent.Post) after the mob's own updatePosition() runs.
     */
    public static void scaleEntityBounds(LivingEntity entity, float scale) {
        if (!isCerbonsMultipart(entity)) return;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null) return;

            Object partMapObj = partMapField.get(bounds);
            if (!(partMapObj instanceof Map<?, ?> partMap)) return;

            for (Object part : partMap.values()) {
                if (part == null) continue;

                // 1. Scale local segment box AABB
                AABB baseBox = BASE_PART_BOXES.computeIfAbsent(part, p -> {
                    try {
                        return (AABB) boxField.get(p);
                    } catch (Exception e) {
                        return new AABB(-1, -1, -1, 1, 1, 1);
                    }
                });

                AABB scaledBox = new AABB(
                        baseBox.minX * scale, baseBox.minY * scale, baseBox.minZ * scale,
                        baseBox.maxX * scale, baseBox.maxY * scale, baseBox.maxZ * scale
                );
                boxField.set(part, scaledBox);

                // 2. Scale pivot offset (px, py, pz)
                // Note: The mob's tick just set unscaled pivots, so we scale them relative to 1.0x
                double unscaledPx = pxField.getDouble(part);
                double unscaledPy = pyField.getDouble(part);
                double unscaledPz = pzField.getDouble(part);

                pxField.setDouble(part, unscaledPx * scale);
                pyField.setDouble(part, unscaledPy * scale);
                pzField.setDouble(part, unscaledPz * scale);

                // Mark part changed so EntityBounds recomputes its OrientedBox cache
                changedField.setBoolean(part, true);
            }

            // 3. Scale overrideBox in EntityBounds so the outer CompoundOrientedBox encapsulates all segments
            Object overrideBox = overrideBoxField.get(bounds);
            if (overrideBox != null) {
                AABB currentBox = (AABB) mutableBoxBoxField.get(overrideBox);
                if (currentBox != null) {
                    Vec3 pos = entity.position();
                    AABB baseColl = BASE_COLLISION_BOXES.computeIfAbsent(entity, e -> {
                        // Unmove from position to get local base box
                        return currentBox.move(pos.scale(-1.0));
                    });

                    AABB scaledColl = new AABB(
                            baseColl.minX * scale, baseColl.minY * scale, baseColl.minZ * scale,
                            baseColl.maxX * scale, baseColl.maxY * scale, baseColl.maxZ * scale
                    ).move(pos);

                    mutableBoxBoxField.set(overrideBox, scaledColl);
                }
            }

            // Trigger getBox(entity.getBoundingBox()) to refresh cache
            getBoxMethod.invoke(bounds, entity.getBoundingBox());

        } catch (Throwable t) {
            // Silently ignore reflection errors during gameplay
        }
    }

    /**
     * Extracts the live physical AABBs of all CerbonsAPI segments for rendering and raycasting.
     */
    public static List<AABB> getCerbonsPartBoxes(LivingEntity entity) {
        List<AABB> result = new ArrayList<>();
        if (!isCerbonsMultipart(entity)) return result;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null) return result;

            Object compoundBox = getBoxMethod.invoke(bounds, entity.getBoundingBox());
            if (compoundBox instanceof Iterable<?> iterable) {
                for (Object orientedBox : iterable) {
                    if (orientedBox != null) {
                        AABB extents = (AABB) getExtentsMethod.invoke(orientedBox);
                        if (extents != null) {
                            result.add(extents);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return result;
    }

    /**
     * Raycasts against CerbonsAPI's scaled EntityBounds and sets the damaged part if hit.
     */
    public static boolean checkRaycastHit(LivingEntity entity, Vec3 from, Vec3 to) {
        if (!isCerbonsMultipart(entity)) return false;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null) return false;

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
     * Diagnostic helper for /mobmodifier inspect to return all CerbonsAPI parts and their status.
     */
    public static List<String> getCerbonsPartDetails(LivingEntity entity) {
        List<String> list = new ArrayList<>();
        if (!isCerbonsMultipart(entity)) return list;

        try {
            Object bounds = getBoundsMethod.invoke(entity);
            if (bounds == null) return list;

            Object partMapObj = partMapField.get(bounds);
            if (partMapObj instanceof Map<?, ?> partMap) {
                for (Map.Entry<?, ?> entry : partMap.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    Object part = entry.getValue();
                    if (part != null) {
                        double pz = pzField.getDouble(part);
                        AABB box = (AABB) boxField.get(part);
                        list.add(String.format(Locale.ROOT, "%s: pz=%.2f, box=%.1fx%.1fx%.1f",
                                name, pz, box.getXsize(), box.getYsize(), box.getZsize()));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return list;
    }
}

