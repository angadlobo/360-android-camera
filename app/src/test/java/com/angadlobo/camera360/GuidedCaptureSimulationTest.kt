package com.angadlobo.camera360

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * End-to-end simulation of a guided capture session, without a phone:
 * a virtual user follows the on-screen directions, the gyroscope is noisy and drifts,
 * the phone is held slightly tilted, both cameras run, every selfie frame contains the
 * person holding the phone (cut out, as the AI segmentation does), and every photo has
 * a different exposure. The stitched panorama must cover the sphere and match the scene.
 */
class GuidedCaptureSimulationTest {

    private val w = 300
    private val h = 400
    private val focal = (h / 2.0) / tan(Math.toRadians(66.0) / 2)

    // ---- scene: textured "room" with a dark line every 30 degrees so seams are visible
    private fun hash(x: Int, y: Int, z: Int): Double {
        var k = x * 374761393 + y * 668265263 + z * 1274126177
        k = (k xor (k ushr 13)) * 1274126177
        return ((k xor (k ushr 16)) and 0xFFFF) / 65535.0
    }

    private fun noise(px: Double, py: Double, pz: Double): Double {
        val x0 = floor(px).toInt(); val y0 = floor(py).toInt(); val z0 = floor(pz).toInt()
        fun s(t: Double) = t * t * (3 - 2 * t)
        val sx = s(px - x0); val sy = s(py - y0); val sz = s(pz - z0)
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        return l(
            l(l(hash(x0, y0, z0), hash(x0 + 1, y0, z0), sx), l(hash(x0, y0 + 1, z0), hash(x0 + 1, y0 + 1, z0), sx), sy),
            l(l(hash(x0, y0, z0 + 1), hash(x0 + 1, y0, z0 + 1), sx), l(hash(x0, y0 + 1, z0 + 1), hash(x0 + 1, y0 + 1, z0 + 1), sx), sy),
            sz,
        )
    }

    private fun scene(x: Double, y: Double, z: Double): Int {
        val n = sqrt(x * x + y * y + z * z)
        val ux = x / n; val uy = y / n; val uz = z / n
        val v = 0.6 * noise(ux * 8, uy * 8, uz * 8) + 0.4 * noise(ux * 22 + 5, uy * 22, uz * 22)
        val lon = Math.toDegrees(atan2(ux, uy))
        val lat = Math.toDegrees(atan2(uz, sqrt(ux * ux + uy * uy)))
        val line = (abs(lon - 30 * Math.round(lon / 30)) < 0.6 && abs(lat) < 80) || abs(lat - 30 * Math.round(lat / 30)) < 0.6
        if (line) return 0xFF202020.toInt()
        val r = (60 + 160 * v * (0.75 + 0.25 * ux)).toInt().coerceIn(0, 255)
        val g = (60 + 160 * v).toInt().coerceIn(0, 255)
        val b = (60 + 160 * v * (0.75 + 0.25 * uz)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---- phone pose helpers (world = R * device)
    private fun upright(heading: Double, pitch: Double): FloatArray {
        val f = doubleArrayOf(sin(heading) * cos(pitch), cos(heading) * cos(pitch), sin(pitch))
        val r = doubleArrayOf(cos(heading), -sin(heading), 0.0)
        val u = doubleArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        return floatArrayOf(
            r[0].toFloat(), u[0].toFloat(), (-f[0]).toFloat(),
            r[1].toFloat(), u[1].toFloat(), (-f[1]).toFloat(),
            r[2].toFloat(), u[2].toFloat(), (-f[2]).toFloat(),
        )
    }

    private fun perturb(rot: FloatArray, ax: Double, ay: Double, az: Double): FloatArray {
        val t = sqrt(ax * ax + ay * ay + az * az)
        if (t < 1e-12) return rot.copyOf()
        val x = ax / t; val y = ay / t; val z = az / t
        val c = cos(t); val s = sin(t); val k = 1 - c
        val m = doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k,
        )
        val out = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            out[i * 3 + j] = (rot[i * 3] * m[j] + rot[i * 3 + 1] * m[3 + j] + rot[i * 3 + 2] * m[6 + j]).toFloat()
        }
        return out
    }

