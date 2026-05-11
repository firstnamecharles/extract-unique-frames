package com.extractuniqueframes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExtractViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        object Idle : UiState()
        data class Capturing(
            val videoUri: Uri,
            val videoDurationMs: Long = 0L,
            val capturedUris: List<Uri> = emptyList(),
            val capturedTimestampsMs: List<Long> = emptyList()
        ) : UiState()
        data class Done(val result: FrameExtractor.Result) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val extractor = FrameExtractor(application)

    fun startCapturing(videoUri: Uri, durationMs: Long) {
        _state.value = UiState.Capturing(videoUri = videoUri, videoDurationMs = durationMs)
    }

    fun captureFrame(positionMs: Long) {
        val current = _state.value as? UiState.Capturing ?: return
        viewModelScope.launch {
            try {
                val uri = extractor.captureFrame(current.videoUri, positionMs)
                val s = _state.value as? UiState.Capturing ?: return@launch
                _state.value = s.copy(
                    capturedUris = s.capturedUris + uri,
                    capturedTimestampsMs = s.capturedTimestampsMs + positionMs
                )
            } catch (_: Exception) { /* silent — don't crash on failed capture */ }
        }
    }

    fun finish() {
        val s = _state.value as? UiState.Capturing ?: return
        _state.value = UiState.Done(
            FrameExtractor.Result(
                savedFrames = s.capturedUris,
                frameTimestampsMs = s.capturedTimestampsMs,
                videoDurationMs = s.videoDurationMs
            )
        )
    }

    fun reset() { _state.value = UiState.Idle }
}
