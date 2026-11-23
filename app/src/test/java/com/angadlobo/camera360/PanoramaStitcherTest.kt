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
                    st.addFrame(renderFrame(rot, facing, w, h, focal), w, h, focal, rot, facing)
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
