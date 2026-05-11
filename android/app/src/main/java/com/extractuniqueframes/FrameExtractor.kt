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
        private const val SCENE_INTERVAL_MS = 33L  // ~30 fps sampling in scene mode
    }

    enum class Mode { INTERVAL, SCENE_BASED }

    data class Config(
        val mode: Mode = Mode.INTERVAL,
        /** Hamming distance threshold (dedup gate in interval; transition gate in scene). */
        val pHashThreshold: Int = 10,
        /** Euclidean RGB distance threshold (same dual role as pHashThreshold). */
        val colorDistanceThreshold: Float = 20f,
        /** Milliseconds between sampled frames — INTERVAL mode only. */
        val frameIntervalMs: Long = 200L,
        /** Whether to suppress touch-effect overlay frames. */
        val filterTouchEffects: Boolean = true,
        /** Consecutive stable frames required before capturing a key frame — SCENE mode only. */
        val stabilityRunLength: Int = 5
    )

    data class Result(
        val savedFrames: List<Uri>,
        /** Video timestamp (ms) for each saved frame — parallel to savedFrames. */
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

            if (config.mode == Mode.INTERVAL) {
                extractInterval(retriever, durationMs, config, onProgress)
            } else {
                extractSceneBased(retriever, durationMs, config, onProgress)
            }
        } finally {
            retriever.release()
        }
    }

    // ── Interval mode ─────────────────────────────────────────────────────────

    private suspend fun extractInterval(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        config: Config,
        onProgress: suspend (Float, Int) -> Unit
    ): Result {
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

        var prevBitmap: Bitmap? = null
        var currBitmap: Bitmap? = null
        var timeUs = 0L

        while (isActive) {
            val nextBitmap: Bitmap? = if (timeUs <= durationUs)
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            else null

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
                        val uri = saveToMediaStore(bmp, "frame_%05d.jpg".format(saved.size))
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
        return Result(saved, savedTimestamps, durationMs, totalChecked, skippedDuplicates, skippedTouchEffects)
    }

    // ── Scene-based mode ──────────────────────────────────────────────────────
    //
    // Algorithm:
    //   Walk frames at ~30 fps. For each non-touch-effect frame, compare its
    //   pHash + colour distance against the previous non-touch-effect frame.
    //
    //   State LOOKING: counting consecutive "stable" pairs (diff ≤ threshold).
    //     – On the first stable pair, record the timestamp of the older frame as
    //       stableRunStartTs (the "first frame of the run").
    //     – When stabilityCount reaches stabilityRunLength, save the current frame
    //       (same scene, just confirmation-delayed) with stableRunStartTs as the
    //       timeline tick. Switch to WAITING.
    //     – A non-stable pair resets stabilityCount to 0.
    //
    //   State WAITING: skip frames until a non-stable pair signals a transition,
    //     then switch back to LOOKING.
    //
    //   Touch-effect frames are skipped without updating any scene state so that
    //   transient overlays never break a stability run.

    private suspend fun extractSceneBased(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        config: Config,
        onProgress: suspend (Float, Int) -> Unit
    ): Result {
        val saved = mutableListOf<Uri>()
        val savedTimestamps = mutableListOf<Long>()
        var totalChecked = 0
        var skippedDuplicates = 0
        var skippedTouchEffects = 0

        val intervalUs = SCENE_INTERVAL_MS * 1_000L
        val durationUs = durationMs * 1_000L
        val estimatedFrames = (durationMs / SCENE_INTERVAL_MS).coerceAtLeast(1)

        // Scene-state machine
        var waitingForTransition = false
        var stabilityCount = 0
        var stableRunStartTs = 0L  // timestamp of first frame in current stable run

        // Reference for frame-to-frame comparison (updated only on non-touch frames)
        var lastHash = 0L
        var lastColor = FloatArray(3)
        var lastTs = 0L
        var firstNonTouchFrame = true

        // Sliding 3-frame buffer for touch-effect detection
        var prevBitmap: Bitmap? = null
        var currBitmap: Bitmap? = null
        var frameIndex = 0
        var timeUs = 0L

        while (isActive) {
            val nextBitmap: Bitmap? = if (timeUs <= durationUs)
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            else null

            if (currBitmap != null) {
                frameIndex++
                val currTs = (timeUs - intervalUs).coerceAtLeast(0L) / 1_000L

                val isTouchEffect = config.filterTouchEffects &&
                    TouchEffectFilter.hasTouchEffect(prevBitmap, currBitmap, nextBitmap)

                if (isTouchEffect) {
                    skippedTouchEffects++
                    // Touch overlays must not affect the stability state — skip entirely.
                } else {
                    val hash = PHashCalculator.compute(currBitmap)
                    val color = PHashCalculator.averageColor(currBitmap)

                    if (firstNonTouchFrame) {
                        // Seed the reference; nothing to compare yet.
                        lastHash = hash
                        lastColor = color
                        lastTs = currTs
                        firstNonTouchFrame = false
                    } else {
                        totalChecked++

                        val isStable =
                            PHashCalculator.hammingDistance(hash, lastHash) <= config.pHashThreshold &&
                            PHashCalculator.colorDistance(color, lastColor) <= config.colorDistanceThreshold

                        if (!waitingForTransition) {
                            // ── LOOKING FOR STABLE RUN ──────────────────────
                            if (isStable) {
                                if (stabilityCount == 0) {
                                    // First stable pair: the older frame (lastTs) starts the run.
                                    stableRunStartTs = lastTs
                                }
                                stabilityCount++
                                if (stabilityCount >= config.stabilityRunLength) {
                                    val bmp = currBitmap
                                    val uri = saveToMediaStore(bmp, "frame_%05d.jpg".format(saved.size))
                                    saved.add(uri)
                                    savedTimestamps.add(stableRunStartTs)
                                    waitingForTransition = true
                                }
                            } else {
                                stabilityCount = 0
                            }
                        } else {
                            // ── WAITING FOR TRANSITION ───────────────────────
                            if (isStable) {
                                skippedDuplicates++
                            } else {
                                waitingForTransition = false
                                stabilityCount = 0
                            }
                        }

                        lastHash = hash
                        lastColor = color
                        lastTs = currTs
                    }
                }

                onProgress(
                    (frameIndex.toFloat() / estimatedFrames).coerceAtMost(1f),
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
        return Result(saved, savedTimestamps, durationMs, totalChecked, skippedDuplicates, skippedTouchEffects)
    }

    // ── MediaStore save ───────────────────────────────────────────────────────

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
