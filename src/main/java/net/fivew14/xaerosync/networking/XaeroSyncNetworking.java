package net.fivew14.xaerosync.networking;

import net.fivew14.xaerosync.XaeroSync;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class XaeroSyncNetworking {
    public static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry
            .newSimpleChannel(XaeroSync.id("network"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void init() {
        int idx = 0;
    }
}
