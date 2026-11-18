package com.serenemobmodifier.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.client.gui.MobModifierScreen;
import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.logic.cerbon.CerbonsApiBridge;
import com.serenemobmodifier.logic.spine.SpineSegmentManager;
import com.serenemobmodifier.network.ClientRuleCache;
import com.serenemobmodifier.network.MeleeAttackSegmentPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Registers the key binding that opens the Mob Modifier configuration GUI and listens
 * for client-side empty-click melee swings against outer multi-part / spine segments.
 */
public final class KeyBindings {

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.serenemobmodifier.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.serenemobmodifier"
    );

    private KeyBindings() {}

    // ── Mod event bus: registration ──────────────────────────────────────────

    @EventBusSubscriber(modid = SereneMobModifier.MOD_ID, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MENU);
            SereneMobModifier.LOGGER.debug("[SereneMobModifier] Registered key binding: M → Open Mob Modifier Menu");
        }
    }

    // ── Game event bus: tick & interaction listener ──────────────────────────

    @EventBusSubscriber(modid = SereneMobModifier.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        /** Forget the previous server's rules so they are never shown while connected to a different one. */
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientRuleCache.clear();
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_MENU.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new MobModifierScreen());
                }
            }
        }

        /**
         * Intercepts left-clicks in empty air when aiming at outer segments of large sea monsters
         * (where vanilla client picking or CerbonsAPI attackHook missed the entity) and transmits
         * an attack packet to the server.
         */
        @SubscribeEvent
        public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            double reach = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
            reach = Math.max(reach, 3.0) + 1.5;
            Vec3 eyePos = mc.player.getEyePosition(1.0f);
            Vec3 viewVec = mc.player.getViewVector(1.0f);
            Vec3 reachEnd = eyePos.add(viewVec.scale(reach));

            AABB searchBox = mc.player.getBoundingBox().expandTowards(viewVec.scale(reach)).inflate(6.0);
            LivingEntity bestTarget = null;
            double closestDistSq = Double.MAX_VALUE;

            for (LivingEntity target : mc.level.getEntitiesOfClass(LivingEntity.class, searchBox,
                    e -> e != mc.player && e.isAlive() && (CerbonsApiBridge.isCerbonsMultipart(e)
                            || SpineSegmentManager.hasSpineSegments(e)
                            || !MobModifierApplier.getAllSubParts(e).isEmpty()))) {
                for (AABB seg : MobModifierApplier.getActiveHitboxSegments(target)) {
                    Optional<Vec3> clip = seg.inflate(0.35).clip(eyePos, reachEnd);
                    if (clip.isPresent()) {
                        double d = eyePos.distanceToSqr(clip.get());
                        if (d < closestDistSq) {
                            closestDistSq = d;
                            bestTarget = target;
                        }
                    }
                }
            }

            if (bestTarget != null) {
                PacketDistributor.sendToServer(new MeleeAttackSegmentPayload(bestTarget.getId()));
            }
        }
    }
}
