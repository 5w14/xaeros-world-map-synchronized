package net.fivew14.xaerosync.server;

import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.TimestampValidator;
import net.fivew14.xaerosync.networking.XaeroSyncNetworking;
import net.fivew14.xaerosync.networking.packets.*;
import net.fivew14.xaerosync.server.storage.ChunkRegistry;
import net.fivew14.xaerosync.server.storage.ServerSyncStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main server-side sync manager.
 * Handles player connections, registry distribution, upload/download requests.
 */
public class ServerSyncManager {

    private static ServerSyncManager instance;

    private final MinecraftServer server;
    private final ServerSyncStorage storage;
    private final ChunkRegistry registry;
    private final Map<UUID, PlayerSyncState> playerStates = new ConcurrentHashMap<>();

    // Cached registry entries for batch sending
    private List<Map.Entry<ChunkCoord, Long>> cachedRegistryEntries;
    private long lastRegistryCacheTime = 0;
    private static final long REGISTRY_CACHE_TTL_MS = 5000; // 5 seconds

    public ServerSyncManager(MinecraftServer server) {
        this.server = server;
        this.storage = ServerSyncStorage.create(server.overworld());
        this.registry = new ChunkRegistry();

        try {
            storage.initialize();
            storage.scanIntoRegistry(registry);
            XaeroSync.LOGGER.info("Loaded {} chunks from storage", registry.size());
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to initialize storage", e);
        }
    }

    public static void init(MinecraftServer server) {
        instance = new ServerSyncManager(server);
    }

    public static void shutdown() {
        instance = null;
    }

    @Nullable
    public static ServerSyncManager getInstance() {
        return instance;
    }

    // ==================== Player Events ====================

    /**
     * Called when a player joins the server.
     */
    public void onPlayerJoin(ServerPlayer player) {
        XaeroSync.LOGGER.info("onPlayerJoin called for {} (syncEnabled={}, shouldBeActive={})",
                player.getName().getString(), Config.SERVER_SYNC_ENABLED.get(), shouldSyncBeActive());

        if (!Config.SERVER_SYNC_ENABLED.get()) {
            XaeroSync.LOGGER.info("Sync disabled in config, skipping");
            return;
        }

        // Check if sync should be active (dedicated server or 2+ players on LAN)
        if (!shouldSyncBeActive()) {
            XaeroSync.LOGGER.info("Sync not active (dedicated={}, playerCount={})",
                    server.isDedicatedServer(), server.getPlayerCount());
            return;
        }

        // Create player state
        PlayerSyncState state = new PlayerSyncState(
                player,
                Config.SERVER_MAX_UPLOAD_PER_SECOND.get(),
                Config.SERVER_MAX_DOWNLOAD_PER_SECOND.get()
        );
        playerStates.put(player.getUUID(), state);

        // Send config packet
        sendConfigPacket(player);

        // Start registry transfer
        startRegistryTransfer(player, state);

        XaeroSync.LOGGER.info("Player {} joined, starting sync (registry size: {})",
                player.getName().getString(), registry.size());
    }

    /**
     * Called when a player leaves the server.
     */
    public void onPlayerLeave(ServerPlayer player) {
        playerStates.remove(player.getUUID());
        XaeroSync.LOGGER.debug("Player {} left, removed sync state", player.getName().getString());
    }

    /**
     * Called every server tick.
     */
    public void onTick() {
        if (!Config.SERVER_SYNC_ENABLED.get() || !shouldSyncBeActive()) {
            return;
        }

        long now = System.currentTimeMillis();
        long registryIntervalMs = 1000 / Config.SERVER_REGISTRY_PACKETS_PER_SECOND.get();

        for (Map.Entry<UUID, PlayerSyncState> entry : playerStates.entrySet()) {
            PlayerSyncState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;

            // Continue registry transfer if not complete
            if (!state.isRegistryTransferComplete()) {
                if (now - state.getLastRegistryTickTime() >= registryIntervalMs) {
                    sendNextRegistryBatch(player, state);
                    state.setLastRegistryTickTime(now);
                }
            }

            // Process pending downloads
            while (state.hasPendingDownloads() && state.canDownload()) {
                ChunkCoord coord = state.pollNextDownload();
                if (coord != null) {
                    sendChunkData(player, coord);
                }
            }
        }
    }

    // ==================== Config & Registry ====================

