package com.extractuniqueframes

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FrameExtractor(private val context: Context) {

    data class Config(
        /** Hamming distance above which two frames are considered different. */
        val pHashThreshold: Int = 10,
        /** Euclidean RGB distance (0–441) above which colours differ enough to keep. */
        val colorDistanceThreshold: Float = 20f,
        /** Extract a candidate frame every N milliseconds of video time. */
        val frameIntervalMs: Long = 200L,
        /** Whether to run the touch-effect filter. */
        val filterTouchEffects: Boolean = true
    )

    data class Result(
        val savedFrames: List<File>,
        val totalChecked: Int,
        val skippedDuplicates: Int,
        val skippedTouchEffects: Int
    )

    suspend fun extract(
        videoUri: Uri,
        outputDir: File,
        config: Config = Config(),
        onProgress: suspend (progress: Float, framesFound: Int) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: error("Cannot read video duration")

            outputDir.mkdirs()

            val saved = mutableListOf<File>()
            var lastHash = 0L
            var lastColor = FloatArray(3)
            var firstFrame = true
            var totalChecked = 0
            var skippedDuplicates = 0
            var skippedTouchEffects = 0

            val intervalUs = config.frameIntervalMs * 1_000L
            val durationUs = durationMs * 1_000L

            // Sliding 3-frame buffer for touch-effect detection
            var prevBitmap: Bitmap? = null
            var currBitmap: Bitmap? = null

            var timeUs = 0L
            while (isActive) {
                val nextBitmap: Bitmap? = if (timeUs <= durationUs) {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else null

                if (currBitmap != null) {
                    totalChecked++

                    val isTouchEffect = config.filterTouchEffects &&
                        TouchEffectFilter.hasTouchEffect(prevBitmap, currBitmap, nextBitmap)

                    if (isTouchEffect) {
                        skippedTouchEffects++
                    } else {
                        val hash = PHashCalculator.compute(currBitmap)
                        val color = PHashCalculator.averageColor(currBitmap)

                        val isUnique = firstFrame ||
                            PHashCalculator.hammingDistance(hash, lastHash) > config.pHashThreshold ||
                            PHashCalculator.colorDistance(color, lastColor) > config.colorDistanceThreshold

                        if (isUnique) {
                            val file = File(outputDir, "frame_%05d.jpg".format(saved.size))
                            FileOutputStream(file).use { out ->
                                currBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            saved.add(file)
                            lastHash = hash
                            lastColor = color
                            firstFrame = false
                        } else {
                            skippedDuplicates++
                        }
                    }

                    onProgress(
                        (timeUs - intervalUs).coerceAtLeast(0).toFloat() / durationUs,
                        saved.size
                    )
                }

                prevBitmap?.recycle()
                prevBitmap = currBitmap
                currBitmap = nextBitmap

                if (nextBitmap == null) break
                timeUs += intervalUs
            }

            prevBitmap?.recycle()
            currBitmap?.recycle()

            onProgress(1f, saved.size)
            Result(saved, totalChecked, skippedDuplicates, skippedTouchEffects)

        } finally {
            retriever.release()
        }
    }
}
