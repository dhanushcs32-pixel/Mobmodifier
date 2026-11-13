package com.serenemobmodifier.logic.sable;

import com.serenemobmodifier.SereneMobModifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Soft reflection bridge for Sable / Serene Sailing sub-level contraptions.
 * Ensures serenemobmodifier runs safely standalone while dynamically interacting
 * with assembled Sable ships when Sable is loaded.
 */
public class SableBridge {

    private static final boolean SABLE_LOADED;

    /**
     * One {@link SableShipData} per sub-level so the expensive block scan cache is shared by every
     * mob targeting the same ship, instead of each canUse() poll building a fresh (cache-less) instance.
     */
    private static final Map<UUID, SableShipData> SHIP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Method mGetContainer;
    private static Method mGetAllSubLevels;
    private static Method mIsRemoved;
    private static Method mGetLevel;
    private static Method mGetUniqueId;
    private static Method mBoundingBox;
    private static Method mLogicalPose;
    private static Method mGetPlot;

    private static Method mTransformPos;
    private static Method mTransformPosInv;
    private static Method mTransformNormalInv;

    private static Method mPlotGetBox;
    private static Method mBox3iMinX, mBox3iMinY, mBox3iMinZ, mBox3iMaxX, mBox3iMaxY, mBox3iMaxZ;

    private static Method mBox3dToMojang;
    private static Method mBox3dMinX, mBox3dMinY, mBox3dMinZ, mBox3dMaxX, mBox3dMaxY, mBox3dMaxZ;

    private static Class<?> cRigidBodyHandle;
    private static Method mBodyOf;
    private static Method mBodyIsValid;
    private static Method mBodyApplyImpulse;

    static {
        boolean loaded = false;
        try {
            boolean hasMod = false;
            try {
                hasMod = ModList.get() != null && ModList.get().isLoaded("sable");
            } catch (Throwable ignored) {}

            if (hasMod) {
                loaded = true;
            } else {
                Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                loaded = true;
            }
        } catch (Throwable ignored) {
            loaded = false;
        }

        if (loaded) {
            try {
                Class<?> subLevelContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                Class<?> serverContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
                Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
                Class<?> serverSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
                Class<?> pose3dcClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
                Class<?> box3dcClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
                Class<?> box3icClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
                Class<?> levelPlotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
                cRigidBodyHandle = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");

                mGetContainer = subLevelContainerClass.getMethod("getContainer", ServerLevel.class);
                mGetAllSubLevels = serverContainerClass.getMethod("getAllSubLevels");
                mIsRemoved = subLevelClass.getMethod("isRemoved");
                mGetLevel = subLevelClass.getMethod("getLevel");
                mGetUniqueId = subLevelClass.getMethod("getUniqueId");
                mBoundingBox = subLevelClass.getMethod("boundingBox");
                mLogicalPose = subLevelClass.getMethod("logicalPose");
                mGetPlot = subLevelClass.getMethod("getPlot");

                mTransformPos = pose3dcClass.getMethod("transformPosition", Vec3.class);
                mTransformPosInv = pose3dcClass.getMethod("transformPositionInverse", Vec3.class);
                try {
                    mTransformNormalInv = pose3dcClass.getMethod("transformNormalInverse", Vec3.class);
                } catch (Throwable ignored) {
                    // Optional: without it ship impulses are skipped instead of being applied in the wrong space.
                }

                mPlotGetBox = levelPlotClass.getMethod("getBoundingBox");

                mBox3iMinX = box3icClass.getMethod("minX");
                mBox3iMinY = box3icClass.getMethod("minY");
                mBox3iMinZ = box3icClass.getMethod("minZ");
                mBox3iMaxX = box3icClass.getMethod("maxX");
                mBox3iMaxY = box3icClass.getMethod("maxY");
                mBox3iMaxZ = box3icClass.getMethod("maxZ");

                try {
                    mBox3dToMojang = box3dcClass.getMethod("toMojang");
                } catch (Throwable ignored) {}

                mBox3dMinX = box3dcClass.getMethod("minX");
                mBox3dMinY = box3dcClass.getMethod("minY");
                mBox3dMinZ = box3dcClass.getMethod("minZ");
                mBox3dMaxX = box3dcClass.getMethod("maxX");
                mBox3dMaxY = box3dcClass.getMethod("maxY");
                mBox3dMaxZ = box3dcClass.getMethod("maxZ");

                mBodyOf = cRigidBodyHandle.getMethod("of", serverSubLevelClass);
                mBodyIsValid = cRigidBodyHandle.getMethod("isValid");
                mBodyApplyImpulse = cRigidBodyHandle.getMethod("applyImpulseAtPoint", Vec3.class, Vec3.class);

                SereneMobModifier.LOGGER.info("[SereneMobModifier] Successfully linked Sable sub-level reflection bridge.");
            } catch (Throwable t) {
                SereneMobModifier.LOGGER.warn("[SereneMobModifier] Sable classes found but reflection binding failed: {}", t.getMessage());
                loaded = false;
            }
        }
        SABLE_LOADED = loaded;
    }

