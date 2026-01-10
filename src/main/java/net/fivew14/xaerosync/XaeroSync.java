package net.fivew14.xaerosync;

import com.mojang.logging.LogUtils;
import net.fivew14.xaerosync.client.XaeroSyncClient;
import net.fivew14.xaerosync.networking.XaeroSyncNetworking;
import net.fivew14.xaerosync.server.XaeroSyncServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(XaeroSync.MODID)
public class XaeroSync {
    public static final String MODID = "xaeromapsync";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static MinecraftServer currentServer;

    public XaeroSync() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Register network packets on both sides
        XaeroSyncNetworking.init();
        // Initialize server module (registers event handlers for both dedicated and integrated servers)
        XaeroSyncServer.init();
    }

    public static ResourceLocation id(String loc) {
        return new ResourceLocation(MODID, loc);
    }

    @SubscribeEvent 
    public void onServerStarting(ServerStartingEvent event) {
        currentServer = event.getServer();
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent 
        public static void onClientSetup(FMLClientSetupEvent event) {
            XaeroSyncClient.init();
        }
    }
}
