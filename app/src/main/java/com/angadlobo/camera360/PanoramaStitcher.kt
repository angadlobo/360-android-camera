package com.angadlobo.camera360

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which physical camera a frame came from. */
enum class Facing { BACK, FRONT }

/**
 * Projects camera frames into an equirectangular (360 x 180 degree) panorama.
 *
 * Coordinate conventions
 * - World frame is Android's sensor world frame: X = east, Y = magnetic north, Z = up.
 * - Device frame is Android's: X = right edge of the screen, Y = top of the screen,
 *   Z = out of the screen toward the user (portrait, natural orientation).
 * - [rotation] is the row-major 3x3 matrix from SensorManager.getRotationMatrixFromVector,
 *   so world = R * device.
 * - Frames must be upright for a portrait-held phone (image top = device +Y) and must
 *   NOT be mirrored (the front camera as the camera itself sees the scene).
 *
 * The back camera looks along device -Z, its image right is device +X.
 * The front camera looks along device +Z, its image right is device -X.
 *
 * Panorama column i maps to longitude (i / W - 0.5) * 2PI relative to [referenceHeading],
 * so the centre column is the direction the back camera faced in the first frame.
 * Row j maps to latitude PI/2 - (j + 0.5) / H * PI.
 *
 * Blending: each panorama pixel keeps the sample that lay closest to the centre of
 * its source frame (seam-selection rather than averaging), which keeps the image
 * sharp and hides lens fall-off at frame edges.
 *
 * Not thread-safe: call from a single thread.
 */
class PanoramaStitcher(val width: Int = 4096) {
    val height = width / 2

    /** ARGB pixels; alpha == 0 means "not yet covered". */
    val pixels = IntArray(width * height)
    private val weights = ByteArray(width * height)

    private val sinLon = DoubleArray(width)
    private val cosLon = DoubleArray(width)
    private val sinLat = DoubleArray(height)
    private val cosLat = DoubleArray(height)
    private val totalArea: Double

    private var coveredArea = 0.0

    /** Absolute compass heading (radians) of the panorama centre, set from the first frame. */
    var referenceHeading: Double = Double.NaN
        private set

    init {
        for (i in 0 until width) {
            val lon = ((i + 0.5) / width - 0.5) * 2 * PI
            sinLon[i] = sin(lon)
            cosLon[i] = cos(lon)
        }
        var area = 0.0
        for (j in 0 until height) {
            val lat = PI / 2 - (j + 0.5) / height * PI
            sinLat[j] = sin(lat)
            cosLat[j] = cos(lat)
            area += cosLat[j] * width
        }
        totalArea = area
    }

    /** Fraction (0..1) of the sphere's surface that has been filled. */
    val coverage: Double get() = coveredArea / totalArea

    fun reset() {
        pixels.fill(0)
        weights.fill(0)
        coveredArea = 0.0
        referenceHeading = Double.NaN
    }

    /** Camera basis vectors (forward, right, down) expressed in the panorama frame. */
    class CameraBasis(
        val fx: Double, val fy: Double, val fz: Double,
        val rx: Double, val ry: Double, val rz: Double,
        val dx: Double, val dy: Double, val dz: Double,
    )

