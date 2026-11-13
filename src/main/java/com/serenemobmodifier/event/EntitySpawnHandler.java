package com.serenemobmodifier.event;

import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.network.NetworkRegistration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public class EntitySpawnHandler {

    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            // Players and armor stands are never configurable; don't touch their attribute sets.
            if (type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) continue;
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
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            NetworkRegistration.syncRulesTo(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        if (!(event.getEntity() instanceof LivingEntity living)) return;

        // Apply permanent attributes and ensure transient AI goals are attached on every join (chunk load or initial spawn).
        // When no rule matches (or modifications are globally disabled) this also removes modifiers added earlier.
        MobModifierApplier.refreshEntity(living);
    }
}
