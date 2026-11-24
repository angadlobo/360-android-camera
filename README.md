# 360 Camera (Android)

Turns an ordinary Android phone into a 360° camera by using the **front and back
cameras at the same time** plus the phone's motion sensors.

A phone camera only sees about 70°, so no phone can capture a full sphere in a
single shot. Instead, the app runs both cameras together: the back camera films
what's in front of you while the selfie camera films what's behind you. As you
slowly turn, every new viewpoint is projected onto a sphere using the gyroscope.
Because the two cameras face opposite ways, **you only need to turn half a circle
(180°)** instead of a full one, then tilt up and down for the sky and floor.

The result is saved as a standard equirectangular JPEG with Google Photo Sphere
(GPano) metadata, so it opens as an interactive 360 photo in Google Photos,
Facebook and other 360 viewers, plus the app's own viewer.

## Using it

1. Tap **Start** and hold the phone upright at eye level. The first photo is taken automatically.
2. **Follow the arrow.** Orange dots mark the spots still to capture. Move the phone until
   the orange dot is inside the centre circle, then hold still. The photo is taken and
   the phone buzzes. The instruction at the bottom tells you which way to go, for example
   "Turn right 30°" or "Tilt up 40°".
3. Keep going until every spot is captured (shown at the top, for example "18 of 36 spots").
   Capture pauses automatically when you're done.
4. Tap **Save 360°**. The photo goes to `Pictures/Camera360` and opens in the viewer.
5. In the viewer, drag to look around, pinch to zoom, or turn on **Motion** to
   look around by moving the phone.

Tips: turn on the spot and keep the phone in the same place rather than swinging it
at arm's length. That cuts down on parallax, which is the one error stitching can't fix.

## Phone support

* **Android 10+** with a gyroscope.
* **Both cameras at once** needs Android 11+ and a phone that supports concurrent
  cameras (most recent Pixels and many Samsung flagships do). On other phones
  the app switches to one camera at a time automatically: turn a full circle,
  and use **Switch camera** to fill in gaps.

## How it works

The phone's motion sensors alone are only accurate to a few degrees, which shows up
as broken seams. Several steps fix that:

* **Image alignment.** Each photo is matched to what is already in the panorama with a
  coarse-to-fine search over small rotations, using normalised cross-correlation. The
  correction is remembered per camera, which cancels gyroscope drift. Blank areas such as
  walls or sky give no reliable match, so for those the sensor estimate is kept.
* **Exposure matching.** Each photo's brightness is scaled to match the panorama, so seams
  don't show as brightness jumps.
* **Horizon levelling.** Averaging the gravity sensor over all photos gives true vertical,
  and the saved image is rotated so the horizon is level.
* **AI person removal.** Google's on-device ML Kit selfie segmentation model cuts the
  person holding the phone out of every front-camera photo, so you don't appear in the
  panorama. It runs on the phone with no network needed.
* **Guided capture.** 36 target directions cover the sphere with about 40% overlap. Opposite
  targets are paired, so both cameras can shoot at the same stop.
* **Matching sensor and camera timing.** Each photo is paired with the phone's orientation
  at the moment it was taken. Cameras and sensors can use different clocks, and mixing
  them up puts photos at the wrong angle.

| File | Role |
| --- | --- |
| `PanoramaStitcher.kt` | Projection, image alignment, exposure matching, blending, levelling. Pure Kotlin. |
| `CaptureGuide.kt` / `CaptureSession.kt` | Target layout, "which way to move" directions, when to take a photo. Pure Kotlin. |
| `GuideOverlayView.kt` | Target dots, centre ring, arrow and instruction drawn over the camera view. |
| `PersonMasker.kt` | ML Kit selfie segmentation that removes the person from front-camera photos. |
| `OrientationTracker.kt` | Rotation sensor with timestamped history, plus gyroscope speed. |
| `MainActivity.kt` | CameraX concurrent front and back cameras (single-camera fallback) and the UI. |
| `PanoSaver.kt` | JPEG and GPano XMP metadata, saved through MediaStore. |
| `SphereRenderer.kt` / `ViewerActivity.kt` | OpenGL 360 viewer. |

`GuidedCaptureSimulationTest` runs a complete session without a phone. A virtual user
follows the on-screen directions while the gyroscope is noisy and drifts. The phone is
held slightly tilted, there is a person in every selfie photo, and exposure varies. The
error against the true scene is 9.0 with alignment, 18.3 with the gyroscope alone, and
7.6 with a perfect gyroscope.

## Building

Open the project in Android Studio, or run:

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # stitching math tests
```

## Limitations

* This is a sweep-and-stitch capture, not a single-shot 360 camera. Moving
  people or objects can appear twice or cut off.
* The front and back cameras differ in sharpness and colour, so the half of the
  sphere from the selfie camera usually looks softer.
* Parallax: nearby objects can show small seams if the phone moves sideways between photos.
* Blank surfaces such as white walls or clear sky can't be aligned by image matching, so
  they rely on the gyroscope.
