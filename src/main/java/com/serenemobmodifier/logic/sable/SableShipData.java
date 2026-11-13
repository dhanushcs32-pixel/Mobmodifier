package com.serenemobmodifier.logic.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Data representation of an active Sable assembled ship vessel.
 * Manages dynamic coordinate transformation and local boarding / perimeter scans.
 */
public class SableShipData {
    private final Object subLevel;
    private final UUID id;
    private final Level subLevelWorld;
    private AABB worldBounds;
    private Object poseObj;
    private long liveTick = Long.MIN_VALUE;
    private final Object plotObj;

    private ShipScanResult cachedScan;
    private long lastScanTime = -1000L;

    public SableShipData(Object subLevel, UUID id, Level subLevelWorld, AABB worldBounds, Object poseObj, Object plotObj) {
        this.subLevel = subLevel;
        this.id = id;
        this.subLevelWorld = subLevelWorld;
        this.worldBounds = worldBounds;
        this.poseObj = poseObj;
        this.plotObj = plotObj;
    }

    public Object subLevel() {
        return this.subLevel;
    }

    public UUID getId() {
        return this.id;
    }

    public Level getSubLevelWorld() {
        return this.subLevelWorld;
    }

    /**
     * Current world-space bounds of the ship. Refreshed at most once per game tick because the ship moves;
     * the bounds captured at construction time would otherwise go stale while a mob is chasing it.
     */
    public AABB getWorldBounds() {
        refreshLive();
        return this.worldBounds;
    }

    private void refreshLive() {
        if (this.subLevelWorld == null) return;
        long now = this.subLevelWorld.getGameTime();
        if (now == this.liveTick) return;
        this.liveTick = now;

        AABB bounds = SableBridge.getBoundingBox(this.subLevel);
        if (bounds != null) this.worldBounds = bounds;
        Object pose = SableBridge.getLogicalPose(this.subLevel);
        if (pose != null) this.poseObj = pose;
    }

    public boolean isRemoved() {
        return SableBridge.isSubLevelRemoved(this.subLevel);
    }

    public Vec3 transformPosition(Vec3 localPos) {
        refreshLive();
        return SableBridge.transformPosition(this.poseObj, localPos);
    }

    public Vec3 transformPositionInverse(Vec3 worldPos) {
        refreshLive();
        return SableBridge.transformPositionInverse(this.poseObj, worldPos);
    }

    /** Converts a world-space direction into the ship's local frame, or {@code null} if unavailable. */
    public Vec3 transformNormalInverse(Vec3 worldDir) {
        refreshLive();
        return SableBridge.transformNormalInverse(this.poseObj, worldDir);
    }

    public Vec3 getDeckCenterWorld() {
        ShipScanResult scan = getScan(this.subLevelWorld.getGameTime());
        if (scan.deckCenterLocal != null) {
            return transformPosition(Vec3.atCenterOf(scan.deckCenterLocal));
        }
        return this.worldBounds.getCenter();
    }

    /**
     * Retrieves or refreshes the cached boarding and perimeter scan of the ship.
     * Caching prevents scanning all blocks every single tick.
     */
    public synchronized ShipScanResult getScan(long currentGameTime) {
        if (this.cachedScan != null && (currentGameTime - this.lastScanTime) < 40L) {
            return this.cachedScan;
        }
        this.cachedScan = performScan();
        this.lastScanTime = currentGameTime;
        return this.cachedScan;
    }

