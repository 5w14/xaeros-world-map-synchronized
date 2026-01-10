package net.fivew14.xaerosync.client.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.fivew14.xaerosync.client.sync.ClientTimestampTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Client-side commands for XaeroSync.
 * <p>
 * Commands:
 * - /xaerosync client status - Show client sync status
 * - /xaerosync client queue - Show upload/download queue status
 * - /xaerosync client config - Show client configuration
 */
public class XaeroSyncClientCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("xaerosync")
                        .then(Commands.literal("client")
                                .then(Commands.literal("status")
                                        .executes(XaeroSyncClientCommands::statusCommand))
                                .then(Commands.literal("queue")
                                        .executes(XaeroSyncClientCommands::queueCommand))
                                .then(Commands.literal("config")
                                        .executes(XaeroSyncClientCommands::configCommand)))
        );
    }

    /**
     * /xaerosync client status - Show client sync status
     */
    private static int statusCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ClientSyncManager manager = ClientSyncManager.getInstance();

        source.sendSuccess(() -> Component.literal("=== XaeroSync Client Status ==="), false);
        source.sendSuccess(() -> Component.literal("Connected: " + manager.isConnected()), false);
        source.sendSuccess(() -> Component.literal("Sync Enabled: " + manager.isSyncEnabled()), false);
        source.sendSuccess(() -> Component.literal("Registry Complete: " + manager.isRegistryComplete()), false);

        ClientTimestampTracker tracker = manager.getTimestampTracker();
        source.sendSuccess(() -> Component.literal("Server Chunks Known: " + tracker.getServerCount()), false);
        source.sendSuccess(() -> Component.literal("Local Chunks Tracked: " + tracker.getLocalCount()), false);

        source.sendSuccess(() -> Component.literal("Upload Queue: " + manager.getUploadQueueSize()), false);
        source.sendSuccess(() -> Component.literal("Download Queue: " + manager.getDownloadQueueSize()), false);

        // Current dimension
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            source.sendSuccess(() -> Component.literal("Current Dimension: " + mc.level.dimension().location()), false);
        }

        return 1;
    }

    /**
     * /xaerosync client queue - Show upload/download queue status
     */
    private static int queueCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ClientSyncManager manager = ClientSyncManager.getInstance();

        source.sendSuccess(() -> Component.literal("=== XaeroSync Queue Status ==="), false);
        source.sendSuccess(() -> Component.literal("Upload Queue Size: " + manager.getUploadQueueSize()), false);
        source.sendSuccess(() -> Component.literal("Download Queue Size: " + manager.getDownloadQueueSize()), false);
        source.sendSuccess(() -> Component.literal("Pending Downloads: " + manager.getPendingDownloadsSize()), false);

        ClientTimestampTracker tracker = manager.getTimestampTracker();
        int needingUpload = tracker.getChunksNeedingUpload().size();
        int needingDownload = tracker.getChunksNeedingDownload().size();
        String worldId = tracker.getCurrentWorldId();

        source.sendSuccess(() -> Component.literal("Chunks Needing Upload: " + needingUpload), false);
        source.sendSuccess(() -> Component.literal("Chunks Needing Download: " + needingDownload), false);
        source.sendSuccess(() -> Component.literal("Local Timestamps: " + tracker.getLocalCount()), false);
        source.sendSuccess(() -> Component.literal("Server Timestamps: " + tracker.getServerCount()), false);
        source.sendSuccess(() -> Component.literal("World ID: " + (worldId != null ? worldId : "(not set)")), false);

        if (!manager.isSyncEnabled()) {
            source.sendSuccess(() -> Component.literal("Note: Sync is disabled - queues won't process"), false);
        } else if (!manager.isConnected()) {
            source.sendSuccess(() -> Component.literal("Note: Not connected - queues won't process"), false);
        }

        return 1;
    }

    /**
     * /xaerosync client config - Show client configuration
     */
    private static int configCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        source.sendSuccess(() -> Component.literal("=== XaeroSync Client Config ==="), false);
        source.sendSuccess(() -> Component.literal("Auto Upload: " + Config.CLIENT_AUTO_UPLOAD.get()), false);
        source.sendSuccess(() -> Component.literal("Auto Download: " + Config.CLIENT_AUTO_DOWNLOAD.get()), false);
        source.sendSuccess(() -> Component.literal("Max Upload/Sec: " + Config.CLIENT_MAX_UPLOAD_PER_SECOND.get()), false);
        source.sendSuccess(() -> Component.literal("Max Download/Sec: " + Config.CLIENT_MAX_DOWNLOAD_PER_SECOND.get()), false);

        return 1;
    }
}
