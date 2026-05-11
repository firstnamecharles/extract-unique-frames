package com.extractuniqueframes

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects Android touch-effect overlays (ripple / tap-dot indicators) by finding
 * transient bright-compact regions that appear in the current frame but not in the
 * frames immediately before and after it.
 *
 * All bitmaps are downscaled to 64×64 before analysis to keep processing fast.
 */
object TouchEffectFilter {

    private const val SCALE = 64
    private const val DIFF_THRESHOLD = 35          // per-channel mean diff to count as changed
    private const val MIN_TRANSIENT_RATIO = 0.002f // ignore sub-pixel noise
    private const val MAX_TRANSIENT_RATIO = 0.15f  // full-scene changes are content, not overlays
    private const val MIN_BRIGHTNESS = 140         // touch indicators are bright
    private const val MAX_CENTROID_DIST_RATIO = 0.22f // compact region check

    fun hasTouchEffect(
        prevFrame: Bitmap?,
        currentFrame: Bitmap,
        nextFrame: Bitmap?
    ): Boolean {
        if (prevFrame == null || nextFrame == null) return false

        val prev = Bitmap.createScaledBitmap(prevFrame, SCALE, SCALE, true)
        val curr = Bitmap.createScaledBitmap(currentFrame, SCALE, SCALE, true)
        val next = Bitmap.createScaledBitmap(nextFrame, SCALE, SCALE, true)

        return try {
            val transient = transientPixels(prev, curr, next)
            val ratio = transient.size.toFloat() / (SCALE * SCALE)

            if (ratio < MIN_TRANSIENT_RATIO || ratio > MAX_TRANSIENT_RATIO) return false

            // Touch overlays are bright
            val avgBrightness = transient.map { (x, y) ->
                val px = curr.getPixel(x, y)
                (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
            }.average()
            if (avgBrightness < MIN_BRIGHTNESS) return false

            // Touch overlays form compact (roughly circular) clusters
            isCompact(transient)
        } finally {
            if (prev != prevFrame) prev.recycle()
            if (curr != currentFrame) curr.recycle()
            if (next != nextFrame) next.recycle()
        }
    }

    private fun transientPixels(
        prev: Bitmap, curr: Bitmap, next: Bitmap
    ): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until SCALE) {
            for (x in 0 until SCALE) {
                val p = prev.getPixel(x, y)
                val c = curr.getPixel(x, y)
                val n = next.getPixel(x, y)
                // Pixel changed significantly coming in AND going out → transient
                if (channelMeanDiff(p, c) > DIFF_THRESHOLD &&
                    channelMeanDiff(c, n) > DIFF_THRESHOLD
                ) {
                    result.add(x to y)
                }
            }
        }
        return result
    }

    private fun channelMeanDiff(a: Int, b: Int): Int =
        (abs(Color.red(a) - Color.red(b)) +
         abs(Color.green(a) - Color.green(b)) +
         abs(Color.blue(a) - Color.blue(b))) / 3

    private fun isCompact(pixels: List<Pair<Int, Int>>): Boolean {
        if (pixels.isEmpty()) return false
        val cx = pixels.sumOf { it.first }.toFloat() / pixels.size
        val cy = pixels.sumOf { it.second }.toFloat() / pixels.size
        val avgDist = pixels.map { (x, y) ->
            sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
        }.average()
        return avgDist < SCALE * MAX_CENTROID_DIST_RATIO
    }
}
