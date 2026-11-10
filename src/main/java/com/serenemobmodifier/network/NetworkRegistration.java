package com.serenemobmodifier.network;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Registers the {@link UpdateMobRulePayload} with NeoForge's payload handler system and
 * provides the server-side handler that validates permissions and commits the rule change.
 */
@EventBusSubscriber(modid = SereneMobModifier.MOD_ID)
public final class NetworkRegistration {

    private NetworkRegistration() {}

    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(SereneMobModifier.MOD_ID).versioned("1.0.0");

        registrar.playToServer(
                UpdateMobRulePayload.TYPE,
                UpdateMobRulePayload.STREAM_CODEC,
                NetworkRegistration::handleUpdateMobRule
        );

        SereneMobModifier.LOGGER.debug("[SereneMobModifier] Registered network payload: update_mob_rule");
    }

    /**
     * Server-side handler: validates that the sender has operator level ≥ 2 (or is in
     * singleplayer), then commits the rule to the in-memory config and persists to disk.
     */
    private static void handleUpdateMobRule(UpdateMobRulePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            // Permission check: OP level 2+ or singleplayer
            boolean isSinglePlayer = serverPlayer.getServer() != null && serverPlayer.getServer().isSingleplayer();
            if (!isSinglePlayer && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(
                        Component.literal("§c[SereneMobModifier] You do not have permission to modify mob rules.")
                );
                SereneMobModifier.LOGGER.warn("[SereneMobModifier] Player {} tried to modify rules without OP.",
                        serverPlayer.getName().getString());
                return;
            }

            String ruleString = payload.ruleString();
            if (ruleString == null || !ruleString.contains("|")) {
                SereneMobModifier.LOGGER.warn("[SereneMobModifier] Received malformed rule string from {}: {}",
                        serverPlayer.getName().getString(), ruleString);
                return;
            }

            // Extract entity ID from the rule string
            String entityId = ruleString.split("\\|")[0].trim();

            // Commit to in-memory config and save to disk
            MobModifierConfig.updateRule(entityId, ruleString);
            MobModifierConfig.saveToDisk();

            // Live-refresh matching loaded entities across all levels
            if (serverPlayer.getServer() != null) {
                for (net.minecraft.server.level.ServerLevel level : serverPlayer.getServer().getAllLevels()) {
                    for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()) {
                            com.serenemobmodifier.logic.MobModifierApplier.applyMatchingRules(living);
                        }
                    }
                }
            }

            serverPlayer.sendSystemMessage(
                    Component.literal("§a[SereneMobModifier] Rule updated and applied for: " + entityId)
            );
            SereneMobModifier.LOGGER.info("[SereneMobModifier] Rule updated by {}: {}",
                    serverPlayer.getName().getString(), ruleString);
        });
    }
}
