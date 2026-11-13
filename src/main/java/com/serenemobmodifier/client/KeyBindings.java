package com.serenemobmodifier.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.client.gui.MobModifierScreen;
import com.serenemobmodifier.network.ClientRuleCache;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the key binding that opens the Mob Modifier configuration GUI.
 *
 * <p>The mod event bus subscriber handles {@link RegisterKeyMappingsEvent} to register the
 * key with NeoForge's key mapping system.  A separate game event bus subscriber listens
 * for {@link ClientTickEvent.Post} to detect key presses at runtime.</p>
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

    // ── Game event bus: tick listener ────────────────────────────────────────

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
    }
}
