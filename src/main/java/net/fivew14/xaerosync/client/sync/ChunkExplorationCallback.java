package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import xaero.map.MapProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Callback system for chunk exploration events.
 * Called by the MapWriterMixin when a chunk is explored/updated.
 */
public class ChunkExplorationCallback {

    private static final List<Consumer<ChunkExplorationEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener for chunk exploration events.
     */
    public static void register(Consumer<ChunkExplorationEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Unregister a listener.
     */
    public static void unregister(Consumer<ChunkExplorationEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * Called by the mixin when a chunk tile is explored/updated.
     */
    public static void onChunkExplored(ChunkCoord coord, int tileLocalX, int tileLocalZ, MapProcessor mapProcessor) {
        if (listeners.isEmpty()) {
            return;
        }

        ChunkExplorationEvent event = new ChunkExplorationEvent(coord, tileLocalX, tileLocalZ, mapProcessor);
        for (Consumer<ChunkExplorationEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                XaeroSync.LOGGER.error("Error in chunk exploration listener", e);
            }
        }
    }

    /**
     * Event data for chunk exploration.
     */
    public record ChunkExplorationEvent(
            ChunkCoord coord,
            int tileLocalX, // Local tile index within the chunk (0-3)
            int tileLocalZ,
            MapProcessor mapProcessor
    ) {
    }
}
