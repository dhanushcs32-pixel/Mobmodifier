package com.serenemobmodifier.logic.ai;

import com.serenemobmodifier.logic.sable.LadderBoardingPoint;
import com.serenemobmodifier.logic.sable.SableBridge;
import com.serenemobmodifier.logic.sable.SableShipData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Advanced AI goal for boat-targeting mobs.
 *
 * <p>Directs aquatic and hostile mobs to hunt vanilla boats and actively intercept,
 * board, or ram assembled Sable / Serene Sailing sub-level ships:
 * <ul>
 *   <li><b>Priority A (Ladder Boarding):</b> If the ship has exterior ladders, vines,
 *       climbable blocks, or low gunwales, the mob intercepts the ladder base,
 *       hoists up the rungs at climbing velocity onto the deck, and prowls the deck
 *       to attack players and ship components.</li>
 *   <li><b>Priority B (Hull Attack &amp; Ramming):</b> When approaching or when no ladders
 *       are reachable, the mob swims to intercept the waterline hull planks, delivers
 *       melee attacks with wooden impact sounds every 20 ticks, and applies physical
 *       rocking impulses to the ship.</li>
 * </ul>
 */
public class AttackAndBoardShipGoal extends Goal {

    public enum Stage {
        IDLE,
        APPROACH_BOAT,
        APPROACH_SHIP_LADDER,
        CLIMBING_LADDER,
        ON_DECK,
        APPROACH_SHIP_HULL,
        ATTACKING_SHIP_HULL
    }

    protected final Mob mob;
    protected final double searchRadius;
    /** Whether this mob is configured as hostile to players (the rule's "Hostile to Players" toggle). */
    protected final boolean attackPlayers;

    protected Boat targetBoat;
    protected SableShipData targetShip;
    protected LadderBoardingPoint targetLadder;
    protected BlockPos targetHullLocal;

    protected Stage stage = Stage.IDLE;
    protected int attackCooldown = 0;
    protected int climbTicks = 0;
    protected int onDeckTicks = 0;

    /** Polls remaining before the next (expensive) target scan in {@link #canUse()}. */
    private int scanDelay = 0;

    public AttackAndBoardShipGoal(Mob mob, double searchRadius, boolean attackPlayers) {
        this.mob = mob;
        this.searchRadius = searchRadius;
        this.attackPlayers = attackPlayers;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // canUse() is polled every other tick for every idle mob, and the scans below (entity query over a
        // 2*radius cube, sub-level lookup) are expensive. Stagger them to roughly every 10 ticks per mob.
        if (this.scanDelay > 0) {
            this.scanDelay--;
            return false;
        }
        this.scanDelay = 4 + this.mob.getRandom().nextInt(3);

        // A nearby target that is not riding a boat belongs to the mob's normal combat AI
        if (shouldYieldToNearbyTarget()) {
            return false;
        }

        Vec3 mobPos = this.mob.position();

        // 1. Scan for assembled Sable ships if in ServerLevel
        SableShipData chosenShip = null;
        if (this.mob.level() instanceof ServerLevel serverLevel && SableBridge.isSableLoaded()) {
            List<SableShipData> nearbyShips = SableBridge.findNearbyShips(serverLevel, mobPos, this.searchRadius);
            if (!nearbyShips.isEmpty()) {
                // Priority: ships with players aboard, else closest ship
                double closestDistSq = Double.MAX_VALUE;
                for (SableShipData ship : nearbyShips) {
                    AABB bounds = ship.getWorldBounds();
                    boolean hasPlayers = false;
                    for (Player player : serverLevel.players()) {
                        if (bounds.inflate(2.0).contains(player.position())) {
                            hasPlayers = true;
                            break;
                        }
                    }
                    double dsq = bounds.distanceToSqr(mobPos);
                    if (hasPlayers) {
                        chosenShip = ship;
                        break;
                    } else if (dsq < closestDistSq) {
                        closestDistSq = dsq;
                        chosenShip = ship;
                    }
                }
            }
        }

        // 2. Scan for vanilla Boat entities
        Boat chosenBoat = null;
        // Use the same sphere as canContinueToUse(); boats in the corners of the inflated cube used to be
        // selected and then dropped immediately, making the goal start/stop every poll.
        final double radiusSq = this.searchRadius * this.searchRadius;
        List<Boat> nearbyBoats = this.mob.level().getEntitiesOfClass(
                Boat.class,
                this.mob.getBoundingBox().inflate(this.searchRadius),
                b -> b.isAlive() && !b.isRemoved() && this.mob.distanceToSqr(b) < radiusSq
        );
        if (!nearbyBoats.isEmpty()) {
            double closestBoatDistSq = Double.MAX_VALUE;
            for (Boat boat : nearbyBoats) {
                double dsq = this.mob.distanceToSqr(boat);
                if (!boat.getPassengers().isEmpty()) {
                    chosenBoat = boat;
                    break;
                } else if (dsq < closestBoatDistSq) {
                    closestBoatDistSq = dsq;
                    chosenBoat = boat;
                }
            }
        }

        // Select best target (prioritizing vehicles with players/passengers)
        if (chosenShip != null && chosenBoat != null) {
            if (chosenBoat.getPassengers().isEmpty()) {
                setupShipTarget(chosenShip);
                return true;
            } else {
                setupBoatTarget(chosenBoat);
                return true;
            }
        } else if (chosenShip != null) {
            setupShipTarget(chosenShip);
            return true;
        } else if (chosenBoat != null) {
            setupBoatTarget(chosenBoat);
            return true;
        }

        return false;
    }

