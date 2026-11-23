package com.angadlobo.camera360

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Interactive 360 viewer: drag to look around, pinch to zoom, or turn on Motion to look by moving the phone. */
class ViewerActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private val renderer = SphereRenderer()
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var yaw = 0.0      // radians, 0 = panorama centre, positive = to the right
    private var pitch = 0.0    // radians, positive = up
    private var motion = false
    private var motionYawOffset = Double.NaN

    private var lastX = 0f
    private var lastY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                renderer.fovDegrees = (renderer.fovDegrees / d.scaleFactor).coerceIn(30f, 110f)
                return true
            }
        })

        val motionButton = findViewById<Button>(R.id.motionButton)
        motionButton.isEnabled = rotationSensor != null
        motionButton.setOnClickListener {
            motion = !motion
            motionYawOffset = Double.NaN
            motionButton.setText(if (motion) R.string.motion_on else R.string.motion_off)
            if (!motion) applyDragLook()
        }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            val uri = intent.data ?: return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        applyDragLook()
        loadImage()
    }

    private fun loadImage() {
        val uri = intent.data ?: run { finish(); return }
        thread(name = "pano-load") {
            try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    if (w > 8192) decoder.setTargetSize(8192, info.size.height * 8192 / w)
                }
                renderer.setBitmap(bmp)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.load_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                val degPerPx = renderer.fovDegrees / glView.height.coerceAtLeast(1).coerceAtMost(glView.width)
                val dx = Math.toRadians(((event.x - lastX) * degPerPx).toDouble())
                val dy = Math.toRadians(((event.y - lastY) * degPerPx).toDouble())
                lastX = event.x; lastY = event.y
                if (motion) {
                    if (!motionYawOffset.isNaN()) motionYawOffset += dx
                } else {
                    yaw -= dx
                    pitch = (pitch + dy).coerceIn(-1.55, 1.55)
                    applyDragLook()
                }
            }
        }
        return true
    }

    private fun applyDragLook() {
        val cp = cos(pitch)
        renderer.setLook(
            (cp * sin(yaw)).toFloat(), (cp * cos(yaw)).toFloat(), sin(pitch).toFloat(),
            0f, 0f, 1f,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!motion) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        @Suppress("DEPRECATION")
        val m = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> { SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped); remapped }
            Surface.ROTATION_180 -> { SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped); remapped }
            Surface.ROTATION_270 -> { SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped); remapped }
            else -> rot
        }
        // Looking "through" the screen = -Z of the screen frame; screen up = +Y.
        val fx = -m[2].toDouble(); val fy = -m[5].toDouble(); val fz = -m[8].toDouble()
        val ux = m[1].toDouble(); val uy = m[4].toDouble(); val uz = m[7].toDouble()
        if (motionYawOffset.isNaN()) {
            // Keep looking where we were when Motion was switched on.
            motionYawOffset = atan2(fx, fy) - yaw
        }
        val c = cos(motionYawOffset); val s = sin(motionYawOffset)
        renderer.setLook(
            (fx * c - fy * s).toFloat(), (fy * c + fx * s).toFloat(), fz.toFloat(),
            (ux * c - uy * s).toFloat(), (uy * c + ux * s).toFloat(), uz.toFloat(),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
