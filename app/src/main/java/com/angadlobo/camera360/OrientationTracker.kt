package com.angadlobo.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks device orientation with the rotation-vector sensor and keeps a short
 * history so camera frames can be matched to the orientation at their capture time.
 *
 * Prefers the "game" rotation vector (gyro + accelerometer, no magnetometer) because
 * it does not jump when you walk past metal, which would tear the panorama.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val isAvailable: Boolean get() = rotationSensor != null

    private val historySize = 64
    private val times = LongArray(historySize)
    private val mats = Array(historySize) { FloatArray(9) }
    private var head = 0
    private var count = 0
    private val lock = Any()

    /** Latest angular speed in rad/s (0 if no gyroscope). */
    @Volatile var angularSpeed: Float = 0f
        private set

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                angularSpeed = sqrt(x * x + y * y + z * z)
            }
            else -> synchronized(lock) {
                SensorManager.getRotationMatrixFromVector(mats[head], event.values)
                times[head] = event.timestamp
                head = (head + 1) % historySize
                if (count < historySize) count++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Latest rotation matrix (world = R * device), or null if none yet. */
    fun latest(): FloatArray? = synchronized(lock) {
        if (count == 0) null else mats[(head - 1 + historySize) % historySize].copyOf()
    }

    /**
     * Rotation closest in time to [timestampNs]. Camera and sensor timestamps share the
     * same clock on most phones; if they clearly don't, falls back to the latest sample.
     */
    fun at(timestampNs: Long): FloatArray? = synchronized(lock) {
        if (count == 0) return null
        var best = -1
        var bestDt = Long.MAX_VALUE
        for (k in 0 until count) {
            val idx = (head - 1 - k + historySize) % historySize
            val dt = abs(times[idx] - timestampNs)
            if (dt < bestDt) { bestDt = dt; best = idx }
        }
        val idx = if (bestDt < 150_000_000L) best else (head - 1 + historySize) % historySize
        mats[idx].copyOf()
    }
}
