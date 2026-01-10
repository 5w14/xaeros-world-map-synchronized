package net.fivew14.xaerosync.server.storage;

import net.fivew14.xaerosync.XaeroSync;
import net.fivew14.xaerosync.common.ChunkCoord;
import net.fivew14.xaerosync.common.ChunkMetadata;
import net.fivew14.xaerosync.common.DimensionUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Handles file I/O for synced chunk data on the server.
 * 
 * Storage structure:
 * WORLD_FOLDER/.xaerosync/{dimension}/{x}_{z}.bin
 * 
 * Each .bin file contains:
 * - 28-byte header (ChunkMetadata: version, UUID, timestamp)
 * - GZIP-compressed Xaero format data
 */
public class ServerSyncStorage {
    
    private static final String STORAGE_FOLDER = ".xaerosync";
    
    private final Path storageRoot;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    public ServerSyncStorage(Path worldFolder) {
        this.storageRoot = worldFolder.resolve(STORAGE_FOLDER);
    }
    
    /**
     * Create a ServerSyncStorage from a ServerLevel.
     */
    public static ServerSyncStorage create(ServerLevel level) {
        Path worldFolder = level.getServer().getWorldPath(LevelResource.ROOT);
        return new ServerSyncStorage(worldFolder);
    }
    
    /**
     * Initialize storage directories.
     */
    public void initialize() throws IOException {
        Files.createDirectories(storageRoot);
    }
    
    /**
     * Get the storage root path.
     */
    public Path getStorageRoot() {
        return storageRoot;
    }
    
    /**
     * Get the file path for a chunk.
     */
    private Path getChunkPath(ChunkCoord coord) {
        String dimFolder = DimensionUtils.toFilesystemName(coord.dimension());
        return storageRoot.resolve(dimFolder).resolve(coord.x() + "_" + coord.z() + ".bin");
    }
    
    /**
     * Check if a chunk exists in storage.
     */
    public boolean exists(ChunkCoord coord) {
        lock.readLock().lock();
        try {
            return Files.exists(getChunkPath(coord));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Read chunk metadata only (without loading the full data).
     * Returns empty if chunk doesn't exist.
     */
    public Optional<ChunkMetadata> readMetadata(ChunkCoord coord) {
        lock.readLock().lock();
        try {
            Path path = getChunkPath(coord);
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            
            try (DataInputStream dis = new DataInputStream(new FileInputStream(path.toFile()))) {
                return Optional.of(ChunkMetadata.read(dis));
            }
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to read chunk metadata: {}", coord, e);
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Read full chunk data (metadata + compressed data).
     * Returns null if chunk doesn't exist or read fails.
     */
    @Nullable
    public ChunkData readChunk(ChunkCoord coord) {
        lock.readLock().lock();
        try {
            Path path = getChunkPath(coord);
            if (!Files.exists(path)) {
                return null;
            }
            
            try (DataInputStream dis = new DataInputStream(new FileInputStream(path.toFile()))) {
                ChunkMetadata metadata = ChunkMetadata.read(dis);
                byte[] data = dis.readAllBytes();
                return new ChunkData(metadata, data);
            }
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to read chunk data: {}", coord, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Write chunk data to storage.
     * Creates parent directories if needed.
     */
    public boolean writeChunk(ChunkCoord coord, UUID contributor, long timestamp, byte[] data) {
        lock.writeLock().lock();
        try {
            Path path = getChunkPath(coord);
            Files.createDirectories(path.getParent());
            
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path.toFile()))) {
                ChunkMetadata metadata = new ChunkMetadata(contributor, timestamp);
                metadata.write(dos);
                dos.write(data);
            }
            
            XaeroSync.LOGGER.debug("Wrote chunk {} from {} at {}", coord, contributor, timestamp);
            return true;
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to write chunk data: {}", coord, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Delete a chunk from storage.
     */
    public boolean deleteChunk(ChunkCoord coord) {
        lock.writeLock().lock();
        try {
            Path path = getChunkPath(coord);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to delete chunk: {}", coord, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Scan storage and populate a registry with all existing chunks.
     */
    public void scanIntoRegistry(ChunkRegistry registry) {
        lock.readLock().lock();
        try {
            if (!Files.exists(storageRoot)) {
                return;
            }
            
            try (var dimDirs = Files.newDirectoryStream(storageRoot, Files::isDirectory)) {
                for (Path dimDir : dimDirs) {
                    String dimName = dimDir.getFileName().toString();
                    ResourceLocation dimension = DimensionUtils.fromFilesystemName(dimName);
                    
                    try (var chunkFiles = Files.newDirectoryStream(dimDir, "*.bin")) {
                        for (Path chunkFile : chunkFiles) {
                            String filename = chunkFile.getFileName().toString();
                            ChunkCoord coord = parseChunkFilename(dimension, filename);
                            if (coord != null) {
                                Optional<ChunkMetadata> metadata = readMetadataFromPath(chunkFile);
                                metadata.ifPresent(meta -> registry.put(coord, meta.timestamp()));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            XaeroSync.LOGGER.error("Failed to scan storage", e);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Parse a chunk filename like "5_-3.bin" into coordinates.
     */
    @Nullable
    private ChunkCoord parseChunkFilename(ResourceLocation dimension, String filename) {
        if (!filename.endsWith(".bin")) {
            return null;
        }
        String name = filename.substring(0, filename.length() - 4);
        String[] parts = name.split("_");
        if (parts.length != 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[1]);
            return new ChunkCoord(dimension, x, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Read metadata directly from a path (internal use).
     */
    private Optional<ChunkMetadata> readMetadataFromPath(Path path) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path.toFile()))) {
            return Optional.of(ChunkMetadata.read(dis));
        } catch (IOException e) {
            XaeroSync.LOGGER.warn("Failed to read metadata from {}", path, e);
            return Optional.empty();
        }
    }
    
    /**
     * Container for chunk metadata and compressed data.
     */
    public record ChunkData(ChunkMetadata metadata, byte[] data) {}
}
