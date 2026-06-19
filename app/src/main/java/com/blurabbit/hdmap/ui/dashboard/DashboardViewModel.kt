package com.blurabbit.hdmap.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import com.blurabbit.hdmap.capture.RecordingForegroundService
import com.blurabbit.hdmap.capture.RecordingState
import com.blurabbit.hdmap.capture.TripRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    recorder: TripRecorder,
) : ViewModel() {

    val state: StateFlow<RecordingState> = recorder.state

    fun start(name: String) = RecordingForegroundService.start(context, name)
    fun stop() = RecordingForegroundService.stop(context)
}
