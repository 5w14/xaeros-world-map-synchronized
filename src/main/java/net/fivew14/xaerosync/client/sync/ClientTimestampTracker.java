package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks timestamps for locally explored/downloaded chunks on the client.
 * Used to determine which chunks need to be uploaded or downloaded.
 * Persists local timestamps to disk per-server to avoid re-downloading chunks.
 */
public class ClientTimestampTracker {

    private static final int FILE_VERSION = 1;

    // Don't download chunks that were updated locally within this time window
    // This prevents overwriting chunks the player is actively exploring
    private static final long RECENT_UPDATE_THRESHOLD_MS = 90_000; // 1.5 minutes

    // Map from ChunkCoord to local timestamp
    private final Map<ChunkCoord, Long> localTimestamps = new ConcurrentHashMap<>();

    // Map from ChunkCoord to server timestamp (from registry)
    private final Map<ChunkCoord, Long> serverTimestamps = new ConcurrentHashMap<>();

    // Current world ID for persistence
    private String currentWorldId = null;

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
     * Also returns false if the chunk was recently updated locally, to avoid
     * overwriting chunks the player is actively exploring.
     */
    public boolean needsDownload(ChunkCoord coord) {
        Long server = serverTimestamps.get(coord);
        if (server == null) return false;

        Long local = localTimestamps.get(coord);

        // If chunk was recently updated locally, don't download
        // (player is actively exploring this area)
        if (local != null) {
            long timeSinceUpdate = System.currentTimeMillis() - local;
            if (timeSinceUpdate < RECENT_UPDATE_THRESHOLD_MS) {
                return false;
            }
        }

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

    // ==================== Persistence ====================

    /**
     * Load local timestamps for the given world ID.
     * Should be called when connecting to a server.
     */
    public void loadForWorld(String worldId) {
        if (worldId == null || worldId.isEmpty()) {
            XaeroSync.LOGGER.warn("Cannot load timestamps: no world ID");
            return;
        }

        // Save current world's timestamps before switching
        if (currentWorldId != null && !currentWorldId.equals(worldId)) {
            save();
        }

        currentWorldId = worldId;
        localTimestamps.clear();

        Path file = getTimestampFile(worldId);
        if (!Files.exists(file)) {
            XaeroSync.LOGGER.debug("No timestamp file found for world {}", worldId);
            return;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version != FILE_VERSION) {
                XaeroSync.LOGGER.warn("Timestamp file version mismatch (expected {}, got {}), ignoring", FILE_VERSION, version);
                return;
            }

            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String dimension = in.readUTF();
                int x = in.readInt();
                int z = in.readInt();
                long timestamp = in.readLong();

                ResourceLocation dim = ResourceLocation.tryParse(dimension);
                if (dim != null) {
                    localTimestamps.put(new ChunkCoord(dim, x, z), timestamp);
                }
            }

            XaeroSync.LOGGER.info("Loaded {} local timestamps for world {}", count, worldId);
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to load timestamps for world {}", worldId, e);
        }
    }

    /**
     * Save local timestamps for the current world.
     * Should be called when disconnecting or periodically.
     */
    public void save() {
        if (currentWorldId == null) {
            XaeroSync.LOGGER.debug("Cannot save timestamps: no current world ID");
            return;
        }

        if (localTimestamps.isEmpty()) {
            XaeroSync.LOGGER.debug("No timestamps to save for world {}", currentWorldId);
            return;
        }

        Path file = getTimestampFile(currentWorldId);
        XaeroSync.LOGGER.info("Saving {} timestamps to {}", localTimestamps.size(), file);

        try {
            Files.createDirectories(file.getParent());

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                out.writeInt(FILE_VERSION);
                out.writeInt(localTimestamps.size());

                for (Map.Entry<ChunkCoord, Long> entry : localTimestamps.entrySet()) {
                    ChunkCoord coord = entry.getKey();
                    out.writeUTF(coord.dimension().toString());
                    out.writeInt(coord.x());
                    out.writeInt(coord.z());
                    out.writeLong(entry.getValue());
                }
            }

            XaeroSync.LOGGER.info("Saved {} local timestamps for world {}", localTimestamps.size(), currentWorldId);
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to save timestamps for world {}", currentWorldId, e);
        }
    }

    /**
     * Get the path to the timestamp file for a world.
     */
    private Path getTimestampFile(String worldId) {
        if (worldId == null || worldId.isEmpty()) {
            return null;
        }

        // Sanitize world ID for use as filename
        String safeWorldId = sanitizeWorldId(worldId);
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path basePath = gameDir.resolve("xaerosync").resolve("timestamps");

        // Resolve against base path to prevent directory traversal
        Path filePath = basePath.resolve(safeWorldId + ".dat");
        if (!filePath.startsWith(basePath)) {
            XaeroSync.LOGGER.warn("Potential path traversal attempt blocked for world ID: {}", worldId);
            return basePath.resolve("invalid_" + System.currentTimeMillis() + ".dat");
        }

        return filePath;
    }

    private String sanitizeWorldId(String worldId) {
        if (worldId == null) {
            return "unknown";
        }
        // Replace problematic characters
        String sanitized = worldId.replaceAll("[^a-zA-Z0-9_.-]", "_");

        // Remove any null bytes or control characters
        sanitized = sanitized.replaceAll("[\\x00-\\x1f\\x7f]", "");

        // Limit length to prevent extremely long paths
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        return sanitized;
    }

    /**
     * Get the current world ID.
     */
    public String getCurrentWorldId() {
        return currentWorldId;
    }
}
