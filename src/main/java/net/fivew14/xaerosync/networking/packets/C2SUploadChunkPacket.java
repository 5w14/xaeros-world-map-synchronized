package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.server.XaeroSyncServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server: Uploads chunk data to the server.
 * Contains the compressed Xaero map data for a single chunk the player explored.
 */
public class C2SUploadChunkPacket {

    private static final int MAX_DATA_SIZE = 1048576; // 1MB

    private final String dimension;
    private final int x;
    private final int z;
    private final long timestamp;
    private final byte[] data; // GZIP-compressed Xaero format data

    public C2SUploadChunkPacket(String dimension, int x, int z, long timestamp, byte[] data) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.timestamp = timestamp;
        this.data = data != null ? data.clone() : new byte[0];
    }

    public static void encode(C2SUploadChunkPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.dimension);
        buf.writeVarInt(packet.x);
        buf.writeVarInt(packet.z);
        buf.writeVarLong(packet.timestamp);
        buf.writeByteArray(packet.data);
    }

    public static C2SUploadChunkPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(Short.MAX_VALUE);
        int x = buf.readVarInt();
        int z = buf.readVarInt();
        long timestamp = buf.readVarLong();

        int readableBytes = buf.readableBytes();
        int maxSize = Math.min(MAX_DATA_SIZE, Config.SERVER_MAX_CHUNK_DATA_SIZE.get());

        if (readableBytes > maxSize) {
            throw new IllegalArgumentException("Chunk data too large: " + readableBytes + " bytes (max: " + maxSize + ")");
        }

        byte[] data = buf.readByteArray();

        return new C2SUploadChunkPacket(dimension, x, z, timestamp, data);
    }

    public static void handle(C2SUploadChunkPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            XaeroSyncServer.handleChunkUpload(packet, ctx.get());
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
