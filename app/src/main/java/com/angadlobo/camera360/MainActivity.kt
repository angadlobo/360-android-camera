package com.angadlobo.camera360

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Capture screen. Runs the back and front cameras at the same time (on phones that
 * support concurrent cameras) and projects every new viewpoint into one 360 panorama
 * using the phone's motion sensors. Because the two cameras face opposite ways, you
 * only have to turn half-way round to cover the full sphere.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { DUAL, SINGLE }

    private lateinit var panoView: PanoPreviewView
    private lateinit var backPreview: PreviewView
    private lateinit var frontPreview: PreviewView
    private lateinit var frontContainer: View
    private lateinit var backContainer: View
    private lateinit var modeText: TextView
    private lateinit var statusText: TextView
    private lateinit var coverageText: TextView
    private lateinit var coverageBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var switchButton: Button

    private lateinit var orientation: OrientationTracker
    private val stitcher = PanoramaStitcher(4096)
    private lateinit var stitchExecutor: ExecutorService

    @Volatile private var capturing = false
    @Volatile private var saving = false
    private var mode = Mode.DUAL
    private var singleFacing = Facing.BACK
    /** null until we've tried; false once we know this phone can't run both cameras together. */
    private var dualSupported: Boolean? = null

    /** focal length / sensor long side, per camera. Multiply by image long side for pixels. */
    private val focalRatio = mutableMapOf(Facing.BACK to DEFAULT_FOCAL_RATIO, Facing.FRONT to DEFAULT_FOCAL_RATIO)

    // Only touched on stitchExecutor.
    private val acceptedDirections = ArrayList<DoubleArray>()
    private var lastPreviewMs = 0L
    private var lastPointingMs = 0L
    private var lastBackPointing: Pair<Double, Double>? = null
    private var lastFrontPointing: Pair<Double, Double>? = null

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
        backContainer = findViewById(R.id.backContainer)
        frontContainer = findViewById(R.id.frontContainer)
        modeText = findViewById(R.id.modeText)
        statusText = findViewById(R.id.statusText)
        coverageText = findViewById(R.id.coverageText)
        coverageBar = findViewById(R.id.coverageBar)
        startButton = findViewById(R.id.startButton)
        saveButton = findViewById(R.id.saveButton)
        switchButton = findViewById(R.id.switchButton)

        orientation = OrientationTracker(this)
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
            readIntrinsics(backInfo, Facing.BACK)
            readIntrinsics(frontInfo, Facing.FRONT)
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
            readIntrinsics(camera.cameraInfo, facing)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open camera", e)
            statusText.text = getString(R.string.camera_error, e.message ?: "")
        }
    }

    /** Reads lens focal length and sensor size to get the true field of view. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readIntrinsics(info: CameraInfo, facing: Facing) {
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
            Log.i(TAG, "$facing focal ratio ${focalRatio[facing]}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read intrinsics for $facing", e)
        }
    }

    // ------------------------------------------------------------------ Frame processing (stitch thread)

    private fun onFrame(image: ImageProxy, facing: Facing) {
        if (!capturing) return
        val rot = orientation.at(image.imageInfo.timestamp) ?: return

        val now = SystemClock.uptimeMillis()
        val pointing = stitcher.pointing(rot, facing)
        if (facing == Facing.BACK) lastBackPointing = pointing else lastFrontPointing = pointing
        if (now - lastPointingMs > 80) {
            lastPointingMs = now
            val back = if (mode == Mode.DUAL || singleFacing == Facing.BACK) lastBackPointing else null
            val front = if (mode == Mode.DUAL || singleFacing == Facing.FRONT) lastFrontPointing else null
            runOnUiThread { panoView.setPointing(back, front) }
        }

        if (orientation.angularSpeed > MAX_ANGULAR_SPEED) {
            postStatus(R.string.status_slow_down)
            return
        }

        val b = stitcher.basisFor(rot, facing)
        val fwd = doubleArrayOf(b.fx, b.fy, b.fz)
        val alreadySeen = acceptedDirections.any { it[0] * fwd[0] + it[1] * fwd[1] + it[2] * fwd[2] > MIN_NEW_VIEW_COS }
        if (alreadySeen) {
            postStatus(if (stitcher.coverage > 0.97) R.string.status_done else R.string.status_keep_turning)
            return
        }

        val bmp = uprightBitmap(image)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        val focalPx = focalRatio.getValue(facing) * max(w, h)
        val changed = stitcher.addFrame(px, w, h, focalPx, rot, facing)
        acceptedDirections.add(fwd)
        Log.d(TAG, "$facing frame ${w}x$h -> $changed px, coverage ${stitcher.coverage}")

        if (now - lastPreviewMs > 250) {
            lastPreviewMs = now
            pushPreview()
        }
        postStatus(R.string.status_capturing)
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

    private var lastStatusRes = 0
    private fun postStatus(res: Int) {
        if (res == lastStatusRes) return
        lastStatusRes = res
        runOnUiThread { if (capturing) statusText.setText(res) }
    }

    // ------------------------------------------------------------------ UI actions

    private fun toggleCapture() {
        capturing = !capturing
        if (capturing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusText.setText(R.string.status_capturing)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusText.setText(R.string.status_paused)
        }
        lastStatusRes = 0
        updateUi()
    }

    private fun resetPanorama() {
        stitchExecutor.execute {
            stitcher.reset()
            acceptedDirections.clear()
            lastBackPointing = null
            lastFrontPointing = null
            pushPreview()
            runOnUiThread { panoView.setPointing(null, null) }
        }
        if (capturing) toggleCapture()
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

    @SuppressLint("SetTextI18n")
    private fun updateUi() {
        startButton.setText(if (capturing) R.string.pause else R.string.start)
        saveButton.isEnabled = !saving
        val dual = mode == Mode.DUAL
        modeText.setText(if (dual) R.string.mode_dual else R.string.mode_single)
        switchButton.visibility = if (dual) View.GONE else View.VISIBLE
        backContainer.visibility = if (dual || singleFacing == Facing.BACK) View.VISIBLE else View.GONE
        frontContainer.visibility = if (dual || singleFacing == Facing.FRONT) View.VISIBLE else View.GONE
        if (!capturing && !saving && stitcher.coverage == 0.0 && orientation.isAvailable) {
            statusText.setText(if (dual) R.string.intro_dual else R.string.intro_single)
        }
    }

    companion object {
        private const val TAG = "Camera360"
        /** ~ 4.3mm lens on a 5.6mm-wide sensor: a typical phone main camera (~66 deg). */
        private const val DEFAULT_FOCAL_RATIO = 0.77
        /** Skip frames while the phone turns faster than this (rad/s) to avoid motion blur. */
        private const val MAX_ANGULAR_SPEED = 1.2f
        /** A frame counts as a new view if it points > ~9 degrees away from every earlier one. */
        private val MIN_NEW_VIEW_COS = cos(Math.toRadians(9.0))
    }
}