    private fun render(rot: FloatArray, facing: Facing, gain: Double, withPerson: Boolean): IntArray {
        val s = if (facing == Facing.BACK) 1.0 else -1.0
        val out = IntArray(w * h)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        for (v in 0 until h) for (u in 0 until w) {
            if (withPerson) {
                // The user's head and shoulders, already cut out by the segmentation model.
                val ex = (u - w * 0.5) / (w * 0.32)
                val ey = (v - h * 0.72) / (h * 0.42)
                if (ex * ex + ey * ey < 1) { out[v * w + u] = 0; continue }
            }
            val dx = s * (u - cx) / focal
            val dy = -(v - cy) / focal
            val dz = -s
            val p = scene(
                rot[0] * dx + rot[1] * dy + rot[2] * dz,
                rot[3] * dx + rot[4] * dy + rot[5] * dz,
                rot[6] * dx + rot[7] * dy + rot[8] * dz,
            )
            fun c(sh: Int) = (((p shr sh) and 0xFF) * gain).toInt().coerceIn(0, 255)
            out[v * w + u] = (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
        return out
    }

    @Test
    fun virtualUserCapturesAFullSphereByFollowingTheGuide() {
        val stitcher = PanoramaStitcher(2048)
        val guide = CaptureGuide()
        val session = CaptureSession(stitcher, guide)
        val rnd = java.util.Random(7)

        var heading = 0.3
        var pitch = 0.0
        var drift = 0.0
        var photos = 0
        var moves = 0
        val maxStep = Math.toRadians(12.0)

        for (step in 0 until 600) {
            // The user can't hold the phone perfectly: a few degrees of roll.
            val roll = Math.toRadians((rnd.nextDouble() * 2 - 1) * 4)
            val truth = perturb(upright(heading, pitch), 0.0, 0.0, roll)
            // The gyroscope: slow yaw drift + ~1 degree of noise.
            drift += Math.toRadians(0.04)
            val nz = Math.toRadians(1.0)
            val sensor = perturb(
                perturb(upright(heading + drift, pitch), 0.0, 0.0, roll),
                (rnd.nextDouble() * 2 - 1) * nz, (rnd.nextDouble() * 2 - 1) * nz, (rnd.nextDouble() * 2 - 1) * nz,
            )

            // Phone is still: each camera may take a photo.
            for (facing in Facing.values()) {
                val d = session.decide(sensor, facing, steady = true)
                if (d == CaptureSession.Decision.Skip) continue
                val frame = render(truth, facing, 0.8 + rnd.nextDouble() * 0.4, withPerson = facing == Facing.FRONT)
                stitcher.addFrame(frame, w, h, focal, sensor, facing)
                session.commit(d, sensor, facing)
                photos++
            }
            if (guide.allDone) break

            // Read the on-screen instruction and move toward the next target.
            val basis = stitcher.basisOrNull(sensor, Facing.BACK) ?: continue
            val next = guide.nextTarget(basis)
            if (next < 0) break
            val dir = guide.directionTo(basis, next)
            val turn = Math.toRadians(dir.turnDegrees).coerceIn(-maxStep, maxStep)
            val tilt = Math.toRadians(dir.tiltDegrees).coerceIn(-maxStep, maxStep)
            heading += turn
            pitch = (pitch + tilt).coerceIn(-1.5, 1.5)
            moves++
        }

        // Evaluate the saved (levelled) image against the true scene, ignoring the polar caps.
        // Which compass direction ends up in the centre is arbitrary, so allow a heading offset.
        val pano = stitcher.renderFinal(0)
        fun errorAt(yawOffset: Double, stepPx: Int): Double {
            var sum = 0.0
            var n = 0
            for (j in stitcher.height / 9 until stitcher.height * 8 / 9 step stepPx) for (i in 0 until stitcher.width step stepPx) {
                val p = pano[j * stitcher.width + i]
                if (p ushr 24 == 0) continue
                val lon = ((i + 0.5) / stitcher.width - 0.5) * 2 * PI + stitcher.referenceHeading + yawOffset
                val lat = PI / 2 - (j + 0.5) / stitcher.height * PI
                val e = scene(cos(lat) * sin(lon), cos(lat) * cos(lon), sin(lat))
                sum += abs(((p shr 8) and 0xFF) - ((e shr 8) and 0xFF))
                n++
            }
            return sum / n
        }
        val bestYaw = (-30..30).map { Math.toRadians(it * 0.1) }.minBy { errorAt(it, 8) }
        val err = errorAt(bestYaw, 2)
        val tilt = stitcher.tiltDegrees

        // Write the result as a PPM image (uncovered areas magenta) for visual inspection.
        val out = File("build/sim-panorama.ppm")
        out.parentFile?.mkdirs()
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write("P6\n${stitcher.width} ${stitcher.height}\n255\n".toByteArray())
        for (p in pano.map { if (it == 0) 0xFFFF00FF.toInt() else it }) { bytes.write((p shr 16) and 0xFF); bytes.write((p shr 8) and 0xFF); bytes.write(p and 0xFF) }
        out.writeBytes(bytes.toByteArray())

        println("SIM: ${guide.doneCount}/${guide.targets.size} targets, $photos photos, $moves moves, " +
            "coverage ${"%.3f".format(stitcher.coverage)}, mean error ${"%.2f".format(err)}, levelled by ${"%.2f".format(tilt)} deg, " +
            "final gyro drift ${"%.1f".format(Math.toDegrees(drift))} deg -> ${out.absolutePath}")

        assertTrue("guide never finished", guide.allDone)
        assertTrue("coverage ${stitcher.coverage}", stitcher.coverage > 0.97)
        assertTrue("mean error $err", err < 10)
    }
}
