# Xaero's World Map Syncronization Project

This project syncs world maps between Xaero World Map clients. Maps are stored on both server and client.

**Important:** Use `remap=false` for all mixins targeting Xaero's classes.

## Build Commands

```bash
# Build the mod
./gradlew build

# Run the client (development)
./gradlew runClient

# Run the dedicated server (development)
./gradlew runServer

# Generate IDE project files
./gradlew eclipse        # Eclipse
./gradlew idea           # IntelliJ IDEA

# Rebuild mixin refmaps
./gradlew rebuildMixins

# Clean build artifacts
./gradlew clean

# Build without running tests
./gradlew build -x test
```

## Code Style Guidelines

### General Principles
- Write clean, readable code over clever optimizations
- Prefer explicitness over implicitness
- Document public APIs; implementation details need not be documented
- Keep methods focused (single responsibility)

### Naming Conventions
- **Classes:** PascalCase (e.g., `ClientSyncManager`, `ChunkRegistry`)
- **Methods & Variables:** camelCase (e.g., `handleRegistryUpdate`, `uploadQueueSet`)
- **Constants:** SCREAMING_SNAKE_CASE (e.g., `SURFACE_LAYER`, `REGISTRY_CACHE_TTL_MS`)
- **Packages:** lowercase (e.g., `net.fivew14.xaerosync.client.sync`)
- **Records:** PascalCase (e.g., `ChunkEntry`, `ChunkExplorationEvent`)
- Use descriptive names: `timestampTracker` not `tt`, `pendingDownloads` not `pd`

### Imports
- Use wildcard imports only for standard collections (`java.util.*`) and internal packets (`networking.packets.*`)
- Group imports: external Minecraft/Forge → internal project → java/javax
- Sort imports alphabetically within groups

### Types
- Use `List<String>`, `Set<ChunkCoord>`, `Map<UUID, PlayerSyncState>` over raw types
- Use `ConcurrentHashMap.newKeySet()` for concurrent sets
- Use `CopyOnWriteArrayList` for thread-safe listener collections
- Use `Optional<@Nullable T>` for potentially null return values
- Prefer interfaces over implementations (`List` not `ArrayList` in declarations)
- Use primitive types (`int`, `boolean`) where boxing isn't needed

### Records
- Use records for simple data carriers (DTOs, packet entries, events)
- Keep records small; extract nested records when they grow beyond 3-4 fields
- Example:
  ```java
  public record ChunkEntry(String dimension, int x, int z, long timestamp) {
      public static void encode(ChunkEntry entry, FriendlyByteBuf buf) { ... }
      public static ChunkEntry decode(FriendlyByteBuf buf) { ... }
  }
  ```

### Mixins
- Always set `remap=false` when mixing into non-Minecraft classes (Xaero's classes)
- Accessors go in `mixin.accessor` package
- Use `@Accessor` for field access instead of reflection where possible
- Prefix mixin classes with target class name (e.g., `MapWriterMixin`)

### Error Handling
- Use specific exceptions (`IllegalArgumentException`, `IllegalStateException`) over generic `Exception`
- Log errors with context: `LOGGER.error("Failed to serialize chunk at {},{}", x, z, e)`
- Never swallow exceptions silently; at minimum log at DEBUG level
- Validate inputs early: throw `IllegalArgumentException` for invalid parameters
- Use `@Nullable` and `Optional` to indicate optional return values

### Synchronization
- Mark fields accessed from multiple threads as `volatile` when appropriate
- Use existing rate limiters (`RateLimiter`) for network operations
- Prefer `synchronized` blocks over `synchronized` methods for finer control
- Document thread-safety assumptions clearly

### Logging
- Use `XaeroSync.LOGGER` for all logging
- Use `{}` for string formatting (SLF4J style)
- Guard expensive debug logs:
  ```java
  if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Processing chunk: {} at {}", chunkX, chunkZ);
  }
  ```

### File Organization
- **Client code:** `client/` package
- **Server code:** `server/` package
- **Common code:** `common/` package
- **Networking:** `networking/packets/` (C2S and S2C subpackages not used; prefix packet classes)
- **Mixins:** `mixin/` package with `accessor/` subpackage for accessors

## Libraries

- Decompiled Xaero World Map reference libraries are in `./locallib/`
- Use these to understand Xaero's internal APIs and field layouts

## Testing

This project uses Minecraft's GameTest framework. To run tests:

```bash
# Run all game tests
./gradlew gameTest

# Run a specific test class
./gradlew gameTest --tests "*TestClassName*"

# Run a specific test method
./gradlew gameTest --tests "*TestClassName.testMethodName*"
```