    /**
     * True when the mob's current attack target is standing close by and is not in a boat. The mob's own
     * melee goal (lower priority than this one) should handle it instead of this goal pulling the mob away
     * to attack an empty boat or ship.
     */
    private boolean shouldYieldToNearbyTarget() {
        LivingEntity currentTarget = this.mob.getTarget();
        if (currentTarget == null) return false;
        boolean targetInBoat = currentTarget.getVehicle() instanceof Boat;
        boolean targetNearMob = this.mob.distanceToSqr(currentTarget) < 36.0;
        return !targetInBoat && targetNearMob;
    }

    private void setupBoatTarget(Boat boat) {
        this.targetBoat = boat;
        this.targetShip = null;
        this.targetLadder = null;
        this.targetHullLocal = null;
        this.stage = Stage.APPROACH_BOAT;
    }

    public static boolean canMobClimbLadders(Mob mob) {
        if (mob instanceof net.minecraft.world.entity.animal.Squid) return false;
        if (mob instanceof net.minecraft.world.entity.animal.WaterAnimal && !(mob instanceof net.minecraft.world.entity.animal.Dolphin) && !(mob instanceof net.minecraft.world.entity.animal.Turtle)) return false;
        if (mob instanceof net.minecraft.world.entity.animal.AbstractFish) return false;
        if (mob instanceof net.minecraft.world.entity.monster.Guardian) return false;
        return true;
    }

    private void setupShipTarget(SableShipData ship) {
        this.targetShip = ship;
        this.targetBoat = null;
        this.climbTicks = 0;
        this.onDeckTicks = 0;

        SableShipData.ShipScanResult scan = ship.getScan(this.mob.level().getGameTime());

        // Priority A: Check for ladder boarding points (only for land/climbing-capable mobs)
        LadderBoardingPoint bestLadder = null;
        if (canMobClimbLadders(this.mob)) {
            bestLadder = findClosestBoardingPoint(ship, scan.ladders);
            if (bestLadder == null) {
                // Check for low gunwales if no ladder is present
                bestLadder = findClosestBoardingPoint(ship, scan.lowGunwales);
            }
        }

        if (bestLadder != null) {
            this.targetLadder = bestLadder;
            this.targetHullLocal = null;
            this.stage = Stage.APPROACH_SHIP_LADDER;
        } else {
            // Priority B: Hull ramming / attack
            this.targetLadder = null;
            this.targetHullLocal = findClosestHullBlock(ship, scan.waterlineHullPerimeter);
            this.stage = Stage.APPROACH_SHIP_HULL;
        }
    }

