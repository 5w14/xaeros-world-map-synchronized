package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: Sends actual chunk data to the client.
 * Contains the compressed Xaero map data for a single chunk.
 */
public class S2CChunkDataPacket {

    private final String dimension;
    private final int x;
    private final int z;
    private final long timestamp;
    private final byte[] data; // GZIP-compressed Xaero format data

    public S2CChunkDataPacket(String dimension, int x, int z, long timestamp, byte[] data) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.timestamp = timestamp;
        this.data = data;
    }

    public static void encode(S2CChunkDataPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.dimension);
        buf.writeVarInt(packet.x);
        buf.writeVarInt(packet.z);
        buf.writeVarLong(packet.timestamp);
        buf.writeByteArray(packet.data);
    }

    public static S2CChunkDataPacket decode(FriendlyByteBuf buf) {
        return new S2CChunkDataPacket(
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readByteArray()
        );
    }

    public static void handle(S2CChunkDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSyncManager.getInstance().handleChunkData(packet);
        });
        ctx.get().setPacketHandled(true);
    }

    // Getters
    public String getDimension() {
        return dimension;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte[] getData() {
        return data;
    }
}
