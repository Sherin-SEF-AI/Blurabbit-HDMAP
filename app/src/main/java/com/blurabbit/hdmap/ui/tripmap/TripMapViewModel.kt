package com.blurabbit.hdmap.ui.tripmap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.repository.MapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TripMapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mapRepository: MapRepository,
) : ViewModel() {
    private val tripId: String = savedStateHandle["tripId"] ?: ""

    val map: StateFlow<HdMap?> = mapRepository.observeMap(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
