package net.fivew14.xaerosync;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

@Mod.EventBusSubscriber(modid = XaeroSync.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    // ==================== SERVER CONFIG ====================
    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SERVER_SYNC_ENABLED;
    public static final ForgeConfigSpec.IntValue SERVER_MAX_UPLOAD_PER_SECOND;
    public static final ForgeConfigSpec.IntValue SERVER_MAX_DOWNLOAD_PER_SECOND;
    public static final ForgeConfigSpec.IntValue SERVER_REGISTRY_BATCH_SIZE;
    public static final ForgeConfigSpec.IntValue SERVER_REGISTRY_PACKETS_PER_SECOND;
    public static final ForgeConfigSpec.IntValue SERVER_MAX_CHUNK_DATA_SIZE;
    public static final ForgeConfigSpec.IntValue SERVER_MIN_UPDATE_INTERVAL_MINUTES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSION_WHITELIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSION_BLACKLIST;

    static {
        SERVER_BUILDER.comment("Server-side sync configuration").push("server");

        SERVER_SYNC_ENABLED = SERVER_BUILDER
                .comment("Enable map synchronization on the server")
                .define("syncEnabled", true);

        SERVER_MAX_UPLOAD_PER_SECOND = SERVER_BUILDER
                .comment("Maximum chunks per second the server will accept from a single client")
                .defineInRange("maxUploadPerSecond", 2, 1, 100);

        SERVER_MAX_DOWNLOAD_PER_SECOND = SERVER_BUILDER
                .comment("Maximum chunks per second the server will send to a single client")
                .defineInRange("maxDownloadPerSecond", 2, 1, 100);

        SERVER_REGISTRY_BATCH_SIZE = SERVER_BUILDER
                .comment("Number of chunk entries per registry packet")
                .defineInRange("registryBatchSize", 50, 1, 500);

        SERVER_REGISTRY_PACKETS_PER_SECOND = SERVER_BUILDER
                .comment("Registry packets per second during initial sync")
                .defineInRange("registryPacketsPerSecond", 2, 1, 20);

        SERVER_MAX_CHUNK_DATA_SIZE = SERVER_BUILDER
                .comment("Maximum chunk data size in bytes (for validation)")
                .defineInRange("maxChunkDataSize", 65536, 1024, 1048576);

        SERVER_MIN_UPDATE_INTERVAL_MINUTES = SERVER_BUILDER
                .comment("Minimum time (in minutes) before a chunk can be re-uploaded. Prevents excessive updates from small changes like doors opening.")
                .defineInRange("minUpdateIntervalMinutes", 5, 1, 60);

        DIMENSION_WHITELIST = SERVER_BUILDER
                .comment("Whitelist of dimensions to sync (empty = all allowed). Format: \"minecraft:overworld\"")
                .defineListAllowEmpty(List.of("dimensionWhitelist"), List::of, obj -> obj instanceof String);

        DIMENSION_BLACKLIST = SERVER_BUILDER
                .comment("Blacklist of dimensions (only checked if whitelist is empty). Format: \"minecraft:overworld\"")
                .defineListAllowEmpty(List.of("dimensionBlacklist"), List::of, obj -> obj instanceof String);

        SERVER_BUILDER.pop();
    }

    public static final ForgeConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    // ==================== CLIENT CONFIG ====================
    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue CLIENT_AUTO_UPLOAD;
    public static final ForgeConfigSpec.BooleanValue CLIENT_AUTO_DOWNLOAD;
    public static final ForgeConfigSpec.IntValue CLIENT_MAX_UPLOAD_PER_SECOND;
    public static final ForgeConfigSpec.IntValue CLIENT_MAX_DOWNLOAD_PER_SECOND;

    static {
        CLIENT_BUILDER.comment("Client-side sync configuration").push("client");

        CLIENT_AUTO_UPLOAD = CLIENT_BUILDER
                .comment("Automatically upload explored chunks to the server")
                .define("autoUpload", true);

        CLIENT_AUTO_DOWNLOAD = CLIENT_BUILDER
                .comment("Automatically download available chunks from the server")
                .define("autoDownload", true);

        CLIENT_MAX_UPLOAD_PER_SECOND = CLIENT_BUILDER
                .comment("Maximum chunks per second to upload")
                .defineInRange("maxUploadPerSecond", 2, 1, 100);

        CLIENT_MAX_DOWNLOAD_PER_SECOND = CLIENT_BUILDER
                .comment("Maximum chunks per second to request")
                .defineInRange("maxDownloadPerSecond", 2, 1, 100);

        CLIENT_BUILDER.pop();
    }

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ==================== HELPER METHODS ====================

    /**
     * Check if a dimension is allowed for sync based on whitelist/blacklist.
     */
    public static boolean isDimensionAllowed(String dimensionId) {
        List<? extends String> whitelist = DIMENSION_WHITELIST.get();
        if (!whitelist.isEmpty()) {
            return whitelist.contains(dimensionId);
        }
        List<? extends String> blacklist = DIMENSION_BLACKLIST.get();
        return !blacklist.contains(dimensionId);
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        // Config loaded/reloaded
    }
}
