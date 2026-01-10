package net.fivew14.xaerosync.common;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Represents a 64x64 block tile chunk coordinate with dimension.
 * This corresponds to a MapTileChunk in Xaero's World Map (4 Minecraft chunks).
 */
public record ChunkCoord(ResourceLocation dimension, int x, int z) {

    /**
     * Create a ChunkCoord from Minecraft chunk coordinates.
     * Converts from 16x16 MC chunks to 64x64 tile chunks.
     */
    public static ChunkCoord fromMinecraftChunk(ResourceLocation dimension, int mcChunkX, int mcChunkZ) {
        // Each tile chunk is 4x4 MC chunks, so divide by 4
        return new ChunkCoord(dimension, mcChunkX >> 2, mcChunkZ >> 2);
    }

    /**
     * Create a ChunkCoord from block coordinates.
     */
    public static ChunkCoord fromBlockPos(ResourceLocation dimension, int blockX, int blockZ) {
        // Each tile chunk is 64x64 blocks
        return new ChunkCoord(dimension, blockX >> 6, blockZ >> 6);
    }

    /**
     * Get the region X coordinate (8x8 tile chunks per region).
     */
    public int regionX() {
        return x >> 3;
    }

    /**
     * Get the region Z coordinate.
     */
    public int regionZ() {
        return z >> 3;
    }

    /**
     * Get the local X coordinate within the region (0-7).
     */
    public int localX() {
        return x & 7;
    }

    /**
     * Get the local Z coordinate within the region (0-7).
     */
    public int localZ() {
        return z & 7;
    }

    /**
     * Write to network buffer.
     */
    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeInt(x);
        buf.writeInt(z);
    }

    /**
     * Read from network buffer.
     */
    public static ChunkCoord read(FriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        int x = buf.readInt();
        int z = buf.readInt();
        return new ChunkCoord(dim, x, z);
    }

    @Override
    public String toString() {
        return String.format("ChunkCoord[%s, %d, %d]", dimension, x, z);
    }
}
