package com.serenemobmodifier.event;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.logic.MobModifierApplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public class EntitySpawnHandler {
    private static final String NBT_PROCESSED_KEY = "SereneMobModified";

    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            if (!event.has(type, Attributes.ATTACK_DAMAGE)) {
                event.add(type, Attributes.ATTACK_DAMAGE, 2.0);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!MobModifierConfig.GLOBAL_ENABLE.get()) return;

        if (!(event.getEntity() instanceof LivingEntity living)) return;

        // Prevent repeated attribute compounding across chunk unloads and world reloads
        CompoundTag persistentData = living.getPersistentData();
        if (persistentData.getBoolean(NBT_PROCESSED_KEY)) {
            return;
        }

        boolean modified = MobModifierApplier.applyMatchingRules(living);
        if (modified) {
            persistentData.putBoolean(NBT_PROCESSED_KEY, true);
            if (MobModifierConfig.LOG_MODIFICATIONS.get()) {
                SereneMobModifier.LOGGER.info("[SereneMobModifier] Modified entity: {} [UUID: {}]",
                        living.getType().getDescriptionId(), living.getUUID());
            }
        }
    }
}

