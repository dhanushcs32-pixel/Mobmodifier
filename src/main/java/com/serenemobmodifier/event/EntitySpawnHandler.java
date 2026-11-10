package com.serenemobmodifier.event;

import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.logic.MobModifierApplier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public class EntitySpawnHandler {

    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            if (!event.has(type, Attributes.ATTACK_DAMAGE)) {
                event.add(type, Attributes.ATTACK_DAMAGE, 2.0);
            }
            if (!event.has(type, Attributes.MOVEMENT_SPEED)) {
                event.add(type, Attributes.MOVEMENT_SPEED, 0.25);
            }
            if (!event.has(type, Attributes.FOLLOW_RANGE)) {
                event.add(type, Attributes.FOLLOW_RANGE, 32.0);
            }
            if (!event.has(type, Attributes.KNOCKBACK_RESISTANCE)) {
                event.add(type, Attributes.KNOCKBACK_RESISTANCE, 0.0);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!MobModifierConfig.GLOBAL_ENABLE.get()) return;

        if (!(event.getEntity() instanceof LivingEntity living)) return;

        // Apply permanent attributes and ensure transient AI goals are attached on every join (chunk load or initial spawn)
        MobModifierApplier.applyMatchingRules(living);
    }
}
