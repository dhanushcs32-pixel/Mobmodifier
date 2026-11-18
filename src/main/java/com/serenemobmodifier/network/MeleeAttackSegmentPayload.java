package com.serenemobmodifier.network;

import com.serenemobmodifier.SereneMobModifier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Network payload sent from client to server when a player swings at an outer segment
 * of a scaled / multipart entity (e.g. Leviathan, Kraken, elongated sea serpent).
 */
public record MeleeAttackSegmentPayload(int targetEntityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MeleeAttackSegmentPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "melee_attack_segment"));

    public static final StreamCodec<ByteBuf, MeleeAttackSegmentPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MeleeAttackSegmentPayload::targetEntityId,
                    MeleeAttackSegmentPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

