# CreatorCam 0.3.0

A creator-style camera for Android: live effects, face-following avatars, voice effects, and a review
screen, where **everything you see and hear is really encoded into the file**.

> ## Build status: READ THIS FIRST
> 0.2.0 compiled and ran on your phone. **0.3.0 (orientation fix + new avatar) has NOT been built or run on a device**:
> the sandbox that produced it has no Android SDK and no access to Gradle/Google/Maven servers.
> Verified here: JVM unit tests, real-GLSL rendering on a software GPU, and compilation of the GL/recorder/storage layer
> against the real Android API. Not verified: Gradle build, CameraX/ML Kit/Compose calls, anything on real hardware.
> See [Verification status](#verification-status). Run `./gradlew test` and `./build.sh`, then follow `TESTING.md`.

## Build
Requirements: JDK 17, Android SDK (platform 35, build-tools), internet for Gradle dependencies.
```bash
./build.sh            # = ./gradlew assembleDebug --no-daemon --max-workers=1
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
`gradle.properties` caps Gradle at 1.5 GB heap / 1 worker for low-end machines.

## What it does
| Feature | How it is really implemented |
|---|---|
| Live preview | CameraX `Preview` -> our own OpenGL ES 2.0 renderer (`gl/CameraRenderer`) -> SurfaceView |
| Effects (Original, Warm, Cool, Vintage, Noir, Cinematic, High contrast, Soft, Vignette) + Brightness / Contrast / Saturation / Sharpness sliders | One fragment shader (`gl/Shaders`). The *same* draw call feeds the screen, the video encoder and photo capture, so preview == file |
| Face tracking | ML Kit (bundled, offline), ACCURATE mode + landmarks + classification, 640x360 stream, <=12 Hz, one frame in flight, **only while an avatar is selected**. Box, roll, turn, nod, smile, eye-open, mouth-open feed `FaceTracker`, which interpolates at 30 fps |
| Avatar (**expression-reactive 2.5D layered avatar**, not 3D) | Procedural SDF shader, layers: back hair, ears, head, blush, nose, eyes, brows, mouth, front hair, accessory. Follows face position/scale/roll; features shift with head turn/nod (parallax); blinks with your eyes; smiles; opens its mouth. 6 presets + customise hair/skin/eyes/accessory. Original artwork, not Apple's Memoji |
| Video | GL frames -> `MediaCodec` H.264 (via input Surface) + `AudioRecord` -> voice DSP -> AAC -> `MediaMuxer` -> MP4. 9:16, 720p30 (1080p optional) |
| Voice effects (Normal, Deep, High, Robot, Echo, Trickster) | Pure-Kotlin DSP in `audio/VoiceEffects.kt`, applied to the PCM *before* encoding. Not heard live (no monitoring, to avoid feedback); heard on playback |
| Photo | Offscreen 1080x1920 render of the current frame (effects + avatar), JPEG |
| Review screen | Media3 ExoPlayer on the exact recorded file: play/pause, seek, time, Retake, Save, Share, Delete |
| Storage | Takes live in cache until **Save**; then MediaStore `Movies/CreatorCam` / `Pictures/CreatorCam` (`IS_PENDING` flow). No storage permission on Android 10+ |
| Share | Sharesheet with `content://` URIs only (FileProvider for unsaved, MediaStore for saved) |
| Permissions | Camera required; mic optional (silent video + banner). Denied / "don't ask again" -> Settings shortcut |
| Settings | Mirror front camera, video quality (both really change behaviour) |

## Design decisions & honest limitations
* **Portrait only.** Output is always upright 9:16 whatever the phone's orientation; the activity is locked to portrait.
* **Flash = torch** (continuous light, rear camera only). It is hidden when the lens has no flash unit.
* **Voice effects are not audible while recording** (headphone-free live monitoring would feed back into the mic).
* **Pitch shifting** uses a two-tap crossfaded delay-line shifter: keeps duration (sync-safe), adds a little
  "granular" character. It is not studio quality. "Trickster" = layered low (0.78x) + high (1.32x) voices + light ring-mod.
* **Avatar level = 2.5D, not a 3D mesh.** Head turn is faked by shifting/narrowing layers; extreme profiles won't look 3D.
  Mouth-open is estimated from the nose-to-chin landmark distance (adaptive neutral), so it is approximate. Blinks are sampled at
  detector rate (~10 Hz) so very fast blinks can be missed. Pitch uses ML Kit's Euler X with `FaceGeometry.PITCH_SIGN`
  (flip it if the avatar nods backwards on your device). Roll, turn and which-eye are derived from landmark geometry and need no sign guess.
* The avatar hides when no face is found (after 0.4 s) and fades back in.
* Orientation: portrait UI, output always upright 9:16. If the display ever rotates, `targetRotation` follows it.
* Preview and avatar assume the preview and analysis streams are both 16:9 (requested); a device that falls back to another
  aspect gets a small avatar offset.
* **A/V sync** is derived from the camera frame timestamps and the first microphone sample; expect roughly +/-50 ms.
* **Photos** are 1080x1920 (the processing pipeline's resolution), not full sensor resolution.
* Clips auto-stop at 3 minutes. Recording stops cleanly if the app is backgrounded.
* No Bitmoji assets: Bitmoji is Snap's trademark/artwork. The built-in avatars are original cartoons.

## Architecture
```
camera/   CameraController   CameraX session (Preview + ImageAnalysis), torch, lens, stale-bind guard
gl/       CameraRenderer     the only place pixels are made (GL thread). EglCore, Shaders
face/     FaceAnalyzer       ML Kit -> FaceTracker (smoothing, fade)
avatar/   AvatarCatalog      procedural + asset avatars
effects/  Effects            presets + sliders -> shader params
audio/    VoiceEffects       PitchShifter, Echo, RingMod, VoiceProcessor
record/   Mp4Recorder        MediaCodec x2 + MediaMuxer, own AudioRecord thread
storage/  MediaStorage       cache takes, MediaStore save, share intents, latest thumbnail
settings/ AppSettings
ui/       CameraViewModel (state machine) + Compose screens (Camera, Review, panels, settings)
```
Recording states: Ready -> Recording -> Processing -> (Review) -> Saved, with Error shown on failure.

## Verification status
**Actually verified (in the sandbox):**
* **Orientation (new):** `gl/Orientation.kt` checked exhaustively on the JVM: 4 sensor rotations x 4 transform hints x hint-mirror x
  mirror x 4 buffer shapes (256 configs): output upright every time; face coordinates map to the same pixels as the image (352 checks).
  The real camera fragment shader was then run on a software GPU (llvmpipe) with those exact matrices on a synthetic sensor buffer:
  48/48 configs identical to the expected upright image (`docs/orientation_sheet.png`: top = rendered, bottom = expected).
  The old formula fails whenever the matrix already carries rotation.
* **Avatar (new):** the real avatar shader compiled and rendered on a software GPU: 6 presets, blink, wink, smile, open mouth, grin,
  head turn, nod (`docs/avatar_presets.png`, `docs/avatar_expr.png`). Face geometry (roll/turn/eye side/mouth/smile) unit-tested.
* All 23 unit tests pass (`app/src/test`, run here via a minimal JUnit shim; use `./gradlew test` on your machine).
* `audio/VoiceEffects.kt` + `face/FaceTracker.kt` compiled with Kotlin 2.0.21 and run: Deep 440 Hz -> 314 Hz (target 317),
  High 440 -> 642 Hz (target 638), Echo repeats at 300 ms, Robot/Trickster alter the signal, tracker follows, converges, fades out.
  The same assertions are in `app/src/test/` as JUnit tests (`./gradlew test`).
* Compiled cleanly against the real Android 35 `android.jar`: `gl/*` (EGL, GLES, shaders host), `record/Mp4Recorder`
  (MediaCodec/MediaMuxer/AudioRecord), `storage/MediaStorage` (MediaStore), `avatar/*`, `effects/*`, `settings/*`
  (CameraX types were stubbed to my understanding of its API, so this proves internal consistency, not CameraX API accuracy).
* 49 classes of the GL/recorder/storage/avatar-model layer compile cleanly against Android 35; no syntax errors in any Kotlin file.

**NOT verified (could not be, here):**
* The Gradle build / APK, dependency resolution, R8, resources, manifest merge.
* CameraX / ML Kit / Media3 / Compose call signatures (written from knowledge of the 1.4.1 / 16.1.7 / 1.5.1 / BOM 2024.12.01 APIs).
* Everything that needs hardware: camera rotation/mirroring correctness on real sensors, GL on your GPU, encoder behaviour,
  A/V sync, avatar alignment, thermal/perf on a Poco C65. The test plan is in `TESTING.md`.

  
[![Architecture diagram of avinashcreates/creatorcam](https://gitdiagram.com/avinashcreates/creatorcam/diagram.png)](https://gitdiagram.com/avinashcreates/creatorcam?utm_source=readme&utm_medium=picture)
