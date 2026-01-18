package net.fivew14.xaerosync.mixin;

import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.client.sync.ChunkExplorationCallback;
import net.fivew14.xaerosync.client.sync.SyncedChunkApplier;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;
import xaero.map.biome.BlockTintProvider;
import xaero.map.region.OverlayManager;

/**
 * Mixin to detect when the player explores new map chunks.
 * Injects after a tile is written and marked as loaded.
 */
@Mixin(value = MapWriter.class, remap = false)
public abstract class MapWriterMixin {
    @Shadow(remap = false) private MapProcessor mapProcessor;

    /**
     * Inject after a tile has been written and committed to the chunk.
     * This captures when map data has been updated/created.
     */
    @Inject(
            method = "writeChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/region/MapTile;setLoaded(Z)V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void onChunkWritten(
            Level world, Registry<Block> blockRegistry, int distance, boolean onlyLoad, Registry<Biome> biomeRegistry, OverlayManager overlayManager, boolean loadChunks, boolean updateChunks, boolean ignoreHeightmaps, boolean flowers, boolean detailedDebug, BlockPos.MutableBlockPos mutableBlockPos3, BlockTintProvider blockTintProvider, int caveDepth, int caveStart, int layerToWrite, int tileChunkX, int tileChunkZ, int tileChunkLocalX, int tileChunkLocalZ, int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
        // Only care about surface layer
        if (layerToWrite != SyncedChunkApplier.SURFACE_LAYER) {
            return;
        }

        // Get dimension from the world
        ResourceKey<Level> dimension = world.dimension();

        // Check if dimension is allowed for sync
        if (!Config.isDimensionAllowed(dimension.location().toString())) {
            return;
        }

        // Convert to tile chunk coordinates (4 MC chunks = 1 tile chunk)
        // chunkX/chunkZ are MC chunk coords, tileChunkX/tileChunkZ are tile chunk coords
        ChunkCoord coord = new ChunkCoord(dimension.location(), tileChunkX, tileChunkZ);

        // Notify the callback that a chunk was explored
        ChunkExplorationCallback.onChunkExplored(coord, tileChunkLocalX, tileChunkLocalZ, mapProcessor);
    }
}
