package com.extractuniqueframes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.extractuniqueframes.ui.theme.ExtractUniqueFramesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExtractUniqueFramesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ExtractUniqueFramesApp()
                }
            }
        }
    }
}

@Composable
fun ExtractUniqueFramesApp(vm: ExtractViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is ExtractViewModel.UiState.Idle -> HomeScreen(
            onStartCapturing = { uri, durationMs -> vm.startCapturing(uri, durationMs) }
        )
        is ExtractViewModel.UiState.Capturing -> PlayerScreen(
            videoUri = s.videoUri,
            videoDurationMs = s.videoDurationMs,
            capturedUris = s.capturedUris,
            capturedTimestampsMs = s.capturedTimestampsMs,
            onCaptureFrame = { positionMs -> vm.captureFrame(positionMs) },
            onDone = { vm.finish() }
        )
        is ExtractViewModel.UiState.Done -> ResultsScreen(
            result = s.result,
            onStartOver = { vm.reset() }
        )
        is ExtractViewModel.UiState.Error -> ErrorScreen(
            message = s.message,
            onDismiss = { vm.reset() }
        )
    }
}

// ─── Home ────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onStartCapturing: (Uri, Long) -> Unit
) {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var videoDurationMs by remember { mutableLongStateOf(0L) }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val writePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = pendingUri
            if (uri != null && videoDurationMs > 0L) {
                onStartCapturing(uri, videoDurationMs)
                pendingUri = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedUri = uri }

    // Read duration when URI changes
    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        videoDurationMs = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            } finally {
                retriever.release()
            }
        }
    }

    fun launchCapturing(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingUri = uri
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            onStartCapturing(uri, videoDurationMs)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Icon(
            imageVector = Icons.Default.VideoFile,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Frame Capture",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Pick a video, scrub through it, and tap to capture the frames you want.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // Video picker card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { picker.launch("video/*") },
            colors = CardDefaults.cardColors(
                containerColor = if (selectedUri != null)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (selectedUri != null) Icons.Default.CheckCircle else Icons.Default.Upload,
                    contentDescription = null,
                    tint = if (selectedUri != null)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedUri != null)
                            selectedUri!!.lastPathSegment ?: "Video selected"
                        else
                            "Tap to pick a video file",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (selectedUri != null && videoDurationMs > 0L) {
                        Text(
                            text = "Duration: ${formatTimestamp(videoDurationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                selectedUri?.let { uri -> launchCapturing(uri) }
            },
            enabled = selectedUri != null && videoDurationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start capturing", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─── Player ──────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoUri: Uri,
    videoDurationMs: Long,
    capturedUris: List<Uri>,
    capturedTimestampsMs: List<Long>,
    onCaptureFrame: (Long) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ExoPlayer setup
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var frameDurationMs by remember { mutableLongStateOf(33L) } // default 30fps

    // Position polling
    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            isPlaying = exoPlayer.isPlaying
            delay(50)
        }
    }

    // Frame rate reading
    LaunchedEffect(videoUri) {
        frameDurationMs = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, videoUri, null)
                var fps = 30
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).takeIf { it > 0 } ?: 30
                        } else 30
                        break
                    }
                }
                (1000L / fps).coerceAtLeast(16L)
            } catch (_: Exception) {
                33L
            } finally {
                extractor.release()
            }
        }
    }

    // Capture flash
    var flashKey by remember { mutableIntStateOf(0) }
    var showFlash by remember { mutableStateOf(false) }
    LaunchedEffect(flashKey) {
        if (flashKey > 0) {
            showFlash = true
            delay(150)
            showFlash = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Captured count chip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "${capturedUris.size} frames captured",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Done button
            IconButton(onClick = onDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Video surface — tappable to capture
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable {
                    val pos = positionMs
                    onCaptureFrame(pos)
                    flashKey++
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Capture flash overlay
            if (showFlash) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.5f))
                )
            }
        }

        // Timeline
        VideoTimeline(
            durationMs = videoDurationMs,
            positionMs = positionMs,
            capturedTimestampsMs = capturedTimestampsMs,
            onSeek = { seekMs ->
                exoPlayer.seekTo(seekMs)
                positionMs = seekMs
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step back one frame
            IconButton(onClick = {
                val newPos = (positionMs - frameDurationMs).coerceAtLeast(0L)
                exoPlayer.seekTo(newPos)
                positionMs = newPos
            }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Step back")
            }

            // Play/pause
            IconButton(onClick = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }

            // Step forward one frame
            IconButton(onClick = {
                val newPos = (positionMs + frameDurationMs).coerceAtMost(videoDurationMs)
                exoPlayer.seekTo(newPos)
                positionMs = newPos
            }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Step forward")
            }

            // Time display
            Text(
                text = "${formatTimestamp(positionMs)} / ${formatTimestamp(videoDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Capture button
        Button(
            onClick = {
                val pos = positionMs
                onCaptureFrame(pos)
                flashKey++
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Capture frame", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─── Video Timeline ───────────────────────────────────────────────────────────

@Composable
fun VideoTimeline(
    durationMs: Long,
    positionMs: Long,
    capturedTimestampsMs: List<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0L) return

    var zoom by remember { mutableFloatStateOf(1f) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var lastSeekMs by remember { mutableLongStateOf(0L) }

    val colorSurfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorTertiary = MaterialTheme.colorScheme.tertiary
    val colorOnSurface = MaterialTheme.colorScheme.onSurface

    // Auto-scroll to keep playhead centred
    LaunchedEffect(positionMs, zoom) {
        if (zoom > 1f && !isDragging && durationMs > 0L) {
            val baseWidthPx = with(density) {
                // We'll compute after we know the actual size, so we approximate
                // This will be updated in BoxWithConstraints
            }
        }
    }

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            val baseWidth = maxWidth
            val baseWidthPx = with(density) { baseWidth.toPx() }

            // Auto-scroll effect (needs baseWidthPx, placed inside BoxWithConstraints scope)
            LaunchedEffect(positionMs, zoom) {
                if (zoom > 1f && !isDragging && durationMs > 0L) {
                    val contentWidthPx = baseWidthPx * zoom
                    val playheadX = (positionMs.toFloat() / durationMs) * contentWidthPx
                    val targetScroll = (playheadX - baseWidthPx / 2f)
                        .roundToInt()
                        .coerceIn(0, (contentWidthPx - baseWidthPx).coerceAtLeast(0f).roundToInt())
                    scrollState.animateScrollTo(targetScroll)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollState, enabled = false)
                    .pointerInput(durationMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isDragging = true

                            // Seek on initial down
                            val contentX = scrollState.value.toFloat() + down.position.x
                            val contentWidthPx = baseWidthPx * zoom
                            val seekMs = ((contentX / contentWidthPx) * durationMs)
                                .toLong()
                                .coerceIn(0L, durationMs)
                            val now = System.currentTimeMillis()
                            if (now - lastSeekMs >= 100L) {
                                onSeek(seekMs)
                                lastSeekMs = now
                            }

                            var prevSpan = 0f
                            var prevCentroidX = down.position.x

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pointers = event.changes.filter { it.pressed }
                                    if (pointers.isEmpty()) break

                                    if (pointers.size == 1) {
                                        // Single finger: seek
                                        val finger = pointers[0]
                                        val cx = scrollState.value.toFloat() + finger.position.x
                                        val sm = ((cx / (baseWidthPx * zoom)) * durationMs)
                                            .toLong()
                                            .coerceIn(0L, durationMs)
                                        val nowMs = System.currentTimeMillis()
                                        if (nowMs - lastSeekMs >= 100L) {
                                            onSeek(sm)
                                            lastSeekMs = nowMs
                                        }
                                        prevCentroidX = finger.position.x
                                        prevSpan = 0f
                                    } else if (pointers.size >= 2) {
                                        // Two fingers: pinch zoom
                                        val p0 = pointers[0].position
                                        val p1 = pointers[1].position
                                        val span = abs(p1.x - p0.x)
                                        val centroidX = (p0.x + p1.x) / 2f

                                        if (prevSpan > 0f && span > 0f) {
                                            val zoomDelta = span / prevSpan
                                            val oldZoom = zoom
                                            val newZoom = (oldZoom * zoomDelta).coerceIn(1f, 50f)
                                            if (newZoom != oldZoom) {
                                                zoom = newZoom
                                                val maxScroll = ((baseWidthPx * newZoom) - baseWidthPx)
                                                    .coerceAtLeast(0f).roundToInt()
                                                val anchorInContent = scrollState.value + centroidX
                                                val scaledAnchor = anchorInContent * (newZoom / oldZoom)
                                                val newScroll = (scaledAnchor - centroidX)
                                                    .roundToInt()
                                                    .coerceIn(0, maxScroll)
                                                scope.launch { scrollState.scrollTo(newScroll) }
                                            }
                                        }
                                        prevSpan = span
                                        prevCentroidX = centroidX
                                    }
                                }
                            } finally {
                                isDragging = false
                            }
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .width(baseWidth * zoom)
                        .height(72.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val barTop = h * 0.35f
                    val barBot = h * 0.65f

                    // Background bar
                    drawRoundRect(
                        color = colorSurfaceVariant,
                        topLeft = Offset(0f, barTop),
                        size = androidx.compose.ui.geometry.Size(w, barBot - barTop),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    )

                    // Determine label interval
                    val intervals = longArrayOf(500, 1000, 5000, 10000, 30000, 60000, 300000)
                    val labelPaint = android.graphics.Paint().apply {
                        color = colorOnSurface.copy(alpha = 0.6f).toArgb()
                        textSize = 9.dp.toPx()
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val labelWidthPx = labelPaint.measureText("00:00") + 6.dp.toPx()
                    var chosenInterval = intervals.last()
                    for (iv in intervals) {
                        val gapPx = (iv.toFloat() / durationMs) * w
                        if (gapPx >= labelWidthPx) {
                            chosenInterval = iv
                            break
                        }
                    }

                    // Draw tick lines and labels
                    var t = 0L
                    while (t <= durationMs) {
                        val x = (t.toFloat() / durationMs) * w
                        drawLine(
                            color = colorOnSurface.copy(alpha = 0.25f),
                            start = Offset(x, barTop - 4.dp.toPx()),
                            end = Offset(x, barBot + 4.dp.toPx()),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                formatTimestamp(t),
                                x,
                                h * 0.95f,
                                labelPaint
                            )
                        }
                        t += chosenInterval
                    }

                    // Captured frame ticks
                    capturedTimestampsMs.forEach { tsMs ->
                        val x = (tsMs.toFloat() / durationMs) * w
                        drawLine(
                            color = colorTertiary,
                            start = Offset(x, barTop - 8.dp.toPx()),
                            end = Offset(x, barBot + 8.dp.toPx()),
                            strokeWidth = 2.5.dp.toPx()
                        )
                    }

                    // Playhead (only when positionMs >= 0)
                    if (positionMs >= 0L) {
                        val px = (positionMs.toFloat() / durationMs) * w
                        drawLine(
                            color = colorPrimary,
                            start = Offset(px, 0f),
                            end = Offset(px, h),
                            strokeWidth = 2.dp.toPx()
                        )
                        // Diamond
                        val diamondSize = 6.dp.toPx()
                        val diamondPath = Path().apply {
                            moveTo(px, barTop - diamondSize)
                            lineTo(px + diamondSize / 2f, barTop)
                            lineTo(px, barTop + diamondSize)
                            lineTo(px - diamondSize / 2f, barTop)
                            close()
                        }
                        drawPath(diamondPath, color = colorPrimary)
                    }
                }
            }
        }

        // Reset zoom button
        AnimatedVisibility(visible = zoom > 1f) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        zoom = 1f
                        scope.launch { scrollState.scrollTo(0) }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOut,
                        contentDescription = "Reset zoom",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Reset zoom", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ─── Results ─────────────────────────────────────────────────────────────────

@Composable
fun ResultsScreen(result: FrameExtractor.Result, onStartOver: () -> Unit) {
    val context = LocalContext.current
    var selectedFrameIndex by remember { mutableStateOf<Int?>(null) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start over
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onStartOver() }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Start over")
                Spacer(Modifier.width(4.dp))
                Text("Start over", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${result.savedFrames.size} frames captured",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            // Share all button
            IconButton(
                onClick = {
                    if (result.savedFrames.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/jpeg"
                            putParcelableArrayListExtra(
                                Intent.EXTRA_STREAM,
                                ArrayList(result.savedFrames)
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share frames"))
                    }
                }
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share all")
            }
        }

        // "Saved to" info + Open in Gallery
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Saved to: ${FrameExtractor.SAVE_FOLDER}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    result.savedFrames.lastOrNull()?.let { lastUri ->
                        val intent = Intent(Intent.ACTION_VIEW, lastUri).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    }
                },
                enabled = result.savedFrames.isNotEmpty()
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Open in Gallery", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (result.savedFrames.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ImageNotSupported,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No frames captured",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Timeline (static, no playhead — positionMs = -1)
            VideoTimeline(
                durationMs = result.videoDurationMs,
                positionMs = -1L,
                capturedTimestampsMs = result.frameTimestampsMs,
                onSeek = { seekMs ->
                    // Find nearest captured frame and scroll to it
                    if (result.frameTimestampsMs.isNotEmpty()) {
                        val nearestIdx = result.frameTimestampsMs
                            .indexOfMinBy { abs(it - seekMs) }
                        if (nearestIdx >= 0) {
                            scope.launch { gridState.animateScrollToItem(nearestIdx) }
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            HorizontalDivider()

            // Frame grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(result.savedFrames) { idx, uri ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { selectedFrameIndex = idx }
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // mm:ss timestamp badge
                        Text(
                            text = formatTimestamp(result.frameTimestampsMs[idx]),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    // Full-screen detail dialog
    selectedFrameIndex?.let { idx ->
        val uri = result.savedFrames[idx]
        Dialog(
            onDismissRequest = { selectedFrameIndex = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { selectedFrameIndex = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                // Timestamp badge
                Text(
                    text = formatTimestamp(result.frameTimestampsMs[idx]),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FloatingActionButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share frame"))
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }
        }
    }
}

// ─── Error ───────────────────────────────────────────────────────────────────

@Composable
fun ErrorScreen(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss) { Text("Try again") }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatTimestamp(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun <T> List<T>.indexOfMinBy(selector: (T) -> Long): Int {
    if (isEmpty()) return -1
    var minIdx = 0
    var minVal = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (v < minVal) { minVal = v; minIdx = i }
    }
    return minIdx
}
