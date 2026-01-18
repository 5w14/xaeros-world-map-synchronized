package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for storing downloaded chunk data on disk.
 * <p>
 * Chunks are stored in: .minecraft/xaerosync-cache/{worldId}/{dimension}/{regionX}_{regionZ}/{localX}_{localZ}.bin
 * <p>
 * This cache is separate from Xaero's own storage. When Xaero loads a region/chunk,
 * we check this cache and apply synced data via mixin hooks.
 */
public class SyncedChunkCache {

    private static SyncedChunkCache instance;

    // In-memory index of what chunks we have cached (coord -> timestamp)
    private final Map<ChunkCoord, Long> cachedChunks = new ConcurrentHashMap<>();

    // Current world ID
    private String currentWorldId;

    private SyncedChunkCache() {
    }

    public static SyncedChunkCache getInstance() {
        if (instance == null) {
            instance = new SyncedChunkCache();
        }
        return instance;
    }

    /**
     * Initialize cache for a specific world.
     */
    public void initForWorld(String worldId) {
        if (worldId == null) {
            XaeroSync.LOGGER.warn("Cannot init cache - worldId is null");
            return;
        }

        if (worldId.equals(currentWorldId)) {
            return; // Already initialized for this world
        }

        currentWorldId = worldId;
        cachedChunks.clear();

        // Scan cache directory to build index
        Path cacheDir = getCacheDir();
        if (cacheDir != null && Files.exists(cacheDir)) {
            scanCacheDirectory(cacheDir);
        }

        XaeroSync.LOGGER.info("Initialized synced chunk cache for world {} with {} cached chunks",
                worldId, cachedChunks.size());
    }

    /**
     * Clear the cache (on disconnect).
     */
    public void clear() {
        cachedChunks.clear();
        currentWorldId = null;
    }

