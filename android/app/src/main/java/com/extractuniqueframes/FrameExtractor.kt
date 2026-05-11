package com.extractuniqueframes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FrameExtractor(private val context: Context) {

    companion object {
        const val SAVE_FOLDER = "Pictures/ExtractUniqueFrames"
        private const val SAVE_FOLDER_NAME = "ExtractUniqueFrames"
    }

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
        val savedFrames: List<Uri>,
        /** Video timestamp (ms) corresponding to each saved frame — parallel to savedFrames. */
        val frameTimestampsMs: List<Long>,
        val videoDurationMs: Long,
        val totalChecked: Int,
        val skippedDuplicates: Int,
        val skippedTouchEffects: Int
    )

    suspend fun extract(
        videoUri: Uri,
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

            val saved = mutableListOf<Uri>()
            val savedTimestamps = mutableListOf<Long>()
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
                    val currTimestampMs = (timeUs - intervalUs).coerceAtLeast(0L) / 1_000L

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
                            val bmp = currBitmap
                            val name = "frame_%05d.jpg".format(saved.size)
                            val uri = saveToMediaStore(bmp, name)
                            saved.add(uri)
                            savedTimestamps.add(currTimestampMs)
                            lastHash = hash
                            lastColor = color
                            firstFrame = false
                        } else {
                            skippedDuplicates++
                        }
                    }

                    onProgress(
                        (timeUs - intervalUs).coerceAtLeast(0L).toFloat() / durationUs,
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
            Result(saved, savedTimestamps, durationMs, totalChecked, skippedDuplicates, skippedTouchEffects)

        } finally {
            retriever.release()
        }
    }

    private fun saveToMediaStore(bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, SAVE_FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                SAVE_FOLDER_NAME
            ).also { it.mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val values = ContentValues().apply {
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed (legacy)")
        }
    }
}
