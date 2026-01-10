package net.fivew14.xaerosync.common;

import net.minecraft.network.FriendlyByteBuf;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/**
 * Metadata for a synced chunk, stored as a header in the chunk file.
 * <p>
 * File format:
 * - int version (4 bytes)
 * - long uuidMost (8 bytes)
 * - long uuidLeast (8 bytes)
 * - long timestamp (8 bytes)
 * Total: 28 bytes
 */
public record ChunkMetadata(UUID contributor, long timestamp) {

    public static final int HEADER_SIZE = 28;
    public static final int CURRENT_VERSION = 1;

    /**
     * Write metadata to a DataOutput stream (for file storage).
     */
    public void write(DataOutput out) throws IOException {
        out.writeInt(CURRENT_VERSION);
        out.writeLong(contributor.getMostSignificantBits());
        out.writeLong(contributor.getLeastSignificantBits());
        out.writeLong(timestamp);
    }

    /**
     * Read metadata from a DataInput stream (for file storage).
     */
    public static ChunkMetadata read(DataInput in) throws IOException {
        int version = in.readInt();
        if (version != CURRENT_VERSION) {
            throw new IOException("Unsupported chunk metadata version: " + version);
        }
        long uuidMost = in.readLong();
        long uuidLeast = in.readLong();
        long timestamp = in.readLong();
        return new ChunkMetadata(new UUID(uuidMost, uuidLeast), timestamp);
    }

    /**
     * Write to network buffer.
     */
    public void writeToNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(contributor);
        buf.writeLong(timestamp);
    }

    /**
     * Read from network buffer.
     */
    public static ChunkMetadata readFromNetwork(FriendlyByteBuf buf) {
        UUID contributor = buf.readUUID();
        long timestamp = buf.readLong();
        return new ChunkMetadata(contributor, timestamp);
    }
}
