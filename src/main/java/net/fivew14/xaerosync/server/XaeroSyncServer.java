package net.fivew14.xaerosync.server;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.networking.packets.C2SRequestChunksPacket;
import net.fivew14.xaerosync.networking.packets.C2SUploadChunkPacket;
import net.fivew14.xaerosync.server.commands.XaeroSyncCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-side initialization and event handling for XaeroSync.
 */
public class XaeroSyncServer {
    
    private static boolean initialized = false;
    
    public static void init() {
        if (initialized) return;
        initialized = true;
        
        // Register event handlers (network packets are registered in common setup)
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
        
        XaeroSync.LOGGER.info("XaeroSync server module initialized");
    }
    
    /**
     * Server-side event handler.
     */
    public static class ServerEventHandler {
        
        @SubscribeEvent
        public void onServerStarted(ServerStartedEvent event) {
            ServerSyncManager.init(event.getServer());
            XaeroSync.LOGGER.info("XaeroSync: Server sync manager started");
        }
        
        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            ServerSyncManager.shutdown();
            XaeroSync.LOGGER.info("XaeroSync: Server sync manager stopped");
        }
        
        @SubscribeEvent
        public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                ServerSyncManager manager = ServerSyncManager.getInstance();
                if (manager != null) {
                    manager.onPlayerJoin(player);
                }
            }
        }
        
        @SubscribeEvent
        public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                ServerSyncManager manager = ServerSyncManager.getInstance();
                if (manager != null) {
                    manager.onPlayerLeave(player);
                }
            }
        }
        
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ServerSyncManager manager = ServerSyncManager.getInstance();
                if (manager != null) {
                    manager.onTick();
                }
            }
        }
        
        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            XaeroSyncCommands.register(event.getDispatcher());
            XaeroSync.LOGGER.info("XaeroSync: Registered admin commands");
        }
    }
    
    /**
     * Handle chunk request packet from client.
     * Called from the packet handler.
     */
    public static void handleChunkRequest(C2SRequestChunksPacket packet, NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        
        ServerSyncManager manager = ServerSyncManager.getInstance();
        if (manager != null) {
            manager.handleChunkRequest(player, packet);
        }
    }
    
    /**
     * Handle chunk upload packet from client.
     * Called from the packet handler.
     */
    public static void handleChunkUpload(C2SUploadChunkPacket packet, NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        
        ServerSyncManager manager = ServerSyncManager.getInstance();
        if (manager != null) {
            manager.handleChunkUpload(player, packet);
        }
    }
}