    /**
     * Returns the camera basis in panorama coordinates (world rotated so that the
     * reference heading is longitude 0). Sets the reference heading if needed.
     */
    fun basisFor(rotation: FloatArray, facing: Facing): CameraBasis {
        val s = if (facing == Facing.BACK) 1.0 else -1.0
        // Device-frame vectors: forward = (0,0,-s), right = (s,0,0), down = (0,-1,0)
        // world = R * device  ->  column k of R is the world image of device axis k.
        val fwdW = doubleArrayOf(-s * rotation[2], -s * rotation[5], -s * rotation[8])
        val rightW = doubleArrayOf(s * rotation[0], s * rotation[3], s * rotation[6])
        val downW = doubleArrayOf(-rotation[1].toDouble(), -rotation[4].toDouble(), -rotation[7].toDouble())

        if (referenceHeading.isNaN()) {
            // Heading of the back camera's optical axis. If the phone is pointing
            // straight up/down this is unstable, so fall back to the top edge.
            val bx = -rotation[2].toDouble()
            val by = -rotation[5].toDouble()
            referenceHeading = if (bx * bx + by * by > 0.05) atan2(bx, by)
            else atan2(rotation[1].toDouble(), rotation[4].toDouble())
        }
        val c = cos(referenceHeading)
        val sn = sin(referenceHeading)
        fun rot(v: DoubleArray) = doubleArrayOf(v[0] * c - v[1] * sn, v[1] * c + v[0] * sn, v[2])
        val f = rot(fwdW)
        val r = rot(rightW)
        val d = rot(downW)
        return CameraBasis(f[0], f[1], f[2], r[0], r[1], r[2], d[0], d[1], d[2])
    }

    /** Longitude (relative, radians) and latitude of the direction a camera is facing. */
    fun pointing(rotation: FloatArray, facing: Facing): Pair<Double, Double> {
        val b = basisFor(rotation, facing)
        return atan2(b.fx, b.fy) to atan2(b.fz, sqrt(b.fx * b.fx + b.fy * b.fy))
    }

    /**
     * Projects one frame into the panorama.
     *
     * @param src ARGB pixels, row-major, [srcW] x [srcH], upright and un-mirrored.
     * @param focalPx focal length in pixels at this resolution.
     * @return number of panorama pixels that were updated.
     */
    fun addFrame(
        src: IntArray, srcW: Int, srcH: Int,
        focalPx: Double, rotation: FloatArray, facing: Facing,
    ): Int {
        val b = basisFor(rotation, facing)
        val cx = (srcW - 1) / 2.0
        val cy = (srcH - 1) / 2.0
        val invF = 1.0 / focalPx

        // ---- Footprint of the frame on the sphere (bounding box in lon/lat) ----
        val centreLon = atan2(b.fx, b.fy)
        var lonMin = Double.MAX_VALUE
        var lonMax = -Double.MAX_VALUE
        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        val steps = 24
        for (k in 0 until steps * 4) {
            val t = (k % steps).toDouble() / steps
            val (u, v) = when (k / steps) {
                0 -> t * (srcW - 1) to 0.0
                1 -> (srcW - 1).toDouble() to t * (srcH - 1)
                2 -> (1 - t) * (srcW - 1) to (srcH - 1).toDouble()
                else -> 0.0 to (1 - t) * (srcH - 1)
            }
            val x = (u - cx) * invF
            val y = (v - cy) * invF
            val wx = b.fx + x * b.rx + y * b.dx
            val wy = b.fy + x * b.ry + y * b.dy
            val wz = b.fz + x * b.rz + y * b.dz
            val lat = atan2(wz, sqrt(wx * wx + wy * wy))
            var lon = atan2(wx, wy)
            while (lon - centreLon > PI) lon -= 2 * PI
            while (lon - centreLon < -PI) lon += 2 * PI
            if (lon < lonMin) lonMin = lon
            if (lon > lonMax) lonMax = lon
            if (lat < latMin) latMin = lat
            if (lat > latMax) latMax = lat
        }
        // Does the frame contain the zenith or nadir? If so it wraps all longitudes.
        val halfW = (srcW - 1) / 2.0 * invF
        val halfH = (srcH - 1) / 2.0 * invF
        fun containsPole(sign: Double): Boolean {
            val z = sign * b.fz
            if (z <= 0) return false
            val x = sign * b.rz / z
            val y = sign * b.dz / z
            return abs(x) <= halfW && abs(y) <= halfH
        }
        if (containsPole(1.0)) {
            latMax = PI / 2; lonMin = centreLon - PI; lonMax = centreLon + PI
        }
        if (containsPole(-1.0)) {
            latMin = -PI / 2; lonMin = centreLon - PI; lonMax = centreLon + PI
        }

        val jStart = floor((PI / 2 - latMax) / PI * height - 1).toInt().coerceIn(0, height - 1)
        val jEnd = ceil((PI / 2 - latMin) / PI * height + 1).toInt().coerceIn(0, height - 1)
        var iStart = floor((lonMin / (2 * PI) + 0.5) * width - 1).toInt()
        var iEnd = ceil((lonMax / (2 * PI) + 0.5) * width + 1).toInt()
        if (iEnd - iStart >= width) {
            iStart = 0; iEnd = width - 1
        }

        val maxU = srcW - 1.0
        val maxV = srcH - 1.0
        val weightNorm = 254.0 / (min(srcW, srcH) / 2.0)
        var updated = 0

        for (j in jStart..jEnd) {
            val sl = sinLat[j]
            val cl = cosLat[j]
            val row = j * width
            for (ii in iStart..iEnd) {
                val i = Math.floorMod(ii, width)
                val wx = cl * sinLon[i]
                val wy = cl * cosLon[i]
                val wz = sl
                val z = wx * b.fx + wy * b.fy + wz * b.fz
                if (z <= 1e-6) continue
                val iz = 1.0 / z
                val u = cx + (wx * b.rx + wy * b.ry + wz * b.rz) * iz * focalPx
                if (u < 0 || u > maxU) continue
                val v = cy + (wx * b.dx + wy * b.dy + wz * b.dz) * iz * focalPx
                if (v < 0 || v > maxV) continue

                val edge = minOf(minOf(u, maxU - u), minOf(v, maxV - v))
                val w = (edge * weightNorm).toInt().coerceIn(1, 255)
                val idx = row + i
                val old = weights[idx].toInt() and 0xFF
                if (w <= old) continue
                if (old == 0) coveredArea += cl
                weights[idx] = w.toByte()
                pixels[idx] = bilinear(src, srcW, srcH, u, v)
                updated++
            }
        }
        return updated
    }

