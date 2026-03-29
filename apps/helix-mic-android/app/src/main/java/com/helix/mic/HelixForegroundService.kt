package com.helix.mic

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep BLE + WebSocket transcription alive
 * when the app is minimized. Thin wrapper — all logic stays in existing managers.
 */
class HelixForegroundService : Service() {

    companion object {
        const val TAG = "HelixFgService"
        const val CHANNEL_ID = "helix_mic_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.helix.mic.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HelixForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HelixForegroundService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startTimeMs = 0L
    private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            // Send broadcast so MainActivity can handle stopping recording
            sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
            stopSelf()
            return START_NOT_STICKY
        }

        startTimeMs = System.currentTimeMillis()
        running = true

        val notification = buildNotification("Recording - 0:00")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.post(tickRunnable)
        Log.d(TAG, "Foreground service started")

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tickRunnable)
        Log.d(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Helix Mic Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Helix Mic is recording"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HelixForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Helix Mic")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP",
                stopIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        val timeStr = "%d:%02d".format(minutes, seconds)

        val notification = buildNotification("Recording - $timeStr")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
