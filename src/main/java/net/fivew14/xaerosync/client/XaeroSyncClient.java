package net.fivew14.xaerosync.client;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.client.commands.XaeroSyncClientCommands;
import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Client-side initialization and event handling for XaeroSync.
 */
public class XaeroSyncClient {
    
    private static boolean initialized = false;
    
    public static void init() {
        if (initialized) return;
        initialized = true;
        
        // Initialize the client sync manager
        ClientSyncManager.init();
        
        // Register event handlers
        MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
        
        XaeroSync.LOGGER.info("XaeroSync client module initialized");
    }
    
    /**
     * Client-side event handler.
     */
    public static class ClientEventHandler {
        
        @SubscribeEvent
        public void onClientPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            XaeroSync.LOGGER.info("XaeroSync: Client player logging in");
            ClientSyncManager.getInstance().onConnect();
        }
        
        @SubscribeEvent
        public void onClientPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            XaeroSync.LOGGER.info("XaeroSync: Client player logging out");
            ClientSyncManager.getInstance().onDisconnect();
        }
        
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ClientSyncManager.getInstance().onTick();
            }
        }
        
        @SubscribeEvent
        public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
            XaeroSyncClientCommands.register(event.getDispatcher());
            XaeroSync.LOGGER.info("XaeroSync: Registered client commands");
        }
    }
}
