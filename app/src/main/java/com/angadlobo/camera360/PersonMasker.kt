package com.angadlobo.camera360

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit

/**
 * Uses ML Kit's on-device selfie segmentation model to find the person holding the
 * phone in front-camera frames, so they can be cut out before stitching. Without this
 * your own face and body end up in the half of the panorama behind you.
 *
 * Runs entirely on the phone; the model is bundled with the app (no network needed).
 */
class PersonMasker : AutoCloseable {

    private val segmenter: Segmenter? = try {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Selfie segmentation unavailable", t)
        null
    }

    val isAvailable get() = segmenter != null

    /**
     * Makes every pixel that belongs to a person (plus a safety margin) transparent in
     * [pixels] (ARGB, same size as [bitmap]). Returns the fraction of the frame removed,
     * or -1 if segmentation failed (pixels are then left untouched).
     */
    fun maskPeople(bitmap: Bitmap, pixels: IntArray): Double {
        val seg = segmenter ?: return -1.0
        val mask = try {
            Tasks.await(seg.process(InputImage.fromBitmap(bitmap, 0)), 3, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "Segmentation failed", t)
            return -1.0
        }
        val mw = mask.width
        val mh = mask.height
        val buf = mask.buffer
        buf.rewind()
        val person = BooleanArray(mw * mh)
        for (k in 0 until mw * mh) person[k] = buf.float > THRESHOLD

        val grown = dilate(person, mw, mh, (mw * MARGIN).toInt().coerceAtLeast(1))

        val w = bitmap.width
        val h = bitmap.height
        var removed = 0
        for (y in 0 until h) {
            val my = (y.toLong() * mh / h).toInt()
            for (x in 0 until w) {
                val mx = (x.toLong() * mw / w).toInt()
                if (grown[my * mw + mx]) {
                    pixels[y * w + x] = 0
                    removed++
                }
            }
        }
        return removed.toDouble() / (w * h)
    }

    /** Square dilation by radius [r] (separable running max). */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            // Forward and backward passes: distance to the nearest set pixel in the row.
            var near = -1_000_000
            for (x in 0 until w) {
                if (src[y * w + x]) near = x
                if (x - near <= r) tmp[y * w + x] = true
            }
            near = 1_000_000
            for (x in w - 1 downTo 0) {
                if (src[y * w + x]) near = x
                if (near - x <= r) tmp[y * w + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var near = -1_000_000
            for (y in 0 until h) {
                if (tmp[y * w + x]) near = y
                if (y - near <= r) out[y * w + x] = true
            }
            near = 1_000_000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) near = y
                if (near - y <= r) out[y * w + x] = true
            }
        }
        return out
    }

    override fun close() {
        segmenter?.close()
    }

    companion object {
        private const val TAG = "PersonMasker"
        private const val THRESHOLD = 0.4f
        /** Extra margin around the person, as a fraction of the mask width. */
        private const val MARGIN = 0.04
    }
}
