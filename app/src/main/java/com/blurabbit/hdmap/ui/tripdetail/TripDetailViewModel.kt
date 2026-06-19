package com.blurabbit.hdmap.ui.tripdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.model.Trip
import com.blurabbit.hdmap.domain.repository.MapRepository
import com.blurabbit.hdmap.domain.repository.TripRepository
import com.blurabbit.hdmap.export.ExportFormat
import com.blurabbit.hdmap.upload.UploadScheduler
import com.blurabbit.hdmap.usecase.ExportTripUseCase
import com.blurabbit.hdmap.usecase.ProcessTripUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    tripRepository: TripRepository,
    mapRepository: MapRepository,
    private val processTrip: ProcessTripUseCase,
    private val exportTrip: ExportTripUseCase,
    private val uploadScheduler: UploadScheduler,
) : ViewModel() {

    val tripId: String = savedStateHandle["tripId"] ?: ""

    val trip: StateFlow<Trip?> = tripRepository.observeTrips()
        .map { list -> list.firstOrNull { it.id == tripId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val map: StateFlow<HdMap?> = mapRepository.observeMap(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val exportFormats: List<ExportFormat> = ExportFormat.entries

    fun generate() {
        viewModelScope.launch {
            _busy.value = true
            val result = processTrip(tripId)
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { "Generated HD map: ${it.featureCount} features" },
                onFailure = { "Generation failed: ${it.message}" },
            )
        }
    }

    fun export(format: ExportFormat) {
        viewModelScope.launch {
            val file = exportTrip(tripId, format)
            _message.value = if (file != null) "Saved ${file.name}" else "Generate the map first"
        }
    }

    fun upload() {
        uploadScheduler.enqueue(tripId)
        _message.value = "Upload queued"
    }

    fun clearMessage() { _message.value = null }
}
