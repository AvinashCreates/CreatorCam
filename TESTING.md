# Device test plan (Poco C65 or any phone)

Tick each on a real device. Report anything failing with `adb logcat -s CameraRenderer CameraController RecordingSession Mp4Recorder AndroidRuntime`.

## Camera
- [ ] Rear preview upright, not stretched. Front preview is mirrored (selfie) and upright.
- [ ] Flip front/rear 10x rapidly: no crash, preview recovers.
- [ ] Flash button appears on rear only; torch turns on/off.
- [ ] Rotate phone while previewing: UI stays portrait, image stays upright.
- [ ] Settings > Mirror front camera OFF: front image is no longer mirrored (preview AND recorded video).

## Recording
- [ ] Record 2 s, 30 s, 2 min. Each lands in Review and plays.
- [ ] Tapping stop within the first second is ignored (by design).
- [ ] 5 recordings back to back: no leak/crash, camera keeps working.
- [ ] Put app in background while recording: recording stops, take appears on return.
- [ ] Fill storage / deny mic mid-way: concise error, no crash.

## Effects & avatars (the key checks)
- [ ] Pick Noir/Warm/etc: preview changes. Record. **Review playback shows the same look.**
- [ ] Move sliders (brightness/contrast/saturation/sharpness): preview and recording both change.
- [ ] Pick an avatar: it covers the face, follows when you move closer/farther, tilts with your head, mirrors correctly on the front camera.
- [ ] **Avatar is visible in the recorded video** (review screen), not just preview.
- [ ] Photo mode: effect + avatar appear in the captured photo.

## Audio
- [ ] Normal: clear voice. Deep / High / Robot / Echo / Trickster each sound different **in the review playback**.
- [ ] Lips and voice stay in sync (watch speech in a 30 s clip).
- [ ] Mic denied: banner appears, video has no sound, message says so.

## Review / save / share
- [ ] Play/pause, drag seek bar, time labels update, replay after the end.
- [ ] Retake returns to camera. Delete asks first and removes the file. Back on an unsaved take asks first.
- [ ] Save: appears in Gallery > Movies/CreatorCam (video) / Pictures/CreatorCam (photo), named `CreatorCam_YYYYMMDD_HHMMSS`.
- [ ] Share (before and after saving) opens the Sharesheet and the receiving app gets the file.
- [ ] Gallery thumbnail (bottom-left) shows the latest save and opens it.

## Permissions
- [ ] First launch asks camera + mic. Deny camera: explanation + Allow button. Deny twice / "don't ask again": button becomes Open Settings; granting there and returning resumes the camera.
- [ ] Deny mic only: camera works, mic banner shown.

## 0.3.0: orientation (do these first)
- [ ] Rear camera, phone held upright: preview shows an upright person. Record 5 s: **review playback is upright with the same framing.**
- [ ] Front camera: same, and mirrored (selfie). Settings > Mirror OFF: not mirrored in preview AND in the saved MP4.
- [ ] Rear camera is never mirrored. Photos are upright and match the preview.
- [ ] Switch cameras several times: orientation stays right each time.
If anything is sideways/upside-down: send phone model + `adb logcat -s CameraRenderer` and which camera/mode.

## 0.3.0: avatar
- [ ] Pick Alex: the character covers your face, centred. Move left/right/up/down: it follows. Move closer/farther: it scales.
- [ ] Tilt head: avatar tilts the same way (also on the front camera with mirror on and off).
- [ ] Turn head left/right: features slide the same direction you turn (if reversed, tell me).  Nod up/down: if backwards flip `FaceGeometry.PITCH_SIGN`.
- [ ] Blink, wink, smile, open mouth: avatar follows. Cover your face: avatar fades out; uncover: fades in.
- [ ] Customise hair / skin / eyes / accessory: changes show instantly.
- [ ] Record with avatar + a filter + a voice effect: **review playback shows avatar and filter, and you hear the voice effect.**
- [ ] Photo with avatar: avatar is in the saved photo.
- [ ] No avatar selected: face detection is off (CPU stays low).
