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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FrameExtractor(private val context: Context) {

    companion object {
        const val SAVE_FOLDER_BASE = "Pictures/ExtractUniqueFrames"
        private const val SAVE_FOLDER_NAME = "ExtractUniqueFrames"
        private const val JPEG_QUALITY = 85
    }

    data class Result(
        val savedFrames: List<Uri>,
        val frameTimestampsMs: List<Long>,
        val videoDurationMs: Long,
        val projectName: String
    )

    suspend fun captureFrame(videoUri: Uri, positionMs: Long, projectName: String): Uri =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val bitmap = retriever.getFrameAtTime(
                    positionMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: error("Could not capture frame at ${positionMs}ms")
                val name = "frame_%08d.jpg".format(positionMs)
                saveToMediaStore(bitmap, name, projectName).also { bitmap.recycle() }
            } finally {
                retriever.release()
            }
        }

    fun saveToMediaStore(bitmap: Bitmap, name: String, projectName: String): Uri {
        val folder = "$SAVE_FOLDER_BASE/$projectName"
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    SAVE_FOLDER_NAME
                ),
                projectName
            ).also { it.mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
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

    fun savePdfToMediaStore(pdfBytes: ByteArray, fileName: String): Uri {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/ExtractUniqueFrames")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: error("MediaStore PDF insert failed")
            resolver.openOutputStream(uri)?.use { it.write(pdfBytes) }
            values.clear()
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ExtractUniqueFrames"
            ).also { it.mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(pdfBytes)
            val values = ContentValues().apply {
                @Suppress("DEPRECATION")
                put(MediaStore.Files.FileColumns.DATA, file.absolutePath)
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
            }
            resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: Uri.fromFile(file)
        }
    }
}
