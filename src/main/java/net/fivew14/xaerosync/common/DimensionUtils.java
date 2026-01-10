package net.fivew14.xaerosync.common;

import net.minecraft.resources.ResourceLocation;

/**
 * Utility methods for converting dimension IDs to/from filesystem-safe names.
 */
public final class DimensionUtils {
    
    private DimensionUtils() {} // Utility class
    
    /**
     * Convert a dimension ResourceLocation to a filesystem-safe name.
     * Replaces ':' with '$' to avoid filesystem issues.
     * 
     * Example: "minecraft:overworld" -> "minecraft$overworld"
     */
    public static String toFilesystemName(ResourceLocation dimension) {
        return dimension.getNamespace() + "$" + dimension.getPath();
    }
    
    /**
     * Convert a filesystem name back to a ResourceLocation.
     * 
     * Example: "minecraft$overworld" -> "minecraft:overworld"
     */
    public static ResourceLocation fromFilesystemName(String name) {
        int dollarIndex = name.indexOf('$');
        if (dollarIndex == -1) {
            // Fallback: treat as path with minecraft namespace
            return new ResourceLocation("minecraft", name);
        }
        String namespace = name.substring(0, dollarIndex);
        String path = name.substring(dollarIndex + 1);
        return new ResourceLocation(namespace, path);
    }
    
    /**
     * Check if a filesystem name is a valid dimension folder name.
     */
    public static boolean isValidDimensionFolder(String name) {
        return name.contains("$") && !name.startsWith("$") && !name.endsWith("$");
    }
}