    private fun bilinear(src: IntArray, w: Int, h: Int, u: Double, v: Double): Int {
        val x0 = u.toInt().coerceIn(0, w - 1)
        val y0 = v.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = u - x0
        val ay = v - y0
        val p00 = src[y0 * w + x0]
        val p10 = src[y0 * w + x1]
        val p01 = src[y1 * w + x0]
        val p11 = src[y1 * w + x1]
        fun ch(shift: Int): Int {
            val c00 = (p00 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c10 - c00) * ax
            val bot = c01 + (c11 - c01) * ax
            return (top + (bot - top) * ay + 0.5).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Downsampled copy for the live preview. Uncovered pixels get [emptyColor]
     * (with a faint grid every 30 degrees so the user can see where the gaps are).
     */
    fun renderPreview(dstW: Int, emptyColor: Int = 0xFF1A1A1A.toInt()): IntArray {
        val dstH = dstW / 2
        val out = IntArray(dstW * dstH)
        val scale = width / dstW
        for (y in 0 until dstH) {
            val sy = y * scale + scale / 2
            for (x in 0 until dstW) {
                val p = pixels[sy * width + x * scale + scale / 2]
                out[y * dstW + x] = if (p ushr 24 != 0) p else {
                    val grid = (x * 12) % dstW < 12 || (y * 6) % dstH < 6
                    if (grid) 0xFF333333.toInt() else emptyColor
                }
            }
        }
        return out
    }

    /** Full-resolution output with uncovered areas filled with [emptyColor]. */
    fun renderFinal(emptyColor: Int = 0xFF101010.toInt()): IntArray {
        val out = pixels.copyOf()
        for (k in out.indices) if (out[k] ushr 24 == 0) out[k] = emptyColor
        return out
    }
}
