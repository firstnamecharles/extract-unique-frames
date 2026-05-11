package com.extractuniqueframes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExtractViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        object Idle : UiState()
        data class Processing(val progress: Float, val framesFound: Int) : UiState()
        data class Done(val result: FrameExtractor.Result) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val extractor = FrameExtractor(application)
    private var job: Job? = null

    fun startExtraction(videoUri: Uri, config: FrameExtractor.Config) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UiState.Processing(0f, 0)
            try {
                val result = extractor.extract(
                    videoUri = videoUri,
                    config = config,
                    onProgress = { progress, framesFound ->
                        _state.value = UiState.Processing(progress, framesFound)
                    }
                )
                _state.value = UiState.Done(result)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Unexpected error")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UiState.Idle
    }

    fun reset() {
        _state.value = UiState.Idle
    }
}
