package com.serenemobmodifier.network;

import com.serenemobmodifier.SereneMobModifier;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Network payload sent from server to client carrying the server's current rule list.
 *
 * <p>The mod's rules live in a COMMON config, which NeoForge does not sync to clients. Without this payload
 * the configuration GUI on a dedicated server showed the <em>client's own</em> local config instead of the
 * rules actually in force on the server.</p>
 */
public record SyncMobRulesPayload(List<String> rules) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncMobRulesPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SereneMobModifier.MOD_ID, "sync_mob_rules"));

    public static final StreamCodec<ByteBuf, SyncMobRulesPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncMobRulesPayload::rules,
                    SyncMobRulesPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
