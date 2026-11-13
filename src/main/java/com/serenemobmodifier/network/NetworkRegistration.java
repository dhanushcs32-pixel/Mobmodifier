package com.serenemobmodifier.network;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

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

        registrar.playToClient(
                SyncMobRulesPayload.TYPE,
                SyncMobRulesPayload.STREAM_CODEC,
                NetworkRegistration::handleSyncMobRules
        );

        SereneMobModifier.LOGGER.debug("[SereneMobModifier] Registered network payloads: update_mob_rule, sync_mob_rules");
    }

    /** Builds a payload holding the server's current rule list. */
    public static SyncMobRulesPayload currentRulesPayload() {
        List<String> out = new ArrayList<>();
        List<?> configured = MobModifierConfig.MODIFIER_RULES.get();
        if (configured != null) {
            for (Object rule : configured) {
                out.add(String.valueOf(rule));
            }
        }
        return new SyncMobRulesPayload(out);
    }

    /** Sends the current rules to one player (used on login). */
    public static void syncRulesTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, currentRulesPayload());
    }

    /** Sends the current rules to every connected player (used after a rule changes). */
    public static void syncRulesToAll() {
        PacketDistributor.sendToAllPlayers(currentRulesPayload());
    }

    /** Client-side handler: remember the server's rules so the GUI can show them. */
    private static void handleSyncMobRules(SyncMobRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRuleCache.set(payload.rules()));
    }

    /**
     * Server-side handler: validates that the sender has operator level ≥ 2 (or is in
     * singleplayer), then commits the rule to the in-memory config and persists to disk.
     */
    private static void handleUpdateMobRule(UpdateMobRulePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

            // Permission check: OP level 2+, or the owner of a singleplayer/LAN world.
            // MinecraftServer#isSingleplayer() is true for the whole integrated server, including
            // guests who join after "Open to LAN", so it must not be used to grant access.
            boolean isWorldOwner = serverPlayer.getServer() != null
                    && serverPlayer.getServer().isSingleplayerOwner(serverPlayer.getGameProfile());
            if (!isWorldOwner && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(
                        Component.literal("§c[SereneMobModifier] You do not have permission to modify mob rules.")
                );
                SereneMobModifier.LOGGER.warn("[SereneMobModifier] Player {} tried to modify rules without OP.",
                        serverPlayer.getName().getString());
                return;
            }

            String ruleString = payload.ruleString();
            String problem = MobModifierConfig.validateRule(ruleString);
            if (problem != null) {
                SereneMobModifier.LOGGER.warn("[SereneMobModifier] Rejected rule from {} ({}): {}",
                        serverPlayer.getName().getString(), problem, ruleString);
                serverPlayer.sendSystemMessage(
                        Component.literal("§c[SereneMobModifier] Rule rejected: " + problem)
                );
                return;
            }

            // Extract entity ID from the rule string
            String entityId = ruleString.split("\\|")[0].trim();

            // Commit to in-memory config and save to disk
            MobModifierConfig.updateRule(entityId, ruleString);
            MobModifierConfig.saveToDisk();

            // Keep every client's GUI in step with the server's rules
            syncRulesToAll();

            // Live-refresh matching loaded entities across all levels
            if (serverPlayer.getServer() != null) {
                for (net.minecraft.server.level.ServerLevel level : serverPlayer.getServer().getAllLevels()) {
                    for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()) {
                            com.serenemobmodifier.logic.MobModifierApplier.refreshEntity(living);
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
