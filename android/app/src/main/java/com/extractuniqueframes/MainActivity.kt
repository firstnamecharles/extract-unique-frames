package com.extractuniqueframes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.extractuniqueframes.ui.theme.ExtractUniqueFramesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val selectedMode by vm.mode.collectAsStateWithLifecycle()

    when (val s = state) {
        is ExtractViewModel.UiState.Idle -> HomeScreen(
            selectedMode = selectedMode,
            onModeChange = vm::setMode,
            onStartExtraction = { uri, config -> vm.startExtraction(uri, config) }
        )
        is ExtractViewModel.UiState.Processing -> ProcessingScreen(
            progress = s.progress,
            framesFound = s.framesFound,
            onCancel = { vm.cancel() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedMode: FrameExtractor.Mode,
    onModeChange: (FrameExtractor.Mode) -> Unit,
    onStartExtraction: (Uri, FrameExtractor.Config) -> Unit
) {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var settingsExpanded by remember { mutableStateOf(false) }

    var pHashThreshold by remember { mutableFloatStateOf(10f) }
    var colorDistance by remember { mutableFloatStateOf(20f) }
    var frameIntervalMs by remember { mutableFloatStateOf(200f) }
    var filterTouchEffects by remember { mutableStateOf(true) }
    var stabilityRunLength by remember { mutableFloatStateOf(5f) }

    // Held while we wait for WRITE_EXTERNAL_STORAGE on API ≤ 28
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingConfig by remember { mutableStateOf<FrameExtractor.Config?>(null) }

    val writePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = pendingUri
            val config = pendingConfig
            if (uri != null && config != null) {
                onStartExtraction(uri, config)
                pendingUri = null
                pendingConfig = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedUri = uri }

    fun launchExtraction(uri: Uri, config: FrameExtractor.Config) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingUri = uri
            pendingConfig = config
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            onStartExtraction(uri, config)
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
            text = "Extract Unique Frames",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Pick an MP4 screen recording and extract visually distinct frames, " +
                   "filtered for touch-effect overlays.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // Video picker card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { picker.launch("video/mp4") },
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
                Text(
                    text = if (selectedUri != null)
                        selectedUri!!.lastPathSegment ?: "Video selected"
                    else
                        "Tap to pick an MP4 file",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Settings accordion
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("Settings") },
                    trailingContent = {
                        Icon(
                            imageVector = if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (settingsExpanded) "Collapse" else "Expand"
                        )
                    },
                    modifier = Modifier.clickable { settingsExpanded = !settingsExpanded }
                )
                AnimatedVisibility(
                    visible = settingsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

                        // Mode selector
                        Text(
                            "Extraction mode",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = selectedMode == FrameExtractor.Mode.INTERVAL,
                                onClick = { onModeChange(FrameExtractor.Mode.INTERVAL) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text("Interval") }
                            )
                            SegmentedButton(
                                selected = selectedMode == FrameExtractor.Mode.SCENE_BASED,
                                onClick = { onModeChange(FrameExtractor.Mode.SCENE_BASED) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text("Scene-based") }
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // pHash + colour thresholds — visible in both modes
                        val thresholdDescription = if (selectedMode == FrameExtractor.Mode.INTERVAL)
                            "Hamming distance; lower = stricter dedup"
                        else
                            "Transition sensitivity; lower = detects smaller changes"
                        SliderSetting(
                            label = "pHash threshold",
                            value = pHashThreshold,
                            onValueChange = { pHashThreshold = it },
                            valueRange = 1f..25f,
                            steps = 23,
                            display = { it.roundToInt().toString() },
                            description = thresholdDescription
                        )
                        val colourDescription = if (selectedMode == FrameExtractor.Mode.INTERVAL)
                            "RGB Euclidean; lower = stricter dedup"
                        else
                            "Colour change sensitivity; lower = detects subtler shifts"
                        SliderSetting(
                            label = "Colour distance",
                            value = colorDistance,
                            onValueChange = { colorDistance = it },
                            valueRange = 0f..100f,
                            steps = 19,
                            display = { it.roundToInt().toString() },
                            description = colourDescription
                        )

                        // Interval-only setting
                        if (selectedMode == FrameExtractor.Mode.INTERVAL) {
                            SliderSetting(
                                label = "Frame interval",
                                value = frameIntervalMs,
                                onValueChange = { frameIntervalMs = it },
                                valueRange = 50f..2000f,
                                steps = 38,
                                display = { "${it.roundToInt()} ms" },
                                description = "How often to sample the video"
                            )
                        }

                        // Scene-based-only setting
                        if (selectedMode == FrameExtractor.Mode.SCENE_BASED) {
                            SliderSetting(
                                label = "Frames unchanged before capture",
                                value = stabilityRunLength,
                                onValueChange = { stabilityRunLength = it },
                                valueRange = 3f..20f,
                                steps = 16,
                                display = { it.roundToInt().toString() },
                                description = "Consecutive stable frames required to save a key frame"
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Filter touch effects", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Remove ripple / tap indicator frames",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = filterTouchEffects, onCheckedChange = { filterTouchEffects = it })
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                selectedUri?.let { uri ->
                    launchExtraction(
                        uri,
                        FrameExtractor.Config(
                            mode = selectedMode,
                            pHashThreshold = pHashThreshold.roundToInt(),
                            colorDistanceThreshold = colorDistance,
                            frameIntervalMs = frameIntervalMs.roundToInt().toLong(),
                            filterTouchEffects = filterTouchEffects,
                            stabilityRunLength = stabilityRunLength.roundToInt()
                        )
                    )
                }
            },
            enabled = selectedUri != null,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Extract Frames", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Float) -> String,
    description: String
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                display(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Processing ──────────────────────────────────────────────────────────────

@Composable
fun ProcessingScreen(progress: Float, framesFound: Int, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(72.dp), strokeWidth = 6.dp)
        Spacer(Modifier.height(32.dp))
        Text("Analysing video…", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Processing: ${(progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "$framesFound unique frame${if (framesFound == 1) "" else "s"} found",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cancel")
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
    var highlightedIndex by remember { mutableStateOf<Int?>(null) }

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
            IconButton(onClick = onStartOver) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Start over")
            }
            Text(
                "${result.savedFrames.size} unique frames",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
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

        // Stats row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatChip("Checked", result.totalChecked, Modifier.weight(1f))
            StatChip("Saved", result.savedFrames.size, Modifier.weight(1f))
            StatChip("Dupes", result.skippedDuplicates, Modifier.weight(1f))
            StatChip("Touch", result.skippedTouchEffects, Modifier.weight(1f))
        }

        // "Saved to" info + Open in Gallery
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
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
                        "No unique frames found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Timeline
            FrameTimeline(
                frameTimestampsMs = result.frameTimestampsMs,
                videoDurationMs = result.videoDurationMs,
                highlightIndex = highlightedIndex,
                onTickTapped = { idx ->
                    highlightedIndex = idx
                    scope.launch {
                        gridState.animateScrollToItem(idx)
                        delay(1_500)
                        highlightedIndex = null
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
                    val isHighlighted = idx == highlightedIndex
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (isHighlighted) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(4.dp)
                                ) else Modifier
                            )
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
                // Timestamp badge in corner
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

// ─── Timeline ────────────────────────────────────────────────────────────────

@Composable
private fun FrameTimeline(
    frameTimestampsMs: List<Long>,
    videoDurationMs: Long,
    highlightIndex: Int?,
    onTickTapped: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (frameTimestampsMs.isEmpty() || videoDurationMs == 0L) return

    var zoom by remember { mutableFloatStateOf(1f) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(modifier = Modifier.weight(1f).height(48.dp)) {
            val baseWidth = maxWidth
            val baseWidthPx = with(density) { baseWidth.toPx() }
            val tapThresholdPx = with(density) { 24.dp.toPx() }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollState, enabled = false)
                    // Tap handler: find nearest tick in content coordinates.
                    .pointerInput(frameTimestampsMs, videoDurationMs) {
                        detectTapGestures { tapOffset ->
                            val contentX = scrollState.value.toFloat() + tapOffset.x
                            val contentW = baseWidthPx * zoom
                            val threshold = tapThresholdPx * zoom
                            var bestIdx = -1
                            var bestDist = Float.MAX_VALUE
                            frameTimestampsMs.forEachIndexed { i, tsMs ->
                                val tickX = (tsMs.toFloat() / videoDurationMs) * contentW
                                val d = abs(contentX - tickX)
                                if (d < bestDist) { bestDist = d; bestIdx = i }
                            }
                            if (bestIdx >= 0 && bestDist < threshold) onTickTapped(bestIdx)
                        }
                    }
                    // Pinch-zoom handler: anchored around the pinch centroid.
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, _, zoomDelta, _ ->
                            val oldZoom = zoom
                            val newZoom = (oldZoom * zoomDelta).coerceIn(1f, 20f)
                            if (newZoom != oldZoom) {
                                zoom = newZoom
                                val maxScroll = ((baseWidthPx * newZoom) - baseWidthPx)
                                    .coerceAtLeast(0f).roundToInt()
                                val rawScroll =
                                    (scrollState.value + centroid.x) * (newZoom / oldZoom) - centroid.x
                                scope.launch {
                                    scrollState.scrollTo(rawScroll.roundToInt().coerceIn(0, maxScroll))
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.width(baseWidth * zoom).fillMaxHeight()) {
                    val cy = size.height / 2f
                    drawLine(
                        color = outline.copy(alpha = 0.35f),
                        start = Offset(0f, cy),
                        end = Offset(size.width, cy),
                        strokeWidth = 2.dp.toPx()
                    )
                    frameTimestampsMs.forEachIndexed { i, tsMs ->
                        val x = (tsMs.toFloat() / videoDurationMs) * size.width
                        val isHl = i == highlightIndex
                        val halfH = if (isHl) 16.dp.toPx() else 10.dp.toPx()
                        drawLine(
                            color = if (isHl) primary else primary.copy(alpha = 0.65f),
                            start = Offset(x, cy - halfH),
                            end = Offset(x, cy + halfH),
                            strokeWidth = if (isHl) 3.dp.toPx() else 1.5.dp.toPx()
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = zoom > 1f) {
            IconButton(
                onClick = { zoom = 1f; scope.launch { scrollState.scrollTo(0) } },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOut,
                    contentDescription = "Reset zoom",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatTimestamp(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun StatChip(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
