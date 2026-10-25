package com.serenemobmodifier.logic;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

public class MobModifierApplier {

    public static boolean applyMatchingRules(LivingEntity entity) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String fullId = entityId.toString();
        String namespace = entityId.getNamespace();

        List rules = MobModifierConfig.MODIFIER_RULES.get();
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        for (Object ruleObj : rules) {
            String ruleStr = String.valueOf(ruleObj);
            String[] tokens = ruleStr.split("\\|");
            if (tokens.length < 8) continue;

            String pattern = tokens[0].trim();
            boolean match = false;

            if (pattern.endsWith(":*")) {
                String targetNamespace = pattern.substring(0, pattern.indexOf(":*"));
                match = namespace.equalsIgnoreCase(targetNamespace);
            } else {
                match = fullId.equalsIgnoreCase(pattern);
            }

            if (match) {
                try {
                    double scaleMult = Double.parseDouble(tokens[1].trim());
                    double healthMult = Double.parseDouble(tokens[2].trim());
                    double damageMult = Double.parseDouble(tokens[3].trim());
                    double speedMult = Double.parseDouble(tokens[4].trim());
                    double followRange = Double.parseDouble(tokens[5].trim());
                    boolean hostileToPlayers = Boolean.parseBoolean(tokens[6].trim());
                    boolean targetBoats = Boolean.parseBoolean(tokens[7].trim());

                    applyModifiers(entity, scaleMult, healthMult, damageMult, speedMult, followRange, hostileToPlayers, targetBoats);
                    return true;
                } catch (Exception e) {
                    SereneMobModifier.LOGGER.error("[SereneMobModifier] Error parsing rule: {}", ruleStr, e);
                }
            }
        }
        return false;
    }

    private static final ResourceLocation SCALE_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "scale");
    private static final ResourceLocation HEALTH_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "health");
    private static final ResourceLocation DAMAGE_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "attack_damage");
    private static final ResourceLocation SPEED_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "movement_speed");
    private static final ResourceLocation FOLLOW_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "follow_range");

    private static void applyModifiers(LivingEntity entity, double scaleMult, double healthMult,
                                       double damageMult, double speedMult, double followRange,
                                       boolean hostileToPlayers, boolean targetBoats) {

        // 1. Native Minecraft 1.21.1 Generic Scale Attribute Modifier
        AttributeInstance scaleAttr = entity.getAttribute(Attributes.SCALE);
        if (scaleAttr != null && scaleMult > 0.0) {
            scaleAttr.removeModifier(SCALE_MOD_ID);
            if (Math.abs(scaleMult - 1.0) > 0.0001) {
                scaleAttr.addPermanentModifier(new AttributeModifier(SCALE_MOD_ID, scaleMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }

        // 2. Max Health & Proportional Healing Attribute Modifier
        AttributeInstance healthAttr = entity.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null && healthMult > 0.0) {
            healthAttr.removeModifier(HEALTH_MOD_ID);
            if (Math.abs(healthMult - 1.0) > 0.0001) {
                healthAttr.addPermanentModifier(new AttributeModifier(HEALTH_MOD_ID, healthMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            entity.setHealth(entity.getMaxHealth());
        }

        // 3. Attack Damage Attribute Modifier
        AttributeInstance damageAttr = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damageAttr != null && damageMult > 0.0) {
            damageAttr.removeModifier(DAMAGE_MOD_ID);
            if (Math.abs(damageMult - 1.0) > 0.0001) {
                damageAttr.addPermanentModifier(new AttributeModifier(DAMAGE_MOD_ID, damageMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }

        // 4. Movement Speed Attribute Modifier
        AttributeInstance speedAttr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null && speedMult > 0.0) {
            speedAttr.removeModifier(SPEED_MOD_ID);
            if (Math.abs(speedMult - 1.0) > 0.0001) {
                speedAttr.addPermanentModifier(new AttributeModifier(SPEED_MOD_ID, speedMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }

        // 5. Follow Range Attribute Modifier
        AttributeInstance followAttr = entity.getAttribute(Attributes.FOLLOW_RANGE);
        if (followAttr != null && followRange > 0.0) {
            followAttr.removeModifier(FOLLOW_MOD_ID);
            double diff = followRange - followAttr.getBaseValue();
            if (diff > 0.0) {
                followAttr.addPermanentModifier(new AttributeModifier(FOLLOW_MOD_ID, diff, AttributeModifier.Operation.ADD_VALUE));
            }
        }

        // 6. Dynamic AI Targeting Goals
        if (entity instanceof Mob mob) {
            if (hostileToPlayers) {
                mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Player.class, true));
            }
            if (targetBoats) {
                mob.goalSelector.addGoal(1, new TargetBoatGoal(mob, Math.max(followRange, 32.0)));
            }
        }
    }

    /**
     * AI Goal directing aquatic predators to hunt, ram, and attack boats and their occupants.
     */
    public static class TargetBoatGoal extends Goal {
        private final Mob mob;
        private final double searchRadius;
        private Boat targetBoat;
        private int attackCooldown = 0;

        public TargetBoatGoal(Mob mob, double searchRadius) {
            this.mob = mob;
            this.searchRadius = searchRadius;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity currentTarget = this.mob.getTarget();
            if (currentTarget != null && !(currentTarget.getVehicle() instanceof Boat)) {
                return false;
            }

            Boat chosen = null;
            List nearbyEntities = this.mob.level().getEntities(
                    this.mob,
                    this.mob.getBoundingBox().inflate(this.searchRadius)
            );

            if (nearbyEntities != null && !nearbyEntities.isEmpty()) {
                for (Object obj : nearbyEntities) {
                    if (obj instanceof Boat boat && boat.isAlive() && !boat.isRemoved()) {
                        if (!boat.getPassengers().isEmpty()) {
                            chosen = boat;
                            break;
                        } else if (chosen == null) {
                            chosen = boat;
                        }
                    }
                }
            }

            this.targetBoat = chosen;
            return this.targetBoat != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.targetBoat != null
                    && this.targetBoat.isAlive()
                    && !this.targetBoat.isRemoved()
                    && this.mob.distanceToSqr(this.targetBoat) < (this.searchRadius * this.searchRadius);
        }

        @Override
        public void start() {
            this.attackCooldown = 0;
            if (this.mob.isInWater()) {
                this.mob.setSwimming(true);
            }
        }

        @Override
        public void tick() {
            if (this.targetBoat == null) return;

            if (this.attackCooldown > 0) {
                this.attackCooldown--;
            }

            double dx = this.targetBoat.getX() - this.mob.getX();
            double dy = (this.targetBoat.getY() - 0.25) - this.mob.getY();
            double dz = this.targetBoat.getZ() - this.mob.getZ();
            double distHorizontalSq = dx * dx + dz * dz;
            double distTotalSq = distHorizontalSq + (dy * dy);

            // 1. Align Look & Body Rotations (avoid spinning when directly underneath)
            if (distHorizontalSq > 0.05) {
                float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                this.mob.setYRot(targetYaw);
                this.mob.yBodyRot = targetYaw;
                this.mob.yHeadRot = targetYaw;
            }
            this.mob.getLookControl().setLookAt(this.targetBoat, 30.0F, 30.0F);

            // 2. Underwater Propulsion vs Land Navigation
            if (this.mob.isInWater()) {
                this.mob.setSwimming(true);

                if (distTotalSq > 0.5) {
                    double speedAttr = this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
                    double swimSpeed = Math.max(speedAttr * 1.5, 0.28);

                    double dist = Math.sqrt(distTotalSq);
                    double dirX = dx / dist;
                    double dirY = dy / dist;
                    double dirZ = dz / dist;

                    // Clamp upward velocity at the waterline so the mob doesn't shoot into the air
                    if (this.mob.getY() >= this.targetBoat.getY() - 0.35 && dirY > 0) {
                        dirY = 0.0;
                    }

                    Vec3 currentMotion = this.mob.getDeltaMovement();
                    double targetVx = dirX * swimSpeed;
                    double targetVy = dirY * swimSpeed;
                    double targetVz = dirZ * swimSpeed;

                    // Smooth velocity integration (0.3 lerp factor per tick)
                    this.mob.setDeltaMovement(
                            currentMotion.x + (targetVx - currentMotion.x) * 0.3,
                            currentMotion.y + (targetVy - currentMotion.y) * 0.3,
                            currentMotion.z + (targetVz - currentMotion.z) * 0.3
                    );
                }
            } else {
                // Land navigation
                if (this.mob.tickCount % 10 == 0) {
                    this.mob.getNavigation().moveTo(
                            this.targetBoat.getX(),
                            this.targetBoat.getY(),
                            this.targetBoat.getZ(),
                            1.25
                    );
                }
            }

            // 3. Attack Check
            double reach = (this.mob.getBbWidth() * 1.5) + (this.targetBoat.getBbWidth() / 2.0) + 0.8;
            if (distTotalSq <= (reach * reach) && this.attackCooldown <= 0) {
                performAttack();
            }
        }

        private void performAttack() {
            this.attackCooldown = 20;
            this.mob.swing(InteractionHand.MAIN_HAND);
            this.mob.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 0.8F);

            float damage = (float) this.mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
            if (damage <= 0.0f) damage = 4.0f;

            // Damage the boat hull
            this.targetBoat.hurt(this.mob.damageSources().mobAttack(this.mob), damage);

            // Damage and knock back passengers
            for (Entity passenger : this.targetBoat.getPassengers()) {
                if (passenger instanceof LivingEntity livingPassenger) {
                    livingPassenger.hurt(this.mob.damageSources().mobAttack(this.mob), damage);
                    livingPassenger.knockback(0.4, this.mob.getX() - livingPassenger.getX(), this.mob.getZ() - livingPassenger.getZ());
                }
            }
        }

        @Override
        public void stop() {
            this.targetBoat = null;
            this.mob.getNavigation().stop();
        }
    }
}