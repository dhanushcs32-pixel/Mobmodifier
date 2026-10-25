package com.serenemobmodifier.network;

import com.serenemobmodifier.SereneMobModifier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Network payload sent from client to server when the player applies a mob rule
 * through the in-game configuration GUI.
 *
 * <p>The {@code ruleString} follows the exact same pipe-delimited format used by the
 * config file: {@code entity_id|scale|health|damage|speed|follow_range|player_aggro|boat_aggro}.
 */
public record UpdateMobRulePayload(String ruleString) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UpdateMobRulePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "update_mob_rule"));

    public static final StreamCodec<ByteBuf, UpdateMobRulePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, UpdateMobRulePayload::ruleString,
                    UpdateMobRulePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
