package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.client.Minecraft;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.*;

/**
 * Inserts downloaded chunk data into Xaero's World Map.
 * <p>
 * NOTE: This is a simplified implementation. Full chunk insertion
 * requires more detailed integration with Xaero's internal APIs.
 */
public class ChunkInserter {

    // Cave layer for surface (we only sync surface for now)
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    /**
     * Insert downloaded chunk data into Xaero's map.
     * <p>
     * This implementation deserializes the data and attempts to update
     * existing tiles. Full tile creation would require deeper Xaero integration.
     */
    public static boolean insertChunk(ChunkCoord coord, byte[] data, long timestamp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            XaeroSync.LOGGER.warn("Cannot insert chunk - no level loaded");
            return false;
        }

        // Deserialize the chunk data
        ChunkSerializer.DeserializedChunk deserializedChunk =
                ChunkSerializer.deserialize(data, mc.level.registryAccess());

        if (deserializedChunk == null) {
            XaeroSync.LOGGER.warn("Failed to deserialize chunk {}", coord);
            return false;
        }

        // Get map processor
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null) {
            XaeroSync.LOGGER.warn("No WorldMapSession available");
            return false;
        }

        MapProcessor processor = session.getMapProcessor();
        if (processor == null) {
            XaeroSync.LOGGER.warn("No MapProcessor available");
            return false;
        }

        // Get the region
        int regionX = coord.regionX();
        int regionZ = coord.regionZ();

        MapRegion region = processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, true);
        if (region == null) {
            XaeroSync.LOGGER.warn("Failed to get/create region for {}", coord);
            return false;
        }

        // Get the BlockStateShortShapeCache for tile operations
        BlockStateShortShapeCache blockStateShortShapeCache = processor.getBlockStateShortShapeCache();
        if (blockStateShortShapeCache == null) {
            XaeroSync.LOGGER.warn("BlockStateShortShapeCache not available");
            return false;
        }

        // Get dimension string for tile pool
        String dimensionStr = processor.getCurrentDimension();

        try {
            // Pause the writer to avoid conflicts
            region.pushWriterPause();

            // Get or create the tile chunk
            int localX = coord.localX();
            int localZ = coord.localZ();

            // Calculate tileChunk coordinates (these are the absolute chunk coordinates)
            // Each MapTileChunk is 4x4 tiles (64x64 blocks)
            // tileChunkX and tileChunkZ are the absolute coordinates of this chunk
            int tileChunkX = coord.x();  // Already in 64-block units
            int tileChunkZ = coord.z();

            MapTileChunk tileChunk;
            boolean createdNewChunk = false;

            synchronized (region) {
                tileChunk = region.getChunk(localX, localZ);

                if (tileChunk == null) {
                    // Region must be in proper load state to create chunks
                    if (region.getLoadState() != 2) {
                        XaeroSync.LOGGER.debug("Region not in proper load state ({}), cannot create chunk {}",
                                region.getLoadState(), coord);
                        return false;
                    }

                    // Create a new MapTileChunk
                    tileChunk = new MapTileChunk(region, tileChunkX, tileChunkZ);
                    region.setChunk(localX, localZ, tileChunk);
                    tileChunk.setLoadState((byte) 2);
                    region.setAllCachePrepared(false);
                    createdNewChunk = true;
                    XaeroSync.LOGGER.debug("Created new MapTileChunk for {}", coord);
                }
            }

            // Update tiles with downloaded data
            boolean anyTilesUpdated = false;
            for (int tx = 0; tx < 4; tx++) {
                for (int tz = 0; tz < 4; tz++) {
                    ChunkSerializer.DeserializedTile deserializedTile = deserializedChunk.tiles()[tx][tz];
                    if (deserializedTile == null) continue;

                    MapTile tile = tileChunk.getTile(tx, tz);

                    if (tile == null) {
                        // Create a new MapTile from the tile pool
                        // Calculate the actual chunk coordinates for this tile
                        int tileChunkX16 = tileChunkX * 4 + tx;  // Convert to 16-block chunk coords
                        int tileChunkZ16 = tileChunkZ * 4 + tz;

                        tile = processor.getTilePool().get(dimensionStr, tileChunkX16, tileChunkZ16);
                        XaeroSync.LOGGER.debug("Created new MapTile for ({}, {}) in chunk {}", tx, tz, coord);
                    }

                    // Update blocks in the tile
                    updateTile(tile, deserializedTile);

                    // Set tile properties
                    tile.setWrittenCave(deserializedTile.writtenCaveStart(), deserializedTile.writtenCaveDepth());
                    tile.setWorldInterpretationVersion(deserializedTile.worldInterpretationVersion());
                    tile.setLoaded(true);
                    tile.setWrittenOnce(true);

                    // Set the tile into the chunk (this also updates the leaf texture heights)
                    tileChunk.setTile(tx, tz, tile, blockStateShortShapeCache);

                    anyTilesUpdated = true;
                }
            }

            if (anyTilesUpdated) {
                tileChunk.setChanged(true);
                tileChunk.setToUpdateBuffers(true);
                tileChunk.setHasHadTerrain();
                XaeroSync.LOGGER.debug("Updated chunk {} with downloaded data (created new: {})", coord, createdNewChunk);
            }

            return anyTilesUpdated;

        } finally {
            region.popWriterPause();
        }
    }

    private static void updateTile(MapTile tile, ChunkSerializer.DeserializedTile deserializedTile) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ChunkSerializer.DeserializedBlock deserializedBlock = deserializedTile.blocks()[x][z];
                if (deserializedBlock == null) continue;

                MapBlock block = tile.getBlock(x, z);
                if (block == null) {
                    block = new MapBlock();
                    tile.setBlock(x, z, block);
                }

                // Update block data using the write method
                block.write(
                        deserializedBlock.state(),
                        deserializedBlock.height(),
                        deserializedBlock.topHeight(),
                        deserializedBlock.biome(),
                        deserializedBlock.light(),
                        deserializedBlock.glowing(),
                        false // cave mode - we're doing surface
                );

                // Restore overlays (water, ice, glass, etc.)
                // The write() method clears overlays if the list is empty, so we add them after
                if (deserializedBlock.overlays() != null && !deserializedBlock.overlays().isEmpty()) {
                    for (ChunkSerializer.DeserializedOverlay deserializedOverlay : deserializedBlock.overlays()) {
                        if (deserializedOverlay.state() == null) continue;

                        // Create new Overlay with the deserialized data
                        Overlay overlay = new Overlay(
                                deserializedOverlay.state(),
                                deserializedOverlay.light(),
                                deserializedOverlay.glowing()
                        );

                        // Set opacity (Overlay starts at 0, we need to increase it)
                        if (deserializedOverlay.opacity() > 0) {
                            overlay.increaseOpacity(deserializedOverlay.opacity());
                        }

                        block.addOverlay(overlay);
                    }
                }

                // Also restore slope data
                block.setVerticalSlope(deserializedBlock.verticalSlope());
                block.setDiagonalSlope(deserializedBlock.diagonalSlope());
                block.setSlopeUnknown(false);
            }
        }
    }
}
