package net.fivew14.xaerosync.client.sync;

import net.fivew14.xaerosync.XaeroSync;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import xaero.map.region.MapBlock;
import xaero.map.region.MapPixel;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.region.Overlay;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes and deserializes Xaero MapTileChunk data for network transmission.
 * Uses a simplified format optimized for sync rather than full file storage.
 */
public class ChunkSerializer {
    
    private static final byte CURRENT_VERSION = 1;
    
    // Reflection for accessing protected fields in MapPixel
    private static Field lightField;
    private static Field glowingField;
    
    static {
        try {
            lightField = MapPixel.class.getDeclaredField("light");
            lightField.setAccessible(true);
            glowingField = MapPixel.class.getDeclaredField("glowing");
            glowingField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            XaeroSync.LOGGER.error("Failed to access MapPixel fields via reflection", e);
        }
    }
    
    private static byte getLight(MapPixel pixel) {
        try {
            return lightField != null ? lightField.getByte(pixel) : 0;
        } catch (IllegalAccessException e) {
            return 0;
        }
    }
    
    private static boolean isGlowing(MapPixel pixel) {
        try {
            return glowingField != null && glowingField.getBoolean(pixel);
        } catch (IllegalAccessException e) {
            return false;
        }
    }
    
    /**
     * Serialize a MapTileChunk to compressed byte array.
     */
    @Nullable
    public static byte[] serialize(MapTileChunk chunk, HolderLookup.Provider registryAccess) {
        if (chunk == null) return null;
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
                 DataOutputStream dos = new DataOutputStream(gzip)) {
                
                // Header
                dos.writeByte(CURRENT_VERSION);
                dos.writeInt(chunk.getX());
                dos.writeInt(chunk.getZ());
                
                // Build block state palette for this chunk
                Map<BlockState, Integer> blockPalette = new HashMap<>();
                List<BlockState> blockPaletteList = new ArrayList<>();
                
                // Build biome palette
                Map<ResourceKey<Biome>, Integer> biomePalette = new HashMap<>();
                List<ResourceKey<Biome>> biomePaletteList = new ArrayList<>();
                
                // First pass: collect palette entries
                for (int tx = 0; tx < 4; tx++) {
                    for (int tz = 0; tz < 4; tz++) {
                        MapTile tile = chunk.getTile(tx, tz);
                        if (tile == null || !tile.isLoaded()) continue;
                        
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                MapBlock block = tile.getBlock(x, z);
                                if (block == null) continue;
                                
                                BlockState state = block.getState();
                                if (state != null && !blockPalette.containsKey(state)) {
                                    blockPalette.put(state, blockPaletteList.size());
                                    blockPaletteList.add(state);
                                }
                                
                                ResourceKey<Biome> biome = block.getBiome();
                                if (biome != null && !biomePalette.containsKey(biome)) {
                                    biomePalette.put(biome, biomePaletteList.size());
                                    biomePaletteList.add(biome);
                                }
                                
                                // Also check overlays
                                ArrayList<Overlay> overlays = block.getOverlays();
                                if (overlays != null) {
                                    for (Overlay overlay : overlays) {
                                        BlockState overlayState = overlay.getState();
                                        if (overlayState != null && !blockPalette.containsKey(overlayState)) {
                                            blockPalette.put(overlayState, blockPaletteList.size());
                                            blockPaletteList.add(overlayState);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Write palettes
                writeBlockPalette(dos, blockPaletteList);
                writeBiomePalette(dos, biomePaletteList);
                
                // Write tiles
                for (int tx = 0; tx < 4; tx++) {
                    for (int tz = 0; tz < 4; tz++) {
                        MapTile tile = chunk.getTile(tx, tz);
                        if (tile == null || !tile.isLoaded()) {
                            dos.writeByte(0); // not present
                            continue;
                        }
                        
                        dos.writeByte(1); // present
                        dos.writeByte(tile.getWorldInterpretationVersion());
                        dos.writeInt(tile.getWrittenCaveStart());
                        dos.writeByte(tile.getWrittenCaveDepth());
                        
                        // Write blocks
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                MapBlock block = tile.getBlock(x, z);
                                serializeBlock(dos, block, blockPalette, biomePalette);
                            }
                        }
                    }
                }
            }
            
            return baos.toByteArray();
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to serialize chunk", e);
            return null;
        }
    }
    
    private static void writeBlockPalette(DataOutputStream dos, List<BlockState> palette) throws IOException {
        writeVarInt(dos, palette.size());
        for (BlockState state : palette) {
            CompoundTag tag = NbtUtils.writeBlockState(state);
            NbtIo.write(tag, dos);
        }
    }
    
    private static void writeBiomePalette(DataOutputStream dos, List<ResourceKey<Biome>> palette) throws IOException {
        writeVarInt(dos, palette.size());
        for (ResourceKey<Biome> biome : palette) {
            dos.writeUTF(biome.location().toString());
        }
    }
    
    private static void serializeBlock(DataOutputStream dos, @Nullable MapBlock block, 
                                       Map<BlockState, Integer> blockPalette,
                                       Map<ResourceKey<Biome>, Integer> biomePalette) throws IOException {
        if (block == null) {
            dos.writeByte(0); // null block marker
            return;
        }
        
        dos.writeByte(1); // block present
        
        // Write state palette index
        BlockState state = block.getState();
        if (state == null) {
            writeVarInt(dos, -1);
        } else {
            writeVarInt(dos, blockPalette.getOrDefault(state, -1));
        }
        
        // Height data
        dos.writeShort(block.getHeight());
        dos.writeShort(block.getTopHeight());
        
        // Light and glow (using reflection)
        dos.writeByte(getLight(block));
        dos.writeBoolean(isGlowing(block));
        
        // Slopes
        dos.writeByte(block.getVerticalSlope());
        dos.writeByte(block.getDiagonalSlope());
        
        // Biome
        ResourceKey<Biome> biome = block.getBiome();
        if (biome == null) {
            writeVarInt(dos, -1);
        } else {
            writeVarInt(dos, biomePalette.getOrDefault(biome, -1));
        }
        
        // Overlays
        ArrayList<Overlay> overlays = block.getOverlays();
        if (overlays == null || overlays.isEmpty()) {
            dos.writeByte(0);
        } else {
            dos.writeByte(overlays.size());
            for (Overlay overlay : overlays) {
                BlockState overlayState = overlay.getState();
                if (overlayState == null) {
                    writeVarInt(dos, -1);
                } else {
                    writeVarInt(dos, blockPalette.getOrDefault(overlayState, -1));
                }
                dos.writeByte(getLight(overlay));
                dos.writeByte((byte) overlay.getOpacity());
            }
        }
    }
    
    /**
     * Deserialize compressed byte array back to chunk data.
     */
    @Nullable
    public static DeserializedChunk deserialize(byte[] data, HolderLookup.Provider registryAccess) {
        if (data == null || data.length == 0) return null;
        
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            try (GZIPInputStream gzip = new GZIPInputStream(bais);
                 DataInputStream dis = new DataInputStream(gzip)) {
                
                byte version = dis.readByte();
                if (version != CURRENT_VERSION) {
                    XaeroSync.LOGGER.warn("Unknown chunk serialization version: {}", version);
                    return null;
                }
                
                int chunkX = dis.readInt();
                int chunkZ = dis.readInt();
                
                // Read palettes
                HolderLookup<Block> blockLookup = registryAccess.lookupOrThrow(Registries.BLOCK);
                List<BlockState> blockPalette = readBlockPalette(dis, blockLookup);
                List<ResourceKey<Biome>> biomePalette = readBiomePalette(dis);
                
                // Read tiles
                DeserializedTile[][] tiles = new DeserializedTile[4][4];
                for (int tx = 0; tx < 4; tx++) {
                    for (int tz = 0; tz < 4; tz++) {
                        byte present = dis.readByte();
                        if (present == 0) {
                            tiles[tx][tz] = null;
                            continue;
                        }
                        
                        byte worldInterpretationVersion = dis.readByte();
                        int writtenCaveStart = dis.readInt();
                        byte writtenCaveDepth = dis.readByte();
                        
                        DeserializedBlock[][] blocks = new DeserializedBlock[16][16];
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                blocks[x][z] = deserializeBlock(dis, blockPalette, biomePalette);
                            }
                        }
                        
                        tiles[tx][tz] = new DeserializedTile(
                            worldInterpretationVersion, writtenCaveStart, writtenCaveDepth, blocks
                        );
                    }
                }
                
                return new DeserializedChunk(chunkX, chunkZ, tiles);
            }
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to deserialize chunk", e);
            return null;
        }
    }
    
    private static List<BlockState> readBlockPalette(DataInputStream dis, HolderLookup<Block> blockLookup) throws IOException {
        int size = readVarInt(dis);
        List<BlockState> palette = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            CompoundTag tag = NbtIo.read(dis);
            BlockState state = NbtUtils.readBlockState(blockLookup, tag);
            palette.add(state);
        }
        return palette;
    }
    
    private static List<ResourceKey<Biome>> readBiomePalette(DataInputStream dis) throws IOException {
        int size = readVarInt(dis);
        List<ResourceKey<Biome>> palette = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String biomeId = dis.readUTF();
            ResourceLocation loc = ResourceLocation.tryParse(biomeId);
            if (loc != null) {
                palette.add(ResourceKey.create(Registries.BIOME, loc));
            } else {
                palette.add(null);
            }
        }
        return palette;
    }
    
    @Nullable
    private static DeserializedBlock deserializeBlock(DataInputStream dis,
                                                       List<BlockState> blockPalette,
                                                       List<ResourceKey<Biome>> biomePalette) throws IOException {
        byte present = dis.readByte();
        if (present == 0) {
            return null;
        }
        
        int stateIdx = readVarInt(dis);
        BlockState state = stateIdx >= 0 && stateIdx < blockPalette.size() ? blockPalette.get(stateIdx) : null;
        
        short height = dis.readShort();
        short topHeight = dis.readShort();
        byte light = dis.readByte();
        boolean glowing = dis.readBoolean();
        byte verticalSlope = dis.readByte();
        byte diagonalSlope = dis.readByte();
        
        int biomeIdx = readVarInt(dis);
        ResourceKey<Biome> biome = biomeIdx >= 0 && biomeIdx < biomePalette.size() ? biomePalette.get(biomeIdx) : null;
        
        int overlayCount = dis.readByte() & 0xFF;
        List<DeserializedOverlay> overlays = new ArrayList<>(overlayCount);
        for (int i = 0; i < overlayCount; i++) {
            int overlayStateIdx = readVarInt(dis);
            BlockState overlayState = overlayStateIdx >= 0 && overlayStateIdx < blockPalette.size() 
                ? blockPalette.get(overlayStateIdx) : null;
            byte overlayLight = dis.readByte();
            byte overlayOpacity = dis.readByte();
            overlays.add(new DeserializedOverlay(overlayState, overlayLight, overlayOpacity));
        }
        
        return new DeserializedBlock(state, height, topHeight, light, glowing, 
            verticalSlope, diagonalSlope, biome, overlays);
    }
    
    // ==================== VarInt Helpers ====================
    
    private static void writeVarInt(DataOutputStream dos, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            dos.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dos.writeByte(value);
    }
    
    private static int readVarInt(DataInputStream dis) throws IOException {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            b = dis.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
    
    // ==================== Data Classes ====================
    
    public record DeserializedChunk(int chunkX, int chunkZ, DeserializedTile[][] tiles) {}
    
    public record DeserializedTile(
        byte worldInterpretationVersion,
        int writtenCaveStart,
        byte writtenCaveDepth,
        DeserializedBlock[][] blocks
    ) {}
    
    public record DeserializedBlock(
        BlockState state,
        short height,
        short topHeight,
        byte light,
        boolean glowing,
        byte verticalSlope,
        byte diagonalSlope,
        ResourceKey<Biome> biome,
        List<DeserializedOverlay> overlays
    ) {}
    
    public record DeserializedOverlay(
        BlockState state,
        byte light,
        byte opacity
    ) {}
}
