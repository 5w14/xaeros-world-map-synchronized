package net.fivew14.xaerosync.server;

import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.RateLimiter;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks sync state for a connected player on the server.
 * Manages registry transfer progress, pending downloads, and rate limiting.
 * Thread-safe implementation.
 */
public class PlayerSyncState {

    private static final int MAX_PENDING_DOWNLOADS = 1000;

    private final UUID playerId;
    private final String playerName;
    private final RateLimiter uploadLimiter;
    private final RateLimiter downloadLimiter;

    private final AtomicBoolean registryTransferComplete = new AtomicBoolean(false);
    private final AtomicInteger registryBatchesSent = new AtomicInteger(0);
    private int totalRegistryBatches = 0;

    private final ConcurrentLinkedQueue<ChunkCoord> pendingDownloads = new ConcurrentLinkedQueue<>();

    private volatile long lastRegistryTickTime = 0;

    private volatile boolean syncEnabled = true;

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
        return registryTransferComplete.get();
    }

    public void setRegistryTransferComplete(boolean complete) {
        registryTransferComplete.set(complete);
    }

    public int getRegistryBatchesSent() {
        return registryBatchesSent.get();
    }

    public void incrementRegistryBatchesSent() {
        registryBatchesSent.incrementAndGet();
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

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean enabled) {
        this.syncEnabled = enabled;
    }

    // ==================== Download Queue ====================

    /**
     * Add a chunk to the pending downloads queue.
     */
    public void queueDownload(ChunkCoord coord) {
        if (pendingDownloads.size() >= MAX_PENDING_DOWNLOADS) {
            return;
        }
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
