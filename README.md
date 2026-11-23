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

1. Hold the phone upright at eye level and tap **Start**.
2. Turn slowly on the spot. The map at the top fills in. The white circle is
   where the back camera points and the orange circle is the front camera.
3. Fill in the dark areas, including the top and bottom (tilt up and down).
4. Tap **Save 360°**. The photo goes to `Pictures/Camera360` and opens in the viewer.
5. In the viewer, drag to look around, pinch to zoom, or turn on **Motion** to
   look around by moving the phone.

Tips: turn the phone around itself rather than swinging it at arm's length (that
cuts down on parallax), and keep moving slowly. Frames are skipped while you turn fast.

## Phone support

* **Android 10+** with a gyroscope.
* **Both cameras at once** needs Android 11+ and a phone that supports concurrent
  cameras (most recent Pixels and many Samsung flagships do). On other phones
  the app switches to one camera at a time automatically: turn a full circle,
  and use **Switch camera** to fill in gaps.

## How it works

| File | Role |
| --- | --- |
| `PanoramaStitcher.kt` | Projects each frame onto a 4096×2048 equirectangular canvas by inverse mapping with bilinear sampling. It uses the lens focal length from Camera2 and the orientation from the rotation sensor. Seams are chosen so each pixel comes from the frame where it sits closest to the centre. Pure Kotlin with unit tests. |
| `OrientationTracker.kt` | Game rotation vector (no magnetometer jumps) with timestamped history to match each frame to the phone's orientation when it was taken. Gyroscope rate is used to reject blurry frames. |
| `MainActivity.kt` | CameraX concurrent front and back binding (`ConcurrentCamera`), falling back to one camera, plus the capture UI. |
| `PanoSaver.kt` | JPEG and GPano XMP injection, saved through MediaStore. |
| `SphereRenderer.kt` / `ViewerActivity.kt` | OpenGL ES 2 sphere viewer with drag, pinch and motion look. |

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
* No feature-based alignment yet. Accuracy depends on the phone's gyroscope, so
  small misalignments at seams are possible.
