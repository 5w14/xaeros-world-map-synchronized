package net.fivew14.xaerosync.networking;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.networking.packets.*;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class XaeroSyncNetworking {
    public static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry
            .newSimpleChannel(XaeroSync.id("network"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void init() {
        int idx = 0;

        // Server -> Client packets
        CHANNEL.registerMessage(idx++,
                S2CSyncConfigPacket.class,
                S2CSyncConfigPacket::encode,
                S2CSyncConfigPacket::decode,
                S2CSyncConfigPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(idx++,
                S2CRegistryChunkPacket.class,
                S2CRegistryChunkPacket::encode,
                S2CRegistryChunkPacket::decode,
                S2CRegistryChunkPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(idx++,
                S2CRegistryUpdatePacket.class,
                S2CRegistryUpdatePacket::encode,
                S2CRegistryUpdatePacket::decode,
                S2CRegistryUpdatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(idx++,
                S2CChunkDataPacket.class,
                S2CChunkDataPacket::encode,
                S2CChunkDataPacket::decode,
                S2CChunkDataPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(idx++,
                S2CUploadResultPacket.class,
                S2CUploadResultPacket::encode,
                S2CUploadResultPacket::decode,
                S2CUploadResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // Client -> Server packets
        CHANNEL.registerMessage(idx++,
                C2SRequestChunksPacket.class,
                C2SRequestChunksPacket::encode,
                C2SRequestChunksPacket::decode,
                C2SRequestChunksPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(idx++,
                C2SUploadChunkPacket.class,
                C2SUploadChunkPacket::encode,
                C2SUploadChunkPacket::decode,
                C2SUploadChunkPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
