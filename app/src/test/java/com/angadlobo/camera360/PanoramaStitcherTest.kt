package com.angadlobo.camera360

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class PanoramaStitcherTest {

    /**
     * Rotation matrix (world = R * device) for a portrait phone held upright, whose back
     * camera faces compass [heading] (radians clockwise from north), tilted up by [pitch].
     */
    private fun uprightRotation(heading: Double, pitch: Double = 0.0): FloatArray {
        // Back camera forward (= device -Z) in world.
        val fwd = doubleArrayOf(sin(heading) * cos(pitch), cos(heading) * cos(pitch), sin(pitch))
        val right = doubleArrayOf(cos(heading), -sin(heading), 0.0)          // device +X
        // device +Y = right x (-fwd) ... compute up = right x fwd? use up = -(fwd x right)
        val up = doubleArrayOf(
            right[1] * fwd[2] - right[2] * fwd[1],
            right[2] * fwd[0] - right[0] * fwd[2],
            right[0] * fwd[1] - right[1] * fwd[0],
        )
        val zDev = doubleArrayOf(-fwd[0], -fwd[1], -fwd[2])                    // device +Z
        // Columns are images of device axes.
        return floatArrayOf(
            right[0].toFloat(), up[0].toFloat(), zDev[0].toFloat(),
            right[1].toFloat(), up[1].toFloat(), zDev[1].toFloat(),
            right[2].toFloat(), up[2].toFloat(), zDev[2].toFloat(),
        )
    }

    /** Smooth synthetic scene as a function of absolute world direction. */
    private fun scene(x: Double, y: Double, z: Double): Int {
        // Linear in the unit direction, so it is smooth everywhere including the poles.
        val n = sqrt(x * x + y * y + z * z)
        val r = (127 + 120 * x / n).toInt()
        val g = (127 + 120 * y / n).toInt()
        val b = (127 + 120 * z / n).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Renders what a camera would see, following the conventions in PanoramaStitcher. */
    private fun renderFrame(rot: FloatArray, facing: Facing, w: Int, h: Int, f: Double): IntArray {
        val s = if (facing == Facing.BACK) 1.0 else -1.0
        val out = IntArray(w * h)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        for (v in 0 until h) for (u in 0 until w) {
            val x = (u - cx) / f
            val y = (v - cy) / f
            // device dir = forward + x*right + y*down
            val dx = s * x
            val dy = -y
            val dz = -s
            val wx = rot[0] * dx + rot[1] * dy + rot[2] * dz
            val wy = rot[3] * dx + rot[4] * dy + rot[5] * dz
            val wz = rot[6] * dx + rot[7] * dy + rot[8] * dz
            out[v * w + u] = scene(wx, wy, wz)
        }
        return out
    }

    private fun channelDiff(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    )

    private val w = 240
    private val h = 320
    private val focal = (h / 2.0) / tan(Math.toRadians(70.0) / 2) // ~70 deg vertical FOV

    @Test
    fun backCameraFacingNorthLandsInCentre() {
        val st = PanoramaStitcher(512)
        val rot = uprightRotation(0.0)
        val frame = IntArray(w * h) { 0xFFFF0000.toInt() }
        st.addFrame(frame, w, h, focal, rot, Facing.BACK)
        assertEquals(0.0, st.referenceHeading, 1e-6)
        val centre = st.pixels[(st.height / 2) * st.width + st.width / 2]
        assertEquals(0xFFFF0000.toInt(), centre)
        // Behind the user should still be empty.
        assertEquals(0, st.pixels[(st.height / 2) * st.width + 2])
    }

    @Test
    fun frontCameraFillsTheOppositeSide() {
        val st = PanoramaStitcher(512)
        val rot = uprightRotation(0.3)
        st.addFrame(IntArray(w * h) { 0xFF00FF00.toInt() }, w, h, focal, rot, Facing.FRONT)
        val row = (st.height / 2) * st.width
        assertEquals(0xFF00FF00.toInt(), st.pixels[row + 1])           // lon ~ -180
        assertEquals(0xFF00FF00.toInt(), st.pixels[row + st.width - 2]) // lon ~ +180
        assertEquals(0, st.pixels[row + st.width / 2])                  // lon 0 empty
    }

    @Test
    fun imageRightMapsToPanoramaRightForBothCameras() {
        // Left half of image dark, right half bright.
        val frame = IntArray(w * h) { i -> if (i % w > w / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val st = PanoramaStitcher(512)
        st.addFrame(frame, w, h, focal, uprightRotation(0.0), Facing.BACK)
        val row = (st.height / 2) * st.width
        assertEquals(0xFFFFFFFF.toInt(), st.pixels[row + st.width / 2 + 20])
        assertEquals(0xFF000000.toInt(), st.pixels[row + st.width / 2 - 20])

        // Front camera looking south: its image right is west, which in the panorama
        // (centred on north, east to the right) is the far left edge, i.e. column ~ W/2 - W/4 ... wraps.
        val st2 = PanoramaStitcher(512)
        st2.addFrame(IntArray(w * h) { 0xFF808080.toInt() }, w, h, focal, uprightRotation(0.0), Facing.BACK)
        st2.addFrame(frame, w, h, focal, uprightRotation(0.0), Facing.FRONT)
        // Just west of south (lon ~ -170deg) should be bright; just east of south dark.
        val westOfSouth = ((-170.0 / 360 + 0.5) * st2.width).toInt()
        val eastOfSouth = ((170.0 / 360 + 0.5) * st2.width).toInt()
        assertEquals(0xFFFFFFFF.toInt(), st2.pixels[row + westOfSouth])
        assertEquals(0xFF000000.toInt(), st2.pixels[row + eastOfSouth])
    }

    @Test
    fun imageTopMapsToSky() {
        val frame = IntArray(w * h) { i -> if (i / w < h / 2) 0xFF0000FF.toInt() else 0xFF00FF00.toInt() }
        val st = PanoramaStitcher(512)
        st.addFrame(frame, w, h, focal, uprightRotation(0.0), Facing.FRONT)
        val col = 3
        assertEquals(0xFF0000FF.toInt(), st.pixels[(st.height / 2 - 20) * st.width + col])
        assertEquals(0xFF00FF00.toInt(), st.pixels[(st.height / 2 + 20) * st.width + col])
    }

    // ---------------------------------------------------------------- textured scene + sensor error

    private fun hash(x: Int, y: Int, z: Int): Double {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }

    private fun valueNoise(px: Double, py: Double, pz: Double): Double {
        val x0 = kotlin.math.floor(px).toInt(); val y0 = kotlin.math.floor(py).toInt(); val z0 = kotlin.math.floor(pz).toInt()
        val fx = px - x0; val fy = py - y0; val fz = pz - z0
        fun s(t: Double) = t * t * (3 - 2 * t)
        val sx = s(fx); val sy = s(fy); val sz = s(fz)
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        val c00 = l(hash(x0, y0, z0), hash(x0 + 1, y0, z0), sx)
        val c10 = l(hash(x0, y0 + 1, z0), hash(x0 + 1, y0 + 1, z0), sx)
        val c01 = l(hash(x0, y0, z0 + 1), hash(x0 + 1, y0, z0 + 1), sx)
        val c11 = l(hash(x0, y0 + 1, z0 + 1), hash(x0 + 1, y0 + 1, z0 + 1), sx)
        return l(l(c00, c10, sy), l(c01, c11, sy), sz)
    }

    /** Textured scene (like a real room) so image alignment has something to lock on to. */
    private fun texturedScene(x: Double, y: Double, z: Double): Int {
        val n = sqrt(x * x + y * y + z * z)
        val ux = x / n; val uy = y / n; val uz = z / n
        val v = 0.6 * valueNoise(ux * 9, uy * 9, uz * 9) + 0.4 * valueNoise(ux * 25 + 7, uy * 25, uz * 25)
        val g = (30 + 170 * v).toInt().coerceIn(0, 255)
        val r = (g * (0.8 + 0.2 * ux)).toInt().coerceIn(0, 255)
        val b = (g * (0.8 + 0.2 * uz)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun renderTextured(rot: FloatArray, facing: Facing, w: Int, h: Int, f: Double, gain: Double): IntArray {
        val s = if (facing == Facing.BACK) 1.0 else -1.0
        val out = IntArray(w * h)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        for (v in 0 until h) for (u in 0 until w) {
            val dx = s * (u - cx) / f
            val dy = -(v - cy) / f
            val dz = -s
            val p = texturedScene(
                rot[0] * dx + rot[1] * dy + rot[2] * dz,
                rot[3] * dx + rot[4] * dy + rot[5] * dz,
                rot[6] * dx + rot[7] * dy + rot[8] * dz,
            )
            fun c(sh: Int) = (((p shr sh) and 0xFF) * gain).toInt().coerceIn(0, 255)
            out[v * w + u] = (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
        return out
    }

    /** rot * (small rotation about the device-frame axis (ax, ay, az) by |a| radians). */
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

    /** Mean absolute error of covered panorama pixels against the true scene (green channel). */
    private fun meanError(st: PanoramaStitcher, gainOfFirst: Double): Double {
        var sum = 0.0
        var n = 0
        for (j in st.height / 6 until st.height * 5 / 6 step 3) for (i in 0 until st.width step 3) {
            val p = st.pixels[j * st.width + i]
            if (p ushr 24 == 0) continue
            val lon = ((i + 0.5) / st.width - 0.5) * 2 * PI + st.referenceHeading
            val lat = PI / 2 - (j + 0.5) / st.height * PI
            val e = texturedScene(cos(lat) * sin(lon), cos(lat) * cos(lon), sin(lat))
            sum += abs(((p shr 8) and 0xFF) - ((e shr 8) and 0xFF) * gainOfFirst)
            n++
        }
        return sum / n
    }

    private fun noisySweep(align: Boolean): Triple<PanoramaStitcher, Double, List<PanoramaStitcher.FrameResult>> {
        val st = PanoramaStitcher(2048)
        val rnd = java.util.Random(42)
        val results = ArrayList<PanoramaStitcher.FrameResult>()
        var first = true
        var drift = 0.0
        for (pitchDeg in listOf(0, 35, -35)) {
            var hd = 0
            while (hd < 360) {
                val truth = uprightRotation(Math.toRadians(hd.toDouble()), Math.toRadians(pitchDeg.toDouble()))
                // Sensor error: up to +-3 degrees per axis plus slow yaw drift. First frame exact.
                val err = Math.toRadians(3.0)
                drift += Math.toRadians(0.15)
                val sensor = if (first) truth else perturb(
                    truth,
                    (rnd.nextDouble() * 2 - 1) * err,
                    (rnd.nextDouble() * 2 - 1) * err + drift,
                    (rnd.nextDouble() * 2 - 1) * err,
                )
                val gain = if (first) 1.0 else 0.8 + rnd.nextDouble() * 0.4
                val frame = renderTextured(truth, Facing.BACK, w, h, focal, gain)
                val r = st.addFrame(frame, w, h, focal, sensor, Facing.BACK, align = align)
                results.add(r)
                first = false
                hd += 30
            }
        }
        return Triple(st, meanError(st, 1.0), results)
    }

    @Test
    fun imageAlignmentFixesSensorErrors() {
        val (_, rawError, _) = noisySweep(align = false)
        val (st, alignedError, results) = noisySweep(align = true)
        val scores = results.drop(1).map { it.matchScore }
        println("mean error without alignment $rawError, with alignment $alignedError; " +
            "min match score ${scores.minOrNull()}, coverage ${st.coverage}")
        assertTrue("alignment should at least halve the error ($rawError -> $alignedError)", alignedError < rawError * 0.5)
        assertTrue("aligned error too high: $alignedError", alignedError < 6.0)
        assertTrue("every frame should be aligned", scores.all { it > 0.7 })
    }

    @Test
    fun maskedPixelsAreNotStitched() {
        val st = PanoramaStitcher(512)
        // Centre third of the frame masked (transparent), like a person cut out of a selfie.
        val frame = IntArray(w * h) { i -> if (i % w in w / 3 until 2 * w / 3) 0 else 0xFF00FF00.toInt() }
        st.addFrame(frame, w, h, focal, uprightRotation(0.0), Facing.FRONT)
        val row = (st.height / 2) * st.width
        assertEquals(0, st.pixels[row + 1])                       // directly behind = masked
        val side = ((162.0 / 360 + 0.5) * st.width).toInt()
        assertEquals(0xFF00FF00.toInt(), st.pixels[row + side])   // edge of the frame = kept
    }

    @Test
    fun guideTargetsAreSymmetricAndCompleteWithCoverage() {
        val g = CaptureGuide()
        for (t in g.targets) {
            assertTrue(g.targets.any { o -> abs(o.x + t.x) < 1e-9 && abs(o.y + t.y) < 1e-9 && abs(o.z + t.z) < 1e-9 })
        }
        val st = PanoramaStitcher(1024)
        val rot = uprightRotation(0.0)
        st.addFrame(renderTextured(rot, Facing.BACK, w, h, focal, 1.0), w, h, focal, rot, Facing.BACK)
        g.refresh(st)
        val under = g.targetUnder(st.basisFor(rot, Facing.BACK))
        assertEquals(-1, under) // the target straight ahead is now done
        assertEquals(1, g.doneCount)
        // Guidance from there points to a neighbouring target on the same row.
        val next = g.nextTarget(st.basisFor(rot, Facing.BACK))
        val d = g.directionTo(st.basisFor(rot, Facing.BACK), next)
        assertEquals(30.0, abs(d.turnDegrees), 0.5)
        assertEquals(0.0, d.tiltDegrees, 0.5)
    }

    @Test
    fun guideTurnDirectionMatchesImage() {
        val g = CaptureGuide()
        val st = PanoramaStitcher(512)
        val rot = uprightRotation(Math.toRadians(-20.0)) // facing 20 deg left of north
        st.addFrame(IntArray(w * h) { 0xFF808080.toInt() }, w, h, focal, uprightRotation(0.0), Facing.BACK)
        val b = st.basisFor(rot, Facing.BACK)
        val idx = g.targets.indexOfFirst { abs(it.lon) < 1e-9 && it.lat == 0.0 }
        val d = g.directionTo(b, idx)
        assertEquals(20.0, d.turnDegrees, 0.5)           // turn right
        assertTrue(d.imageX!! > 0)                        // target appears right of centre
        assertEquals(0.0, d.arrowAngle, 0.05)             // arrow points right
    }

    @Test
    fun sweepReconstructsTheSceneAndCoversTheSphere() {
        val st = PanoramaStitcher(1024)
        val heading0 = 1.0
        // Dual camera sweep: turn 180 degrees, tilting up/down; both cameras every step.
        for (pitchDeg in listOf(-60, -20, 20, 60)) {
            var hd = 0
            while (hd <= 180) {
                val rot = uprightRotation(heading0 + Math.toRadians(hd.toDouble()), Math.toRadians(pitchDeg.toDouble()))
                for (facing in Facing.values()) {
                    val r = st.addFrame(renderFrame(rot, facing, w, h, focal), w, h, focal, rot, facing)
                    // Smooth gradients give no reliable match, so the sensor orientation must be kept.
                    assertTrue("frame moved ${r.correctionDegrees} deg on a featureless scene", r.correctionDegrees < 0.1)
                }
                hd += 30
            }
        }
        // Straight up and down to close the poles.
        for (pitchDeg in listOf(89.0, -89.0)) {
            val rot = uprightRotation(heading0, Math.toRadians(pitchDeg))
            for (facing in Facing.values()) st.addFrame(renderFrame(rot, facing, w, h, focal), w, h, focal, rot, facing)
        }
        assertTrue("coverage was ${st.coverage}", st.coverage > 0.99)

        // Every covered pixel should match the scene in that direction.
        var worst = 0
        var checked = 0
        for (j in 0 until st.height step 7) for (i in 0 until st.width step 7) {
            val p = st.pixels[j * st.width + i]
            if (p ushr 24 == 0) continue
            val lon = ((i + 0.5) / st.width - 0.5) * 2 * PI + st.referenceHeading
            val lat = PI / 2 - (j + 0.5) / st.height * PI
            val expected = scene(cos(lat) * sin(lon), cos(lat) * cos(lon), sin(lat))
            worst = maxOf(worst, channelDiff(p, expected))
            checked++
        }
        assertTrue(checked > 10000)
        assertTrue("worst channel error $worst", worst <= 12)
    }
}
