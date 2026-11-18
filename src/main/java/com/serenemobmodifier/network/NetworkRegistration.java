package com.serenemobmodifier.network;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.logic.MobModifierApplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers network payloads with NeoForge's payload handler system and provides
 * server-side handlers for rule modification and outer-segment melee attacks.
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

        registrar.playToServer(
                MeleeAttackSegmentPayload.TYPE,
                MeleeAttackSegmentPayload.STREAM_CODEC,
                NetworkRegistration::handleMeleeAttackSegment
        );

        registrar.playToClient(
                SyncMobRulesPayload.TYPE,
                SyncMobRulesPayload.STREAM_CODEC,
                NetworkRegistration::handleSyncMobRules
        );

        SereneMobModifier.LOGGER.debug("[SereneMobModifier] Registered network payloads: update_mob_rule, melee_attack_segment, sync_mob_rules");
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
     * Server-side handler: validates that the sender has operator level >= 2 (or is in
     * singleplayer), then commits the rule to the in-memory config and persists to disk.
     */
    private static void handleUpdateMobRule(UpdateMobRulePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

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
                    for (Entity entity : level.getAllEntities()) {
                        if (entity instanceof LivingEntity living && living.isAlive()) {
                            MobModifierApplier.refreshEntity(living);
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

    /**
     * Server-side handler: executes a melee attack on an outer segment of a large/multipart mob.
     */
    private static void handleMeleeAttackSegment(MeleeAttackSegmentPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity entity = player.level().getEntity(payload.targetEntityId());
            if (!(entity instanceof LivingEntity target) || !target.isAlive() || target.isRemoved()) return;

            double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
            reach = Math.max(reach, 3.0) + 3.0; // Generous reach tolerance for large multi-part hitboxes
            double reachSq = reach * reach;

            Vec3 eyePos = player.getEyePosition();
            Vec3 viewVec = player.getViewVector(1.0f);
            Vec3 reachEnd = eyePos.add(viewVec.scale(reach));

            List<AABB> segments = MobModifierApplier.getActiveHitboxSegments(target);
            boolean inReach = false;
            for (AABB seg : segments) {
                if (seg.inflate(0.5).clip(eyePos, reachEnd).isPresent()
                        || seg.distanceToSqr(eyePos) <= reachSq) {
                    inReach = true;
                    break;
                }
            }

            if (!inReach) return;

            AABB originalBb = target.getBoundingBox();
            float preHealth = target.getHealth();
            try {
                // Temporarily expand bounding box so vanilla server reach validation in handleInteract/attack passes
                target.setBoundingBox(target.getBoundingBox().inflate(reach + 2.0));
                player.attack(target);
            } finally {
                target.setBoundingBox(originalBb);
            }

            // Fallback direct damage if player.attack did not reduce health (e.g. cooldown or multipart resistance)
            if (target.isAlive() && target.getHealth() >= preHealth) {
                DamageSource source = target.damageSources().playerAttack(player);
                double baseDamage = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
                target.hurt(source, (float) Math.max(1.0, baseDamage));
            }
        });
    }
}
