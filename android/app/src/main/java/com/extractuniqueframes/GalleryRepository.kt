package com.extractuniqueframes

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProjectInfo(
    val name: String,
    val frameCount: Int,
    val thumbnailUri: Uri?,
    val createdMs: Long
)

data class ProjectFrame(val uri: Uri, val timestampMs: Long, val displayName: String)

suspend fun loadProjects(context: Context): List<ProjectInfo> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val prefix = "${FrameExtractor.SAVE_FOLDER_BASE}/"

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED
    )

    val imagesByProject = mutableMapOf<String, MutableList<Pair<Uri, Long>>>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$prefix%")
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection + arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
            selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val relativePath = cursor.getString(pathCol) ?: continue
                val dateAdded = cursor.getLong(dateCol) * 1000L
                val projectName = relativePath.removePrefix(prefix).trimEnd('/')
                if (projectName.isNotEmpty() && !projectName.contains('/')) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    imagesByProject.getOrPut(projectName) { mutableListOf() }.add(uri to dateAdded)
                }
            }
        }
    } else {
        @Suppress("DEPRECATION")
        val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
        val extDir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        val basePath = "${extDir.absolutePath}/ExtractUniqueFrames/"
        val selectionArgs = arrayOf("$basePath%")
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection + arrayOf(MediaStore.Images.Media.DATA),
            selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            @Suppress("DEPRECATION")
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dataPath = cursor.getString(dataCol) ?: continue
                val dateAdded = cursor.getLong(dateCol) * 1000L
                val relative = dataPath.removePrefix(basePath)
                val projectName = relative.substringBefore('/')
                if (projectName.isNotEmpty()) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    imagesByProject.getOrPut(projectName) { mutableListOf() }.add(uri to dateAdded)
                }
            }
        }
    }

    imagesByProject.map { (name, frames) ->
        ProjectInfo(
            name = name,
            frameCount = frames.size,
            thumbnailUri = frames.firstOrNull()?.first,
            createdMs = frames.firstOrNull()?.second ?: 0L
        )
    }.sortedByDescending { it.createdMs }
}

suspend fun loadProjectFrames(context: Context, projectName: String): List<ProjectFrame> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val frames = mutableListOf<ProjectFrame>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val folder = "${FrameExtractor.SAVE_FOLDER_BASE}/$projectName/"
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection, arrayOf(folder),
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    // filename is frame_00012345.jpg → timestamp in ms from digits
                    val tsMs = displayName.removePrefix("frame_").removeSuffix(".jpg").toLongOrNull() ?: 0L
                    frames.add(ProjectFrame(uri, tsMs, displayName))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val extDir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            val basePath = "${extDir.absolutePath}/ExtractUniqueFrames/$projectName/"
            val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection + arrayOf(MediaStore.Images.Media.DATA),
                selection, arrayOf("$basePath%"),
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    val tsMs = displayName.removePrefix("frame_").removeSuffix(".jpg").toLongOrNull() ?: 0L
                    frames.add(ProjectFrame(uri, tsMs, displayName))
                }
            }
        }

        frames.sortedBy { it.timestampMs }
    }

suspend fun generatePdf(context: Context, frames: List<ProjectFrame>, projectName: String): Uri =
    withContext(Dispatchers.IO) {
        val extractor = FrameExtractor(context)
        val pageWidth = 595   // A4 at 72 DPI
        val pageHeight = 842
        val pdfDocument = PdfDocument()

        frames.forEachIndexed { index, frame ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val bitmap = try {
                context.contentResolver.openInputStream(frame.uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) { null }

            if (bitmap != null) {
                val scale = minOf(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
                val scaledW = bitmap.width * scale
                val scaledH = bitmap.height * scale
                val left = (pageWidth - scaledW) / 2f
                val top = (pageHeight - scaledH) / 2f
                canvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(left, top, left + scaledW, top + scaledH),
                    null
                )
                bitmap.recycle()
            }
            pdfDocument.finishPage(page)
        }

        val bytes = ByteArrayOutputStream().also { pdfDocument.writeTo(it) }.toByteArray()
        pdfDocument.close()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "${projectName}_${timestamp}.pdf"
        extractor.savePdfToMediaStore(bytes, fileName)
    }

fun launchPrint(context: Context, pdfUri: Uri, title: String) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes,
            cancel: android.os.CancellationSignal,
            cb: LayoutResultCallback, extras: android.os.Bundle?
        ) {
            if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(
                PrintDocumentInfo.Builder(title)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build(),
                new != old
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            dest: android.os.ParcelFileDescriptor,
            cancel: android.os.CancellationSignal,
            cb: WriteResultCallback
        ) {
            if (cancel.isCanceled) { cb.onWriteCancelled(); return }
            try {
                context.contentResolver.openInputStream(pdfUri)?.use { input ->
                    FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) }
                }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            }
        }
    }
    printManager.print(title, adapter, null)
}

fun sanitizeProjectName(raw: String): String =
    raw.replace(Regex("[^A-Za-z0-9 _\\-]"), "").trim().take(64).ifEmpty { "project" }
