# Changelog

## 0.6.0
- Read prepared PMX diffuse textures, UVs and alpha through the HMAPTEX1 GPU format.
- Fit real model bounds to all three prism dimensions while preserving aspect ratio.
- Read prepared lightweight house meshes ahead of the original OBJ; tolerate a renamed adjacent MTL.
- Deliver changed map rectangles in native Paper 26.3 packet bundles, with a separate clock targeting up to 60 fps.
- Predict the eye position between server samples, capped at one sample period; snap on teleports.
- Keep eight mesh buffers resident to avoid reloading three simultaneous displays every frame.
- Add model preparation tools, map-patch/motion tests and native packet throughput checks.

## 0.5.1 (GPU continuity fix)
- Reproduce and fix the 0.5.0 back-face blank cache persisting in later foreground tiles.
- Render all six faces from one captured eye position and animation frame; publish a complete scene atomically and retain one scene across all tiles in a server tick.
- Coalesce queued requests to the latest view and discard invalidated results.
- Restore triangle winding in the GPU lighting calculation, fixing view-dependent shade changes.
- Update the bundled GPU helper on startup and restart its mesh cache after reload.
- Add real RTX 3050 geometry comparisons and an opt-in isolated Paper GPU regression verifier.
- Build with the official Paper 26.3 API; unit tests and real GPU/Paper checks pass. See docs/gpu-0.5.1-verification.md.

## 0.4.0
- Build against the official Paper 26.3 build 243 API with JDK 25 and include Gradle wrapper.
- Transparent map backgrounds, opaque black model pixels, and AIR-backed freestanding prisms.
- Explicit per-player map refresh, locked maps and complete initial canvas writes.
- Correct planar wall directions and projection planes; consistent world-space lighting between prism faces.
- Validate frame overlap, block obstruction and world height before placement; load entities before overlap checks and removal.
- Release renderers on removal/disable; retain player changes to backing blocks.
- Console coordinate placement via `holomap create`.
- Unit tests and an optional real-Paper integration verifier, with server startup/console/stop scripts.

## 0.3.0 (Paper 26.3 target)
- New command syntax: `/holomap cube <X> <Y> <Z> [model]`.
- Six-face rectangular-prism tiled maps with independent face widths, heights, camera transforms, and model depth.
- Preserve `0.2.0` saved cubes (`size` automatically migrates to equal width/height/depth on load).
- Restrict map and axis sizes with `max-prism-maps` / `max-prism-axis`.
- Target Paper API 26.3 and JDK 25 in Gradle/plugin.yml.
- Standalone rectangular-prism geometry + projection test (125 sizes, six faces).

## 0.2.0
- Six-face cube displays.

## 0.1.0
- Viewer-dependent flat wall displays.

## 0.5.0 (GPU / streaming OBJ)
- Based on the user's revised 0.4.0 project; kept flat and rectangular six-face commands.
- Added ModernGL OpenGL GPU rendering sidecar, automatically installed Python helper, and asynchronous GPU IPC.
- Changed default render-fps and animation-fps to 20 (target, not guaranteed actual rate).
- Added v0.4 default config migration to 20 fps when upgrading.
- Added streaming OBJ-to-binary GPU mesh conversion and persistent validation cache (1.5GB max input by default).
- Added asynchronous model preparation so large OBJ decoding does not block the Paper main thread.
- Added /holomap status and GPU diagnostics, improved cache eviction for GPU animation frames.
- Note: v0.5.0 new JAR and real GPU execution have NOT been built/tested in this environment.
