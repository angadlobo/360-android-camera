package com.angadlobo.camera360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Capture screen.
 *
 * Guided capture: target dots are placed around the sphere and shown over the camera
 * view; an arrow and instruction tell the user which way to move. A photo is only
 * taken when a camera is on a target and the phone is held still. The back and front
 * cameras run together where the phone supports it, so each stop can capture two
 * opposite directions. Each photo is aligned to the panorama by image matching (the
 * motion sensor alone is not accurate enough), and the person holding the phone is cut
 * out of front-camera photos with an on-device AI segmentation model.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { DUAL, SINGLE }

    private lateinit var panoView: PanoPreviewView
    private lateinit var backPreview: PreviewView
    private lateinit var frontPreview: PreviewView
    private lateinit var frontContainer: FrameLayout
    private lateinit var overlay: GuideOverlayView
    private lateinit var modeText: TextView
    private lateinit var statusText: TextView
    private lateinit var coverageText: TextView
    private lateinit var coverageBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var switchButton: Button

    private lateinit var orientation: OrientationTracker
    private val stitcher = PanoramaStitcher(4096)
    private val guide = CaptureGuide()
    private val session = CaptureSession(stitcher, guide)
    private lateinit var masker: PersonMasker
    private lateinit var stitchExecutor: ExecutorService

    @Volatile private var capturing = false
    @Volatile private var saving = false
    @Volatile private var mode = Mode.DUAL
    @Volatile private var singleFacing = Facing.BACK
    private var dualSupported: Boolean? = null

    /** focal length / sensor long side, per camera. Multiply by image long side for pixels. */
    private val focalRatio = mutableMapOf(Facing.BACK to DEFAULT_FOCAL_RATIO, Facing.FRONT to DEFAULT_FOCAL_RATIO)
    /** Whether the camera's timestamps already use the elapsedRealtime clock (like sensors do). */
    private val realtimeTimestamps = mutableMapOf(Facing.BACK to false, Facing.FRONT to false)

    // Only touched on stitchExecutor.
    private var lastPreviewMs = 0L

    /** The camera the user is being guided with. */
    private val guideFacing get() = if (mode == Mode.DUAL) Facing.BACK else singleFacing

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameras()
            else statusText.text = getString(R.string.need_camera_permission)
        }

    private val openImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) startActivity(Intent(this, ViewerActivity::class.java).setData(uri))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        panoView = findViewById(R.id.panoPreview)
        backPreview = findViewById(R.id.backPreview)
        frontPreview = findViewById(R.id.frontPreview)
        frontContainer = findViewById(R.id.frontContainer)
        overlay = findViewById(R.id.guideOverlay)
        modeText = findViewById(R.id.modeText)
        statusText = findViewById(R.id.statusText)
        coverageText = findViewById(R.id.coverageText)
        coverageBar = findViewById(R.id.coverageBar)
        startButton = findViewById(R.id.startButton)
        saveButton = findViewById(R.id.saveButton)
        switchButton = findViewById(R.id.switchButton)

        orientation = OrientationTracker(this)
        masker = PersonMasker()
        stitchExecutor = Executors.newSingleThreadExecutor()

        startButton.setOnClickListener { toggleCapture() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetPanorama() }
        saveButton.setOnClickListener { savePanorama() }
        switchButton.setOnClickListener {
            singleFacing = if (singleFacing == Facing.BACK) Facing.FRONT else Facing.BACK
            startCameras()
        }
        findViewById<Button>(R.id.openButton).setOnClickListener { openImageLauncher.launch("image/*") }

        if (!orientation.isAvailable) {
            statusText.text = getString(R.string.no_motion_sensor)
            startButton.isEnabled = false
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameras()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
    }

    override fun onPause() {
        super.onPause()
        orientation.stop()
        if (capturing) toggleCapture()
    }

    override fun onDestroy() {
        super.onDestroy()
        stitchExecutor.execute { masker.close() }
        stitchExecutor.shutdown()
    }

    // ------------------------------------------------------------------ Camera setup

    private fun startCameras() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            if (dualSupported != false && tryBindDual(provider)) {
                dualSupported = true
                mode = Mode.DUAL
            } else {
                dualSupported = false
                bindSingle(provider, singleFacing)
                mode = Mode.SINGLE
            }
            updateUi()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildAnalysis(facing: Facing): ImageAnalysis {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(stitchExecutor) { image ->
                    try {
                        onFrame(image, facing)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Frame failed", t)
                    } finally {
                        image.close()
                    }
                }
            }
    }

    private fun buildPreview(view: PreviewView): Preview {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        return Preview.Builder().setResolutionSelector(selector).build()
            .also { it.surfaceProvider = view.surfaceProvider }
    }

    private fun tryBindDual(provider: ProcessCameraProvider): Boolean {
        val pair = provider.availableConcurrentCameraInfos.firstOrNull { infos ->
            infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        } ?: return false
        val backInfo = pair.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        val frontInfo = pair.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        return try {
            val backGroup = UseCaseGroup.Builder()
                .addUseCase(buildPreview(backPreview))
                .addUseCase(buildAnalysis(Facing.BACK))
                .build()
            val frontGroup = UseCaseGroup.Builder()
                .addUseCase(buildPreview(frontPreview))
                .addUseCase(buildAnalysis(Facing.FRONT))
                .build()
            provider.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(backInfo.cameraSelector, backGroup, this),
                    ConcurrentCamera.SingleCameraConfig(frontInfo.cameraSelector, frontGroup, this),
                )
            )
            readCameraInfo(backInfo, Facing.BACK)
            readCameraInfo(frontInfo, Facing.FRONT)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Concurrent front+back camera not usable, falling back", e)
            provider.unbindAll()
            false
        }
    }

    private fun bindSingle(provider: ProcessCameraProvider, facing: Facing) {
        val (view, selector) = if (facing == Facing.BACK) backPreview to CameraSelector.DEFAULT_BACK_CAMERA
        else frontPreview to CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            val camera = provider.bindToLifecycle(this, selector, buildPreview(view), buildAnalysis(facing))
            readCameraInfo(camera.cameraInfo, facing)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open camera", e)
            statusText.text = getString(R.string.camera_error, e.message ?: "")
        }
    }

    /** Reads the lens field of view and which clock the camera timestamps use. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readCameraInfo(info: CameraInfo, facing: Facing) {
        try {
            val c2 = Camera2CameraInfo.from(info)
            val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelArray = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (focal != null && physical != null && physical.width > 0) {
                // Physical size covers the full pixel array; the output covers the active array.
                val activeFraction = if (pixelArray != null && active != null && pixelArray.width > 0)
                    active.width().toFloat() / pixelArray.width else 1f
                val ratio = focal / (physical.width * activeFraction)
                if (ratio in 0.3f..3f) focalRatio[facing] = ratio.toDouble()
            }
            val source = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            realtimeTimestamps[facing] = source == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            Log.i(TAG, "$facing focal ratio ${focalRatio[facing]}, realtime timestamps ${realtimeTimestamps[facing]}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read camera info for $facing", e)
        }
        if (facing == guideFacing) runOnUiThread { overlay.focalRatio = focalRatio.getValue(facing) }
    }

    /**
     * Converts a camera timestamp to the sensor clock. Cameras with an "unknown" timestamp
     * source use CLOCK_MONOTONIC (System.nanoTime), while sensor events use
     * elapsedRealtimeNanos; the two differ by the time the phone has spent asleep.
     * Matching the wrong clocks puts each photo at the wrong angle.
     */
    private fun toSensorClock(cameraTs: Long, facing: Facing): Long =
        if (realtimeTimestamps.getValue(facing)) cameraTs
        else cameraTs + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())

    // ------------------------------------------------------------------ Frame processing (stitch thread)

    private fun onFrame(image: ImageProxy, facing: Facing) {
        if (!capturing) return
        if (mode == Mode.SINGLE && facing != singleFacing) return
        val rot = orientation.at(toSensorClock(image.imageInfo.timestamp, facing)) ?: return
        val steady = orientation.angularSpeed < CAPTURE_MAX_SPEED
        session.guideFacing = guideFacing
        val decision = session.decide(rot, facing, steady)
        if (decision == CaptureSession.Decision.Skip) return

        val bmp = uprightBitmap(image)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (facing == Facing.FRONT) {
            val removed = masker.maskPeople(bmp, px)
            Log.d(TAG, "Person mask removed ${(removed * 100).roundToInt()}% of front frame")
        }
        bmp.recycle()

        val focalPx = focalRatio.getValue(facing) * max(w, h)
        val result = stitcher.addFrame(px, w, h, focalPx, rot, facing)
        Log.d(TAG, "$facing $decision: ${result.updatedPixels} px, corrected %.2f deg, match %.3f, gain %.2f"
            .format(result.correctionDegrees, result.matchScore, result.gain))
        session.commit(decision, rot, facing)

        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewMs > 200 || guide.allDone) {
            lastPreviewMs = now
            pushPreview()
        }
        runOnUiThread {
            overlay.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
            )
            if (guide.allDone && capturing) {
                toggleCapture()
                statusText.setText(R.string.status_done)
            }
        }
    }

    private fun uprightBitmap(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) return raw
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** Must run on the stitch thread. */
    private fun pushPreview() {
        val pw = 1024
        val preview = Bitmap.createBitmap(stitcher.renderPreview(pw), pw, pw / 2, Bitmap.Config.ARGB_8888)
        val coverage = stitcher.coverage
        runOnUiThread {
            panoView.setPanorama(preview)
            showCoverage(coverage)
        }
    }

    // ------------------------------------------------------------------ Live guidance (UI thread)

    private val guideLoop = object : Runnable {
        override fun run() {
            if (!capturing) return
            updateGuidance()
            overlay.postOnAnimation(this)
        }
    }

    private fun updateGuidance() {
        val rot = orientation.latest() ?: return
        val facing = guideFacing
        val mirrored = facing == Facing.FRONT // the selfie preview is shown mirrored
        val steady = orientation.angularSpeed < CAPTURE_MAX_SPEED
        val basis = stitcher.basisOrNull(rot, facing)

        if (basis == null) {
            // Before the first photo: get the phone upright and still.
            val pitch = Math.toDegrees(CaptureSession.pitchOf(rot, facing))
            val text = when {
                pitch > 12 -> getString(R.string.guide_tilt_down)
                pitch < -12 -> getString(R.string.guide_tilt_up)
                !steady -> getString(R.string.guide_hold_still)
                else -> getString(R.string.guide_capturing)
            }
            overlay.update(emptyList(), null, text, getString(R.string.guide_start_hint), false, mirrored)
            return
        }

        val back = stitcher.basisOrNull(rot, Facing.BACK)
        val front = stitcher.basisOrNull(rot, Facing.FRONT)
        panoView.setPointing(
            if (mode == Mode.DUAL || singleFacing == Facing.BACK) back?.let { lonLat(it) } else null,
            if (mode == Mode.DUAL || singleFacing == Facing.FRONT) front?.let { lonLat(it) } else null,
        )

        val progress = getString(R.string.guide_progress, guide.doneCount, guide.targets.size)
        val next = guide.nextTarget(basis)
        if (next < 0) {
            overlay.update(emptyList(), null, getString(R.string.guide_done), progress, true, mirrored)
            return
        }

        val done = guide.done
        val dots = ArrayList<GuideOverlayView.Dot>()
        for ((i, t) in guide.targets.withIndex()) {
            if (done[i]) continue
            val z = t.x * basis.fx + t.y * basis.fy + t.z * basis.fz
            if (z < 0.2) continue
            dots.add(GuideOverlayView.Dot(
                (t.x * basis.rx + t.y * basis.ry + t.z * basis.rz) / z,
                (t.x * basis.dx + t.y * basis.dy + t.z * basis.dz) / z,
                i == next,
            ))
        }
        val dir = guide.directionTo(basis, next)
        val onTarget = dir.distanceDegrees < Math.toDegrees(CaptureGuide.CAPTURE_TOLERANCE)
        val text = when {
            onTarget && !steady -> getString(R.string.guide_hold_still)
            onTarget -> getString(R.string.guide_capturing)
            abs(dir.turnDegrees) >= abs(dir.tiltDegrees) ->
                if (dir.turnDegrees > 0) getString(R.string.guide_turn_right, abs(dir.turnDegrees).roundToInt())
                else getString(R.string.guide_turn_left, abs(dir.turnDegrees).roundToInt())
            else ->
                if (dir.tiltDegrees > 0) getString(R.string.guide_tilt_up_by, abs(dir.tiltDegrees).roundToInt())
                else getString(R.string.guide_tilt_down_by, abs(dir.tiltDegrees).roundToInt())
        }
        // Arrow: towards the target on screen, or sideways if it's behind the camera.
        val arrow = when {
            onTarget -> null
            dir.imageX != null -> dir.arrowAngle
            else -> if (dir.turnDegrees >= 0) 0.0 else Math.PI
        }
        overlay.update(dots, arrow, text, progress, onTarget, mirrored)
    }

    private fun lonLat(b: PanoramaStitcher.CameraBasis) =
        kotlin.math.atan2(b.fx, b.fy) to kotlin.math.atan2(b.fz, kotlin.math.sqrt(b.fx * b.fx + b.fy * b.fy))

    // ------------------------------------------------------------------ UI actions

    private fun toggleCapture() {
        capturing = !capturing
        if (capturing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusText.setText(if (mode == Mode.DUAL) R.string.intro_dual else R.string.intro_single)
            overlay.postOnAnimation(guideLoop)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusText.setText(R.string.status_paused)
            overlay.clear()
        }
        updateUi()
    }

    private fun resetPanorama() {
        if (capturing) toggleCapture()
        stitchExecutor.execute {
            session.reset()
            pushPreview()
            runOnUiThread { panoView.setPointing(null, null) }
        }
        statusText.setText(if (mode == Mode.DUAL) R.string.intro_dual else R.string.intro_single)
    }

    private fun savePanorama() {
        if (saving) return
        if (capturing) toggleCapture()
        saving = true
        updateUi()
        statusText.setText(R.string.status_saving)
        stitchExecutor.execute {
            try {
                if (stitcher.coverage < 0.01) {
                    runOnUiThread { Toast.makeText(this, R.string.nothing_to_save, Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                val uri = PanoSaver.save(this, stitcher.renderFinal(), stitcher.width, stitcher.height)
                runOnUiThread {
                    statusText.setText(R.string.status_saved)
                    startActivity(Intent(this, ViewerActivity::class.java).setData(uri))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Save failed", e)
                runOnUiThread { statusText.text = getString(R.string.save_failed, e.message ?: "") }
            } finally {
                saving = false
                runOnUiThread { updateUi() }
            }
        }
    }

    private fun showCoverage(coverage: Double) {
        val pct = (coverage * 100).roundToInt()
        coverageBar.progress = pct
        coverageText.text = getString(R.string.coverage, pct)
    }

    private fun updateUi() {
        startButton.setText(if (capturing) R.string.pause else R.string.start)
        saveButton.isEnabled = !saving
        val dual = mode == Mode.DUAL
        modeText.setText(if (dual) R.string.mode_dual else R.string.mode_single)
        switchButton.visibility = if (dual) View.GONE else View.VISIBLE
        overlay.focalRatio = focalRatio.getValue(guideFacing)

        // Layout: the guiding camera fills the big view; in dual mode the selfie camera is an inset.
        val frontFull = !dual && singleFacing == Facing.FRONT
        backPreview.visibility = if (dual || singleFacing == Facing.BACK) View.VISIBLE else View.GONE
        frontContainer.visibility = if (dual || frontFull) View.VISIBLE else View.GONE
        val lp = frontContainer.layoutParams as FrameLayout.LayoutParams
        if (frontFull) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.setMargins(0, 0, 0, 0)
        } else {
            val d = resources.displayMetrics.density
            lp.width = (96 * d).toInt()
            lp.height = (128 * d).toInt()
            lp.setMargins(0, (8 * d).toInt(), (8 * d).toInt(), 0)
        }
        frontContainer.layoutParams = lp

        if (!capturing && !saving && stitcher.coverage == 0.0 && orientation.isAvailable) {
            statusText.setText(if (dual) R.string.intro_dual else R.string.intro_single)
        }
    }

    companion object {
        private const val TAG = "Camera360"
        /** ~ 4.3mm lens on a 5.6mm-wide sensor: a typical phone main camera (~66 deg). */
        private const val DEFAULT_FOCAL_RATIO = 0.77
        /** Only take photos while turning slower than this (rad/s, ~17 deg/s) to avoid blur. */
        private const val CAPTURE_MAX_SPEED = 0.3f
    }
}
