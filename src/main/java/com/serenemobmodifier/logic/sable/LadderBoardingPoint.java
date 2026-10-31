package com.serenemobmodifier.logic.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Encapsulates a ship boarding point in local sub-level space and computes
 * world-space coordinates dynamically using the ship's current logical pose.
 */
public class LadderBoardingPoint {
    public final BlockPos bottomLocal;
    public final BlockPos topLocal;
    public final Direction facing;
    public final boolean isLowGunwale;

    public LadderBoardingPoint(BlockPos bottomLocal, BlockPos topLocal, Direction facing, boolean isLowGunwale) {
        this.bottomLocal = bottomLocal;
        this.topLocal = topLocal;
        this.facing = facing != null ? facing : Direction.NORTH;
        this.isLowGunwale = isLowGunwale;
    }

    /**
     * World position at the bottom rung/base of the climbable surface.
     */
    public Vec3 getBottomWorld(SableShipData ship) {
        return ship.transformPosition(Vec3.atCenterOf(this.bottomLocal));
    }

    /**
     * World position directly in front of the bottom of the ladder
     * where an aquatic or ground mob can stand to begin climbing.
     */
    public Vec3 getFrontWorld(SableShipData ship) {
        BlockPos frontPos = this.bottomLocal.relative(this.facing);
        return ship.transformPosition(Vec3.atCenterOf(frontPos));
    }

    /**
     * World position at the top rung of the ladder near the ship's gunwale/deck.
     */
    public Vec3 getTopWorld(SableShipData ship) {
        return ship.transformPosition(Vec3.atCenterOf(this.topLocal));
    }

    /**
     * World position on the deck surface behind the top of the ladder
     * where the mob steps onto once climbing completes.
     */
    public Vec3 getDeckStepWorld(SableShipData ship) {
        BlockPos stepPos = this.topLocal.relative(this.facing.getOpposite());
        return ship.transformPosition(Vec3.atCenterOf(stepPos));
    }
}