    /**
     * Store a chunk in the cache.
     */
    public void store(ChunkCoord coord, byte[] data, long timestamp) {
        if (currentWorldId == null) {
            XaeroSync.LOGGER.warn("Cannot store chunk - cache not initialized");
            return;
        }

        Path chunkFile = getChunkFile(coord);
        if (chunkFile == null) {
            return;
        }

        try {
            Files.createDirectories(chunkFile.getParent());

            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(chunkFile)))) {
                // Write header
                dos.writeLong(timestamp);
                dos.writeInt(data.length);
                dos.write(data);
            }

            cachedChunks.put(coord, timestamp);
            XaeroSync.LOGGER.debug("Stored chunk {} in cache (timestamp: {})", coord, timestamp);

        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to store chunk {} in cache", coord, e);
        }
    }

    /**
     * Check if we have a cached chunk that's newer than the given timestamp.
     */
    public boolean hasNewerChunk(ChunkCoord coord, long thanTimestamp) {
        Long cachedTs = cachedChunks.get(coord);
        return cachedTs != null && cachedTs > thanTimestamp;
    }

    /**
     * Check if we have any cached data for this chunk.
     */
    public boolean hasChunk(ChunkCoord coord) {
        return cachedChunks.containsKey(coord);
    }

    /**
     * Get the timestamp of a cached chunk.
     */
    @Nullable
    public Long getTimestamp(ChunkCoord coord) {
        return cachedChunks.get(coord);
    }

    /**
     * Load chunk data from cache.
     */
    @Nullable
    public CachedChunk load(ChunkCoord coord) {
        if (!cachedChunks.containsKey(coord)) {
            return null;
        }

        Path chunkFile = getChunkFile(coord);
        if (chunkFile == null || !Files.exists(chunkFile)) {
            cachedChunks.remove(coord);
            return null;
        }

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(chunkFile)))) {
            long timestamp = dis.readLong();
            int dataLength = dis.readInt();
            byte[] data = new byte[dataLength];
            dis.readFully(data);

            return new CachedChunk(coord, data, timestamp);

        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to load chunk {} from cache", coord, e);
            cachedChunks.remove(coord);
            return null;
        }
    }

    /**
     * Remove a chunk from cache (e.g., after successfully applying it).
     */
    public void remove(ChunkCoord coord) {
        cachedChunks.remove(coord);

        Path chunkFile = getChunkFile(coord);
        if (chunkFile != null) {
            try {
                Files.deleteIfExists(chunkFile);
            } catch (IOException e) {
                XaeroSync.LOGGER.warn("Failed to delete cached chunk file for {}", coord);
            }
        }
    }

    /**
     * Get count of cached chunks.
     */
    public int getCachedCount() {
        return cachedChunks.size();
    }

    /**
     * Get all cached chunk coordinates (for periodic processing).
     */
    public Set<ChunkCoord> getCachedCoords() {
        return new HashSet<>(cachedChunks.keySet());
    }

    // ==================== Helpers ====================

    @Nullable
    private Path getCacheDir() {
        if (currentWorldId == null) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc.gameDirectory.toPath();
        return gameDir.resolve("xaerosync-cache").resolve(sanitizeFileName(currentWorldId));
    }

    @Nullable
    private Path getChunkFile(ChunkCoord coord) {
        Path cacheDir = getCacheDir();
        if (cacheDir == null) {
            return null;
        }

        String dimStr = sanitizeFileName(coord.dimension().toString());
        int regionX = coord.regionX();
        int regionZ = coord.regionZ();
        int localX = coord.localX();
        int localZ = coord.localZ();

        return cacheDir
                .resolve(dimStr)
                .resolve(regionX + "_" + regionZ)
                .resolve(localX + "_" + localZ + ".bin");
    }

    private void scanCacheDirectory(Path cacheDir) {
        try {
            Files.walk(cacheDir)
                    .filter(p -> p.toString().endsWith(".bin"))
                    .forEach(this::indexChunkFile);
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to scan cache directory", e);
        }
    }

    private void indexChunkFile(Path chunkFile) {
        try {
            // Parse path: {cacheDir}/{dimension}/{regionX}_{regionZ}/{localX}_{localZ}.bin
            Path regionDir = chunkFile.getParent();
            Path dimDir = regionDir.getParent();

            String fileName = chunkFile.getFileName().toString();
            String regionDirName = regionDir.getFileName().toString();
            String dimName = dimDir.getFileName().toString();

            // Parse local coords from filename
            String[] localParts = fileName.replace(".bin", "").split("_");
            int localX = Integer.parseInt(localParts[0]);
            int localZ = Integer.parseInt(localParts[1]);

            // Parse region coords from directory name
            String[] regionParts = regionDirName.split("_");
            int regionX = Integer.parseInt(regionParts[0]);
            int regionZ = Integer.parseInt(regionParts[1]);

            // Calculate chunk coords
            int chunkX = regionX * 8 + localX;
            int chunkZ = regionZ * 8 + localZ;

            // Restore dimension name (replace _ back to :)
            String dimension = dimName.replace("_", ":");
            ResourceLocation dimLoc = ResourceLocation.tryParse(dimension);
            if (dimLoc == null) {
                return;
            }

            // Read timestamp from file
            try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(chunkFile)))) {
                long timestamp = dis.readLong();

                ChunkCoord coord = new ChunkCoord(dimLoc, chunkX, chunkZ);
                cachedChunks.put(coord, timestamp);
            }

        } catch (Exception e) {
            XaeroSync.LOGGER.debug("Failed to index cache file: {}", chunkFile);
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return "unknown";
        }
        // Replace problematic characters
        String sanitized = name.replace(":", "_")
                .replace("/", "_")
                .replace("\\", "_")
                .replace("..", "_")
                .replace(" ", "_");

        // Remove any null bytes or control characters
        sanitized = sanitized.replaceAll("[\\x00-\\x1f\\x7f]", "");

        // Limit length to prevent extremely long paths
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        return sanitized;
    }

    // ==================== Data Classes ====================

    public record CachedChunk(ChunkCoord coord, byte[] data, long timestamp) {
    }
}
