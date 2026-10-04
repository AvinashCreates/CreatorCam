# 0.3.0 changes (orientation fix + layered avatar)

## 1. Orientation (root cause + fix)
**Bug:** the shader applied the SurfaceTexture transform matrix AND a second rotation from CameraX's
`rotationDegrees`. The camera's transform hint can already carry the sensor rotation, so the image was rotated twice
(sideways) in preview and, because the encoder uses the same draw call, in the MP4.

**Fix:** one authoritative transform, `gl/Orientation.kt`. It reads the rotation/flip that is *already inside* the
SurfaceTexture matrix, cancels it (G^-1), then applies the physical sensor-to-upright rotation exactly once,
plus mirror and aspect-fill crop, all in upright space. Nothing is hard-coded to 90 degrees. `targetRotation` is now set
explicitly from the display and follows display rotation changes. `FaceMapping` uses the same crop/mirror, so the avatar
and the image share one coordinate system.

## 2. Avatar
Bitmap sprites removed. New expression-reactive 2.5D layered avatar, drawn by a procedural SDF fragment shader
(back hair, ears, head, blush, nose, eyes, brows, mouth, front hair, accessory). Customisable: character presets,
hair style (5) and colour (7), skin tone (6), eye colour (4), accessory (5). No bitmaps, no per-frame allocation.
Face tracking now also reads landmarks + classification (smile, eye-open) and only runs while an avatar is selected.

## Files changed
New:      gl/Orientation.kt, avatar/AvatarSpec.kt, face/FaceGeometry.kt,
          test/OrientationTest.kt, test/FaceGeometryTest.kt, tools/shader-preview/*, docs/*.png, CHANGES.md
Modified: gl/CameraRenderer.kt, gl/Shaders.kt, camera/CameraController.kt, face/FaceAnalyzer.kt, face/FaceTracker.kt,
          ui/CameraViewModel.kt, ui/CameraPanels.kt, ui/CameraScreen.kt, test/FaceTrackerTest.kt, README.md, TESTING.md
Removed:  avatar/Avatars.kt (bitmap catalog), assets/avatars/ (PNG drop-in; replaced by the spec system)
Unchanged: recording, audio/voice, storage, review/playback, permissions, effects, settings.
