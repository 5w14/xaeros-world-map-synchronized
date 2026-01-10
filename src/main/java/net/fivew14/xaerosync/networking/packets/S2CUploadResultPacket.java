package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: Response to a chunk upload attempt.
 * Tells the client whether the upload was accepted or rejected.
 */
public class S2CUploadResultPacket {

    public enum Result {
        /**
         * Upload accepted and stored
         */
        ACCEPTED,
        /**
         * Rejected: server has newer data
         */
        REJECTED_OUTDATED,
        /**
         * Rejected: not enough time since last update
         */
        REJECTED_TOO_SOON,
        /**
         * Rejected: dimension not allowed
         */
        REJECTED_DIMENSION_NOT_ALLOWED,
        /**
         * Rejected: data too large
         */
        REJECTED_DATA_TOO_LARGE,
        /**
         * Rejected: rate limited
         */
        REJECTED_RATE_LIMITED,
        /**
         * Rejected: sync disabled
         */
        REJECTED_SYNC_DISABLED,
        /**
         * Rejected: invalid data format
         */
        REJECTED_INVALID_DATA,
        /**
         * Rejected: other error
         */
        REJECTED_ERROR
    }

    private final String dimension;
    private final int x;
    private final int z;
    private final Result result;
    private final String message; // Optional message for debugging/logging

    public S2CUploadResultPacket(String dimension, int x, int z, Result result, String message) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.result = result;
        this.message = message != null ? message : "";
    }

    public S2CUploadResultPacket(String dimension, int x, int z, Result result) {
        this(dimension, x, z, result, "");
    }

    public static void encode(S2CUploadResultPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.dimension);
        buf.writeVarInt(packet.x);
        buf.writeVarInt(packet.z);
        buf.writeEnum(packet.result);
        buf.writeUtf(packet.message);
    }

    public static S2CUploadResultPacket decode(FriendlyByteBuf buf) {
        return new S2CUploadResultPacket(
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readEnum(Result.class),
                buf.readUtf()
        );
    }

    public static void handle(S2CUploadResultPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSyncManager.getInstance().handleUploadResult(packet);
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

    public Result getResult() {
        return result;
    }

    public String getMessage() {
        return message;
    }

    public boolean isAccepted() {
        return result == Result.ACCEPTED;
    }
}
