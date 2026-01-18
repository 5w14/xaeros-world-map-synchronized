package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> Client: Sends a batch of available chunks with their timestamps.
 * Used during initial sync when a player joins, sent in paginated batches.
 */
public class S2CRegistryChunkPacket {

    /**
     * Entry for a single chunk in the registry batch.
     */
    public record ChunkEntry(String dimension, int x, int z, long timestamp) {
        public static void encode(ChunkEntry entry, FriendlyByteBuf buf) {
            buf.writeUtf(entry.dimension);
            buf.writeVarInt(entry.x);
            buf.writeVarInt(entry.z);
            buf.writeVarLong(entry.timestamp);
        }

        public static ChunkEntry decode(FriendlyByteBuf buf) {
            return new ChunkEntry(
                    buf.readUtf(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarLong()
            );
        }
    }

    private final List<ChunkEntry> entries;
    private final boolean isLastBatch;
    private final int batchIndex;
    private final int totalBatches;

    public S2CRegistryChunkPacket(List<ChunkEntry> entries, boolean isLastBatch, int batchIndex, int totalBatches) {
        this.entries = entries;
        this.isLastBatch = isLastBatch;
        this.batchIndex = batchIndex;
        this.totalBatches = totalBatches;
    }

    public static void encode(S2CRegistryChunkPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entries.size());
        for (ChunkEntry entry : packet.entries) {
            ChunkEntry.encode(entry, buf);
        }
        buf.writeBoolean(packet.isLastBatch);
        buf.writeVarInt(packet.batchIndex);
        buf.writeVarInt(packet.totalBatches);
    }

    public static S2CRegistryChunkPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ChunkEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(ChunkEntry.decode(buf));
        }
        boolean isLastBatch = buf.readBoolean();
        int batchIndex = buf.readVarInt();
        int totalBatches = buf.readVarInt();

        return new S2CRegistryChunkPacket(entries, isLastBatch, batchIndex, totalBatches);
    }

    public static void handle(S2CRegistryChunkPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSyncManager manager = ClientSyncManager.getInstance();
            if (manager != null) {
                manager.handleRegistryChunk(packet);
            } else {
                XaeroSync.LOGGER.warn("ClientSyncManager not initialized, ignoring registry chunk packet");
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // Getters
    public List<ChunkEntry> getEntries() {
        return entries;
    }

    public boolean isLastBatch() {
        return isLastBatch;
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public int getTotalBatches() {
        return totalBatches;
    }
}