    private static final Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Logs a bridge failure once per call site; these used to be swallowed silently, hiding Sable API drift. */
    private static void warnOnce(String where, Throwable t) {
        if (WARNED.add(where)) {
            SereneMobModifier.LOGGER.warn("[SereneMobModifier] Sable bridge call failed in {} (further failures suppressed): {}", where, String.valueOf(t));
        }
    }

    public static boolean isSableLoaded() {
        return SABLE_LOADED;
    }

    /** Releases cached ship data, e.g. when the server stops, so sub-levels are not retained across worlds. */
    public static void clearCache() {
        SHIP_CACHE.clear();
    }

    public static List<SableShipData> findNearbyShips(ServerLevel level, Vec3 center, double searchRadius) {
        if (!SABLE_LOADED || mGetContainer == null || level == null) {
            return Collections.emptyList();
        }

        try {
            Object container = mGetContainer.invoke(null, level);
            if (container == null) return Collections.emptyList();

            List<?> subLevels = (List<?>) mGetAllSubLevels.invoke(container);
            if (subLevels == null || subLevels.isEmpty()) return Collections.emptyList();

            List<SableShipData> result = new ArrayList<>();
            double radiusSq = searchRadius * searchRadius;

            for (Object subLevel : subLevels) {
                if (subLevel == null) continue;
                Boolean removed = (Boolean) mIsRemoved.invoke(subLevel);
                if (Boolean.TRUE.equals(removed)) continue;

                AABB aabb = getBoundingBox(subLevel);
                if (aabb == null) continue;

                // Check distance from mob to ship world bounding box
                double distSq = aabb.distanceToSqr(center);
                if (distSq <= radiusSq) {
                    UUID id = (UUID) mGetUniqueId.invoke(subLevel);
                    SableShipData data = id != null ? SHIP_CACHE.get(id) : null;
                    if (data == null || data.subLevel() != subLevel) {
                        Level subLevelWorld = (Level) mGetLevel.invoke(subLevel);
                        Object poseObj = mLogicalPose.invoke(subLevel);
                        Object plotObj = mGetPlot.invoke(subLevel);
                        data = new SableShipData(subLevel, id, subLevelWorld, aabb, poseObj, plotObj);
                        if (id != null) SHIP_CACHE.put(id, data);
                    }
                    result.add(data);
                }
            }

            // Drop entries for ships that no longer exist so the cache cannot leak sub-levels.
            SHIP_CACHE.values().removeIf(d -> isSubLevelRemoved(d.subLevel()));
            return result;
        } catch (Throwable t) {
            warnOnce("findNearbyShips", t);
            return Collections.emptyList();
        }
    }

    public static boolean isSubLevelRemoved(Object subLevel) {
        if (!SABLE_LOADED || subLevel == null || mIsRemoved == null) return true;
        try {
            return Boolean.TRUE.equals(mIsRemoved.invoke(subLevel));
        } catch (Throwable t) {
            return true;
        }
    }

