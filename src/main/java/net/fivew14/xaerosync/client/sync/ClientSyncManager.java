package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.RateLimiter;
import net.fivew14.xaerosync.networking.XaeroSyncNetworking;
import net.fivew14.xaerosync.networking.packets.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.PacketDistributor;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main client-side sync manager.
 * Handles:
 * - Receiving server config and registry
 * - Queueing uploads when chunks are explored
 * - Queueing downloads when server has newer data
 * - Processing uploads/downloads with rate limiting
 */
public class ClientSyncManager {

    private static ClientSyncManager instance;

    // State
    private boolean syncEnabled = false;
    private boolean connected = false;
    private boolean registryComplete = false;

    // Server config (received from S2CSyncConfigPacket)
    private int serverMaxUploadPerSec = 2;
    private int serverMaxDownloadPerSec = 2;
    private int serverMinUpdateIntervalMinutes = 5;
    private List<String> allowedDimensions = new ArrayList<>();
    private List<String> blacklistedDimensions = new ArrayList<>();

    // Tracking
    private final ClientTimestampTracker timestampTracker = new ClientTimestampTracker();

    // Rate limiters (use minimum of client and server limits)
    private RateLimiter uploadLimiter;
    private RateLimiter downloadLimiter;

    // Queues - priority based on distance to player (closer = higher priority)
    private final Set<ChunkCoord> uploadQueueSet = ConcurrentHashMap.newKeySet();
    private final Set<ChunkCoord> downloadQueueSet = ConcurrentHashMap.newKeySet();
    
    // Current player chunk position for distance calculations
    private volatile int playerChunkX = 0;
    private volatile int playerChunkZ = 0;

    // Pending chunks (waiting for data from server)
    private final Set<ChunkCoord> pendingDownloads = Collections.synchronizedSet(new HashSet<>());

    // Local timestamp update interval - don't update localTimestamp more often than this
    // This prevents constant timestamp updates from Xaero's per-tile writeChunk calls
    // Should match or be close to server's min update interval since we can't upload more often anyway
    private static final long LOCAL_UPDATE_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // Periodic save of timestamps (every 5 minutes)
    private static final long TIMESTAMP_SAVE_INTERVAL_MS = 5 * 60 * 1000;

    // Periodic processing of cached chunks waiting to be applied
    private static final long CACHE_PROCESS_INTERVAL_MS = 1_000; // Every second
    private static final int CACHE_PROCESS_MAX_CHUNKS = 5; // Max chunks per tick

    // Re-queue timer for failed uploads (every 30 seconds)
    private static final long REQUEUE_INTERVAL_MS = 30_000;

    // Debounce map: tracks when chunks were last queued for upload to avoid rapid re-queueing
    private static final long DEBOUNCE_INTERVAL_MS = 5_000;
    private final Map<ChunkCoord, Long> recentlyQueuedChunks = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_CLEANUP_INTERVAL_MS = 60_000;

    private long lastTimestampSaveTime = 0;
    private long lastCacheProcessTime = 0;
    private long lastDebounceCleanupTime = 0;
    private long lastRequeueTime = 0;

    private ClientSyncManager() {
        uploadLimiter = new RateLimiter(Config.CLIENT_MAX_UPLOAD_PER_SECOND.get());
        downloadLimiter = new RateLimiter(Config.CLIENT_MAX_DOWNLOAD_PER_SECOND.get());
    }

