package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: Broadcasts a single chunk registry update to all players.
 * Sent when a player uploads a new/updated chunk so other players can download it.
 */
public class S2CRegistryUpdatePacket {

    private final String dimension;
    private final int x;
    private final int z;
    private final long timestamp;

    public S2CRegistryUpdatePacket(String dimension, int x, int z, long timestamp) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.timestamp = timestamp;
    }

    public static void encode(S2CRegistryUpdatePacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.dimension);
        buf.writeVarInt(packet.x);
        buf.writeVarInt(packet.z);
        buf.writeVarLong(packet.timestamp);
    }

    public static S2CRegistryUpdatePacket decode(FriendlyByteBuf buf) {
        return new S2CRegistryUpdatePacket(
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarLong()
        );
    }

    public static void handle(S2CRegistryUpdatePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSyncManager.getInstance().handleRegistryUpdate(packet);
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
}
