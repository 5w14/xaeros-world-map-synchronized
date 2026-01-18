package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.*;

import java.util.List;
import java.util.Optional;

/**
 * Applies cached synced chunks to Xaero's loaded regions.
 * Called by the MapSaveLoadMixin after a region is loaded.
 */
public class SyncedChunkApplier {

    public static final int SURFACE_LAYER = Integer.MAX_VALUE;

    /**
     * Try to apply a single cached chunk immediately.
     * Called after storing a chunk in cache.
     * 
     * IMPORTANT: We never force regions to loaded state or create regions ourselves.
     * This ensures we don't interfere with Xaero's normal map generation.
     * We only apply synced data to regions that Xaero has already properly loaded.
     * 
     * If the region isn't ready, the chunk stays in cache and will be applied:
     * - Via the MapSaveLoadMixin when Xaero loads the region
     * - Via processPendingChunks() periodic check
     */
    public static void tryApplyChunk(ChunkCoord coord) {
        SyncedChunkCache cache = SyncedChunkCache.getInstance();
        if (!cache.hasChunk(coord)) {
            return;
        }

        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) return;

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) return;

        // Only get existing region - don't create one
        // Let Xaero handle region creation through its normal flow
        MapRegion region = processor.getLeafMapRegion(
                SURFACE_LAYER,
                coord.regionX(),
                coord.regionZ(),
                false  // Don't create - let Xaero handle region lifecycle
        );

        if (region == null) {
            // Region doesn't exist yet - chunk stays in cache for later
            XaeroSync.LOGGER.debug("Region not ready for {}, chunk stays in cache", coord);
            return;
        }

        int loadState = region.getLoadState();
        
        if (loadState != 2) {
            // Region exists but isn't fully loaded yet
            // The mixin will apply our data when Xaero finishes loading it
            XaeroSync.LOGGER.debug("Region not in loaded state ({}) for {}, chunk stays in cache", loadState, coord);
            return;
        }

        // Region is ready (loadState == 2) - apply the chunk
        int localX = coord.localX();
        int localZ = coord.localZ();

        boolean success = applyChunk(region, coord, localX, localZ);
        if (success) {
            cache.remove(coord);
            XaeroSync.LOGGER.debug("Immediately applied synced chunk {}", coord);
        }
    }

    /**
     * Process all pending cached chunks and try to apply them.
     * Called periodically from ClientSyncManager to handle chunks whose regions
     * became ready without triggering the mixin.
     * 
     * IMPORTANT: We never force regions to loaded state or create regions ourselves.
     * This ensures we don't interfere with Xaero's normal map generation.
     * 
     * @param maxChunks Maximum number of chunks to process per call (to avoid lag)
     * @return Number of chunks successfully applied
     */
    public static int processPendingChunks(int maxChunks) {
        SyncedChunkCache cache = SyncedChunkCache.getInstance();
        if (cache.getCachedCount() == 0) {
            return 0;
        }

        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) return 0;

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) return 0;

        int applied = 0;
        for (ChunkCoord coord : cache.getCachedCoords()) {
            if (applied >= maxChunks) break;

            // Only get existing region - don't create one
            MapRegion region = processor.getLeafMapRegion(
                    SURFACE_LAYER,
                    coord.regionX(),
                    coord.regionZ(),
                    false  // Don't create - let Xaero handle region lifecycle
            );

            // Only apply if region exists and is fully loaded
            if (region != null && region.getLoadState() == 2) {
                int localX = coord.localX();
                int localZ = coord.localZ();

                boolean success = applyChunk(region, coord, localX, localZ);
                if (success) {
                    cache.remove(coord);
                    applied++;
                    XaeroSync.LOGGER.debug("Applied pending cached chunk {}", coord);
                }
            }
        }

        if (applied > 0) {
            XaeroSync.LOGGER.debug("Processed {} pending cached chunks", applied);
        }

        return applied;
    }

    /**
     * Apply any cached synced chunks to a loaded region.
     * Called from MapSaveLoadMixin after Xaero loads a region from disk.
     * 
     * Note: At this point loadState may still be 1 (caller sets it to 2 after loadRegion returns),
     * but the region is ready for us to apply our data.
     */
    public static void applyToRegion(MapRegion region) {
        if (region == null) {
            return;
        }

        SyncedChunkCache cache = SyncedChunkCache.getInstance();
        if (cache.getCachedCount() == 0) {
            return;
        }

        // Get current dimension from the region
        String dimId = region.getDimId();
        if (dimId == null) {
            return;
        }

        ResourceLocation dimension = ResourceLocation.tryParse(dimId);
        if (dimension == null) {
            return;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();

        // Check each chunk position in this region (8x8 chunks per region)
        int appliedCount = 0;
        for (int localX = 0; localX < 8; localX++) {
            for (int localZ = 0; localZ < 8; localZ++) {
                int chunkX = regionX * 8 + localX;
                int chunkZ = regionZ * 8 + localZ;
                
                ChunkCoord coord = new ChunkCoord(dimension, chunkX, chunkZ);
                
                if (cache.hasChunk(coord)) {
                    boolean success = applyChunk(region, coord, localX, localZ);
                    if (success) {
                        appliedCount++;
                        cache.remove(coord); // Remove from cache after successful apply
                    }
                }
            }
        }

        if (appliedCount > 0) {
            XaeroSync.LOGGER.info("Applied {} synced chunks to region ({}, {})", 
                    appliedCount, regionX, regionZ);
        }
    }

    // Don't overwrite chunks that were updated locally within this time window
    // This gives Xaero time to write chunks the player is actively exploring
    private static final long RECENT_UPDATE_THRESHOLD_MS = 90_000; // 1.5 minutes
    
    /**
     * Apply a single cached chunk to the region.
     * 
     * We skip chunks that were recently updated locally - this means Xaero
     * is actively writing to them and we shouldn't overwrite.
     */
    private static boolean applyChunk(MapRegion region, ChunkCoord coord, int localX, int localZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        
        // Check if this chunk was recently updated locally
        // If so, Xaero is likely still working on it - don't overwrite
        ClientTimestampTracker tracker = ClientSyncManager.getInstance().getTimestampTracker();
        Optional<Long> localTimestamp = tracker.getLocalTimestamp(coord);
        
        if (localTimestamp.isPresent()) {
            long timeSinceUpdate = System.currentTimeMillis() - localTimestamp.get();
            if (timeSinceUpdate < RECENT_UPDATE_THRESHOLD_MS) {
                XaeroSync.LOGGER.debug("Chunk {} was updated {}ms ago, deferring sync", coord, timeSinceUpdate);
                return false;
            }
        }
        
        SyncedChunkCache cache = SyncedChunkCache.getInstance();
        SyncedChunkCache.CachedChunk cached = cache.load(coord);
        if (cached == null) {
            return false;
        }

        // Deserialize the chunk data
        ChunkSerializer.DeserializedChunk deserializedChunk =
                ChunkSerializer.deserialize(cached.data(), mc.level.registryAccess());

        if (deserializedChunk == null) {
            XaeroSync.LOGGER.warn("Failed to deserialize cached chunk {}", coord);
            return false;
        }

        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) {
            return false;
        }

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) {
            return false;
        }

        BlockStateShortShapeCache blockStateShortShapeCache = processor.getBlockStateShortShapeCache();
        if (blockStateShortShapeCache == null) {
            return false;
        }

        String dimensionStr = processor.getCurrentDimension();
        if (dimensionStr == null) {
            return false;
        }

        // Convert ResourceLocation dimension to string format for comparison
        String coordDimensionStr = coord.dimension().toString();

        // Validate dimension matches - if not, the chunk may be from a different dimension
        if (!dimensionStr.equals(coordDimensionStr) && !dimensionStr.equals(coord.dimension().getNamespace() + ":" + coord.dimension().getPath())) {
            XaeroSync.LOGGER.debug("Dimension mismatch for chunk {}: expected {}, got {}", coord, coordDimensionStr, dimensionStr);
            return false;
        }

        try {
            // Pause the writer while we modify the region
            // This ensures no conflict with Xaero's MapWriter
            region.pushWriterPause();

            MapTileChunk tileChunk;
            boolean createdNewChunk = false;

            // Synchronize on region when checking/creating chunks to avoid race conditions
            synchronized (region) {
                tileChunk = region.getChunk(localX, localZ);

                if (tileChunk == null) {
                    // Create a new chunk for synced data
                    int tileChunkX = coord.x();
                    int tileChunkZ = coord.z();
                    tileChunk = new MapTileChunk(region, tileChunkX, tileChunkZ);
                    region.setChunk(localX, localZ, tileChunk);
                    tileChunk.setLoadState((byte) 2);
                    region.setAllCachePrepared(false);
                    createdNewChunk = true;
                }
            }

            // Apply tiles from synced data
            // We overwrite existing tiles because synced data may be newer
            // (The writer is paused so no conflict with active generation)
            boolean anyTilesUpdated = false;
            for (int tx = 0; tx < 4; tx++) {
                for (int tz = 0; tz < 4; tz++) {
                    ChunkSerializer.DeserializedTile deserializedTile = deserializedChunk.tiles()[tx][tz];
                    if (deserializedTile == null) continue;

                    MapTile tile = tileChunk.getTile(tx, tz);
                    
                    if (tile == null) {
                        int tileChunkX16 = coord.x() * 4 + tx;
                        int tileChunkZ16 = coord.z() * 4 + tz;
                        if (tileChunkX16 < 0 || tileChunkZ16 < 0 ||
                            tileChunkX16 > Integer.MAX_VALUE / 2 || tileChunkZ16 > Integer.MAX_VALUE / 2) {
                            XaeroSync.LOGGER.warn("Invalid tile coordinates for chunk {}: ({}, {})", coord, tx, tz);
                            continue;
                        }
                        tile = processor.getTilePool().get(dimensionStr, tileChunkX16, tileChunkZ16);
                    }

                    // Apply block data (overwrites existing data with synced data)
                    applyTile(tile, deserializedTile, processor);

                    // Set tile properties
                    tile.setWrittenCave(deserializedTile.writtenCaveStart(), deserializedTile.writtenCaveDepth());
                    tile.setWorldInterpretationVersion(deserializedTile.worldInterpretationVersion());
                    tile.setLoaded(true);
                    tile.setWrittenOnce(true);

                    tileChunk.setTile(tx, tz, tile, blockStateShortShapeCache);
                    anyTilesUpdated = true;
                }
            }

            if (anyTilesUpdated) {
                tileChunk.setChanged(true);
                tileChunk.setToUpdateBuffers(true);
                tileChunk.setHasHadTerrain();
            }

            XaeroSync.LOGGER.debug("Applied synced chunk {} (new chunk: {}, tiles updated: {})", 
                    coord, createdNewChunk, anyTilesUpdated);
            
            // Return true to remove from cache - we've processed this chunk
            // (even if no tiles were updated, the data has been handled)
            return true;

        } finally {
            region.popWriterPause();
        }
    }

    /**
     * Apply deserialized tile data to a MapTile.
     */
    private static void applyTile(MapTile tile, ChunkSerializer.DeserializedTile deserializedTile,
                                   MapProcessor processor) {
        OverlayManager overlayManager = processor.getOverlayManager();
        OverlayBuilder overlayBuilder = new OverlayBuilder(overlayManager);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ChunkSerializer.DeserializedBlock deserializedBlock = deserializedTile.blocks()[x][z];
                if (deserializedBlock == null) continue;

                MapBlock block = tile.getBlock(x, z);
                if (block == null) {
                    block = new MapBlock();
                    tile.setBlock(x, z, block);
                }

                // Update block data
                block.write(
                        deserializedBlock.state(),
                        deserializedBlock.height(),
                        deserializedBlock.topHeight(),
                        deserializedBlock.biome(),
                        deserializedBlock.light(),
                        deserializedBlock.glowing(),
                        false // cave mode - surface only
                );

                // Restore overlays
                List<ChunkSerializer.DeserializedOverlay> overlays = deserializedBlock.overlays();
                if (overlays != null && !overlays.isEmpty()) {
                    overlayBuilder.startBuilding();
                    for (ChunkSerializer.DeserializedOverlay deserializedOverlay : overlays) {
                        if (deserializedOverlay.state() == null) continue;
                        overlayBuilder.build(
                                deserializedOverlay.state(),
                                deserializedOverlay.opacity(),
                                deserializedOverlay.light(),
                                processor,
                                null
                        );
                    }
                    overlayBuilder.finishBuilding(block);
                }

                // Restore slope data
                block.setVerticalSlope(deserializedBlock.verticalSlope());
                block.setDiagonalSlope(deserializedBlock.diagonalSlope());
                block.setSlopeUnknown(false);
            }
        }
    }
}