    public static ClientSyncManager getInstance() {
        if (instance == null) {
            instance = new ClientSyncManager();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        // Register for chunk exploration events
        ChunkExplorationCallback.register(ClientSyncManager::onChunkExplored);
        XaeroSync.LOGGER.info("XaeroSync client sync manager initialized");
    }

    // ==================== Connection Lifecycle ====================

    public void onConnect() {
        connected = true;
        registryComplete = false;
        timestampTracker.clearServerTimestamps();
        uploadQueueSet.clear();
        downloadQueueSet.clear();
        pendingDownloads.clear();
        XaeroSync.LOGGER.debug("Connected to server");
    }

    public void onDisconnect() {
        // Save timestamps before clearing
        timestampTracker.save();

        connected = false;
        syncEnabled = false;
        registryComplete = false;
        timestampTracker.clearServerTimestamps();
        uploadQueueSet.clear();
        downloadQueueSet.clear();
        pendingDownloads.clear();
        recentlyQueuedChunks.clear();
        SyncedChunkCache.getInstance().clear();
        XaeroSync.LOGGER.debug("Disconnected from server");
    }

    // ==================== Packet Handlers ====================

    public void handleSyncConfig(S2CSyncConfigPacket packet) {
        syncEnabled = packet.isSyncEnabled();
        serverMaxUploadPerSec = packet.getMaxUploadPerSecond();
        serverMaxDownloadPerSec = packet.getMaxDownloadPerSecond();
        serverMinUpdateIntervalMinutes = packet.getMinUpdateIntervalMinutes();
        allowedDimensions = new ArrayList<>(packet.getAllowedDimensions());
        blacklistedDimensions = new ArrayList<>(packet.getBlacklistedDimensions());

        // Update rate limiters to use minimum of client and server limits
        int uploadRate = Math.min(Config.CLIENT_MAX_UPLOAD_PER_SECOND.get(), serverMaxUploadPerSec);
        int downloadRate = Math.min(Config.CLIENT_MAX_DOWNLOAD_PER_SECOND.get(), serverMaxDownloadPerSec);
        uploadLimiter = new RateLimiter(uploadRate);
        downloadLimiter = new RateLimiter(downloadRate);

        // Load persisted local timestamps for this server
        if (syncEnabled) {
            String worldId = getWorldId();
            if (worldId != null) {
                timestampTracker.loadForWorld(worldId);
                SyncedChunkCache.getInstance().initForWorld(worldId);
            } else {
                XaeroSync.LOGGER.warn("World ID not available yet when receiving sync config - timestamps will be loaded later");
            }
        }

        XaeroSync.LOGGER.info("Received server config - sync={}, upload={}/s, download={}/s, minInterval={}min",
                syncEnabled, uploadRate, downloadRate, serverMinUpdateIntervalMinutes);
    }

    public void handleRegistryChunk(S2CRegistryChunkPacket packet) {
        XaeroSync.LOGGER.info("Received registry batch {}/{} with {} entries (syncEnabled={})",
                packet.getBatchIndex() + 1, packet.getTotalBatches(), packet.getEntries().size(), syncEnabled);

        if (!syncEnabled) {
            XaeroSync.LOGGER.warn("Ignoring registry - sync not enabled");
            return;
        }

        // Try to load timestamps if not already loaded (world ID may not have been available earlier)
        if (timestampTracker.getCurrentWorldId() == null) {
            String worldId = getWorldId();
            if (worldId != null) {
                timestampTracker.loadForWorld(worldId);
                SyncedChunkCache.getInstance().initForWorld(worldId);
            } else {
                XaeroSync.LOGGER.warn("World ID still not available when processing registry batch");
            }
        }

        int queuedDownloads = 0;
        int skippedAutoDownloadDisabled = 0;
        int skippedAlreadyHave = 0;
        for (S2CRegistryChunkPacket.ChunkEntry entry : packet.getEntries()) {
            ResourceLocation dim = ResourceLocation.tryParse(entry.dimension());
            if (dim == null) continue;

            ChunkCoord coord = new ChunkCoord(dim, entry.x(), entry.z());
            timestampTracker.setServerTimestamp(coord, entry.timestamp());

            // Check if we need to download this chunk
            if (!Config.CLIENT_AUTO_DOWNLOAD.get()) {
                skippedAutoDownloadDisabled++;
            } else if (!timestampTracker.needsDownload(coord)) {
                skippedAlreadyHave++;
            } else {
                queueDownload(coord);
                queuedDownloads++;
            }
        }

        if (skippedAutoDownloadDisabled > 0) {
            XaeroSync.LOGGER.debug("Skipped {} chunks - auto download disabled", skippedAutoDownloadDisabled);
        }
        if (skippedAlreadyHave > 0) {
            XaeroSync.LOGGER.debug("Skipped {} chunks - local version is newer or equal", skippedAlreadyHave);
        }

        if (queuedDownloads > 0) {
            XaeroSync.LOGGER.info("Queued {} chunks for download from batch", queuedDownloads);
        }

        if (packet.isLastBatch()) {
            registryComplete = true;
            XaeroSync.LOGGER.info("Registry transfer complete - {} server chunks, download queue: {}",
                    timestampTracker.getServerCount(), downloadQueueSet.size());

            // Check for chunks that need uploading
            if (Config.CLIENT_AUTO_UPLOAD.get()) {
                queuePendingUploads();
            }
        }
    }

    public void handleRegistryUpdate(S2CRegistryUpdatePacket packet) {
        XaeroSync.LOGGER.info("Received registry update: dim={}, x={}, z={}, ts={}, syncEnabled={}",
                packet.getDimension(), packet.getX(), packet.getZ(), packet.getTimestamp(), syncEnabled);
        
        if (!syncEnabled) {
            XaeroSync.LOGGER.warn("Ignoring registry update - sync not enabled");
            return;
        }

        ResourceLocation dim = ResourceLocation.tryParse(packet.getDimension());
        if (dim == null) {
            XaeroSync.LOGGER.warn("Failed to parse dimension: {}", packet.getDimension());
            return;
        }

        ChunkCoord coord = new ChunkCoord(dim, packet.getX(), packet.getZ());
        timestampTracker.setServerTimestamp(coord, packet.getTimestamp());

        // Check if we need to download this chunk
        boolean autoDownload = Config.CLIENT_AUTO_DOWNLOAD.get();
        Optional<Long> localTs = timestampTracker.getLocalTimestamp(coord);
        Optional<Long> serverTs = timestampTracker.getServerTimestamp(coord);
        boolean needsDownload = timestampTracker.needsDownload(coord);
        
        XaeroSync.LOGGER.info("Registry update for {}: autoDownload={}, needsDownload={}, localTs={}, serverTs={}, inPending={}, inQueue={}", 
                coord, autoDownload, needsDownload, 
                localTs.orElse(null), serverTs.orElse(null),
                pendingDownloads.contains(coord), downloadQueueSet.contains(coord));
        
        if (autoDownload && needsDownload) {
            queueDownload(coord);
            XaeroSync.LOGGER.info("Queued chunk {} for download from registry update (queue size now: {})", 
                    coord, downloadQueueSet.size());
        }
    }

    public void handleChunkData(S2CChunkDataPacket packet) {
        XaeroSync.LOGGER.debug("Received chunk data for {}:{},{} ({} bytes)",
                packet.getDimension(), packet.getX(), packet.getZ(), packet.getData().length);

        if (!syncEnabled) return;

        ResourceLocation dim = ResourceLocation.tryParse(packet.getDimension());
        if (dim == null) return;

        ChunkCoord coord = new ChunkCoord(dim, packet.getX(), packet.getZ());
        pendingDownloads.remove(coord);

        // Store in cache - the mixin will apply it when Xaero loads the region
        SyncedChunkCache.getInstance().store(coord, packet.getData(), packet.getTimestamp());
        timestampTracker.setLocalTimestamp(coord, packet.getTimestamp());
        
        // Try to apply immediately if region is already loaded
        // (The MapSaveLoadMixin will handle it if region loads later)
        SyncedChunkApplier.tryApplyChunk(coord);
        
        XaeroSync.LOGGER.debug("Cached chunk {} for application", coord);
    }

    public void handleUploadResult(S2CUploadResultPacket packet) {
        ResourceLocation dim = ResourceLocation.tryParse(packet.getDimension());
        if (dim == null) return;

        ChunkCoord coord = new ChunkCoord(dim, packet.getX(), packet.getZ());

        if (packet.isAccepted()) {
            // Update server timestamp to match what we uploaded
            Optional<Long> localTs = timestampTracker.getLocalTimestamp(coord);
            localTs.ifPresent(ts -> timestampTracker.setServerTimestamp(coord, ts));
        } else {
            XaeroSync.LOGGER.debug("Upload rejected for {}:{},{} - {}: {}",
                    packet.getDimension(), packet.getX(), packet.getZ(),
                    packet.getResult(), packet.getMessage());

            // For certain rejection types, update server timestamp to prevent immediate retry
            S2CUploadResultPacket.Result result = packet.getResult();
            if (result == S2CUploadResultPacket.Result.REJECTED_OUTDATED ||
                    result == S2CUploadResultPacket.Result.REJECTED_TOO_SOON) {
                // Server has data that's newer or recent enough - sync timestamps
                // Set server timestamp to current time so we don't keep trying
                Optional<Long> localTs = timestampTracker.getLocalTimestamp(coord);
                localTs.ifPresent(ts -> timestampTracker.setServerTimestamp(coord, ts));
            }
        }
    }

    // ==================== Chunk Exploration ====================

    private static void onChunkExplored(ChunkExplorationCallback.ChunkExplorationEvent event) {
        ClientSyncManager manager = getInstance();
        ChunkCoord coord = event.coord();

        if (!manager.syncEnabled || !manager.connected) {
            return;
        }
        if (!Config.CLIENT_AUTO_UPLOAD.get()) {
            return;
        }

        long now = System.currentTimeMillis();

        // Throttle local timestamp updates - only update if:
        // 1. Chunk has no local timestamp yet (first exploration), OR
        // 2. Enough time has passed since last local update
        Optional<Long> existingLocalTs = manager.timestampTracker.getLocalTimestamp(coord);
        if (existingLocalTs.isPresent() && (now - existingLocalTs.get()) < LOCAL_UPDATE_INTERVAL_MS) {
            // Recently updated locally - skip this update entirely
            return;
        }

        // Update local timestamp
        manager.timestampTracker.setLocalTimestamp(coord, now);

        if (!manager.registryComplete) {
            return;
        }

        // Check minimum update interval - don't queue if server would reject anyway
        long minIntervalMs = manager.serverMinUpdateIntervalMinutes * 60 * 1000L;
        Optional<Long> serverTimestamp = manager.timestampTracker.getServerTimestamp(coord);
        if (serverTimestamp.isPresent() && (now - serverTimestamp.get()) < minIntervalMs) {
            // Not enough time has passed since last server update
            return;
        }

        if (manager.timestampTracker.needsUpload(coord)) {
            // Debounce: skip if this chunk was recently queued
            Long lastQueued = manager.recentlyQueuedChunks.get(coord);
            if (lastQueued != null && (now - lastQueued) < DEBOUNCE_INTERVAL_MS) {
                return;
            }

            manager.recentlyQueuedChunks.put(coord, now);
            manager.queueUpload(coord);
            XaeroSync.LOGGER.debug("Queued chunk {} for upload (queue size: {})",
                    coord, manager.uploadQueueSet.size());
        }
    }

    // ==================== Queue Management ====================

    private void queueUpload(ChunkCoord coord) {
        uploadQueueSet.add(coord);
    }

    private void queueDownload(ChunkCoord coord) {
        if (pendingDownloads.contains(coord)) {
            XaeroSync.LOGGER.debug("Not queueing {} - already in pendingDownloads", coord);
            return;
        }
        boolean added = downloadQueueSet.add(coord);
        XaeroSync.LOGGER.debug("queueDownload({}) - added={}, queue size now={}", coord, added, downloadQueueSet.size());
    }

    private void queuePendingUploads() {
        Map<ChunkCoord, Long> needUpload = timestampTracker.getChunksNeedingUpload();
        int queued = 0;
        int skippedNotLoaded = 0;
        for (ChunkCoord coord : needUpload.keySet()) {
            if (!isDimensionAllowed(coord.dimension().toString())) {
                continue;
            }
            // Only queue chunks that actually exist in Xaero's map
            // Chunks from previous sessions may not be loaded in memory
            if (getMapTileChunk(coord) != null) {
                queueUpload(coord);
                queued++;
            } else {
                skippedNotLoaded++;
            }
        }
        if (queued > 0 || skippedNotLoaded > 0) {
            XaeroSync.LOGGER.info("Queued {} chunks for upload ({} skipped - not loaded in map)", 
                    queued, skippedNotLoaded);
        }
    }

    // ==================== Tick Processing ====================

    /**
     * Called every client tick to process queued uploads/downloads.
     */
    public void onTick() {
        if (!connected || !syncEnabled) return;

        long now = System.currentTimeMillis();

        // Periodically clean up old entries from debounce map to prevent memory growth
        if (now - lastDebounceCleanupTime > DEBOUNCE_CLEANUP_INTERVAL_MS) {
            lastDebounceCleanupTime = now;
            recentlyQueuedChunks.entrySet().removeIf(entry ->
                    (now - entry.getValue()) > DEBOUNCE_INTERVAL_MS);
        }

        // Periodically save timestamps to disk to prevent data loss on crash
        if (now - lastTimestampSaveTime > TIMESTAMP_SAVE_INTERVAL_MS) {
            lastTimestampSaveTime = now;
            timestampTracker.save();
        }

        // Periodically process cached chunks waiting to be applied
        // This handles chunks whose regions became ready without triggering the mixin
        if (now - lastCacheProcessTime > CACHE_PROCESS_INTERVAL_MS) {
            lastCacheProcessTime = now;
            SyncedChunkApplier.processPendingChunks(CACHE_PROCESS_MAX_CHUNKS);
        }

        // Periodically re-queue chunks that need uploading but aren't in the queue
        // This handles chunks that failed serialization (e.g., partial chunks) and may be ready now
        if (registryComplete && Config.CLIENT_AUTO_UPLOAD.get() && uploadQueueSet.isEmpty()) {
            if (now - lastRequeueTime > REQUEUE_INTERVAL_MS) {
                lastRequeueTime = now;
                int before = uploadQueueSet.size();
                queuePendingUploads();
                int added = uploadQueueSet.size() - before;
                if (added > 0) {
                    XaeroSync.LOGGER.debug("Re-queued {} chunks for upload", added);
                }
            }
        }
        
        // Update player position for distance-based prioritization
        updatePlayerPosition();

        // Process uploads - pick closest chunk to player
        while (!uploadQueueSet.isEmpty() && uploadLimiter.tryAcquire()) {
            ChunkCoord coord = pollClosest(uploadQueueSet);
            if (coord != null) {
                processUpload(coord);
            }
        }

        // Process download requests - pick closest chunk to player
        int downloadsProcessed = 0;
        while (!downloadQueueSet.isEmpty() && downloadLimiter.tryAcquire()) {
            ChunkCoord coord = pollClosest(downloadQueueSet);
            if (coord != null) {
                requestDownload(coord);
                downloadsProcessed++;
            }
        }
        
        // Periodic debug logging of queue state
        if (now % 5000 < 50) { // Log every ~5 seconds
            if (!downloadQueueSet.isEmpty() || !pendingDownloads.isEmpty()) {
                XaeroSync.LOGGER.info("[DEBUG] Download state: queue={}, pending={}, player=({},{})->sync({},{})", 
                        downloadQueueSet.size(), pendingDownloads.size(),
                        playerChunkX, playerChunkZ, playerChunkX >> 2, playerChunkZ >> 2);
            }
        }
    }
    
    /**
     * Update the cached player chunk position.
     */
    private void updatePlayerPosition() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            playerChunkX = player.chunkPosition().x;
            playerChunkZ = player.chunkPosition().z;
        }
    }
    
    /**
     * Poll and remove the closest chunk to the player from the set.
     */
    @Nullable
    private ChunkCoord pollClosest(Set<ChunkCoord> set) {
        if (set.isEmpty()) return null;
        
        ChunkCoord closest = null;
        int closestDistSq = Integer.MAX_VALUE;
        
        // Convert player chunk coords to our chunk coords (64-block chunks = 4 MC chunks)
        int playerSyncChunkX = playerChunkX >> 2;
        int playerSyncChunkZ = playerChunkZ >> 2;
        
        // Get current dimension for filtering
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation currentDim = mc.level != null ? mc.level.dimension().location() : null;
        
        for (ChunkCoord coord : set) {
            // Only consider chunks in the current dimension for distance calculation
            // (chunks in other dimensions get MAX_VALUE distance, effectively deprioritized)
            int distSq;
            if (currentDim != null && coord.dimension().equals(currentDim)) {
                int dx = coord.x() - playerSyncChunkX;
                int dz = coord.z() - playerSyncChunkZ;
                distSq = dx * dx + dz * dz;
            } else {
                distSq = Integer.MAX_VALUE - 1; // Other dimensions are lower priority
            }
            
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = coord;
            }
        }
        
        if (closest != null) {
            boolean removed = set.remove(closest);
            if (!removed) {
                XaeroSync.LOGGER.warn("[DEBUG] Failed to remove {} from set! Set size: {}", closest, set.size());
            }
        }
        return closest;
    }

    private void processUpload(ChunkCoord coord) {
        // Check minimum update interval before sending (save bandwidth)
        long now = System.currentTimeMillis();
        long minIntervalMs = serverMinUpdateIntervalMinutes * 60 * 1000L;
        Optional<Long> serverTimestamp = timestampTracker.getServerTimestamp(coord);

        if (serverTimestamp.isPresent() && (now - serverTimestamp.get()) < minIntervalMs) {
            // Server would reject this upload - skip it
            // Update local timestamp to match server so needsUpload() returns false
            long localTs = timestampTracker.getLocalTimestamp(coord).orElse(now);
            if (localTs <= serverTimestamp.get()) {
                // Local is not newer than server, no need to upload
                return;
            }
            // Local is newer but interval not met - will try again later via re-queue
            XaeroSync.LOGGER.debug("Skipping upload for {} - interval not met ({} min remaining)",
                    coord, (minIntervalMs - (now - serverTimestamp.get())) / 60000);
            return;
        }

        // Get the chunk from Xaero's map
        MapTileChunk chunk = getMapTileChunk(coord);
        if (chunk == null) {
            XaeroSync.LOGGER.debug("Chunk {} not found for upload", coord);
            return;
        }

        // Serialize
        byte[] data = ChunkSerializer.serialize(chunk, Minecraft.getInstance().level.registryAccess());
        if (data == null) {
            // Not a warning - this happens when tiles aren't fully loaded yet
            // The chunk will be re-queued later via periodic queuePendingUploads
            XaeroSync.LOGGER.debug("Chunk {} not ready for serialization", coord);
            return;
        }

        // Get timestamp
        long timestamp = timestampTracker.getLocalTimestamp(coord).orElse(now);

        // Send upload packet
        C2SUploadChunkPacket packet = new C2SUploadChunkPacket(
                coord.dimension().toString(),
                coord.x(),
                coord.z(),
                timestamp,
                data
        );
        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.SERVER.noArg(), packet);

        XaeroSync.LOGGER.debug("Uploading chunk {} ({} bytes)", coord, data.length);
    }

    private void requestDownload(ChunkCoord coord) {
        pendingDownloads.add(coord);

        C2SRequestChunksPacket packet = new C2SRequestChunksPacket(List.of(
                new C2SRequestChunksPacket.ChunkRequest(
                        coord.dimension().toString(),
                        coord.x(),
                        coord.z()
                )
        ));
        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.SERVER.noArg(), packet);

        XaeroSync.LOGGER.debug("Requesting chunk {}", coord);
    }

    // ==================== Helpers ====================

    @Nullable
    private MapTileChunk getMapTileChunk(ChunkCoord coord) {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) return null;

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) return null;

        // Get the current world map ID
        String worldId = processor.getCurrentWorldId();
        if (worldId == null) return null;

        // Find the region containing this chunk
        int regionX = coord.regionX();
        int regionZ = coord.regionZ();

        // Get the current dimension from the world
        // Note: processor.getCurrentDimId() returns a String ID, but getMapRegion needs int caveLayer
        MapRegion region = processor.getLeafMapRegion(
                Integer.MAX_VALUE, // Surface layer
                regionX,
                regionZ,
                false  // don't create if doesn't exist
        );

        if (region == null) return null;

        // Get the chunk from the region
        int localX = coord.localX();
        int localZ = coord.localZ();
        return region.getChunk(localX, localZ);
    }

    private boolean isDimensionAllowed(String dimensionId) {
        if (!allowedDimensions.isEmpty()) {
            return allowedDimensions.contains(dimensionId);
        }
        return !blacklistedDimensions.contains(dimensionId);
    }

    @Nullable
    private String getWorldId() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) return null;

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) return null;

        return processor.getCurrentWorldId();
    }

    // ==================== Getters ====================

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isRegistryComplete() {
        return registryComplete;
    }

    public int getUploadQueueSize() {
        return uploadQueueSet.size();
    }

    public int getDownloadQueueSize() {
        return downloadQueueSet.size();
    }

    public int getPendingDownloadsSize() {
        return pendingDownloads.size();
    }

    public int getCachedChunksCount() {
        return SyncedChunkCache.getInstance().getCachedCount();
    }

    public ClientTimestampTracker getTimestampTracker() {
        return timestampTracker;
    }
}
