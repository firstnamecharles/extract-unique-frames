package com.extractuniqueframes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.extractuniqueframes.ui.theme.ExtractUniqueFramesTheme
import java.io.File
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

@Composable
fun HomeScreen(onStartExtraction: (Uri, FrameExtractor.Config) -> Unit) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var settingsExpanded by remember { mutableStateOf(false) }

    var pHashThreshold by remember { mutableFloatStateOf(10f) }
    var colorDistance by remember { mutableFloatStateOf(20f) }
    var frameIntervalMs by remember { mutableFloatStateOf(200f) }
    var filterTouchEffects by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedUri = uri }

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
                        SliderSetting(
                            label = "pHash threshold",
                            value = pHashThreshold,
                            onValueChange = { pHashThreshold = it },
                            valueRange = 1f..25f,
                            steps = 23,
                            display = { it.roundToInt().toString() },
                            description = "Hamming distance; lower = stricter dedup"
                        )
                        SliderSetting(
                            label = "Colour distance",
                            value = colorDistance,
                            onValueChange = { colorDistance = it },
                            valueRange = 0f..100f,
                            steps = 19,
                            display = { it.roundToInt().toString() },
                            description = "RGB Euclidean; lower = stricter dedup"
                        )
                        SliderSetting(
                            label = "Frame interval",
                            value = frameIntervalMs,
                            onValueChange = { frameIntervalMs = it },
                            valueRange = 50f..2000f,
                            steps = 38,
                            display = { "${it.roundToInt()} ms" },
                            description = "How often to sample the video"
                        )
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
                    onStartExtraction(
                        uri,
                        FrameExtractor.Config(
                            pHashThreshold = pHashThreshold.roundToInt(),
                            colorDistanceThreshold = colorDistance,
                            frameIntervalMs = frameIntervalMs.roundToInt().toLong(),
                            filterTouchEffects = filterTouchEffects
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
            "${(progress * 100).roundToInt()}%",
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
    var selectedFrame by remember { mutableStateOf<File?>(null) }

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
                        val uris = result.savedFrames.map { file ->
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                        }
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/jpeg"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(result.savedFrames) { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { selectedFrame = file }
                    )
                }
            }
        }
    }

    // Full-screen detail dialog
    selectedFrame?.let { file ->
        Dialog(
            onDismissRequest = { selectedFrame = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { selectedFrame = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                // Share single frame button
                FloatingActionButton(
                    onClick = {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
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
