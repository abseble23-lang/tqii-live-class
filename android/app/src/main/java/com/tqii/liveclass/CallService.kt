package com.tqii.liveclass

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Keeps camera / microphone (and screen share) alive while a class is running. */
class CallService : Service() {
    companion object {
        @Volatile var instance: CallService? = null
        private const val ID = 42

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, CallService::class.java).putExtra("title", title)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CallService::class.java)) }
    }

    private var title = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        title = intent?.getStringExtra("title") ?: title
        goForeground(withProjection = false)
        return START_NOT_STICKY
    }

    fun promoteForProjection() = goForeground(withProjection = true)

    private fun goForeground(withProjection: Boolean) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, Config.CALL_CHANNEL)
            .setContentTitle(title.ifBlank { L.t("app") })
            .setContentText(L.t("live"))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(ID, n, type)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val type = if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
                startForeground(ID, n, type)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            Log.w("CallService", "startForeground failed", e)
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