    private ShipScanResult performScan() {
        List<LadderBoardingPoint> ladders = new ArrayList<>();
        List<LadderBoardingPoint> lowGunwales = new ArrayList<>();
        List<BlockPos> waterlineHullLocal = new ArrayList<>();
        Set<BlockPos> hullSet = new LinkedHashSet<>();
        BlockPos deckCenterLocal = null;

        if (this.subLevelWorld == null || this.plotObj == null) {
            return new ShipScanResult(ladders, lowGunwales, waterlineHullLocal, deckCenterLocal);
        }

        int[] box = SableBridge.getPlotBoundingBox(this.plotObj);
        if (box == null) {
            // The old fallback box was made-up coordinates in plot space, where nothing can be found anyway.
            return new ShipScanResult(ladders, lowGunwales, waterlineHullLocal, deckCenterLocal);
        }
        int minX = box[0];
        int minY = box[1];
        int minZ = box[2];
        int maxX = box[3];
        int maxY = box[4];
        int maxZ = box[5];

        // Local Y coordinate corresponding to the dimension's sea level (not a hard-coded 63)
        double seaLevel = this.subLevelWorld.getSeaLevel();
        AABB bounds = getWorldBounds();
        Vec3 seaLevelWorld = new Vec3(bounds.getCenter().x, seaLevel, bounds.getCenter().z);
        Vec3 seaLevelLocal = transformPositionInverse(seaLevelWorld);
        int localWaterlineY = (int) Math.round(seaLevelLocal.y);

        // Clamp scan range vertically around waterline and deck. The upper bound used to be waterline+6, which
        // truncated ladders on ships with more freeboard and gave the mob a bogus "top" inside the hull wall.
        int scanMinY = Math.max(minY, localWaterlineY - 4);
        int scanMaxY = Math.min(maxY, localWaterlineY + 16);

        // Map to track climbable column rungs: (x, z) packed key -> [minY, maxY, facingIndex]
        Map<Long, int[]> ladderColumns = new HashMap<>();

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        // 1. Scan for Ladders, Vines, and Climbables in the waterline/deck zone
        for (int y = scanMinY; y <= scanMaxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mpos.set(x, y, z);
                    BlockState state = this.subLevelWorld.getBlockState(mpos);
                    Direction facing = climbableFacing(state);
                    if (facing != null) {
                        long colKey = (((long) x) << 32) | (z & 0xFFFFFFFFL);
                        int[] col = ladderColumns.get(colKey);
                        if (col == null) {
                            ladderColumns.put(colKey, new int[]{y, y, facing.get2DDataValue()});
                        } else {
                            if (y < col[0]) col[0] = y;
                            if (y > col[1]) col[1] = y;
                        }
                    }
                }
            }
        }

        // Validate climbable columns to ensure they are on the hull exterior and reachable
        for (Map.Entry<Long, int[]> entry : ladderColumns.entrySet()) {
            long key = entry.getKey();
            int x = (int) (key >> 32);
            int z = (int) key;
            int[] col = entry.getValue();
            int bY = col[0];
            int tY = col[1];
            Direction facing = Direction.from2DDataValue(col[2]);

            BlockPos bottomPos = new BlockPos(x, bY, z);
            BlockPos topPos = new BlockPos(x, tY, z);

            // Exterior check: the space in front of the ladder bottom must be air or water
            BlockPos frontPos = bottomPos.relative(facing);
            BlockState frontState = this.subLevelWorld.getBlockState(frontPos);
            if (frontState.isAir() || !frontState.getFluidState().isEmpty()) {
                Vec3 bottomWorld = transformPosition(Vec3.atCenterOf(bottomPos));
                // Ladder bottom must be near water level so mobs can reach it from water
                if (bottomWorld.y <= seaLevel + 2.5 && bottomWorld.y >= seaLevel - 5.0) {
                    ladders.add(new LadderBoardingPoint(bottomPos, topPos, facing, false));
                }
            }
        }

        // 2. Scan Hull Perimeter near the waterline for Priority B ramming and low gunwales
        int wlY = Math.max(minY, Math.min(maxY, localWaterlineY));
        for (int y = wlY; y <= Math.min(maxY, wlY + 1); y++) {
            // Scan Z lines across X width (Port and Starboard hull flanks)
            for (int z = minZ; z <= maxZ; z++) {
                // Port flank (scan from minX inward)
                for (int x = minX; x <= maxX; x++) {
                    mpos.set(x, y, z);
                    BlockState st = this.subLevelWorld.getBlockState(mpos);
                    if (isSolidHull(st)) {
                        hullSet.add(mpos.immutable());
                        break;
                    }
                }
                // Starboard flank (scan from maxX inward)
                for (int x = maxX; x >= minX; x--) {
                    mpos.set(x, y, z);
                    BlockState st = this.subLevelWorld.getBlockState(mpos);
                    if (isSolidHull(st)) {
                        hullSet.add(mpos.immutable());
                        break;
                    }
                }
            }

            // Scan X lines across Z length (Bow and Stern)
            for (int x = minX; x <= maxX; x++) {
                // Stern (scan from minZ inward)
                for (int z = minZ; z <= maxZ; z++) {
                    mpos.set(x, y, z);
                    BlockState st = this.subLevelWorld.getBlockState(mpos);
                    if (isSolidHull(st)) {
                        hullSet.add(mpos.immutable());
                        break;
                    }
                }
                // Bow (scan from maxZ inward)
                for (int z = maxZ; z >= minZ; z--) {
                    mpos.set(x, y, z);
                    BlockState st = this.subLevelWorld.getBlockState(mpos);
                    if (isSolidHull(st)) {
                        hullSet.add(mpos.immutable());
                        break;
                    }
                }
            }
        }

        waterlineHullLocal.addAll(hullSet);

        // 3. Low Gunwales detection if no ladders are found
        if (ladders.isEmpty()) {
            for (BlockPos hp : waterlineHullLocal) {
                Vec3 hpWorld = transformPosition(Vec3.atCenterOf(hp));
                // If a hull edge block is within ~1.5 blocks of sea level, mobs can vault/climb over
                if (hpWorld.y >= seaLevel - 1.5 && hpWorld.y <= seaLevel + 1.5) {
                    // Check if block above is open air
                    if (this.subLevelWorld.getBlockState(hp.above()).isAir()) {
                        // Outward = direction of the nearest edge of the plot box (bow/stern blocks used to be
                        // treated as port/starboard because only the X axis was considered)
                        int dWest = hp.getX() - minX;
                        int dEast = maxX - hp.getX();
                        int dNorth = hp.getZ() - minZ;
                        int dSouth = maxZ - hp.getZ();
                        int nearest = Math.min(Math.min(dWest, dEast), Math.min(dNorth, dSouth));
                        Direction outward = nearest == dWest ? Direction.WEST
                                : nearest == dEast ? Direction.EAST
                                : nearest == dNorth ? Direction.NORTH : Direction.SOUTH;
                        lowGunwales.add(new LadderBoardingPoint(hp.below(), hp, outward, true));
                        if (lowGunwales.size() >= 4) break;
                    }
                }
            }
        }

        deckCenterLocal = new BlockPos((minX + maxX) / 2, Math.min(maxY, localWaterlineY + 2), (minZ + maxZ) / 2);

        return new ShipScanResult(ladders, lowGunwales, waterlineHullLocal, deckCenterLocal);
    }

    private static boolean isSolidHull(BlockState state) {
        // Waterlogged slabs/stairs/fences are still hull; only pure fluid blocks must be excluded.
        return !state.isAir()
                && (state.getFluidState().isEmpty() || state.hasProperty(BlockStateProperties.WATERLOGGED))
                && !state.is(BlockTags.CLIMBABLE);
    }

    /**
     * The side a climbable block can be climbed from, or {@code null} if it is not a usable boarding ladder.
     * Vines store their attachment in boolean properties rather than FACING, and other climbables (scaffolding,
     * hanging vines) have no facing at all; those used to default to NORTH and create bogus boarding points.
     */
    private static Direction climbableFacing(BlockState state) {
        if (state.is(Blocks.VINE)) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (state.getValue(VineBlock.getPropertyForFace(d))) {
                    return d.getOpposite();
                }
            }
            return null;
        }
        if (state.is(Blocks.LADDER) || state.is(BlockTags.CLIMBABLE)) {
            return state.hasProperty(HorizontalDirectionalBlock.FACING)
                    ? state.getValue(HorizontalDirectionalBlock.FACING)
                    : null;
        }
        return null;
    }

    public static class ShipScanResult {
        public final List<LadderBoardingPoint> ladders;
        public final List<LadderBoardingPoint> lowGunwales;
        public final List<BlockPos> waterlineHullPerimeter;
        public final BlockPos deckCenterLocal;

        public ShipScanResult(List<LadderBoardingPoint> ladders, List<LadderBoardingPoint> lowGunwales,
                              List<BlockPos> waterlineHullPerimeter, BlockPos deckCenterLocal) {
            this.ladders = ladders;
            this.lowGunwales = lowGunwales;
            this.waterlineHullPerimeter = waterlineHullPerimeter;
            this.deckCenterLocal = deckCenterLocal;
        }
    }
}
