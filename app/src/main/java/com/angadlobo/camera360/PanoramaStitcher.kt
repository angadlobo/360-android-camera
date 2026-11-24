package com.angadlobo.camera360

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which physical camera a frame came from. */
enum class Facing { BACK, FRONT }

/**
 * Projects camera frames into an equirectangular (360 x 180 degree) panorama.
 *
 * Coordinate conventions
 * - World frame is Android's sensor world frame: X = east, Y = north, Z = up.
 * - Device frame is Android's: X = right edge of the screen, Y = top of the screen,
 *   Z = out of the screen toward the user (portrait, natural orientation).
 * - Rotation matrices are row-major 3x3 from SensorManager.getRotationMatrixFromVector,
 *   so world = R * device.
 * - Frames must be upright for a portrait-held phone (image top = device +Y) and must
 *   NOT be mirrored (the front camera as the camera itself sees the scene).
 *
 * The back camera looks along device -Z, its image right is device +X.
 * The front camera looks along device +Z, its image right is device -X.
 *
 * Panorama column i maps to longitude (i / W - 0.5) * 2PI relative to [referenceHeading],
 * so the centre column is the direction the back camera faced in the first frame.
 *
 * Alignment: the motion sensor only gives orientation to within a degree or few, which
 * shows as broken seams. Every new frame is therefore refined against what is already
 * in the panorama by a coarse-to-fine search over small rotations, maximising the
 * zero-mean normalised cross-correlation (robust to exposure differences). The
 * resulting sensor-to-image correction is remembered per camera, which also cancels
 * gyro drift and small camera mounting offsets for the following frames.
 *
 * Blending: each panorama pixel keeps the sample that lay closest to the centre of
 * its source frame, after the frame's brightness has been matched to the panorama.
 * The back camera is preferred over the front camera, whose view usually contains
 * the person holding the phone.
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

    /** Low-resolution grey panoramas used for alignment (coarse to fine). */
    private class GrayLevel(val w: Int) {
        val h = w / 2
        val gray = FloatArray(w * h)
        val mask = BooleanArray(w * h)
    }
    private val levels = listOf(GrayLevel(width / 8), GrayLevel(width / 4), GrayLevel(width / 2))

    /** Per-camera correction (sensor -> image-aligned), as a row-major 3x3 rotation. */
    @Volatile private var correction: Map<Facing, DoubleArray> = emptyMap()

    /**
     * Sum over frames of the gravity sensor's "up" direction, expressed in the panorama frame
     * through each frame's image-aligned orientation. Its average is a precise estimate of
     * true vertical, used to level the horizon of the final image.
     */
    private val upSum = DoubleArray(3)

    /** Absolute compass heading (radians) of the panorama centre, set from the first frame. */
    @Volatile var referenceHeading: Double = Double.NaN
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
        for (l in levels) { l.gray.fill(0f); l.mask.fill(false) }
        correction = emptyMap()
        upSum.fill(0.0)
        coveredArea = 0.0
        referenceHeading = Double.NaN
    }

    /** Camera orientation in the panorama frame: forward, right and down unit vectors. */
    class CameraBasis(
        val fx: Double, val fy: Double, val fz: Double,
        val rx: Double, val ry: Double, val rz: Double,
        val dx: Double, val dy: Double, val dz: Double,
    ) {
        fun angleTo(o: CameraBasis): Double {
            // Rotation angle between two orthonormal frames: trace of A^T B = 1 + 2cos(theta)
            val tr = fx * o.fx + fy * o.fy + fz * o.fz + rx * o.rx + ry * o.ry + rz * o.rz +
                dx * o.dx + dy * o.dy + dz * o.dz
            return kotlin.math.acos(((tr - 1) / 2).coerceIn(-1.0, 1.0))
        }
    }

    /** Result of adding a frame. */
    data class FrameResult(
        val updatedPixels: Int,
        /** How far (degrees) image alignment moved the frame from the sensor estimate. */
        val correctionDegrees: Double,
        /** Alignment match quality (-1..1), NaN if the frame was not aligned. */
        val matchScore: Double,
        val gain: Double,
        /** Final camera orientation used for this frame. */
        val basis: CameraBasis,
    )

    /**
     * Camera basis straight from the motion sensor (no image correction).
     * Sets the reference heading on first use.
     */
    fun basisFor(rotation: FloatArray, facing: Facing): CameraBasis {
        val s = if (facing == Facing.BACK) 1.0 else -1.0
        // Column k of R is the world image of device axis k.
        val fwdW = doubleArrayOf(-s * rotation[2], -s * rotation[5], -s * rotation[8])
        val rightW = doubleArrayOf(s * rotation[0], s * rotation[3], s * rotation[6])
        val downW = doubleArrayOf(-rotation[1].toDouble(), -rotation[4].toDouble(), -rotation[7].toDouble())

        if (referenceHeading.isNaN()) {
            val bx = -rotation[2].toDouble()
            val by = -rotation[5].toDouble()
            referenceHeading = if (bx * bx + by * by > 0.05) kotlin.math.atan2(bx, by)
            else kotlin.math.atan2(rotation[1].toDouble(), rotation[4].toDouble())
        }
        val c = cos(referenceHeading)
        val sn = sin(referenceHeading)
        fun rot(v: DoubleArray) = doubleArrayOf(v[0] * c - v[1] * sn, v[1] * c + v[0] * sn, v[2])
        val f = rot(fwdW)
        val r = rot(rightW)
        val d = rot(downW)
        return CameraBasis(f[0], f[1], f[2], r[0], r[1], r[2], d[0], d[1], d[2])
    }

    /**
     * Same as [correctedBasis] but never sets the reference heading, so it is safe to call
     * from the UI thread. Returns null until the first frame has been added.
     */
    fun basisOrNull(rotation: FloatArray, facing: Facing): CameraBasis? {
        if (referenceHeading.isNaN()) return null
        return correctedBasis(rotation, facing)
    }

    /** Sensor basis with the learned per-camera correction applied (best initial guess). */
    fun correctedBasis(rotation: FloatArray, facing: Facing): CameraBasis {
        val b = basisFor(rotation, facing)
        val c = correction[facing] ?: return b
        return applyRotation(c, b)
    }

    /** Longitude (relative, radians) and latitude of the direction a camera is facing. */
    fun pointing(rotation: FloatArray, facing: Facing): Pair<Double, Double> {
        val b = correctedBasis(rotation, facing)
        return kotlin.math.atan2(b.fx, b.fy) to kotlin.math.atan2(b.fz, sqrt(b.fx * b.fx + b.fy * b.fy))
    }

    /**
     * Aligns one frame to the panorama and projects it in.
     *
     * @param src ARGB pixels, row-major, [srcW] x [srcH], upright and un-mirrored.
     * @param focalPx focal length in pixels at this resolution.
     * @param align refine the sensor orientation against the existing panorama.
     */
    fun addFrame(
        src: IntArray, srcW: Int, srcH: Int,
        focalPx: Double, rotation: FloatArray, facing: Facing,
        align: Boolean = true,
    ): FrameResult {
        val sensorBasis = basisFor(rotation, facing)
        var basis = correctedBasis(rotation, facing)
        var score = Double.NaN
        var gain = 1.0

        if (coveredArea > 0) {
            val gray = luma(src)
            val integral = integralImage(gray, srcW, srcH)
            val holes = IntArray(src.size) { if (src[it] ushr 24 == 0) 1 else 0 }
            holeIntegral = if (holes.any { it != 0 }) integralImage(holes, srcW, srcH) else null
            if (align) {
                val a = align(integral, srcW, srcH, focalPx, basis)
                if (a != null) {
                    score = a.second
                    if (score >= MIN_ACCEPT_SCORE) {
                        basis = a.first
                        if (score >= MIN_LEARN_SCORE) correction = correction + (facing to correctionMatrix(sensorBasis, basis))
                    }
                }
            }
            gain = estimateGain(integral, srcW, srcH, focalPx, basis)
        }

        accumulateUp(rotation, facing, basis)
        val weightScale = if (facing == Facing.BACK) 1.0 else FRONT_WEIGHT
        val updated = project(src, srcW, srcH, focalPx, basis, gain, weightScale)
        return FrameResult(updated, Math.toDegrees(sensorBasis.angleTo(basis)), score, gain, basis)
    }

    // ------------------------------------------------------------------ Horizon levelling

    private fun accumulateUp(rotation: FloatArray, facing: Facing, b: CameraBasis) {
        // World up in device coordinates is row 2 of R (device = R^T * world).
        val ux = rotation[6].toDouble(); val uy = rotation[7].toDouble(); val uz = rotation[8].toDouble()
        val s = if (facing == Facing.BACK) 1.0 else -1.0
        // Components along the camera's right (s*X), down (-Y) and forward (-s*Z) axes.
        val cr = s * ux
        val cd = -uy
        val cf = -s * uz
        upSum[0] += cr * b.rx + cd * b.dx + cf * b.fx
        upSum[1] += cr * b.ry + cd * b.dy + cf * b.fy
        upSum[2] += cr * b.rz + cd * b.dz + cf * b.fz
    }

    /** Angle (degrees) by which the stitched panorama's vertical differs from true vertical. */
    val tiltDegrees: Double get() {
        val n = sqrt(upSum[0] * upSum[0] + upSum[1] * upSum[1] + upSum[2] * upSum[2])
        if (n < 1e-9) return 0.0
        return Math.toDegrees(kotlin.math.acos((upSum[2] / n).coerceIn(-1.0, 1.0)))
    }

    /** Rotation that takes the measured "up" onto the panorama's +Z axis, or null if level. */
    private fun levellingRotation(): DoubleArray? {
        val n = sqrt(upSum[0] * upSum[0] + upSum[1] * upSum[1] + upSum[2] * upSum[2])
        if (n < 1e-9) return null
        val x = upSum[0] / n; val y = upSum[1] / n; val z = upSum[2] / n
        val angle = kotlin.math.acos(z.coerceIn(-1.0, 1.0))
        if (angle < Math.toRadians(0.03) || angle > Math.toRadians(20.0)) return null
        // Axis = up x Z
        val ax = y; val ay = -x
        val an = sqrt(ax * ax + ay * ay)
        return axisAngle(ax / an, ay / an, 0.0, angle)
    }

    // ------------------------------------------------------------------ Projection

    private fun project(
        src: IntArray, srcW: Int, srcH: Int, focalPx: Double,
        b: CameraBasis, gain: Double, weightScale: Double,
    ): Int {
        val fp = footprint(srcW, srcH, focalPx, b)
        val cx = (srcW - 1) / 2.0
        val cy = (srcH - 1) / 2.0
        val maxU = srcW - 1.0
        val maxV = srcH - 1.0
        val weightNorm = 254.0 * weightScale / (min(srcW, srcH) / 2.0)
        val gainFixed = (gain * 256).toInt()
        var updated = 0

        for (j in fp.jStart..fp.jEnd) {
            val sl = sinLat[j]
            val cl = cosLat[j]
            val row = j * width
            for (ii in fp.iStart..fp.iEnd) {
                val i = Math.floorMod(ii, width)
                val wx = cl * sinLon[i]
                val wy = cl * cosLon[i]
                val z = wx * b.fx + wy * b.fy + sl * b.fz
                if (z <= 1e-6) continue
                val iz = focalPx / z
                val u = cx + (wx * b.rx + wy * b.ry + sl * b.rz) * iz
                if (u < 0 || u > maxU) continue
                val v = cy + (wx * b.dx + wy * b.dy + sl * b.dz) * iz
                if (v < 0 || v > maxV) continue

                val edge = minOf(minOf(u, maxU - u), minOf(v, maxV - v))
                val w = (edge * weightNorm).toInt().coerceIn(1, 255)
                val idx = row + i
                val old = weights[idx].toInt() and 0xFF
                if (w <= old) continue
                val sample = bilinear(src, srcW, srcH, u, v)
                if (sample == 0) continue // masked-out source pixel
                if (old == 0) coveredArea += cl
                weights[idx] = w.toByte()
                pixels[idx] = applyGain(sample, gainFixed)
                updated++
            }
        }
        updateGrayLevels(fp)
        return updated
    }

    private class Footprint(val jStart: Int, val jEnd: Int, val iStart: Int, val iEnd: Int)

    /** Bounding box (in full-res panorama pixels) of a frame's footprint on the sphere. */
    private fun footprint(srcW: Int, srcH: Int, focalPx: Double, b: CameraBasis): Footprint {
        val cx = (srcW - 1) / 2.0
        val cy = (srcH - 1) / 2.0
        val invF = 1.0 / focalPx
        val centreLon = kotlin.math.atan2(b.fx, b.fy)
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
            val lat = kotlin.math.atan2(wz, sqrt(wx * wx + wy * wy))
            var lon = kotlin.math.atan2(wx, wy)
            while (lon - centreLon > PI) lon -= 2 * PI
            while (lon - centreLon < -PI) lon += 2 * PI
            lonMin = min(lonMin, lon); lonMax = max(lonMax, lon)
            latMin = min(latMin, lat); latMax = max(latMax, lat)
        }
        val halfW = (srcW - 1) / 2.0 * invF
        val halfH = (srcH - 1) / 2.0 * invF
        fun containsPole(sign: Double): Boolean {
            val z = sign * b.fz
            if (z <= 0) return false
            return abs(sign * b.rz / z) <= halfW && abs(sign * b.dz / z) <= halfH
        }
        if (containsPole(1.0)) { latMax = PI / 2; lonMin = centreLon - PI; lonMax = centreLon + PI }
        if (containsPole(-1.0)) { latMin = -PI / 2; lonMin = centreLon - PI; lonMax = centreLon + PI }

        // Pad by a few pixels so the grey pyramid blocks on the boundary get refreshed too.
        val pad = width / levels.first().w + 1
        val jStart = floor((PI / 2 - latMax) / PI * height - pad).toInt().coerceIn(0, height - 1)
        val jEnd = ceil((PI / 2 - latMin) / PI * height + pad).toInt().coerceIn(0, height - 1)
        var iStart = floor((lonMin / (2 * PI) + 0.5) * width - pad).toInt()
        var iEnd = ceil((lonMax / (2 * PI) + 0.5) * width + pad).toInt()
        if (iEnd - iStart >= width) { iStart = 0; iEnd = width - 1 }
        return Footprint(jStart, jEnd, iStart, iEnd)
    }

    /** Recomputes the grey alignment pyramid inside a footprint from the full-res panorama. */
    private fun updateGrayLevels(fp: Footprint) {
        for (l in levels) {
            val k = width / l.w
            val j0 = fp.jStart / k
            val j1 = fp.jEnd / k
            val i0 = Math.floorDiv(fp.iStart, k)
            val i1 = Math.floorDiv(fp.iEnd, k)
            val full = i1 - i0 + 1 >= l.w
            val half = k * k / 2
            for (lj in j0..j1) {
                for (li0 in (if (full) 0 else i0)..(if (full) l.w - 1 else i1)) {
                    val li = Math.floorMod(li0, l.w)
                    var sum = 0
                    var n = 0
                    for (dj in 0 until k) {
                        val row = (lj * k + dj) * width + li * k
                        for (di in 0 until k) {
                            val p = pixels[row + di]
                            if (p ushr 24 != 0) {
                                sum += (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                                n++
                            }
                        }
                    }
                    val idx = lj * l.w + li
                    if (n > half) {
                        l.gray[idx] = sum.toFloat() / n
                        l.mask[idx] = true
                    } else {
                        l.mask[idx] = false
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Alignment

    /** Samples of the new frame at one pyramid level: normalised camera coords + grey value. */
    private class Samples(val x: DoubleArray, val y: DoubleArray, val v: DoubleArray) {
        val n get() = v.size
    }

    /** Integral image of masked-out (transparent) source pixels for the current frame, if any. */
    private var holeIntegral: IntArray? = null

    private fun samplesFor(integral: IntArray, srcW: Int, srcH: Int, focalPx: Double, level: GrayLevel, maxSamples: Int): Samples {
        val fLevel = level.w / (2 * PI)
        val box = max(1.0, focalPx / fLevel)               // source pixels per level pixel
        var spacing = box * 1.5
        val usableW = srcW - box
        val usableH = srcH - box
        val est = (usableW / spacing) * (usableH / spacing)
        if (est > maxSamples) spacing *= sqrt(est / maxSamples)
        val nx = max(2, (usableW / spacing).toInt())
        val ny = max(2, (usableH / spacing).toInt())
        var xs = DoubleArray(nx * ny)
        var ys = DoubleArray(nx * ny)
        var vs = DoubleArray(nx * ny)
        val holesII = holeIntegral
        val cx = (srcW - 1) / 2.0
        val cy = (srcH - 1) / 2.0
        val half = box / 2
        var k = 0
        for (iy in 0 until ny) {
            val v = half + (iy + 0.5) * (usableH / ny)
            for (ix in 0 until nx) {
                val u = half + (ix + 0.5) * (usableW / nx)
                // Skip samples touching masked-out pixels (e.g. the person in a selfie frame).
                if (holesII != null && boxMean(holesII, srcW, srcH, u, v, half + 1) > 0) continue
                xs[k] = (u - cx) / focalPx
                ys[k] = (v - cy) / focalPx
                vs[k] = boxMean(integral, srcW, srcH, u, v, half)
                k++
            }
        }
        if (k < xs.size) { xs = xs.copyOf(k); ys = ys.copyOf(k); vs = vs.copyOf(k) }
        return Samples(xs, ys, vs)
    }

    /**
     * Coarse-to-fine search for the small rotation that best matches the frame to the
     * panorama. Returns the refined basis and its match score, or null if there is not
     * enough overlap to judge.
     */
    private fun align(integral: IntArray, srcW: Int, srcH: Int, focalPx: Double, start: CameraBasis): Pair<CameraBasis, Double>? {
        var a = 0.0 // yaw (about camera down axis)
        var b = 0.0 // pitch (about camera right axis)
        var c = 0.0 // roll (about camera forward axis)
        var bestScore = Double.NaN

        for ((li, level) in levels.withIndex()) {
            val samples = samplesFor(integral, srcW, srcH, focalPx, level, if (li == 0) 1500 else 3500)
            val unit = 2 * PI / level.w
            val (range, step) = if (li == 0) 8 to 1.0 else 2 to 0.5
            val minOverlap = max(30, (samples.n * MIN_OVERLAP_FRACTION).toInt())
            fun eval(da: Double, db: Double, dc: Double): Double {
                val s = score(samples, level, rotate(start, da, db, dc), minOverlap)
                if (li > 0) return s // the prior only breaks ties at the coarse level; finer levels must be unbiased
                val deg = Math.toDegrees(sqrt(da * da + db * db + dc * dc))
                return s - PRIOR_PER_DEG2 * deg * deg
            }

            var best = eval(a, b, c)
            var ba = a; var bb = b
            val n = (range / step).toInt()
            for (p in -n..n) for (q in -n..n) {
                if (p == 0 && q == 0) continue
                val ta = a + p * step * unit
                val tb = b + q * step * unit
                val s = eval(ta, tb, c)
                if (s > best) { best = s; ba = ta; bb = tb }
            }
            a = ba; b = bb
            if (li == 0) {
                // Reject ambiguous matches (blank walls, sky, smooth gradients): the score must
                // drop clearly when the frame is moved a few degrees away from the best position.
                // Uses the raw score: the small-correction prior must not make a flat score look peaked.
                val off = Math.toRadians(PEAK_TEST_DEGREES)
                fun raw(da: Double, db: Double) = score(samples, level, rotate(start, da, db, c), minOverlap)
                val centre = raw(a, b)
                val neighbours = listOf(raw(a + off, b), raw(a - off, b), raw(a, b + off), raw(a, b - off))
                if (centre - neighbours.max() < MIN_PEAK_DROP) return null
            }

            // Roll moves the frame corners by roughly roll * halfDiagonal, so search it in
            // steps that move the corners about one level pixel.
            val halfDiag = sqrt((srcW * srcW + srcH * srcH).toDouble()) / 2 / focalPx
            val rollUnit = unit / halfDiag
            var bc = c
            val rollN = if (li == 0) 4 else 2
            for (r in -rollN..rollN) {
                if (r == 0) continue
                val tc = c + r * step * rollUnit
                val s = eval(a, b, tc)
                if (s > best) { best = s; bc = tc }
            }
            c = bc
            if (best.isNaN() || best == Double.NEGATIVE_INFINITY) return null
            bestScore = best
        }
        return rotate(start, a, b, c) to bestScore
    }

    /** Zero-mean normalised cross-correlation between the frame samples and the panorama. */
    private fun score(s: Samples, l: GrayLevel, b: CameraBasis, minOverlap: Int): Double {
        var n = 0
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        val w = l.w
        val h = l.h
        val lonScale = w / (2 * PI)
        val latScale = h / PI
        for (k in 0 until s.n) {
            val x = s.x[k]
            val y = s.y[k]
            val dx = b.fx + x * b.rx + y * b.dx
            val dy = b.fy + x * b.ry + y * b.dy
            val dz = b.fz + x * b.rz + y * b.dz
            val lon = fastAtan2(dx, dy)
            val lat = fastAtan2(dz, sqrt(dx * dx + dy * dy))
            val px = (lon * lonScale + w / 2.0) - 0.5
            val py = (PI / 2 - lat) * latScale - 0.5
            if (py < 0 || py > h - 1) continue
            val x0f = floor(px)
            val y0 = py.toInt()
            val ax = px - x0f
            val ay = py - y0
            val x0 = Math.floorMod(x0f.toInt(), w)
            val x1 = if (x0 + 1 == w) 0 else x0 + 1
            val y1 = min(y0 + 1, h - 1)
            val i00 = y0 * w + x0; val i10 = y0 * w + x1
            val i01 = y1 * w + x0; val i11 = y1 * w + x1
            if (!(l.mask[i00] && l.mask[i10] && l.mask[i01] && l.mask[i11])) continue
            val top = l.gray[i00] + (l.gray[i10] - l.gray[i00]) * ax
            val bot = l.gray[i01] + (l.gray[i11] - l.gray[i01]) * ax
            val pv = top + (bot - top) * ay
            val fv = s.v[k]
            n++
            sa += fv; sb += pv; saa += fv * fv; sbb += pv * pv; sab += fv * pv
        }
        if (n < minOverlap) return Double.NEGATIVE_INFINITY
        val va = saa - sa * sa / n
        val vb = sbb - sb * sb / n
        if (va < n * 4.0 || vb < n * 4.0) return Double.NEGATIVE_INFINITY // featureless (std < 2 grey levels)
        return (sab - sa * sb / n) / sqrt(va * vb)
    }

    /** Brightness ratio panorama / frame over their overlap at the finest pyramid level. */
    private fun estimateGain(integral: IntArray, srcW: Int, srcH: Int, focalPx: Double, b: CameraBasis): Double {
        val l = levels.last()
        val s = samplesFor(integral, srcW, srcH, focalPx, l, 3000)
        var sf = 0.0
        var sp = 0.0
        var n = 0
        for (k in 0 until s.n) {
            val dx = b.fx + s.x[k] * b.rx + s.y[k] * b.dx
            val dy = b.fy + s.x[k] * b.ry + s.y[k] * b.dy
            val dz = b.fz + s.x[k] * b.rz + s.y[k] * b.dz
            val lon = fastAtan2(dx, dy)
            val lat = fastAtan2(dz, sqrt(dx * dx + dy * dy))
            val px = Math.floorMod((lon / (2 * PI) * l.w + l.w / 2.0).toInt(), l.w)
            val py = ((PI / 2 - lat) / PI * l.h).toInt().coerceIn(0, l.h - 1)
            val idx = py * l.w + px
            if (!l.mask[idx]) continue
            sf += s.v[k]; sp += l.gray[idx]; n++
        }
        if (n < 50 || sf < 1) return 1.0
        return (sp / sf).coerceIn(0.5, 2.0)
    }

    // ------------------------------------------------------------------ Rotation helpers

    /** Rotates a basis by small angles about its own down (a), right (b) and forward (c) axes. */
    private fun rotate(s: CameraBasis, a: Double, b: Double, c: Double): CameraBasis {
        val ax = a * s.dx + b * s.rx + c * s.fx
        val ay = a * s.dy + b * s.ry + c * s.fy
        val az = a * s.dz + b * s.rz + c * s.fz
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle < 1e-12) return s
        val m = axisAngle(ax / angle, ay / angle, az / angle, angle)
        return applyRotation(m, s)
    }

    private fun axisAngle(x: Double, y: Double, z: Double, t: Double): DoubleArray {
        val c = cos(t); val s = sin(t); val k = 1 - c
        return doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k,
        )
    }

    private fun applyRotation(m: DoubleArray, s: CameraBasis): CameraBasis {
        fun mx(x: Double, y: Double, z: Double) = m[0] * x + m[1] * y + m[2] * z
        fun my(x: Double, y: Double, z: Double) = m[3] * x + m[4] * y + m[5] * z
        fun mz(x: Double, y: Double, z: Double) = m[6] * x + m[7] * y + m[8] * z
        return CameraBasis(
            mx(s.fx, s.fy, s.fz), my(s.fx, s.fy, s.fz), mz(s.fx, s.fy, s.fz),
            mx(s.rx, s.ry, s.rz), my(s.rx, s.ry, s.rz), mz(s.rx, s.ry, s.rz),
            mx(s.dx, s.dy, s.dz), my(s.dx, s.dy, s.dz), mz(s.dx, s.dy, s.dz),
        )
    }

    /** Rotation C with C * from = to, where each basis is the matrix with columns (f, r, d). */
    private fun correctionMatrix(from: CameraBasis, to: CameraBasis): DoubleArray {
        val a = doubleArrayOf(from.fx, from.rx, from.dx, from.fy, from.ry, from.dy, from.fz, from.rz, from.dz)
        val b = doubleArrayOf(to.fx, to.rx, to.dx, to.fy, to.ry, to.dy, to.fz, to.rz, to.dz)
        // C = B * A^T
        val c = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            c[i * 3 + j] = b[i * 3] * a[j * 3] + b[i * 3 + 1] * a[j * 3 + 1] + b[i * 3 + 2] * a[j * 3 + 2]
        }
        return c
    }

    // ------------------------------------------------------------------ Pixel helpers

    private fun luma(src: IntArray) = IntArray(src.size) { k ->
        val p = src[k]
        (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
    }

    private fun integralImage(g: IntArray, w: Int, h: Int): IntArray {
        val ii = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            val src = y * w
            val dst = (y + 1) * (w + 1)
            val prev = y * (w + 1)
            for (x in 0 until w) {
                rowSum += g[src + x]
                ii[dst + x + 1] = ii[prev + x + 1] + rowSum
            }
        }
        return ii
    }

    /**
     * Mean over the box centred on pixel-centre coordinates (u, v) with half-size [half].
     * Pixel k spans the edge interval [k, k+1), so its centre is at k + 0.5 in edge
     * coordinates; rounding (not floor/ceil) keeps the box centred and unbiased.
     */
    private fun boxMean(ii: IntArray, w: Int, h: Int, u: Double, v: Double, half: Double): Double {
        val a = Math.round(u + 0.5 - half).toInt().coerceIn(0, w - 1)
        val b = Math.round(v + 0.5 - half).toInt().coerceIn(0, h - 1)
        val c = max(a + 1, Math.round(u + 0.5 + half).toInt().coerceIn(1, w))
        val d = max(b + 1, Math.round(v + 0.5 + half).toInt().coerceIn(1, h))
        val s = w + 1
        val sum = ii[d * s + c] - ii[b * s + c] - ii[d * s + a] + ii[b * s + a]
        return sum.toDouble() / ((c - a) * (d - b))
    }

    private fun applyGain(p: Int, g: Int): Int {
        if (g == 256) return p
        val r = min(255, (((p shr 16) and 0xFF) * g) shr 8)
        val gg = min(255, (((p shr 8) and 0xFF) * g) shr 8)
        val b = min(255, ((p and 0xFF) * g) shr 8)
        return (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
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
        if ((p00 ushr 24) == 0 || (p10 ushr 24) == 0 || (p01 ushr 24) == 0 || (p11 ushr 24) == 0) return 0
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
     * Fraction (0..1) of a circular patch of the sphere, of angular [radius] around the
     * direction ([lon], [lat]) in panorama coordinates, that is already covered.
     */
    fun coverageAround(lon: Double, lat: Double, radius: Double): Double {
        val cl = cos(lat); val sl = sin(lat)
        val cx = cl * sin(lon); val cy = cl * cos(lon); val cz = sl
        // Orthonormal tangent vectors at the centre.
        val ex = cos(lon); val ey = -sin(lon); val ez = 0.0          // east
        val nx = -sl * sin(lon); val ny = -sl * cos(lon); val nz = cl // north
        var total = 0
        var hit = 0
        for (ring in 0..4) {
            val r = radius * ring / 4
            val count = if (ring == 0) 1 else ring * 8
            for (k in 0 until count) {
                val a = 2 * PI * k / count
                val ta = cos(a) * sin(r); val tb = sin(a) * sin(r); val cr = cos(r)
                val dx = cx * cr + ex * ta + nx * tb
                val dy = cy * cr + ey * ta + ny * tb
                val dz = cz * cr + ez * ta + nz * tb
                val pl = kotlin.math.atan2(dx, dy)
                val pt = kotlin.math.atan2(dz, sqrt(dx * dx + dy * dy))
                val i = ((pl / (2 * PI) + 0.5) * width).toInt().coerceIn(0, width - 1)
                val j = ((0.5 - pt / PI) * height).toInt().coerceIn(0, height - 1)
                total++
                if (weights[j * width + i].toInt() != 0) hit++
            }
        }
        return hit.toDouble() / total
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

    /**
     * Full-resolution output with the horizon levelled (using the averaged gravity sensor)
     * and uncovered areas filled with [emptyColor].
     */
    fun renderFinal(emptyColor: Int = 0xFF101010.toInt()): IntArray {
        val l = levellingRotation()
        if (l == null) {
            val out = pixels.copyOf()
            for (k in out.indices) if (out[k] ushr 24 == 0) out[k] = emptyColor
            return out
        }
        // Each output direction o comes from source direction L^T o.
        val out = IntArray(width * height)
        for (j in 0 until height) {
            val sl = sinLat[j]; val cl = cosLat[j]
            for (i in 0 until width) {
                val ox = cl * sinLon[i]; val oy = cl * cosLon[i]; val oz = sl
                val sx = l[0] * ox + l[3] * oy + l[6] * oz
                val sy = l[1] * ox + l[4] * oy + l[7] * oz
                val sz = l[2] * ox + l[5] * oy + l[8] * oz
                val lon = fastAtan2(sx, sy)
                val lat = fastAtan2(sz, sqrt(sx * sx + sy * sy))
                val p = samplePanorama((lon / (2 * PI) + 0.5) * width - 0.5, (0.5 - lat / PI) * height - 0.5)
                out[j * width + i] = if (p == 0) emptyColor else p
            }
        }
        return out
    }

    /** Bilinear sample of the panorama at continuous pixel-centre coordinates (wrapping in x). */
    private fun samplePanorama(px: Double, py: Double): Int {
        val x0f = floor(px)
        val y0 = floor(py).toInt().coerceIn(0, height - 1)
        val ax = px - x0f
        val ay = (py - y0).coerceIn(0.0, 1.0)
        val x0 = Math.floorMod(x0f.toInt(), width)
        val x1 = if (x0 + 1 == width) 0 else x0 + 1
        val y1 = min(y0 + 1, height - 1)
        val p00 = pixels[y0 * width + x0]; val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]; val p11 = pixels[y1 * width + x1]
        if ((p00 ushr 24) == 0 || (p10 ushr 24) == 0 || (p01 ushr 24) == 0 || (p11 ushr 24) == 0) {
            // At the edge of the covered area: nearest neighbour.
            val nx = if (ax < 0.5) x0 else x1
            val ny = if (ay < 0.5) y0 else y1
            return pixels[ny * width + nx]
        }
        fun ch(shift: Int): Int {
            val c00 = (p00 shr shift) and 0xFF; val c10 = (p10 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF; val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c10 - c00) * ax
            val bot = c01 + (c11 - c01) * ax
            return (top + (bot - top) * ay + 0.5).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** Seam priority of front-camera pixels relative to back-camera pixels. */
        const val FRONT_WEIGHT = 0.55
        /** Need at least this share of samples overlapping existing panorama to align. */
        private const val MIN_OVERLAP_FRACTION = 0.12
        /** Use the image-based correction for this frame only above this match score. */
        private const val MIN_ACCEPT_SCORE = 0.45
        /** Remember the correction for following frames only when the match is convincing. */
        private const val MIN_LEARN_SCORE = 0.7
        /** Gentle preference for small corrections (score units per degree squared). */
        private const val PRIOR_PER_DEG2 = 0.0008
        /**
         * Distinct-peak test: the match score must fall by MIN_PEAK_DROP when the frame is moved
         * PEAK_TEST_DEGREES away. Measured: textured scenes drop 0.26-0.67, smooth ones < 0.012.
         */
        private const val PEAK_TEST_DEGREES = 4.0
        private const val MIN_PEAK_DROP = 0.08

        /** atan2 approximation, max error ~1e-5 rad; much faster than Math.atan2. */
        fun fastAtan2(y: Double, x: Double): Double {
            val ax = abs(x)
            val ay = abs(y)
            if (ax == 0.0 && ay == 0.0) return 0.0
            val swap = ay > ax
            val t = if (swap) ax / ay else ay / ax
            val t2 = t * t
            var r = (((((-0.0040540580 * t2 + 0.0218612288) * t2 - 0.0559098861) * t2 + 0.0964200441) * t2
                - 0.1390853351) * t2 + 0.1994653599) * t2
            r = ((r - 0.3332985605) * t2 + 0.9999993329) * t
            if (swap) r = PI / 2 - r
            if (x < 0) r = PI - r
            return if (y < 0) -r else r
        }
    }
}
