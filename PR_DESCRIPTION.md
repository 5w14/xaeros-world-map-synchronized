# Fix mixin injection signature and add compile-time stub for MapUpdateFastConfig ✅

## Summary
- Updated mixins to match recent changes in Xaero's World Map and added a compile-time stub so the project compiles cleanly.  
- **This change is to maintain compatibility with Xaero's Minimap.** ✨

---

## Changes
- **Modified** `src/main/java/net/fivew14/xaerosync/mixin/MapWriterMixin.java`:
  - Added `import xaero.map.region.MapUpdateFastConfig;`
  - Added `MapUpdateFastConfig mapUpdateFastConfig` to the `writeChunk` injection signature.
- **Modified** `src/main/java/net/fivew14/xaerosync/mixin/MapSaveLoadMixin.java`:
  - Added `boolean fastLoad` parameter to `onRegionLoaded(...)` to match Xaero's `loadRegion` signature.
- **Added/kept** a compile-time stub: `src/main/java/xaero/map/region/MapUpdateFastConfig.java` (empty class) to allow local builds when the Xaero artifact isn't available.
- **Updated** `build.gradle` to exclude `xaero/map/region/MapUpdateFastConfig.class` (and inner classes) from the final JAR to avoid shipping a duplicate class.

---

## Why
- Xaero changed method signatures; our mixins must match exact descriptors or Mixin will fail to apply and the mod will crash during load. These updates prevent mod-loading crashes and maintain compatibility with Xaero's Minimap.

---

## Tests & Verification
- Ran `./gradlew clean build` — **BUILD SUCCESSFUL** on JDK 17.
- Verified `build/libs/xaeromapsync-0.0.1.jar` does **not** contain the stub class (excluded via `build.gradle`).
- Recommended runtime test: `./gradlew runClient` with Xaero present to validate at runtime.

---

## Notes
- The compile-time stub is excluded from the JAR and kept only to make local development convenient. If a published Xaero artifact becomes available, we can transition to `compileOnly` instead.

---

If you want, I can run `./gradlew runClient` with Xaero installed and post the results here, or open/update the PR description directly; just say which you prefer.