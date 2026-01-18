package net.fivew14.xaerosync.common;

import net.minecraft.resources.ResourceLocation;

/**
 * Utility methods for converting dimension IDs to/from filesystem-safe names.
 */
public final class DimensionUtils {

    private DimensionUtils() {
    } // Utility class

    /**
     * Convert a dimension ResourceLocation to a filesystem-safe name.
     * Replaces ':' with '$' to avoid filesystem issues.
     * <p>
     * Example: "minecraft:overworld" -> "minecraft$overworld"
     */
    public static String toFilesystemName(ResourceLocation dimension) {
        return dimension.getNamespace() + "$" + dimension.getPath();
    }

    /**
     * Convert a filesystem name back to a ResourceLocation.
     * <p>
     * Example: "minecraft$overworld" -> "minecraft:overworld"
     */
    public static ResourceLocation fromFilesystemName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        int dollarIndex = name.indexOf('$');
        if (dollarIndex == -1) {
            if (name.contains(":") || name.contains("/") || name.contains("\\")) {
                return null;
            }
            return new ResourceLocation("minecraft", name);
        }
        String namespace = name.substring(0, dollarIndex);
        String path = name.substring(dollarIndex + 1);

        if (namespace.isEmpty() || path.isEmpty()) {
            return null;
        }

        return new ResourceLocation(namespace, path);
    }

    /**
     * Check if a filesystem name is a valid dimension folder name.
     */
    public static boolean isValidDimensionFolder(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.contains("$")) {
            return !name.startsWith("$") && !name.endsWith("$");
        }
        return !name.contains(":") && !name.contains("/") && !name.contains("\\");
    }
}
