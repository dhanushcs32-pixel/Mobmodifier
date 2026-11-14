package com.serenemobmodifier.logic;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.logic.ai.AttackAndBoardShipGoal;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MobModifierApplier {

    public static final ResourceLocation SCALE_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "scale");
    public static final ResourceLocation HEALTH_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "health");
    public static final ResourceLocation DAMAGE_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "attack_damage");
    public static final ResourceLocation SPEED_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "movement_speed");
    public static final ResourceLocation FOLLOW_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "follow_range");
    public static final ResourceLocation KNOCKBACK_MOD_ID = ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "knockback_resistance");

    public static final String NBT_HEALTH_INITIALIZED = "SereneMobHealthInitialized";

    /** Invalid rule strings already reported, so a bad rule is logged once instead of on every entity join. */
    private static final java.util.Set<String> REPORTED_BAD_RULES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void reportBadRule(String rule, String reason) {
        if (REPORTED_BAD_RULES.add(rule)) {
            SereneMobModifier.LOGGER.error("[SereneMobModifier] Ignoring invalid rule '{}': {}", rule, reason);
        }
    }

    public static class SereneAttackPlayerGoal extends NearestAttackableTargetGoal<Player> {
        public SereneAttackPlayerGoal(Mob mob) {
            super(mob, Player.class, true);
        }
    }

    /**
     * Basic melee behaviour for mobs that were made hostile but have no attack AI of their own (cows, sheep,
     * fish, ...). Without it they would acquire a player as target and then just stand there.
     * Overrides getAttackReachSqr to properly scale attack reach with the mob's bounding box width.
     */
    public static class SereneMeleeGoal extends MeleeAttackGoal {
        public SereneMeleeGoal(PathfinderMob mob) {
            super(mob, 1.2D, true);
        }

        @Override
        protected boolean canPerformAttack(LivingEntity attackTarget) {
            if (!this.isTimeToAttack()) return false;
            if (this.mob.isWithinMeleeAttackRange(attackTarget)) return true;
            double reach = (this.mob.getBbWidth() * 0.5) + (attackTarget.getBbWidth() * 0.5) + 0.8;
            return this.mob.distanceToSqr(attackTarget) <= (reach * reach)
                    && this.mob.getSensing().hasLineOfSight(attackTarget);
        }
    }

    private static boolean hasOwnAttackGoal(Mob mob) {
        return mob.goalSelector.getAvailableGoals().stream()
                .map(WrappedGoal::getGoal)
                .anyMatch(g -> !(g instanceof SereneMeleeGoal)
                        && !(g instanceof AttackAndBoardShipGoal)
                        && (g instanceof MeleeAttackGoal || g.getClass().getSimpleName().contains("Attack")));
    }

    public static boolean applyMatchingRules(LivingEntity entity) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String fullId = entityId.toString();
        String namespace = entityId.getNamespace();

        List<?> rules = MobModifierConfig.MODIFIER_RULES.get();
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        // Two passes so that an exact entity-id rule always beats a namespace wildcard,
        // regardless of where each sits in the config list (the GUI appends new rules at the end).
        for (int pass = 0; pass < 2; pass++) {
            boolean wildcardPass = pass == 1;
            for (Object ruleObj : rules) {
                String ruleStr = String.valueOf(ruleObj);
                String[] tokens = ruleStr.split("\\|");
                if (tokens.length < 8) {
                    if (pass == 0) reportBadRule(ruleStr, "expected at least 8 '|'-separated fields");
                    continue;
                }

                String pattern = tokens[0].trim();
                boolean isWildcard = pattern.endsWith(":*");
                if (isWildcard != wildcardPass) continue;

                boolean match;
                if (isWildcard) {
                    String targetNamespace = pattern.substring(0, pattern.length() - 2);
                    match = namespace.equalsIgnoreCase(targetNamespace);
                } else {
                    match = fullId.equalsIgnoreCase(pattern);
                }
                if (!match) continue;

                try {
                    double scaleMult = Double.parseDouble(tokens[1].trim());
                    double healthMult = Double.parseDouble(tokens[2].trim());
                    double damageMult = Double.parseDouble(tokens[3].trim());
                    double speedMult = Double.parseDouble(tokens[4].trim());
                    double followRange = Double.parseDouble(tokens[5].trim());
                    boolean hostileToPlayers = Boolean.parseBoolean(tokens[6].trim());
                    boolean targetBoats = Boolean.parseBoolean(tokens[7].trim());

                    double knockbackVal = 0.0;
                    if (tokens.length >= 9) {
                        knockbackVal = Double.parseDouble(tokens[8].trim());
                    } else if (scaleMult > 1.0) {
                        knockbackVal = Math.min(1.0, (scaleMult - 1.0) * 0.35);
                    }

                    applyModifiers(entity, scaleMult, healthMult, damageMult, speedMult, followRange, knockbackVal, hostileToPlayers, targetBoats);
                    return true;
                } catch (Exception e) {
                    reportBadRule(ruleStr, e.toString());
                }
            }
        }
        return false;
    }

    /**
     * Brings an entity in line with the current config: applies the matching rule if modifications are
     * enabled and a rule matches, otherwise strips everything this mod previously added.
     *
     * <p>Attribute modifiers are permanent and saved with the entity, so without the cleanup path deleting
     * a rule (or disabling {@code globalEnable}) would leave already-modified mobs changed forever.</p>
     *
     * @return {@code true} if a rule was applied
     */
    public static boolean refreshEntity(LivingEntity entity) {
        if (MobModifierConfig.GLOBAL_ENABLE.get() && applyMatchingRules(entity)) {
            return true;
        }
        clearModifiers(entity);
        return false;
    }

    /**
     * Removes every attribute modifier, persisted flag and AI goal this mod added to the entity.
     */
    public static void clearModifiers(LivingEntity entity) {
        removeModifier(entity, Attributes.SCALE, SCALE_MOD_ID);
        removeModifier(entity, Attributes.MAX_HEALTH, HEALTH_MOD_ID);
        removeModifier(entity, Attributes.ATTACK_DAMAGE, DAMAGE_MOD_ID);
        removeModifier(entity, Attributes.MOVEMENT_SPEED, SPEED_MOD_ID);
        removeModifier(entity, Attributes.FOLLOW_RANGE, FOLLOW_MOD_ID);
        removeModifier(entity, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_MOD_ID);

        // Force immediate bounding box refresh and sync cleared scale to clients
        entity.refreshDimensions();
        syncScaleToTrackingClients(entity);

        if (entity.getPersistentData().contains(NBT_HEALTH_INITIALIZED)) {
            entity.getPersistentData().remove(NBT_HEALTH_INITIALIZED);
        }
        if (entity instanceof Mob mob) {
            applyGoals(mob, false, false, 0.0);
        }
    }

    private static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && instance.hasModifier(id)) {
            instance.removeModifier(id);
        }
    }

    private static void applyModifiers(LivingEntity entity, double scaleMult, double healthMult,
                                       double damageMult, double speedMult, double followRange,
                                       double knockbackVal, boolean hostileToPlayers, boolean targetBoats) {

        // Capture stats before modification for verification logging
        double beforeHp = entity.getMaxHealth();
        double beforeDmg = getAttrValue(entity, Attributes.ATTACK_DAMAGE, 0.0);
        double beforeSpeed = getAttrValue(entity, Attributes.MOVEMENT_SPEED, 0.0);
        double beforeScale = getAttrValue(entity, Attributes.SCALE, 1.0);
        double beforeFollow = getAttrValue(entity, Attributes.FOLLOW_RANGE, 0.0);
        double beforeKb = getAttrValue(entity, Attributes.KNOCKBACK_RESISTANCE, 0.0);

        // 1. Native Minecraft Generic Scale Attribute Modifier (Clamped to vanilla RangedAttribute limits [0.0625, 16.0])
        AttributeInstance scaleAttr = entity.getAttribute(Attributes.SCALE);
        if (scaleAttr != null && scaleMult > 0.0) {
            double clampedScale = Mth.clamp(scaleMult, 0.0625, 16.0);
            if (Math.abs(clampedScale - 1.0) > 0.0001) {
                scaleAttr.addOrReplacePermanentModifier(new AttributeModifier(
                        SCALE_MOD_ID,
                        clampedScale - 1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                ));
            } else {
                scaleAttr.removeModifier(SCALE_MOD_ID);
            }
        }

        // 2. Max Health & Proportional Healing Attribute Modifier
        AttributeInstance healthAttr = entity.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null && healthMult > 0.0) {
            // Only the persisted flag decides this. Checking hasModifier() as well made every mob with a
            // 1.0x health multiplier (no modifier is ever added) count as "first init" and heal to full on
            // each chunk load, /mobmodifier reload and GUI apply.
            boolean isFirstInit = !entity.getPersistentData().getBoolean(NBT_HEALTH_INITIALIZED);
            if (Math.abs(healthMult - 1.0) > 0.0001) {
                healthAttr.addOrReplacePermanentModifier(new AttributeModifier(HEALTH_MOD_ID, healthMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else {
                healthAttr.removeModifier(HEALTH_MOD_ID);
            }

            // Properly heal newly spawned mobs to their new getMaxHealth() while preserving health of existing mobs across chunk reload
            if (isFirstInit) {
                entity.setHealth(entity.getMaxHealth());
                entity.getPersistentData().putBoolean(NBT_HEALTH_INITIALIZED, true);
            }
        }

        // 3. Attack Damage Attribute Modifier
        AttributeInstance damageAttr = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damageAttr != null && damageMult > 0.0) {
            if (Math.abs(damageMult - 1.0) > 0.0001) {
                damageAttr.addOrReplacePermanentModifier(new AttributeModifier(DAMAGE_MOD_ID, damageMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else {
                damageAttr.removeModifier(DAMAGE_MOD_ID);
            }
        }

        // 4. Movement Speed Attribute Modifier
        AttributeInstance speedAttr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null && speedMult > 0.0) {
            if (Math.abs(speedMult - 1.0) > 0.0001) {
                speedAttr.addOrReplacePermanentModifier(new AttributeModifier(SPEED_MOD_ID, speedMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else {
                speedAttr.removeModifier(SPEED_MOD_ID);
            }
        }

        // 5. Follow Range Attribute Modifier
        AttributeInstance followAttr = entity.getAttribute(Attributes.FOLLOW_RANGE);
        if (followAttr != null && followRange > 0.0) {
            // Allow lowering as well as raising the follow range (a negative ADD_VALUE modifier).
            double diff = followRange - followAttr.getBaseValue();
            if (Math.abs(diff) > 0.0001) {
                followAttr.addOrReplacePermanentModifier(new AttributeModifier(FOLLOW_MOD_ID, diff, AttributeModifier.Operation.ADD_VALUE));
            } else {
                followAttr.removeModifier(FOLLOW_MOD_ID);
            }
        }

        // 6. Knockback Resistance Attribute Modifier
        AttributeInstance knockbackAttr = entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockbackAttr != null) {
            if (knockbackVal > 0.0001) {
                knockbackAttr.addOrReplacePermanentModifier(new AttributeModifier(KNOCKBACK_MOD_ID, knockbackVal, AttributeModifier.Operation.ADD_VALUE));
            } else {
                knockbackAttr.removeModifier(KNOCKBACK_MOD_ID);
            }
        }

        // 7. Dynamic AI Targeting Goals (Re-injected idempotently on every EntityJoinLevelEvent)
        if (entity instanceof Mob mob) {
            applyGoals(mob, hostileToPlayers, targetBoats, followRange);
        }

        // Force immediate bounding box recalculation (dimensions, eye height, and physical AABB)
        entity.refreshDimensions();

        // Sync updated scale attribute immediately to all tracking clients
        syncScaleToTrackingClients(entity);

        // Capture stats after modification for verification logging
        double afterHp = entity.getMaxHealth();
        double afterDmg = getAttrValue(entity, Attributes.ATTACK_DAMAGE, 0.0);
        double afterSpeed = getAttrValue(entity, Attributes.MOVEMENT_SPEED, 0.0);
        double afterScale = getAttrValue(entity, Attributes.SCALE, 1.0);
        double afterFollow = getAttrValue(entity, Attributes.FOLLOW_RANGE, 0.0);
        double afterKb = getAttrValue(entity, Attributes.KNOCKBACK_RESISTANCE, 0.0);

        if (MobModifierConfig.LOG_MODIFICATIONS.get()) {
            SereneMobModifier.LOGGER.info("[SereneMobModifier] Modified {}: HP: {} -> {} (Current: {}), Dmg: {} -> {}, Spd: {} -> {}, Scale: {} -> {}, Follow: {} -> {}, KB: {} -> {} [UUID: {}]",
                    entity.getType().getDescriptionId(),
                    formatVal(beforeHp), formatVal(afterHp), formatVal(entity.getHealth()),
                    formatVal(beforeDmg), formatVal(afterDmg),
                    formatVal(beforeSpeed), formatVal(afterSpeed),
                    formatVal(beforeScale), formatVal(afterScale),
                    formatVal(beforeFollow), formatVal(afterFollow),
                    formatVal(beforeKb), formatVal(afterKb),
                    entity.getUUID());
        }
    }

    public static void syncScaleToTrackingClients(LivingEntity entity) {
        if (entity.level() instanceof ServerLevel serverLevel) {
            AttributeInstance scaleAttr = entity.getAttribute(Attributes.SCALE);
            if (scaleAttr != null) {
                serverLevel.getChunkSource().broadcastAndSend(entity,
                        new ClientboundUpdateAttributesPacket(entity.getId(), Collections.singleton(scaleAttr))
                );
            }
        }
    }

    public static void applyGoals(Mob mob, boolean hostileToPlayers, boolean targetBoats, double followRange) {
        // Player hostility goal
        boolean hasPlayerGoal = mob.targetSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof SereneAttackPlayerGoal);
        if (hostileToPlayers) {
            if (!hasPlayerGoal) {
                mob.targetSelector.addGoal(2, new SereneAttackPlayerGoal(mob));
            }
        } else if (hasPlayerGoal) {
            mob.targetSelector.getAvailableGoals().stream()
                    .filter(g -> g.getGoal() instanceof SereneAttackPlayerGoal)
                    .map(WrappedGoal::getGoal)
                    .toList()
                    .forEach(mob.targetSelector::removeGoal);
        }

        // Melee fallback for hostile-flagged mobs that cannot attack on their own. Vanilla hostile mobs
        // (Enemy) and anything that already has an attack goal are left alone.
        boolean hasMeleeGoal = mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof SereneMeleeGoal);
        boolean needsMelee = hostileToPlayers && mob instanceof PathfinderMob && !(mob instanceof Enemy)
                && !hasOwnAttackGoal(mob);
        if (needsMelee) {
            if (!hasMeleeGoal) {
                mob.goalSelector.addGoal(2, new SereneMeleeGoal((PathfinderMob) mob));
            }
        } else if (hasMeleeGoal) {
            mob.goalSelector.getAvailableGoals().stream()
                    .filter(g -> g.getGoal() instanceof SereneMeleeGoal)
                    .map(WrappedGoal::getGoal)
                    .toList()
                    .forEach(mob.goalSelector::removeGoal);
        }

        // Boat & ship targeting goal. The goal captures its search radius and player-hostility at construction,
        // so an existing instance built from an older rule must be replaced rather than kept.
        double desiredRadius = Math.max(followRange, 32.0);
        boolean boatGoalUpToDate = false;
        List<Goal> existingBoatGoals = mob.goalSelector.getAvailableGoals().stream()
                .map(WrappedGoal::getGoal)
                .filter(g -> g instanceof AttackAndBoardShipGoal)
                .toList();
        for (Goal existing : existingBoatGoals) {
            if (targetBoats && !boatGoalUpToDate
                    && ((AttackAndBoardShipGoal) existing).hasSettings(desiredRadius, hostileToPlayers)) {
                boatGoalUpToDate = true;
            } else {
                mob.goalSelector.removeGoal(existing);
            }
        }
        if (targetBoats && !boatGoalUpToDate) {
            mob.goalSelector.addGoal(1, new AttackAndBoardShipGoal(mob, desiredRadius, hostileToPlayers));
        }
    }

    private static double getAttrValue(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double defaultValue) {
        AttributeInstance inst = entity.getAttribute(attribute);
        return inst != null ? inst.getValue() : defaultValue;
    }

    private static String formatVal(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
