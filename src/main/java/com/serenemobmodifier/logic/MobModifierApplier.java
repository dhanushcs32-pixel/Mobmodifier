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
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

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
                mob.goalSelector.addGoal(1, new AttackAndBoardShipGoal(mob, Math.max(followRange, 32.0)));
            }
        }
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