package com.angadlobo.camera360

import kotlin.math.abs
import kotlin.math.asin

/**
 * Decides when to take a photo during guided capture and records what has been shot.
 * Pure Kotlin (no Android), so a whole capture session can be simulated in tests.
 *
 * Call from the stitching thread only.
 */
class CaptureSession(val stitcher: PanoramaStitcher, val guide: CaptureGuide) {

    /** The camera the user is being guided with (the back camera when both run together). */
    @Volatile var guideFacing: Facing = Facing.BACK

    private val shotBy = mapOf(Facing.BACK to HashSet<Int>(), Facing.FRONT to HashSet())

    sealed interface Decision {
        /** Don't take this frame. */
        data object Skip : Decision
        /** Take it as the first photo of the panorama. */
        data object First : Decision
        /** Take it for this target index. */
        data class Target(val index: Int) : Decision
    }

    fun decide(rotation: FloatArray, facing: Facing, steady: Boolean): Decision {
        if (!steady) return Decision.Skip
        if (stitcher.referenceHeading.isNaN()) {
            // The first photo defines the panorama centre: take it from the guiding camera
            // once the phone is upright (roughly level).
            if (facing != guideFacing) return Decision.Skip
            if (abs(pitchOf(rotation, facing)) > FIRST_SHOT_MAX_PITCH) return Decision.Skip
            return Decision.First
        }
        val t = guide.targetUnder(stitcher.correctedBasis(rotation, facing))
        if (t < 0 || t in shotBy.getValue(facing)) return Decision.Skip
        return Decision.Target(t)
    }

    /** Records a photo that was added to the stitcher. */
    fun commit(decision: Decision, rotation: FloatArray, facing: Facing) {
        when (decision) {
            Decision.Skip -> return
            Decision.First -> {
                val t0 = guide.targetUnder(stitcher.correctedBasis(rotation, facing), Math.toRadians(20.0))
                if (t0 >= 0) { shotBy.getValue(facing).add(t0); guide.markDone(t0) }
            }
            is Decision.Target -> {
                shotBy.getValue(facing).add(decision.index)
                // The guiding camera's own shot always completes its target; selfie-camera shots
                // only count for the area actually covered once the person has been cut out.
                if (facing == guideFacing) guide.markDone(decision.index)
            }
        }
        guide.refresh(stitcher)
    }

    fun reset() {
        stitcher.reset()
        guide.reset()
        shotBy.values.forEach { it.clear() }
    }

    companion object {
        val FIRST_SHOT_MAX_PITCH = Math.toRadians(12.0)

        /** Elevation of a camera's optical axis (radians, positive = up). */
        fun pitchOf(rot: FloatArray, facing: Facing): Double {
            val s = if (facing == Facing.BACK) 1.0 else -1.0
            return asin((-s * rot[8]).toDouble().coerceIn(-1.0, 1.0))
        }
    }
}
