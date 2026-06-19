package com.blurabbit.hdmap.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blurabbit.hdmap.domain.model.Trip
import com.blurabbit.hdmap.domain.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(
    tripRepository: TripRepository,
) : ViewModel() {

    val trips: StateFlow<List<Trip>> = tripRepository.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
