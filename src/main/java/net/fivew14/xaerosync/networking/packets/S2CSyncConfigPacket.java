package net.fivew14.xaerosync.networking.packets;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.client.sync.ClientSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> Client: Sends sync configuration to the client on join.
 * Includes rate limits and allowed dimensions so the client knows the server's constraints.
 */
public class S2CSyncConfigPacket {

    private final boolean syncEnabled;
    private final int maxUploadPerSecond;
    private final int maxDownloadPerSecond;
    private final int minUpdateIntervalMinutes;
    private final List<String> allowedDimensions; // Empty means all allowed (after blacklist check)
    private final List<String> blacklistedDimensions;

    public S2CSyncConfigPacket(boolean syncEnabled, int maxUploadPerSecond, int maxDownloadPerSecond,
                               int minUpdateIntervalMinutes,
                               List<String> allowedDimensions, List<String> blacklistedDimensions) {
        this.syncEnabled = syncEnabled;
        this.maxUploadPerSecond = maxUploadPerSecond;
        this.maxDownloadPerSecond = maxDownloadPerSecond;
        this.minUpdateIntervalMinutes = minUpdateIntervalMinutes;
        this.allowedDimensions = allowedDimensions;
        this.blacklistedDimensions = blacklistedDimensions;
    }

    public static void encode(S2CSyncConfigPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.syncEnabled);
        buf.writeVarInt(packet.maxUploadPerSecond);
        buf.writeVarInt(packet.maxDownloadPerSecond);
        buf.writeVarInt(packet.minUpdateIntervalMinutes);

        buf.writeVarInt(packet.allowedDimensions.size());
        for (String dim : packet.allowedDimensions) {
            buf.writeUtf(dim);
        }

        buf.writeVarInt(packet.blacklistedDimensions.size());
        for (String dim : packet.blacklistedDimensions) {
            buf.writeUtf(dim);
        }
    }

    public static S2CSyncConfigPacket decode(FriendlyByteBuf buf) {
        boolean syncEnabled = buf.readBoolean();
        int maxUploadPerSecond = buf.readVarInt();
        int maxDownloadPerSecond = buf.readVarInt();
        int minUpdateIntervalMinutes = buf.readVarInt();

        int allowedCount = buf.readVarInt();
        List<String> allowedDimensions = new ArrayList<>(allowedCount);
        for (int i = 0; i < allowedCount; i++) {
            allowedDimensions.add(buf.readUtf());
        }

        int blacklistCount = buf.readVarInt();
        List<String> blacklistedDimensions = new ArrayList<>(blacklistCount);
        for (int i = 0; i < blacklistCount; i++) {
            blacklistedDimensions.add(buf.readUtf());
        }

        return new S2CSyncConfigPacket(syncEnabled, maxUploadPerSecond, maxDownloadPerSecond,
                minUpdateIntervalMinutes, allowedDimensions, blacklistedDimensions);
    }

    public static void handle(S2CSyncConfigPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSyncManager manager = ClientSyncManager.getInstance();
            if (manager != null) {
                manager.handleSyncConfig(packet);
            } else {
                XaeroSync.LOGGER.warn("ClientSyncManager not initialized, ignoring sync config packet");
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // Getters
    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public int getMaxUploadPerSecond() {
        return maxUploadPerSecond;
    }

    public int getMaxDownloadPerSecond() {
        return maxDownloadPerSecond;
    }

    public List<String> getAllowedDimensions() {
        return allowedDimensions;
    }

    public List<String> getBlacklistedDimensions() {
        return blacklistedDimensions;
    }

    public int getMinUpdateIntervalMinutes() {
        return minUpdateIntervalMinutes;
    }
}
