package net.fivew14.xaerosync.mixin;

import net.fivew14.xaerosync.client.sync.SyncedChunkApplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.biome.BiomeGetter;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;

/**
 * Mixin to hook into Xaero's region loading.
 * After a region is successfully loaded, we apply any cached synced chunks.
 */
@Mixin(value = MapSaveLoad.class, remap = false)
public abstract class MapSaveLoadMixin {

    /**
     * Inject after loadRegion successfully loads a region.
     * This is where we apply our cached synced chunks.
     * <p>
     * Note: At this point the region's loadState may still be 1 (it gets set to 2 by the caller),
     * but the chunks inside are loaded and ready for our data.
     */
    @Inject(
            method = "loadRegion",
            at = @At("RETURN"),
            remap = false
    )
    private void onRegionLoaded(
            MapRegion region, HolderLookup<Block> blockLookup, Registry<Block> blockRegistry, Registry<Fluid> fluidRegistry,
            BiomeGetter biomeGetter, boolean fastLoad, int extraAttempts, CallbackInfoReturnable<Boolean> cir
    ) {
        // Only apply if the region was successfully loaded
        // Don't check region.getLoadState() == 2 because the caller sets that AFTER this method returns
        if (cir.getReturnValue()) {
            SyncedChunkApplier.applyToRegion(region);
        }
    }
}
