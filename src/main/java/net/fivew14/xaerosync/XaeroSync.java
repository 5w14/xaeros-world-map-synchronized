package net.fivew14.xaerosync;

import com.mojang.logging.LogUtils;
import net.fivew14.xaerosync.client.XaeroSyncClient;
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
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(XaeroSync.MODID)
public class XaeroSync {
    public static final String MODID = "xaeromapsync";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static MinecraftServer currentServer;

    public XaeroSync() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::serverSetup);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    // Common setup
    public static void init() {
    }

    private void commonSetup(final FMLCommonSetupEvent event) { init(); }
    private void serverSetup(final FMLDedicatedServerSetupEvent event) { XaeroSyncServer.init(); }

    public static ResourceLocation id(String loc) {
        return new ResourceLocation(MODID, loc);
    }

    @SubscribeEvent public void onServerStarting(ServerStartingEvent event) {
        currentServer = event.getServer();
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent public static void onClientSetup(FMLClientSetupEvent event) {
            XaeroSyncClient.init();
        }
    }
}
