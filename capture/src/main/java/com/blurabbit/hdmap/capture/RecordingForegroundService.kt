package com.blurabbit.hdmap.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the recording pipeline as a foreground service so capture survives the app going to the
 * background. Extends [LifecycleService] to supply CameraX a [androidx.lifecycle.LifecycleOwner].
 * Declares camera + location + dataSync foreground-service types (required on Android 14+).
 */
@AndroidEntryPoint
class RecordingForegroundService : LifecycleService() {

    @Inject lateinit var recorder: TripRecorder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lifecycleScope.launch {
            recorder.state.collectLatest { state ->
                notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                recorder.start(this, intent.getStringExtra(EXTRA_NAME) ?: "")
            }
            ACTION_PAUSE -> recorder.pause()
            ACTION_RESUME -> recorder.resume(this)
            ACTION_STOP -> lifecycleScope.launch {
                recorder.stop()
                ServiceCompat.stopForeground(this@RecordingForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(recorder.state.value), type)
    }

    private fun buildNotification(state: RecordingState): Notification {
        val phase = when (state.phase) {
            RecordingPhase.RECORDING -> "● RECORDING"
            RecordingPhase.PAUSED -> "❚❚ PAUSED"
            RecordingPhase.STOPPING -> "Finalizing…"
            RecordingPhase.IDLE -> "Idle"
        }
        val km = state.distanceMeters / 1000.0
        val text = "%s · %.2f km · %d sats · %d frames".format(
            phase, km, state.satellitesUsed, state.frameCount,
        )
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Blurabbit HD Map Collector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "HD Map Recording", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active sensor + camera capture" }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "hdmap_recording"
        private const val NOTIFICATION_ID = 0x4242
        const val ACTION_START = "com.blurabbit.hdmap.action.START"
        const val ACTION_PAUSE = "com.blurabbit.hdmap.action.PAUSE"
        const val ACTION_RESUME = "com.blurabbit.hdmap.action.RESUME"
        const val ACTION_STOP = "com.blurabbit.hdmap.action.STOP"
        const val EXTRA_NAME = "extra_name"

        fun start(context: Context, name: String) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NAME, name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingForegroundService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
