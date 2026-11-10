package com.serenemobmodifier.logic;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.logic.ai.AttackAndBoardShipGoal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

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

    public static class SereneAttackPlayerGoal extends NearestAttackableTargetGoal<Player> {
        public SereneAttackPlayerGoal(Mob mob) {
            super(mob, Player.class, true);
        }
    }

    public static boolean applyMatchingRules(LivingEntity entity) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String fullId = entityId.toString();
        String namespace = entityId.getNamespace();

        List<?> rules = MobModifierConfig.MODIFIER_RULES.get();
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        for (Object ruleObj : rules) {
            String ruleStr = String.valueOf(ruleObj);
            String[] tokens = ruleStr.split("\\|");
            if (tokens.length < 8) continue;

            String pattern = tokens[0].trim();
            boolean match;

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

                    double knockbackVal = 0.0;
                    if (tokens.length >= 9) {
                        knockbackVal = Double.parseDouble(tokens[8].trim());
                    } else if (scaleMult > 1.0) {
                        knockbackVal = Math.min(1.0, (scaleMult - 1.0) * 0.35);
                    }

                    applyModifiers(entity, scaleMult, healthMult, damageMult, speedMult, followRange, knockbackVal, hostileToPlayers, targetBoats);
                    return true;
                } catch (Exception e) {
                    SereneMobModifier.LOGGER.error("[SereneMobModifier] Error parsing rule: {}", ruleStr, e);
                }
            }
        }
        return false;
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

        // 1. Native Minecraft Generic Scale Attribute Modifier
        AttributeInstance scaleAttr = entity.getAttribute(Attributes.SCALE);
        if (scaleAttr != null && scaleMult > 0.0) {
            if (Math.abs(scaleMult - 1.0) > 0.0001) {
                scaleAttr.addOrReplacePermanentModifier(new AttributeModifier(SCALE_MOD_ID, scaleMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else {
                scaleAttr.removeModifier(SCALE_MOD_ID);
            }
        }

        // 2. Max Health & Proportional Healing Attribute Modifier
        AttributeInstance healthAttr = entity.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null && healthMult > 0.0) {
            boolean isFirstInit = !healthAttr.hasModifier(HEALTH_MOD_ID) || !entity.getPersistentData().getBoolean(NBT_HEALTH_INITIALIZED);
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
            double diff = followRange - followAttr.getBaseValue();
            if (diff > 0.0001) {
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

        // Boat & ship targeting goal
        boolean hasBoatGoal = mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof AttackAndBoardShipGoal);
        if (targetBoats) {
            if (!hasBoatGoal) {
                mob.goalSelector.addGoal(1, new AttackAndBoardShipGoal(mob, Math.max(followRange, 32.0)));
            }
        } else if (hasBoatGoal) {
            mob.goalSelector.getAvailableGoals().stream()
                    .filter(g -> g.getGoal() instanceof AttackAndBoardShipGoal)
                    .map(WrappedGoal::getGoal)
                    .toList()
                    .forEach(mob.goalSelector::removeGoal);
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

    /**
     * AI Goal directing aquatic predators to hunt, ram, and attack vanilla boats
     * and intercept, board, or ram assembled Sable sub-level ships.
     */
    public static class TargetBoatGoal extends AttackAndBoardShipGoal {
        public TargetBoatGoal(Mob mob, double searchRadius) {
            super(mob, searchRadius);
        }
    }
}
