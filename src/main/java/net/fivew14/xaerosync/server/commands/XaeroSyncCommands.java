package net.fivew14.xaerosync.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fivew14.xaerosync.Config;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.ChunkMetadata;
import net.fivew14.xaerosync.server.PlayerSyncState;
import net.fivew14.xaerosync.server.ServerSyncManager;
import net.fivew14.xaerosync.server.storage.ChunkRegistry;
import net.fivew14.xaerosync.server.storage.ServerSyncStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Admin commands for XaeroSync.
 * 
 * Commands:
 * - /xaerosync status - Show overall sync status
 * - /xaerosync info <player> - Show sync info for a specific player
 * - /xaerosync chunk <dimension> <x> <z> - Show info about a specific chunk
 * - /xaerosync delete <dimension> <x> <z> - Delete a specific chunk from server storage
 * - /xaerosync stats - Show detailed statistics
 */
public class XaeroSyncCommands {
    
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("xaerosync")
                .requires(source -> source.hasPermission(2)) // Requires OP level 2
                .then(Commands.literal("status")
                    .executes(XaeroSyncCommands::statusCommand))
                .then(Commands.literal("stats")
                    .executes(XaeroSyncCommands::statsCommand))
                .then(Commands.literal("info")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(XaeroSyncCommands::playerInfoCommand)))
                .then(Commands.literal("chunk")
                    .then(Commands.argument("dimension", StringArgumentType.string())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(XaeroSyncCommands::chunkInfoCommand)))))
                .then(Commands.literal("delete")
                    .then(Commands.argument("dimension", StringArgumentType.string())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(XaeroSyncCommands::deleteChunkCommand)))))
        );
    }
    
    /**
     * /xaerosync status - Show overall sync status
     */
    private static int statusCommand(CommandContext<CommandSourceStack> ctx) {
        ServerSyncManager manager = ServerSyncManager.getInstance();
        CommandSourceStack source = ctx.getSource();
        
        if (manager == null) {
            source.sendFailure(Component.literal("XaeroSync server manager is not initialized"));
            return 0;
        }
        
        ChunkRegistry registry = manager.getRegistry();
        
        source.sendSuccess(() -> Component.literal("=== XaeroSync Status ==="), false);
        source.sendSuccess(() -> Component.literal("Sync Enabled: " + Config.SERVER_SYNC_ENABLED.get()), false);
        source.sendSuccess(() -> Component.literal("Total Chunks in Registry: " + registry.size()), false);
        source.sendSuccess(() -> Component.literal("Upload Rate Limit: " + Config.SERVER_MAX_UPLOAD_PER_SECOND.get() + "/sec"), false);
        source.sendSuccess(() -> Component.literal("Download Rate Limit: " + Config.SERVER_MAX_DOWNLOAD_PER_SECOND.get() + "/sec"), false);
        
        // Count dimensions
        Map<ResourceLocation, Integer> dimCounts = new HashMap<>();
        for (ChunkCoord coord : registry.snapshot().keySet()) {
            dimCounts.merge(coord.dimension(), 1, Integer::sum);
        }
        
        if (!dimCounts.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Chunks by Dimension:"), false);
            for (Map.Entry<ResourceLocation, Integer> entry : dimCounts.entrySet()) {
                final String dimInfo = "  " + entry.getKey() + ": " + entry.getValue() + " chunks";
                source.sendSuccess(() -> Component.literal(dimInfo), false);
            }
        }
        
        // Connected players with sync state
        int connectedPlayers = ctx.getSource().getServer().getPlayerCount();
        source.sendSuccess(() -> Component.literal("Connected Players: " + connectedPlayers), false);
        
        return 1;
    }
    
    /**
     * /xaerosync stats - Show detailed statistics
     */
    private static int statsCommand(CommandContext<CommandSourceStack> ctx) {
        ServerSyncManager manager = ServerSyncManager.getInstance();
        CommandSourceStack source = ctx.getSource();
        
        if (manager == null) {
            source.sendFailure(Component.literal("XaeroSync server manager is not initialized"));
            return 0;
        }
        
        ChunkRegistry registry = manager.getRegistry();
        ServerSyncStorage storage = manager.getStorage();
        
        source.sendSuccess(() -> Component.literal("=== XaeroSync Statistics ==="), false);
        source.sendSuccess(() -> Component.literal("Registry Size: " + registry.size() + " chunks"), false);
        
        // Find oldest and newest chunks
        long oldest = Long.MAX_VALUE;
        long newest = Long.MIN_VALUE;
        
        for (Long timestamp : registry.snapshot().values()) {
            if (timestamp < oldest) oldest = timestamp;
            if (timestamp > newest) newest = timestamp;
        }
        
        if (oldest != Long.MAX_VALUE) {
            final String oldestStr = DATE_FORMAT.format(new Date(oldest));
            final String newestStr = DATE_FORMAT.format(new Date(newest));
            source.sendSuccess(() -> Component.literal("Oldest Chunk: " + oldestStr), false);
            source.sendSuccess(() -> Component.literal("Newest Chunk: " + newestStr), false);
        }
        
        // Config info
        source.sendSuccess(() -> Component.literal("--- Configuration ---"), false);
        source.sendSuccess(() -> Component.literal("Registry Batch Size: " + Config.SERVER_REGISTRY_BATCH_SIZE.get()), false);
        source.sendSuccess(() -> Component.literal("Registry Packets/Sec: " + Config.SERVER_REGISTRY_PACKETS_PER_SECOND.get()), false);
        source.sendSuccess(() -> Component.literal("Max Chunk Data Size: " + Config.SERVER_MAX_CHUNK_DATA_SIZE.get() + " bytes"), false);
        
        List<? extends String> whitelist = Config.DIMENSION_WHITELIST.get();
        List<? extends String> blacklist = Config.DIMENSION_BLACKLIST.get();
        
        if (!whitelist.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Dimension Whitelist: " + String.join(", ", whitelist)), false);
        }
        if (!blacklist.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Dimension Blacklist: " + String.join(", ", blacklist)), false);
        }
        
        return 1;
    }
    
    /**
     * /xaerosync info <player> - Show sync info for a specific player
     */
    private static int playerInfoCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            ServerSyncManager manager = ServerSyncManager.getInstance();
            
            if (manager == null) {
                source.sendFailure(Component.literal("XaeroSync server manager is not initialized"));
                return 0;
            }
            
            source.sendSuccess(() -> Component.literal("=== Player Sync Info: " + player.getName().getString() + " ==="), false);
            source.sendSuccess(() -> Component.literal("UUID: " + player.getUUID()), false);
            
            // Note: We'd need to expose PlayerSyncState from ServerSyncManager to get detailed info
            // For now, just show basic info
            source.sendSuccess(() -> Component.literal("Connected: Yes"), false);
            source.sendSuccess(() -> Component.literal("Dimension: " + player.level().dimension().location()), false);
            source.sendSuccess(() -> Component.literal("Position: " + player.blockPosition()), false);
            
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    /**
     * /xaerosync chunk <dimension> <x> <z> - Show info about a specific chunk
     */
    private static int chunkInfoCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        
        String dimensionStr = StringArgumentType.getString(ctx, "dimension");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        
        ResourceLocation dimension = ResourceLocation.tryParse(dimensionStr);
        if (dimension == null) {
            source.sendFailure(Component.literal("Invalid dimension: " + dimensionStr));
            return 0;
        }
        
        ServerSyncManager manager = ServerSyncManager.getInstance();
        if (manager == null) {
            source.sendFailure(Component.literal("XaeroSync server manager is not initialized"));
            return 0;
        }
        
        ChunkCoord coord = new ChunkCoord(dimension, x, z);
        ChunkRegistry registry = manager.getRegistry();
        ServerSyncStorage storage = manager.getStorage();
        
        Optional<Long> timestamp = registry.getTimestamp(coord);
        
        if (timestamp.isEmpty()) {
            source.sendFailure(Component.literal("Chunk not found in registry: " + coord));
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("=== Chunk Info: " + coord + " ==="), false);
        
        final String timestampStr = DATE_FORMAT.format(new Date(timestamp.get()));
        source.sendSuccess(() -> Component.literal("Timestamp: " + timestampStr), false);
        source.sendSuccess(() -> Component.literal("Timestamp (raw): " + timestamp.get()), false);
        
        // Try to read chunk data for more info
        ServerSyncStorage.ChunkData chunkData = storage.readChunk(coord);
        if (chunkData != null) {
            ChunkMetadata metadata = chunkData.metadata();
            final String contributorStr = metadata.contributor().toString();
            final int dataSize = chunkData.data().length;
            source.sendSuccess(() -> Component.literal("Contributor: " + contributorStr), false);
            source.sendSuccess(() -> Component.literal("Data Size: " + dataSize + " bytes"), false);
        }
        
        return 1;
    }
    
    /**
     * /xaerosync delete <dimension> <x> <z> - Delete a specific chunk from server storage
     */
    private static int deleteChunkCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        
        String dimensionStr = StringArgumentType.getString(ctx, "dimension");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        
        ResourceLocation dimension = ResourceLocation.tryParse(dimensionStr);
        if (dimension == null) {
            source.sendFailure(Component.literal("Invalid dimension: " + dimensionStr));
            return 0;
        }
        
        ServerSyncManager manager = ServerSyncManager.getInstance();
        if (manager == null) {
            source.sendFailure(Component.literal("XaeroSync server manager is not initialized"));
            return 0;
        }
        
        ChunkCoord coord = new ChunkCoord(dimension, x, z);
        ChunkRegistry registry = manager.getRegistry();
        ServerSyncStorage storage = manager.getStorage();
        
        if (registry.getTimestamp(coord).isEmpty()) {
            source.sendFailure(Component.literal("Chunk not found in registry: " + coord));
            return 0;
        }
        
        // Delete from storage
        boolean deleted = storage.deleteChunk(coord);
        if (deleted) {
            // Remove from registry
            registry.remove(coord);
            source.sendSuccess(() -> Component.literal("Deleted chunk: " + coord), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to delete chunk: " + coord));
            return 0;
        }
    }
}
