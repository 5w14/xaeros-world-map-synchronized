package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.server.XaeroSyncServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client -> Server: Requests specific chunks from the server.
 * Client sends this after receiving registry data and determining which chunks it needs.
 */
public class C2SRequestChunksPacket {

    /**
     * A single chunk request.
     */
    public record ChunkRequest(String dimension, int x, int z) {
        public static void encode(ChunkRequest request, FriendlyByteBuf buf) {
            buf.writeUtf(request.dimension);
            buf.writeVarInt(request.x);
            buf.writeVarInt(request.z);
        }

        public static ChunkRequest decode(FriendlyByteBuf buf) {
            return new ChunkRequest(
                    buf.readUtf(),
                    buf.readVarInt(),
                    buf.readVarInt()
            );
        }
    }

    private final List<ChunkRequest> requests;

    public C2SRequestChunksPacket(List<ChunkRequest> requests) {
        this.requests = requests;
    }

    public static void encode(C2SRequestChunksPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.requests.size());
        for (ChunkRequest request : packet.requests) {
            ChunkRequest.encode(request, buf);
        }
    }

    public static C2SRequestChunksPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ChunkRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            requests.add(ChunkRequest.decode(buf));
        }
        return new C2SRequestChunksPacket(requests);
    }

    public static void handle(C2SRequestChunksPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            XaeroSyncServer.handleChunkRequest(packet, ctx.get());
        });
        ctx.get().setPacketHandled(true);
    }

    // Getters
    public List<ChunkRequest> getRequests() {
        return requests;
    }
}
