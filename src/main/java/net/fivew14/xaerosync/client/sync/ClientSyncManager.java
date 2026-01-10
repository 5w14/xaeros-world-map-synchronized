package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.RateLimiter;
import net.fivew14.xaerosync.networking.XaeroSyncNetworking;
import net.fivew14.xaerosync.networking.packets.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.PacketDistributor;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    
    // Queues
    private final Queue<ChunkCoord> uploadQueue = new ConcurrentLinkedQueue<>();
    private final Queue<ChunkCoord> downloadQueue = new ConcurrentLinkedQueue<>();
    
    // Pending chunks (waiting for data from server)
    private final Set<ChunkCoord> pendingDownloads = Collections.synchronizedSet(new HashSet<>());
    
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
        uploadQueue.clear();
        downloadQueue.clear();
        pendingDownloads.clear();
        XaeroSync.LOGGER.debug("XaeroSync: Connected to server");
    }
    
    public void onDisconnect() {
        connected = false;
        syncEnabled = false;
        registryComplete = false;
        timestampTracker.clearServerTimestamps();
        uploadQueue.clear();
        downloadQueue.clear();
        pendingDownloads.clear();
        XaeroSync.LOGGER.debug("XaeroSync: Disconnected from server");
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
        
        XaeroSync.LOGGER.info("XaeroSync: Received server config - sync={}, upload={}/s, download={}/s, minInterval={}min",
            syncEnabled, uploadRate, downloadRate, serverMinUpdateIntervalMinutes);
    }
    
    public void handleRegistryChunk(S2CRegistryChunkPacket packet) {
        XaeroSync.LOGGER.info("XaeroSync: Received registry batch {}/{} with {} entries (syncEnabled={})",
            packet.getBatchIndex() + 1, packet.getTotalBatches(), packet.getEntries().size(), syncEnabled);
        
        if (!syncEnabled) {
            XaeroSync.LOGGER.warn("XaeroSync: Ignoring registry - sync not enabled");
            return;
        }
        
        int queuedDownloads = 0;
        for (S2CRegistryChunkPacket.ChunkEntry entry : packet.getEntries()) {
            ResourceLocation dim = ResourceLocation.tryParse(entry.dimension());
            if (dim == null) continue;
            
            ChunkCoord coord = new ChunkCoord(dim, entry.x(), entry.z());
            timestampTracker.setServerTimestamp(coord, entry.timestamp());
            
            // Check if we need to download this chunk
            if (Config.CLIENT_AUTO_DOWNLOAD.get() && timestampTracker.needsDownload(coord)) {
                queueDownload(coord);
                queuedDownloads++;
            }
        }
        
        if (queuedDownloads > 0) {
            XaeroSync.LOGGER.info("XaeroSync: Queued {} chunks for download from batch", queuedDownloads);
        }
        
        if (packet.isLastBatch()) {
            registryComplete = true;
            XaeroSync.LOGGER.info("XaeroSync: Registry transfer complete - {} server chunks, download queue: {}", 
                timestampTracker.getServerCount(), downloadQueue.size());
            
            // Check for chunks that need uploading
            if (Config.CLIENT_AUTO_UPLOAD.get()) {
                queuePendingUploads();
            }
        }
    }
    
    public void handleRegistryUpdate(S2CRegistryUpdatePacket packet) {
        if (!syncEnabled) return;
        
        ResourceLocation dim = ResourceLocation.tryParse(packet.getDimension());
        if (dim == null) return;
        
        ChunkCoord coord = new ChunkCoord(dim, packet.getX(), packet.getZ());
        timestampTracker.setServerTimestamp(coord, packet.getTimestamp());
        
        // Check if we need to download this chunk
        if (Config.CLIENT_AUTO_DOWNLOAD.get() && timestampTracker.needsDownload(coord)) {
            queueDownload(coord);
        }
    }
    
    public void handleChunkData(S2CChunkDataPacket packet) {
        XaeroSync.LOGGER.info("XaeroSync: Received chunk data for {}:{},{} ({} bytes)",
            packet.getDimension(), packet.getX(), packet.getZ(), packet.getData().length);
        
        if (!syncEnabled) return;
        
        ResourceLocation dim = ResourceLocation.tryParse(packet.getDimension());
        if (dim == null) return;
        
        ChunkCoord coord = new ChunkCoord(dim, packet.getX(), packet.getZ());
        pendingDownloads.remove(coord);
        
        // Deserialize and insert into Xaero's map
        boolean success = ChunkInserter.insertChunk(coord, packet.getData(), packet.getTimestamp());
        
        if (success) {
            // Update local timestamp
            timestampTracker.setLocalTimestamp(coord, packet.getTimestamp());
            XaeroSync.LOGGER.info("XaeroSync: Inserted chunk {} into map", coord);
        } else {
            XaeroSync.LOGGER.warn("XaeroSync: Failed to insert chunk {} into map", coord);
        }
    }
    
    public void handleUploadResult(S2CUploadResultPacket packet) {
        if (packet.isAccepted()) {
            ResourceLocation dim = ResourceLocation.tryParse(packet.getDimension());
            if (dim != null) {
                ChunkCoord coord = new ChunkCoord(dim, packet.getX(), packet.getZ());
                // Update server timestamp to match what we uploaded
                Optional<Long> localTs = timestampTracker.getLocalTimestamp(coord);
                localTs.ifPresent(ts -> timestampTracker.setServerTimestamp(coord, ts));
            }
        } else {
            XaeroSync.LOGGER.debug("XaeroSync: Upload rejected for {}:{},{} - {}: {}", 
                packet.getDimension(), packet.getX(), packet.getZ(), 
                packet.getResult(), packet.getMessage());
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
            manager.queueUpload(coord);
            XaeroSync.LOGGER.debug("XaeroSync: Queued chunk {} for upload (queue size: {})", 
                coord, manager.uploadQueue.size());
        }
    }
    
    // ==================== Queue Management ====================
    
    private void queueUpload(ChunkCoord coord) {
        if (!uploadQueue.contains(coord)) {
            uploadQueue.add(coord);
        }
    }
    
    private void queueDownload(ChunkCoord coord) {
        if (!downloadQueue.contains(coord) && !pendingDownloads.contains(coord)) {
            downloadQueue.add(coord);
        }
    }
    
    private void queuePendingUploads() {
        Map<ChunkCoord, Long> needUpload = timestampTracker.getChunksNeedingUpload();
        for (ChunkCoord coord : needUpload.keySet()) {
            if (isDimensionAllowed(coord.dimension().toString())) {
                queueUpload(coord);
            }
        }
        XaeroSync.LOGGER.info("XaeroSync: Queued {} chunks for upload", needUpload.size());
    }
    
    // ==================== Tick Processing ====================
    
    /**
     * Called every client tick to process queued uploads/downloads.
     */
    public void onTick() {
        if (!connected || !syncEnabled) return;
        
        // Process uploads
        while (!uploadQueue.isEmpty() && uploadLimiter.tryAcquire()) {
            ChunkCoord coord = uploadQueue.poll();
            if (coord != null) {
                processUpload(coord);
            }
        }
        
        // Process download requests
        while (!downloadQueue.isEmpty() && downloadLimiter.tryAcquire()) {
            ChunkCoord coord = downloadQueue.poll();
            if (coord != null) {
                requestDownload(coord);
            }
        }
    }
    
    private void processUpload(ChunkCoord coord) {
        // Get the chunk from Xaero's map
        MapTileChunk chunk = getMapTileChunk(coord);
        if (chunk == null) {
            XaeroSync.LOGGER.debug("XaeroSync: Chunk {} not found for upload", coord);
            return;
        }
        
        // Serialize
        byte[] data = ChunkSerializer.serialize(chunk, Minecraft.getInstance().level.registryAccess());
        if (data == null) {
            XaeroSync.LOGGER.warn("XaeroSync: Failed to serialize chunk {}", coord);
            return;
        }
        
        // Get timestamp
        long timestamp = timestampTracker.getLocalTimestamp(coord).orElse(System.currentTimeMillis());
        
        // Send upload packet
        C2SUploadChunkPacket packet = new C2SUploadChunkPacket(
            coord.dimension().toString(),
            coord.x(),
            coord.z(),
            timestamp,
            data
        );
        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.SERVER.noArg(), packet);
        
        XaeroSync.LOGGER.debug("XaeroSync: Uploading chunk {} ({} bytes)", coord, data.length);
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
        
        XaeroSync.LOGGER.debug("XaeroSync: Requesting chunk {}", coord);
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
        return uploadQueue.size();
    }
    
    public int getDownloadQueueSize() {
        return downloadQueue.size();
    }
    
    public ClientTimestampTracker getTimestampTracker() {
        return timestampTracker;
    }
}
