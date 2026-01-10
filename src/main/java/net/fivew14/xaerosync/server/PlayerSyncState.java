package net.fivew14.xaerosync.server;

import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.RateLimiter;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Tracks sync state for a connected player on the server.
 * Manages registry transfer progress, pending downloads, and rate limiting.
 */
public class PlayerSyncState {

    private final UUID playerId;
    private final String playerName;
    private final RateLimiter uploadLimiter;
    private final RateLimiter downloadLimiter;

    // Registry transfer state
    private boolean registryTransferComplete = false;
    private int registryBatchesSent = 0;
    private int totalRegistryBatches = 0;

    // Pending download queue (chunks requested by player, waiting to be sent)
    private final Queue<ChunkCoord> pendingDownloads = new LinkedList<>();

    // Last tick time for rate-limited operations
    private long lastRegistryTickTime = 0;

    public PlayerSyncState(ServerPlayer player, int maxUploadsPerSec, int maxDownloadsPerSec) {
        this.playerId = player.getUUID();
        this.playerName = player.getName().getString();
        this.uploadLimiter = new RateLimiter(maxUploadsPerSec);
        this.downloadLimiter = new RateLimiter(maxDownloadsPerSec);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    // ==================== Rate Limiting ====================

    /**
     * Check if player can upload (rate limit check).
     */
    public boolean canUpload() {
        return uploadLimiter.tryAcquire();
    }

    /**
     * Check if player can receive a download (rate limit check).
     */
    public boolean canDownload() {
        return downloadLimiter.tryAcquire();
    }

    // ==================== Registry Transfer ====================

    public boolean isRegistryTransferComplete() {
        return registryTransferComplete;
    }

    public void setRegistryTransferComplete(boolean complete) {
        this.registryTransferComplete = complete;
    }

    public int getRegistryBatchesSent() {
        return registryBatchesSent;
    }

    public void incrementRegistryBatchesSent() {
        registryBatchesSent++;
    }

    public int getTotalRegistryBatches() {
        return totalRegistryBatches;
    }

    public void setTotalRegistryBatches(int total) {
        this.totalRegistryBatches = total;
    }

    public long getLastRegistryTickTime() {
        return lastRegistryTickTime;
    }

    public void setLastRegistryTickTime(long time) {
        this.lastRegistryTickTime = time;
    }

    // ==================== Download Queue ====================

    /**
     * Add a chunk to the pending downloads queue.
     */
    public void queueDownload(ChunkCoord coord) {
        if (!pendingDownloads.contains(coord)) {
            pendingDownloads.add(coord);
        }
    }

    /**
     * Get the next chunk to download (and remove from queue).
     */
    public ChunkCoord pollNextDownload() {
        return pendingDownloads.poll();
    }

    /**
     * Check if there are pending downloads.
     */
    public boolean hasPendingDownloads() {
        return !pendingDownloads.isEmpty();
    }

    /**
     * Get number of pending downloads.
     */
    public int getPendingDownloadCount() {
        return pendingDownloads.size();
    }

    /**
     * Clear all pending downloads.
     */
    public void clearPendingDownloads() {
        pendingDownloads.clear();
    }
}