    private LadderBoardingPoint findClosestBoardingPoint(SableShipData ship, List<LadderBoardingPoint> points) {
        if (points == null || points.isEmpty()) return null;
        LadderBoardingPoint closest = null;
        double bestDistSq = Double.MAX_VALUE;
        Vec3 mobPos = this.mob.position();
        for (LadderBoardingPoint pt : points) {
            Vec3 frontPos = pt.getFrontWorld(ship);
            double dsq = mobPos.distanceToSqr(frontPos);
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                closest = pt;
            }
        }
        return closest;
    }

    private BlockPos findClosestHullBlock(SableShipData ship, List<BlockPos> hullBlocks) {
        if (hullBlocks == null || hullBlocks.isEmpty()) return null;
        BlockPos closest = null;
        double bestDistSq = Double.MAX_VALUE;
        Vec3 mobPos = this.mob.position();
        for (BlockPos hp : hullBlocks) {
            Vec3 worldPos = ship.transformPosition(Vec3.atCenterOf(hp));
            double dsq = mobPos.distanceToSqr(worldPos);
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                closest = hp;
            }
        }
        return closest;
    }

    @Override
    public boolean canContinueToUse() {
        // Previously only canUse() checked for a nearby target, so once started the mob kept hammering an empty
        // boat while a player stood next to it. (On deck the goal sets the target itself, so don't yield there.)
        if (this.stage != Stage.CLIMBING_LADDER && this.stage != Stage.ON_DECK && shouldYieldToNearbyTarget()) {
            return false;
        }

        if (this.targetBoat != null) {
            return this.targetBoat.isAlive()
                    && !this.targetBoat.isRemoved()
                    && this.mob.distanceToSqr(this.targetBoat) < (this.searchRadius * this.searchRadius);
        }

        if (this.targetShip != null) {
            if (this.targetShip.isRemoved()) return false;
            double distSq = this.targetShip.getWorldBounds().distanceToSqr(this.mob.position());
            return distSq < (this.searchRadius * this.searchRadius);
        }

        return false;
    }

    /** True if this goal instance was built with the given settings (used to detect outdated goals). */
    public boolean hasSettings(double searchRadius, boolean attackPlayers) {
        return this.searchRadius == searchRadius && this.attackPlayers == attackPlayers;
    }

    /**
     * Without this, Mob#serverAiStep only ticks running goals on every second game tick (the parity depends
     * on the mob's id and spawn tick). The goal relies on {@code tickCount % 10/20/30} checks for navigation
     * and target refresh, which never fire for mobs whose polls land on odd tickCounts, and its "20 tick"
     * attack cooldown effectively lasted 40 ticks.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.attackCooldown = 0;
        this.climbTicks = 0;
        this.onDeckTicks = 0;
        if (this.mob.isInWater()) {
            this.mob.setSwimming(true);
        }
    }

    @Override
    public void tick() {
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }

        // Dispatch based on active target and current stage
        if (this.targetBoat != null) {
            tickVanillaBoat();
        } else if (this.targetShip != null) {
            tickSableShip();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Vanilla Boat Pursuit & Attack
    // ─────────────────────────────────────────────────────────────────────────────

    private void tickVanillaBoat() {
        if (this.targetBoat == null) return;

        double dx = this.targetBoat.getX() - this.mob.getX();
        double dy = (this.targetBoat.getY() - 0.25) - this.mob.getY();
        double dz = this.targetBoat.getZ() - this.mob.getZ();
        double distHorizontalSq = dx * dx + dz * dz;
        double distTotalSq = distHorizontalSq + (dy * dy);

        alignLookAndYaw(dx, dz, distHorizontalSq, new Vec3(this.targetBoat.getX(), this.targetBoat.getY(), this.targetBoat.getZ()));

        double reach = (this.mob.getBbWidth() * 0.5) + (this.targetBoat.getBbWidth() * 0.5) + 1.2;
        if (distTotalSq <= (reach * reach)) {
            if (this.mob.isInWater()) {
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().scale(0.8));
            } else {
                this.mob.getNavigation().stop();
            }
            if (this.attackCooldown <= 0) {
                performVanillaBoatAttack();
            }
        } else {
            if (this.mob.isInWater()) {
                applyAquaticSwimVelocity(dx, dy, dz, distTotalSq, this.targetBoat.getY() - 0.35);
            } else {
                if (this.mob.tickCount % 10 == 0) {
                    this.mob.getNavigation().moveTo(this.targetBoat.getX(), this.targetBoat.getY(), this.targetBoat.getZ(), 1.25);
                }
            }
        }
    }

    private void performVanillaBoatAttack() {
        this.attackCooldown = 20;
        this.mob.swing(InteractionHand.MAIN_HAND);
        this.mob.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 0.8F);
        this.mob.playSound(SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 0.7F, 1.1F);

        float damage = (float) this.mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (damage <= 0.0f) damage = 4.0f;

        this.targetBoat.hurt(this.mob.damageSources().mobAttack(this.mob), damage);

        // Only hit passengers this mob is actually hostile to: players (when the rule makes it hostile to
        // players) or its current attack target. Villagers, pets and other mobs riding the boat are left alone.
        for (Entity passenger : this.targetBoat.getPassengers()) {
            if (!(passenger instanceof LivingEntity livingPassenger)) continue;
            boolean isHostileTarget = livingPassenger == this.mob.getTarget()
                    || (this.attackPlayers && livingPassenger instanceof Player);
            if (!isHostileTarget) continue;

            livingPassenger.hurt(this.mob.damageSources().mobAttack(this.mob), damage);
            livingPassenger.knockback(0.4, this.mob.getX() - livingPassenger.getX(), this.mob.getZ() - livingPassenger.getZ());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Sable Assembled Ship Pursuit, Boarding & Ramming
    // ─────────────────────────────────────────────────────────────────────────────

    private void tickSableShip() {
        if (this.targetShip == null || this.targetShip.isRemoved()) return;

        // Periodic target verification / refresh (every 30 ticks)
        if (this.mob.tickCount % 30 == 0 && this.stage != Stage.CLIMBING_LADDER && this.stage != Stage.ON_DECK) {
            SableShipData.ShipScanResult scan = this.targetShip.getScan(this.mob.level().getGameTime());
            LadderBoardingPoint ladder = null;
            if (canMobClimbLadders(this.mob)) {
                ladder = findClosestBoardingPoint(this.targetShip, scan.ladders);
                if (ladder == null) {
                    ladder = findClosestBoardingPoint(this.targetShip, scan.lowGunwales);
                }
            }
            if (ladder != null) {
                this.targetLadder = ladder;
                this.stage = Stage.APPROACH_SHIP_LADDER;
            } else if (this.targetHullLocal == null) {
                this.targetHullLocal = findClosestHullBlock(this.targetShip, scan.waterlineHullPerimeter);
                this.stage = Stage.APPROACH_SHIP_HULL;
            }
        }

        switch (this.stage) {
            case APPROACH_SHIP_LADDER -> tickApproachShipLadder();
            case CLIMBING_LADDER     -> tickClimbingLadder();
            case ON_DECK             -> tickOnDeck();
            case APPROACH_SHIP_HULL, ATTACKING_SHIP_HULL -> tickApproachOrAttackHull();
            default -> {}
        }
    }

    /**
     * Priority A1: Navigate/swim toward the base of the ship's boarding ladder.
     */
    private void tickApproachShipLadder() {
        if (this.targetLadder == null || !canMobClimbLadders(this.mob)) {
            this.stage = Stage.APPROACH_SHIP_HULL;
            return;
        }

        Vec3 frontWorld = this.targetLadder.getFrontWorld(this.targetShip);
        Vec3 bottomWorld = this.targetLadder.getBottomWorld(this.targetShip);

        double dx = frontWorld.x - this.mob.getX();
        double dy = (frontWorld.y - 0.2) - this.mob.getY();
        double dz = frontWorld.z - this.mob.getZ();
        double distHorizontalSq = dx * dx + dz * dz;
        double distTotalSq = distHorizontalSq + (dy * dy);

        alignLookAndYaw(dx, dz, distHorizontalSq, frontWorld);

        if (this.mob.isInWater()) {
            applyAquaticSwimVelocity(dx, dy, dz, distTotalSq, frontWorld.y - 0.1);
        } else {
            if (this.mob.tickCount % 10 == 0) {
                this.mob.getNavigation().moveTo(frontWorld.x, frontWorld.y, frontWorld.z, 1.25);
            }
        }

        // Distance check to ladder base (1.75 blocks)
        double distToFront = this.mob.position().distanceTo(frontWorld);
        double distToBottom = this.mob.position().distanceTo(bottomWorld);
        if (Math.min(distToFront, distToBottom) <= 1.75) {
            this.stage = Stage.CLIMBING_LADDER;
            this.climbTicks = 0;
            this.mob.playSound(SoundEvents.LADDER_STEP, 1.0F, 1.0F);
        }
    }

    /**
     * Priority A2: Smoothly hoist the mob up the ladder rungs onto the ship's deck.
     */
    private void tickClimbingLadder() {
        if (this.targetLadder == null || this.targetShip == null || !canMobClimbLadders(this.mob)) {
            this.stage = Stage.APPROACH_SHIP_HULL;
            return;
        }

        this.climbTicks++;

        Vec3 bottomWorld = this.targetLadder.getBottomWorld(this.targetShip);
        Vec3 topWorld = this.targetLadder.getTopWorld(this.targetShip);
        double deckWorldY = topWorld.y + 0.5;

        // 1. Face the ladder rungs
        double dx = bottomWorld.x - this.mob.getX();
        double dz = bottomWorld.z - this.mob.getZ();
        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.mob.setYRot(targetYaw);
        this.mob.yBodyRot = targetYaw;
        this.mob.yHeadRot = targetYaw;
        this.mob.getLookControl().setLookAt(bottomWorld.x, this.mob.getEyeY(), bottomWorld.z, 30.0F, 30.0F);

        // 2. Set upward climbing velocity (0.22 blocks/tick) and reset fall distance
        double vx = Mth.clamp(dx * 0.25, -0.15, 0.15);
        double vz = Mth.clamp(dz * 0.25, -0.15, 0.15);
        this.mob.setDeltaMovement(vx, 0.22, vz);
        this.mob.resetFallDistance();
        this.mob.fallDistance = 0.0F;

        if (this.mob.isInWater()) {
            this.mob.setSwimming(false);
        }

        if (this.climbTicks % 6 == 0) {
            this.mob.playSound(SoundEvents.LADDER_STEP, 0.8F, 1.0F);
        }

        // 3. Check if mob reached deck level (topOfLadderWorldY + 0.5)
        if (this.mob.getY() >= (deckWorldY - 0.25) || this.climbTicks > 120) {
            Vec3 stepWorld = this.targetLadder.getDeckStepWorld(this.targetShip);
            double fwdX = stepWorld.x - this.mob.getX();
            double fwdZ = stepWorld.z - this.mob.getZ();

            // Smooth step forward onto deck
            this.mob.setDeltaMovement(fwdX * 0.35, 0.18, fwdZ * 0.35);
            this.mob.setPos(this.mob.getX() + fwdX * 0.2, deckWorldY, this.mob.getZ() + fwdZ * 0.2);
            this.mob.resetFallDistance();
            this.mob.fallDistance = 0.0F;

            this.stage = Stage.ON_DECK;
            this.onDeckTicks = 0;
            this.mob.playSound(SoundEvents.WOOD_STEP, 1.0F, 1.0F);
        }
    }

    /**
     * Priority A3: Mob is on deck; prowl the moving deck and hunt players or ship components.
     */
    private void tickOnDeck() {
        this.onDeckTicks++;

        // If mob fell off into ocean water below ship bounds, reset to re-approach
        if (this.mob.isInWater() && this.mob.getY() < (this.targetShip.getWorldBounds().minY + 1.2)) {
            this.stage = Stage.APPROACH_SHIP_LADDER;
            return;
        }

        // Hunt nearby players on or near the vessel (only if the rule makes this mob hostile to players, and
        // never creative/spectator players, which could not be hurt and would trap the mob in a pointless chase)
        Player nearestPlayer = null;
        if (this.attackPlayers) {
            nearestPlayer = this.mob.level().getNearestPlayer(
                    this.mob.getX(), this.mob.getY(), this.mob.getZ(), 32.0, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
        }
        if (nearestPlayer != null) {
            this.mob.setTarget(nearestPlayer);
            if (this.mob.tickCount % 10 == 0) {
                this.mob.getNavigation().moveTo(nearestPlayer, 1.25);
            }

            // This goal holds MOVE|LOOK at priority 1, so the mob's own (lower priority) melee goal cannot run
            // while we are active. Deliver the melee attack here, otherwise boarded mobs never hurt anyone.
            double reach = this.mob.getBbWidth() * 2.0F;
            double reachSqr = reach * reach + nearestPlayer.getBbWidth();
            if (this.attackCooldown <= 0 && this.mob.distanceToSqr(nearestPlayer) <= reachSqr) {
                this.attackCooldown = 20;
                this.mob.swing(InteractionHand.MAIN_HAND);
                this.mob.doHurtTarget(nearestPlayer);
            }
        } else {
            // Pathfind across deck toward the ship's deck center or helm
            Vec3 deckCenter = this.targetShip.getDeckCenterWorld();
            if (this.mob.tickCount % 20 == 0) {
                this.mob.getNavigation().moveTo(deckCenter.x, deckCenter.y, deckCenter.z, 1.1);
            }
        }
    }

    /**
     * Priority B: Intercept and ram the waterline hull planks.
     */
    private void tickApproachOrAttackHull() {
        if (this.targetHullLocal == null) {
            SableShipData.ShipScanResult scan = this.targetShip.getScan(this.mob.level().getGameTime());
            this.targetHullLocal = findClosestHullBlock(this.targetShip, scan.waterlineHullPerimeter);
            if (this.targetHullLocal == null) return;
        }

        Vec3 hullWorld = this.targetShip.transformPosition(Vec3.atCenterOf(this.targetHullLocal));

        double dx = hullWorld.x - this.mob.getX();
        double dy = (hullWorld.y - 0.2) - this.mob.getY();
        double dz = hullWorld.z - this.mob.getZ();
        double distHorizontalSq = dx * dx + dz * dz;
        double distTotalSq = distHorizontalSq + (dy * dy);

        alignLookAndYaw(dx, dz, distHorizontalSq, hullWorld);

        // Melee reach check scaled with mob bounding box width
        double reach = (this.mob.getBbWidth() * 0.5) + 1.8;
        double distToHull = this.mob.position().distanceTo(hullWorld);

        if (distToHull <= Math.max(reach, 2.5)) {
            this.stage = Stage.ATTACKING_SHIP_HULL;
            if (this.mob.isInWater()) {
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().scale(0.8));
            } else {
                this.mob.getNavigation().stop();
            }
            if (this.attackCooldown <= 0) {
                performShipHullAttack(hullWorld);
            }
        } else {
            this.stage = Stage.APPROACH_SHIP_HULL;
            if (this.mob.isInWater()) {
                applyAquaticSwimVelocity(dx, dy, dz, distTotalSq, hullWorld.y - 0.2);
            } else {
                if (this.mob.tickCount % 10 == 0) {
                    this.mob.getNavigation().moveTo(hullWorld.x, hullWorld.y, hullWorld.z, 1.25);
                }
            }
        }
    }

    private void performShipHullAttack(Vec3 hitPos) {
        this.attackCooldown = 20; // 20 ticks = 1 attack per second
        this.mob.swing(InteractionHand.MAIN_HAND);

        // Play authentic wooden hull impact sounds
        this.mob.playSound(SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 1.0F, 0.9F + (this.mob.getRandom().nextFloat() * 0.2F));
        this.mob.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 0.8F, 0.8F);

        float damage = (float) this.mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (damage <= 0.0f) damage = 4.0f;

        // Apply physical knock and rocking impulse to the assembled ship contraption
        // Sable expects a plot-local position and a local-frame impulse, not world-space values.
        if (this.targetHullLocal != null) {
            Vec3 dir = hitPos.subtract(this.mob.position()).normalize();
            double impulseMag = Math.max(damage * 350.0, 1200.0);
            Vec3 worldImpulse = new Vec3(dir.x * impulseMag, 300.0, dir.z * impulseMag);
            Vec3 localImpulse = this.targetShip.transformNormalInverse(worldImpulse);
            if (localImpulse != null) {
                SableBridge.applyImpulseAtPoint(this.targetShip.subLevel(), Vec3.atCenterOf(this.targetHullLocal), localImpulse);
            }
        }

        // Damage any players within close reach of the hull impact (scaled with mob width)
        double impactRadius = Math.max(2.5, this.mob.getBbWidth() * 0.75);
        AABB impactZone = new AABB(hitPos.x - impactRadius, hitPos.y - 2.0, hitPos.z - impactRadius,
                hitPos.x + impactRadius, hitPos.y + 2.5, hitPos.z + impactRadius);
        if (this.attackPlayers) {
            List<Player> nearbyPlayers = this.mob.level().getEntitiesOfClass(
                    Player.class, impactZone, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
            for (Player p : nearbyPlayers) {
                p.hurt(this.mob.damageSources().mobAttack(this.mob), damage);
                p.knockback(0.35, this.mob.getX() - p.getX(), this.mob.getZ() - p.getZ());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Movement & Look Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private void alignLookAndYaw(double dx, double dz, double distHorizontalSq, Vec3 targetPos) {
        if (distHorizontalSq > 0.05) {
            float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            this.mob.setYRot(targetYaw);
            this.mob.yBodyRot = targetYaw;
            this.mob.yHeadRot = targetYaw;
        }
        this.mob.getLookControl().setLookAt(targetPos.x, targetPos.y, targetPos.z, 30.0F, 30.0F);
    }

    private void applyAquaticSwimVelocity(double dx, double dy, double dz, double distTotalSq, double clampMaxY) {
        if (!this.mob.isInWater()) {
            return;
        }

        if (this.mob.canBreatheUnderwater() || this.mob.isSwimming()) {
            this.mob.setSwimming(true);
        }

        if (distTotalSq > 0.4) {
            double speedAttr = this.mob.getAttribute(Attributes.MOVEMENT_SPEED) != null
                    ? this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    : 0.25;
            double swimSpeed = Math.max(speedAttr * 1.6, 0.38);

            double dist = Math.sqrt(distTotalSq);
            double dirX = dx / dist;
            double dirY = dy / dist;
            double dirZ = dz / dist;

            // Clamp upward swim velocity near waterline to prevent dolphin-hopping above water
            if (this.mob.getY() >= clampMaxY && dirY > 0) {
                dirY = 0.0;
            }

            Vec3 currentMotion = this.mob.getDeltaMovement();
            double targetVx = dirX * swimSpeed;
            double targetVy = dirY * swimSpeed;
            double targetVz = dirZ * swimSpeed;

            // When mob is at/above waterline and descending, do NOT lerp targetVy=0 which halts gravity
            double newVy;
            if (this.mob.getY() >= clampMaxY && currentMotion.y <= 0) {
                newVy = currentMotion.y; // Preserve natural downward gravity in wave zones
            } else {
                newVy = currentMotion.y + (targetVy - currentMotion.y) * 0.35;
            }

            // Smooth velocity integration
            this.mob.setDeltaMovement(
                    currentMotion.x + (targetVx - currentMotion.x) * 0.35,
                    newVy,
                    currentMotion.z + (targetVz - currentMotion.z) * 0.35
            );
        }
    }

    @Override
    public void stop() {
        this.targetBoat = null;
        this.targetShip = null;
        this.targetLadder = null;
        this.targetHullLocal = null;
        this.stage = Stage.IDLE;
        this.mob.getNavigation().stop();
    }
}