    private void sendConfigPacket(ServerPlayer player) {
        List<String> whitelist = new ArrayList<>(Config.DIMENSION_WHITELIST.get());
        List<String> blacklist = new ArrayList<>(Config.DIMENSION_BLACKLIST.get());

        S2CSyncConfigPacket packet = new S2CSyncConfigPacket(
                Config.SERVER_SYNC_ENABLED.get(),
                Config.SERVER_MAX_UPLOAD_PER_SECOND.get(),
                Config.SERVER_MAX_DOWNLOAD_PER_SECOND.get(),
                Config.SERVER_MIN_UPDATE_INTERVAL_MINUTES.get(),
                whitelist,
                blacklist
        );

        XaeroSync.LOGGER.info("Sending config packet to {} (syncEnabled={}, upload={}/s, download={}/s, minInterval={}min)",
                player.getName().getString(), packet.isSyncEnabled(),
                packet.getMaxUploadPerSecond(), packet.getMaxDownloadPerSecond(), packet.getMinUpdateIntervalMinutes());

        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private void startRegistryTransfer(ServerPlayer player, PlayerSyncState state) {
        List<Map.Entry<ChunkCoord, Long>> entries = getRegistryEntries();
        int batchSize = Config.SERVER_REGISTRY_BATCH_SIZE.get();
        int totalBatches = (int) Math.ceil((double) entries.size() / batchSize);

        state.setTotalRegistryBatches(totalBatches);
        state.setLastRegistryTickTime(System.currentTimeMillis());

        if (entries.isEmpty()) {
            // No chunks to send, mark complete immediately
            state.setRegistryTransferComplete(true);
            sendEmptyRegistryPacket(player);
        }
    }

    private void sendNextRegistryBatch(ServerPlayer player, PlayerSyncState state) {
        List<Map.Entry<ChunkCoord, Long>> entries = getRegistryEntries();
        int batchSize = Config.SERVER_REGISTRY_BATCH_SIZE.get();
        int batchIndex = state.getRegistryBatchesSent();
        int start = batchIndex * batchSize;

        if (start >= entries.size()) {
            state.setRegistryTransferComplete(true);
            return;
        }

        int end = Math.min(start + batchSize, entries.size());
        List<S2CRegistryChunkPacket.ChunkEntry> batch = new ArrayList<>();

        for (int i = start; i < end; i++) {
            Map.Entry<ChunkCoord, Long> entry = entries.get(i);
            ChunkCoord coord = entry.getKey();
            batch.add(new S2CRegistryChunkPacket.ChunkEntry(
                    coord.dimension().toString(),
                    coord.x(),
                    coord.z(),
                    entry.getValue()
            ));
        }

        boolean isLast = end >= entries.size();
        S2CRegistryChunkPacket packet = new S2CRegistryChunkPacket(
                batch, isLast, batchIndex, state.getTotalRegistryBatches()
        );

        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        state.incrementRegistryBatchesSent();

        if (isLast) {
            state.setRegistryTransferComplete(true);
        }
    }

    private void sendEmptyRegistryPacket(ServerPlayer player) {
        S2CRegistryChunkPacket packet = new S2CRegistryChunkPacket(
                Collections.emptyList(), true, 0, 0
        );
        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private List<Map.Entry<ChunkCoord, Long>> getRegistryEntries() {
        long now = System.currentTimeMillis();
        if (cachedRegistryEntries == null || now - lastRegistryCacheTime > REGISTRY_CACHE_TTL_MS) {
            cachedRegistryEntries = new ArrayList<>(registry.snapshot().entrySet());
            lastRegistryCacheTime = now;
        }
        return cachedRegistryEntries;
    }

    // ==================== Chunk Download ====================

    /**
     * Handle chunk request from client.
     */
    public void handleChunkRequest(ServerPlayer player, C2SRequestChunksPacket packet) {
        PlayerSyncState state = playerStates.get(player.getUUID());
        if (state == null) return;

        for (C2SRequestChunksPacket.ChunkRequest request : packet.getRequests()) {
            ResourceLocation dim = ResourceLocation.tryParse(request.dimension());
            if (dim == null) continue;

            if (!Config.isDimensionAllowed(dim.toString())) continue;

            ChunkCoord coord = new ChunkCoord(dim, request.x(), request.z());
            state.queueDownload(coord);
        }
    }

    private void sendChunkData(ServerPlayer player, ChunkCoord coord) {
        ServerSyncStorage.ChunkData chunkData = storage.readChunk(coord);
        if (chunkData == null) {
            XaeroSync.LOGGER.warn("Requested chunk {} not found in storage", coord);
            return;
        }

        S2CChunkDataPacket packet = new S2CChunkDataPacket(
                coord.dimension().toString(),
                coord.x(),
                coord.z(),
                chunkData.metadata().timestamp(),
                chunkData.data()
        );

        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    // ==================== Chunk Upload ====================

    /**
     * Handle chunk upload from client.
     */
    public void handleChunkUpload(ServerPlayer player, C2SUploadChunkPacket packet) {
        PlayerSyncState state = playerStates.get(player.getUUID());
        String dimension = packet.getDimension();
        int x = packet.getX();
        int z = packet.getZ();

        // Check if sync enabled
        if (!Config.SERVER_SYNC_ENABLED.get()) {
            sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_SYNC_DISABLED, null);
            return;
        }

        // Check rate limit
        if (state != null && !state.canUpload()) {
            sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_RATE_LIMITED, null);
            return;
        }

        // Check dimension allowed
        if (!Config.isDimensionAllowed(dimension)) {
            sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_DIMENSION_NOT_ALLOWED, null);
            return;
        }

        // Check data size
        if (packet.getData().length > Config.SERVER_MAX_CHUNK_DATA_SIZE.get()) {
            sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_DATA_TOO_LARGE, null);
            return;
        }

