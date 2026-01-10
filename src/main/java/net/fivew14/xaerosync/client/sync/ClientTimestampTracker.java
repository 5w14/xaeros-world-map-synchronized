package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks timestamps for locally explored/downloaded chunks on the client.
 * Used to determine which chunks need to be uploaded or downloaded.
 */
public class ClientTimestampTracker {
    
    // Map from ChunkCoord to local timestamp
    private final Map<ChunkCoord, Long> localTimestamps = new ConcurrentHashMap<>();
    
    // Map from ChunkCoord to server timestamp (from registry)
    private final Map<ChunkCoord, Long> serverTimestamps = new ConcurrentHashMap<>();
    
    /**
     * Record that a chunk was explored locally at the given time.
     */
    public void setLocalTimestamp(ChunkCoord coord, long timestamp) {
        localTimestamps.put(coord, timestamp);
    }
    
    /**
     * Get the local timestamp for a chunk.
     */
    public Optional<Long> getLocalTimestamp(ChunkCoord coord) {
        return Optional.ofNullable(localTimestamps.get(coord));
    }
    
    /**
     * Update server registry timestamp for a chunk.
     */
    public void setServerTimestamp(ChunkCoord coord, long timestamp) {
        serverTimestamps.put(coord, timestamp);
    }
    
    /**
     * Get the server timestamp for a chunk.
     */
    public Optional<Long> getServerTimestamp(ChunkCoord coord) {
        return Optional.ofNullable(serverTimestamps.get(coord));
    }
    
    /**
     * Check if a chunk needs to be uploaded (local is newer than server).
     */
    public boolean needsUpload(ChunkCoord coord) {
        Long local = localTimestamps.get(coord);
        if (local == null) return false;
        
        Long server = serverTimestamps.get(coord);
        return server == null || local > server;
    }
    
    /**
     * Check if a chunk needs to be downloaded (server is newer than local).
     */
    public boolean needsDownload(ChunkCoord coord) {
        Long server = serverTimestamps.get(coord);
        if (server == null) return false;
        
        Long local = localTimestamps.get(coord);
        return local == null || server > local;
    }
    
    /**
     * Get all chunks that need to be uploaded.
     */
    public Map<ChunkCoord, Long> getChunksNeedingUpload() {
        Map<ChunkCoord, Long> result = new ConcurrentHashMap<>();
        localTimestamps.forEach((coord, localTs) -> {
            Long serverTs = serverTimestamps.get(coord);
            if (serverTs == null || localTs > serverTs) {
                result.put(coord, localTs);
            }
        });
        return result;
    }
    
    /**
     * Get all server chunks that need to be downloaded.
     */
    public Map<ChunkCoord, Long> getChunksNeedingDownload() {
        Map<ChunkCoord, Long> result = new ConcurrentHashMap<>();
        serverTimestamps.forEach((coord, serverTs) -> {
            Long localTs = localTimestamps.get(coord);
            if (localTs == null || serverTs > localTs) {
                result.put(coord, serverTs);
            }
        });
        return result;
    }
    
    /**
     * Clear all timestamps (e.g., when disconnecting).
     */
    public void clear() {
        localTimestamps.clear();
        serverTimestamps.clear();
    }
    
    /**
     * Clear only server timestamps (keep local for offline use).
     */
    public void clearServerTimestamps() {
        serverTimestamps.clear();
    }
    
    /**
     * Get count of tracked local chunks.
     */
    public int getLocalCount() {
        return localTimestamps.size();
    }
    
    /**
     * Get count of known server chunks.
     */
    public int getServerCount() {
        return serverTimestamps.size();
    }
}
