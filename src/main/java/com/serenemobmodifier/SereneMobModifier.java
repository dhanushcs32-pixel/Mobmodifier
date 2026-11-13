package com.serenemobmodifier;

import com.mojang.logging.LogUtils;
import com.serenemobmodifier.command.MobModifierCommand;
import com.serenemobmodifier.config.MobModifierConfig;
import com.serenemobmodifier.event.EntitySpawnHandler;
import com.serenemobmodifier.logic.sable.SableBridge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(SereneMobModifier.MOD_ID)
public class SereneMobModifier {
    public static final String MOD_ID = "serenemobmodifier";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SereneMobModifier(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[SereneMobModifier] Initializing runtime entity value modifier.");

        modContainer.registerConfig(ModConfig.Type.COMMON, MobModifierConfig.SPEC);
        NeoForge.EVENT_BUS.register(EntitySpawnHandler.class);
        modEventBus.addListener(EntitySpawnHandler::onEntityAttributeModification);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        SableBridge.clearCache();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        MobModifierCommand.register(event.getDispatcher());
    }
}