        ResourceLocation dim = ResourceLocation.tryParse(dimension);
        if (dim == null) {
            sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_INVALID_DATA, "Invalid dimension");
            return;
        }

        ChunkCoord coord = new ChunkCoord(dim, x, z);

        // Validate and sanitize timestamp
        long timestamp = TimestampValidator.sanitize(packet.getTimestamp());
        long now = System.currentTimeMillis();
        long minIntervalMs = Config.SERVER_MIN_UPDATE_INTERVAL_MINUTES.get() * 60 * 1000L;

        // Check if we have existing data
        Optional<Long> existingTimestamp = registry.getTimestamp(coord);
        if (existingTimestamp.isPresent()) {
            long existing = existingTimestamp.get();

            // Reject if server already has newer or equal data
            if (existing >= timestamp) {
                sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_OUTDATED, null);
                return;
            }

            // Reject if not enough time has passed since last update
            if (now - existing < minIntervalMs) {
                long remainingMinutes = (minIntervalMs - (now - existing)) / 60000 + 1;
                XaeroSync.LOGGER.debug("Chunk {} rejected - too soon, {} minutes remaining", coord, remainingMinutes);
                sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_TOO_SOON,
                        "Wait " + remainingMinutes + " more minute(s)");
                return;
            }
        }

        // Store the chunk
        boolean success = storage.writeChunk(coord, player.getUUID(), timestamp, packet.getData());
        if (!success) {
            sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.REJECTED_ERROR, "Storage error");
            return;
        }

        // Update registry
        registry.put(coord, timestamp);
        invalidateRegistryCache();

        // Send success response
        sendUploadResult(player, dimension, x, z, S2CUploadResultPacket.Result.ACCEPTED, null);

        // Broadcast registry update to all other players
        broadcastRegistryUpdate(player.getUUID(), coord, timestamp);

        XaeroSync.LOGGER.debug("Chunk {} uploaded by {}", coord, player.getName().getString());
    }

    private void sendUploadResult(ServerPlayer player, String dimension, int x, int z,
                                  S2CUploadResultPacket.Result result, @Nullable String message) {
        S2CUploadResultPacket packet = new S2CUploadResultPacket(dimension, x, z, result, message);
        XaeroSyncNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private void broadcastRegistryUpdate(UUID excludePlayer, ChunkCoord coord, long timestamp) {
        S2CRegistryUpdatePacket packet = new S2CRegistryUpdatePacket(
                coord.dimension().toString(),
                coord.x(),
                coord.z(),
                timestamp
        );

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getUUID().equals(excludePlayer) && playerStates.containsKey(player.getUUID())) {
                XaeroSyncNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    private void invalidateRegistryCache() {
        cachedRegistryEntries = null;
    }

    // ==================== Utility ====================

    /**
     * Check if sync should be active based on server type and player count.
     * - Dedicated servers: always enabled
     * - Integrated servers (LAN): enabled only when 2+ players connected
     */
    private boolean shouldSyncBeActive() {
        if (server.isDedicatedServer()) {
            return true;
        }
        // Integrated server (LAN) - only sync when 2+ players
        return server.getPlayerCount() >= 2;
    }

    public ChunkRegistry getRegistry() {
        return registry;
    }

    public ServerSyncStorage getStorage() {
        return storage;
    }
}