    public static Vec3 transformPosition(Object poseObj, Vec3 localPos) {
        if (poseObj == null || mTransformPos == null) return localPos;
        try {
            return (Vec3) mTransformPos.invoke(poseObj, localPos);
        } catch (Throwable t) {
            // Falling back to the local position means plot-space coordinates leak into world-space maths.
            warnOnce("transformPosition", t);
            return localPos;
        }
    }

    /**
     * Rotates a world-space direction (impulse, normal) into the sub-level's local frame.
     * Returns {@code null} when the transform is unavailable so callers can skip the operation.
     */
    public static Vec3 transformNormalInverse(Object poseObj, Vec3 worldDir) {
        if (poseObj == null || mTransformNormalInv == null) return null;
        try {
            return (Vec3) mTransformNormalInv.invoke(poseObj, worldDir);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns the sub-level's current (live) logical pose object, or {@code null}.
     */
    public static Object getLogicalPose(Object subLevel) {
        if (!SABLE_LOADED || subLevel == null || mLogicalPose == null) return null;
        try {
            return mLogicalPose.invoke(subLevel);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Vec3 transformPositionInverse(Object poseObj, Vec3 worldPos) {
        if (poseObj == null || mTransformPosInv == null) return worldPos;
        try {
            return (Vec3) mTransformPosInv.invoke(poseObj, worldPos);
        } catch (Throwable t) {
            warnOnce("transformPositionInverse", t);
            return worldPos;
        }
    }

    public static AABB getBoundingBox(Object subLevel) {
        if (subLevel == null || mBoundingBox == null) return null;
        try {
            Object boxObj = mBoundingBox.invoke(subLevel);
            if (boxObj == null) return null;

            if (mBox3dToMojang != null) {
                try {
                    return (AABB) mBox3dToMojang.invoke(boxObj);
                } catch (Throwable ignored) {}
            }

            double minX = (double) mBox3dMinX.invoke(boxObj);
            double minY = (double) mBox3dMinY.invoke(boxObj);
            double minZ = (double) mBox3dMinZ.invoke(boxObj);
            double maxX = (double) mBox3dMaxX.invoke(boxObj);
            double maxY = (double) mBox3dMaxY.invoke(boxObj);
            double maxZ = (double) mBox3dMaxZ.invoke(boxObj);
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Throwable t) {
            warnOnce("getBoundingBox", t);
            return null;
        }
    }

    public static int[] getPlotBoundingBox(Object plotObj) {
        if (plotObj == null || mPlotGetBox == null) return null;
        try {
            Object boxObj = mPlotGetBox.invoke(plotObj);
            if (boxObj == null) return null;

            int minX = (int) mBox3iMinX.invoke(boxObj);
            int minY = (int) mBox3iMinY.invoke(boxObj);
            int minZ = (int) mBox3iMinZ.invoke(boxObj);
            int maxX = (int) mBox3iMaxX.invoke(boxObj);
            int maxY = (int) mBox3iMaxY.invoke(boxObj);
            int maxZ = (int) mBox3iMaxZ.invoke(boxObj);
            return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Applies a physical contact impulse to an assembled Sable ship.
     *
     * <p>Sable's {@code RigidBodyHandle#applyImpulseAtPoint} works in the sub-level's own space:
     * {@code localPos} is a plot-local position and {@code localImpulse} is expressed in the
     * local frame (callers inside Sable convert with {@code transformNormalInverse}).</p>
     */
    public static void applyImpulseAtPoint(Object subLevel, Vec3 localPos, Vec3 localImpulse) {
        if (!SABLE_LOADED || subLevel == null || mBodyOf == null || mBodyApplyImpulse == null) return;
        try {
            Object body = mBodyOf.invoke(null, subLevel);
            if (body != null) {
                Boolean valid = (Boolean) mBodyIsValid.invoke(body);
                if (Boolean.TRUE.equals(valid)) {
                    mBodyApplyImpulse.invoke(body, localPos, localImpulse);
                }
            }
        } catch (Throwable t) {
            SereneMobModifier.LOGGER.debug("[SereneMobModifier] Error applying impulse to Sable ship: {}", t.getMessage());
        }
    }
}
