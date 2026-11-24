package com.angadlobo.camera360

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photo-Sphere-style guided capture: a fixed set of target directions covering the
 * sphere with enough overlap for alignment. The user is directed to the nearest
 * unfinished target, and a frame is only taken when a camera is on a target.
 *
 * The target set is symmetric through the centre (every target's opposite direction is
 * also a target), so when the back camera is on one target the front camera is on the
 * opposite one and both can be captured at the same moment.
 *
 * Pure Kotlin; directions are in the panorama frame of [PanoramaStitcher].
 */
class CaptureGuide {

    class Target(val lon: Double, val lat: Double) {
        val x = cos(lat) * sin(lon)
        val y = cos(lat) * cos(lon)
        val z = sin(lat)
    }

    val targets: List<Target> = buildList {
        // (latitude, count). Rows overlap by ~40% for a typical 65 x 50 degree portrait frame.
        val rows = listOf(0.0 to 12, 42.0 to 8, -42.0 to 8, 75.0 to 4, -75.0 to 4)
        for ((latDeg, count) in rows) {
            for (k in 0 until count) {
                var lon = 2 * PI * k / count
                if (lon > PI) lon -= 2 * PI
                add(Target(lon, Math.toRadians(latDeg)))
            }
        }
    }

    /** Patch radius around a target that must be covered for it to count as done. */
    val doneRadius = Math.toRadians(18.0)

    /** done[i] is true once the area around target i is covered. Replaced, never mutated. */
    @Volatile var done: BooleanArray = BooleanArray(targets.size)
        private set

    val doneCount: Int get() = done.count { it }
    val allDone: Boolean get() = done.all { it }

    fun reset() {
        done = BooleanArray(targets.size)
    }

    /** Marks a target done (used when the main camera has photographed it directly). */
    fun markDone(index: Int) {
        val d = done.copyOf()
        d[index] = true
        done = d
    }

    /** Re-checks which targets are covered. Call on the stitching thread. */
    fun refresh(stitcher: PanoramaStitcher) {
        val d = done.copyOf()
        for ((i, t) in targets.withIndex()) {
            if (!d[i] && stitcher.coverageAround(t.lon, t.lat, doneRadius) >= DONE_FRACTION) d[i] = true
        }
        done = d
    }

    /** Index of the unfinished target within [tolerance] of the camera's forward axis, or -1. */
    fun targetUnder(b: PanoramaStitcher.CameraBasis, tolerance: Double = CAPTURE_TOLERANCE): Int {
        val d = done
        var best = -1
        var bestAngle = tolerance
        for ((i, t) in targets.withIndex()) {
            if (d[i]) continue
            val a = angle(b, t)
            if (a < bestAngle) { bestAngle = a; best = i }
        }
        return best
    }

    /**
     * Next target to send the (back) camera to: the nearest unfinished one, with a mild
     * preference for staying on the current row so the sweep is orderly. -1 when finished.
     */
    fun nextTarget(b: PanoramaStitcher.CameraBasis): Int {
        val d = done
        val camLat = atan2(b.fz, kotlin.math.sqrt(b.fx * b.fx + b.fy * b.fy))
        var best = -1
        var bestCost = Double.MAX_VALUE
        for ((i, t) in targets.withIndex()) {
            if (d[i]) continue
            val cost = angle(b, t) + 0.6 * abs(t.lat - camLat)
            if (cost < bestCost) { bestCost = cost; best = i }
        }
        return best
    }

    /** Instruction telling the user how to move the phone to bring the camera onto a target. */
    data class Direction(
        /** Target position in camera image coordinates (x right, y down, 1 = focal length); null if behind. */
        val imageX: Double?,
        val imageY: Double?,
        /** Screen-space angle (radians, 0 = right, PI/2 = down) of the arrow to draw. */
        val arrowAngle: Double,
        /** Signed degrees to turn: positive = turn right. */
        val turnDegrees: Double,
        /** Signed degrees to tilt: positive = tilt up. */
        val tiltDegrees: Double,
        val distanceDegrees: Double,
    )

    fun directionTo(b: PanoramaStitcher.CameraBasis, index: Int): Direction {
        val t = targets[index]
        val z = t.x * b.fx + t.y * b.fy + t.z * b.fz
        val x = t.x * b.rx + t.y * b.ry + t.z * b.rz
        val y = t.x * b.dx + t.y * b.dy + t.z * b.dz
        // Turn = difference in compass direction; tilt = difference in elevation.
        var turn = t.lon - atan2(b.fx, b.fy)
        while (turn > PI) turn -= 2 * PI
        while (turn < -PI) turn += 2 * PI
        val camLat = atan2(b.fz, kotlin.math.sqrt(b.fx * b.fx + b.fy * b.fy))
        val tilt = t.lat - camLat
        // Near the poles compass direction is meaningless; only tilt matters.
        val turnDeg = if (abs(camLat) > Math.toRadians(80.0) || abs(t.lat) > Math.toRadians(80.0)) 0.0 else Math.toDegrees(turn)
        return Direction(
            imageX = if (z > 0.05) x / z else null,
            imageY = if (z > 0.05) y / z else null,
            arrowAngle = atan2(y, x),
            turnDegrees = turnDeg,
            tiltDegrees = Math.toDegrees(tilt),
            distanceDegrees = Math.toDegrees(angle(b, t)),
        )
    }

    fun angle(b: PanoramaStitcher.CameraBasis, t: Target): Double =
        acos((t.x * b.fx + t.y * b.fy + t.z * b.fz).coerceIn(-1.0, 1.0))

    companion object {
        /** A camera is "on" a target within this angle. */
        val CAPTURE_TOLERANCE = Math.toRadians(6.0)
        const val DONE_FRACTION = 0.93
    }
}
