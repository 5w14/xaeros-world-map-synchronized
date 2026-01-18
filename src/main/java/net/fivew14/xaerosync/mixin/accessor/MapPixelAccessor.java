package net.fivew14.xaerosync.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.region.MapPixel;

@Mixin(value = MapPixel.class, remap = false)
public interface MapPixelAccessor {

    @Accessor(value = "light", remap = false)
    byte xaeromapsync$getLight();

    @Accessor(value = "light", remap = false)
    void xaeromapsync$setLight(byte light);

    @Accessor(value = "glowing", remap = false)
    boolean xaeromapsync$isGlowing();

    @Accessor(value = "glowing", remap = false)
    void xaeromapsync$setGlowing(boolean glowing);
}
