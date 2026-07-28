# Xaero's World Map Synchronized

A Minecraft mod designed to synchronize Xaero's World Map data between clients and a server. This project ensures that explored map data is shared across the network, allowing players to see map progress made by others and maintaining consistent map states across different client installations.

## Features

- **Bi-directional Sync**: Automatically upload explored chunks to the server and download missing/updated chunks from the server.
- **Intelligent Prioritization**: Download and upload queues are distance-based, prioritizing chunks closest to the player to provide the most immediate visual feedback.
- **Efficient Serialization**: Uses a custom compressed binary format (GZIP) and palettes for block states and biomes to minimize network bandwidth.
- **Rate Limiting**: Configurable upload and download limits (per second) to prevent network congestion, negotiated between client and server settings.
- **Dimension Support**: Ability to whitelist or blacklist specific dimensions for synchronization.
- **Robust Tracking**: Uses timestamps to ensure only the newest map data is synchronized.

## Commands (Client)

The mod provides several client-side commands to monitor the synchronization process:

- `/xaerosync client status` - Displays connection status, whether sync is enabled, and a summary of known chunks.
- `/xaerosync client queue` - Shows the current size of upload/download queues and pending requests.
- `/xaerosync client config` - Displays current client-side configuration settings (auto-upload/download and rate limits).

## Development

### Build Commands

The project uses Gradle for building and testing.

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
```

### Testing

The project utilizes Minecraft's GameTest framework.

```bash
# Run all game tests
./gradlew gameTest

# Run a specific test class
./gradlew gameTest --tests "*TestClassName*"
```

## Technical Notes

- **Mixins**: This project heavily relies on Mixins to integrate with Xaero's World Map. When targeting Xaero's classes, `remap=false` is used as these are non-Minecraft classes.
- **Architecture**:
    - `client/`: Logic for serialization, distance-based queue management, and local timestamp tracking.
    - `server/`: (Inferred) Logic for storing map data and managing the global registry of explored chunks.
    - `common/`: Shared data structures and utilities.
    - `networking/`: Packet definitions for C2S and S2C communication.
