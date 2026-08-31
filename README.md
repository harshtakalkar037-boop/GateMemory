# GateMemory

GateMemory is an offline Android app for tagging and recognizing gate or building entrance scenes.

The Android implementation is being built incrementally from the existing Python prototype in `gatememory.py`. The prototype's core recognition intent is preserved:

- OpenCV ORB feature extraction with 1000 requested features.
- Hamming-distance brute-force matching.
- KNN matching with `k=2`.
- Lowe-style ratio filtering using a 0.6 ratio.
- Confidence as `good_matches / live_frame_descriptors`.
- A default recognition threshold near 0.10.
- Rejection of scenes with too few useful keypoints.

The current Android MVP includes:

- CameraX live preview and frame analysis.
- App-private offline tag storage.
- OpenCV ORB descriptor extraction.
- Hamming KNN matching and ratio-test confidence scoring.
- Location tagging with keypoint rejection.
- Voice note recording and playback using Android-native audio APIs.
- Live tag quality meter based on detected keypoints.
- Confidence progress bar and 2-frame match lock.
- Saved location gallery with thumbnails and per-tag voice playback.
- Privacy proof screen showing that no internet permission is requested.
- Camera match overlay when a known gate is locked.
- Memory Trail card with saved note, age, and confidence history.
- Haptic feedback when a saved gate locks.
- Scene fingerprint labels like `GATE-A7F3`.
- In-app demo guide for quick judging.
- One-tap demo reset for clearing local saved gates.
- Low-light warning based on live frame brightness.
- Short sound cue when a memory unlocks.
- Offline voice note transcription using bundled Whisper tiny English model assets.
- Voice notes recorded as 16 kHz mono WAV for reliable Whisper input.
- Real-time saved-location search across name, note, and transcript.

Build a shareable debug APK with:

```bash
GRADLE_USER_HOME=.gradle-home ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
# GateMemory
