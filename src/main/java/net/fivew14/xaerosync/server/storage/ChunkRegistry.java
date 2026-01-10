package net.fivew14.xaerosync.server.storage;

import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * In-memory index of all synced chunks and their timestamps.
 * Thread-safe for concurrent access.
 */
public class ChunkRegistry {
    
    // Map from ChunkCoord to timestamp
    private final Map<ChunkCoord, Long> chunks = new ConcurrentHashMap<>();
    
    /**
     * Add or update a chunk entry.
     */
    public void put(ChunkCoord coord, long timestamp) {
        chunks.put(coord, timestamp);
    }
    
    /**
     * Get the timestamp for a chunk, if it exists.
     */
    public Optional<Long> getTimestamp(ChunkCoord coord) {
        return Optional.ofNullable(chunks.get(coord));
    }
    
    /**
     * Check if a chunk exists in the registry.
     */
    public boolean contains(ChunkCoord coord) {
        return chunks.containsKey(coord);
    }
    
    /**
     * Remove a chunk from the registry.
     */
    public boolean remove(ChunkCoord coord) {
        return chunks.remove(coord) != null;
    }
    
    /**
     * Get the total number of chunks in the registry.
     */
    public int size() {
        return chunks.size();
    }
    
    /**
     * Clear all entries.
     */
    public void clear() {
        chunks.clear();
    }
    
    /**
     * Iterate over all chunks in the registry.
     */
    public void forEach(BiConsumer<ChunkCoord, Long> action) {
        chunks.forEach(action);
    }
    
    /**
     * Get all chunks for a specific dimension.
     */
    public Map<ChunkCoord, Long> getForDimension(ResourceLocation dimension) {
        Map<ChunkCoord, Long> result = new ConcurrentHashMap<>();
        chunks.forEach((coord, timestamp) -> {
            if (coord.dimension().equals(dimension)) {
                result.put(coord, timestamp);
            }
        });
        return result;
    }
    
    /**
     * Check if the registry has newer data than the provided timestamp.
     * Returns true if the chunk doesn't exist or has an older timestamp.
     */
    public boolean isNewer(ChunkCoord coord, long timestamp) {
        Long existing = chunks.get(coord);
        return existing == null || timestamp > existing;
    }
    
    /**
     * Get a snapshot of all entries (for sending registry packets).
     */
    public Map<ChunkCoord, Long> snapshot() {
        return new ConcurrentHashMap<>(chunks);
    }
}
