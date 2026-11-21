package com.tqii.liveclass

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Incoming call data, from a push or from the realtime inbox. */
data class IncomingCall(
    val callId: String, val teacherId: String, val studentId: String,
    val callerName: String, val teacherName: String, val studentName: String
)

object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ready = false
    private val shown = mutableSetOf<String>()

    fun init(ctx: Context) {
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(
                    ctx, FirebaseOptions.Builder()
                        .setApplicationId("1:594229143906:android:e1d58a5d176fe5ed69a43b")
                        .setApiKey("AIzaSyAKVaHxamqL-DlOEn6gvyNAYhWu24fu2lw")
                        .setProjectId("tqii-live")
                        .setGcmSenderId("594229143906")
                        .setStorageBucket("tqii-live.firebasestorage.app")
                        .build()
                )
            }
            ready = true
        } catch (e: Exception) {
            Log.w("Push", "firebase init", e)
        }
    }

    /** Send this phone's push token to the server (call after login and on app start). */
    fun register() {
        if (!ready || !Api.loggedIn) return
        scope.launch {
            try {
                val t = FirebaseMessaging.getInstance().token.await()
                Api.savePushToken(t)
                Diag.log("push token saved")
            } catch (e: Exception) { Log.w("Push", "register", e); Diag.log("push register failed: ${e.message}") }
        }
    }

    suspend fun unregister() {
        if (!ready) return
        try {
            val t = FirebaseMessaging.getInstance().token.await()
            Api.deletePushToken(t)
        } catch (_: Exception) {}
    }

    private fun ringId(callId: String) = 7000 + (callId.hashCode() and 0xFFF)

    /** Shows the ringing, full-screen incoming call (like WhatsApp). */
    fun showIncoming(ctx: Context, c: IncomingCall) {
        synchronized(shown) { if (!shown.add(c.callId)) return }
        val me = Api.me
        val other = when {
            me?.studentId == c.studentId -> c.teacherName
            me?.teacherId == c.teacherId -> c.studentName
            else -> c.callerName
        }.ifBlank { c.callerName }
        fun intent(action: String) = Intent(ctx, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("action", action).putExtra("callId", c.callId).putExtra("teacherId", c.teacherId)
            .putExtra("studentId", c.studentId).putExtra("name", other)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val full = PendingIntent.getActivity(ctx, ringId(c.callId), intent("show"), flags)
        val accept = PendingIntent.getActivity(ctx, ringId(c.callId) + 1, intent("accept"), flags)
        val decline = PendingIntent.getActivity(ctx, ringId(c.callId) + 2, intent("decline"), flags)

        val n = Notification.Builder(ctx, Config.RING_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(other)
            .setContentText(L.t("incomingCall"))
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(50_000)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, L.t("decline"), decline).build())
            .addAction(Notification.Action.Builder(null, L.t("accept"), accept).build())
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until answered/cancelled
        try { ctx.getSystemService(NotificationManager::class.java).notify(ringId(c.callId), n) } catch (e: SecurityException) { Log.w("Push", "notify", e) }
        // if the app is in front, open the call screen right away too
        if (App.foreground) try { ctx.startActivity(intent("show")) } catch (_: Exception) {}
    }

    fun cancelRing(ctx: Context, callId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ringId(callId))
        IncomingCallActivity.closeFor(callId)
    }

    fun showMessage(ctx: Context, data: Map<String, String>) {
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("chatTeacher", data["teacher_id"]).putExtra("chatStudent", data["student_id"]),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, Config.MSG_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(data["sender_name"] ?: "TQII")
            .setContentText(data["text"] ?: "")
            .setStyle(Notification.BigTextStyle().bigText(data["text"] ?: ""))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(9000 + ((data["teacher_id"] + data["student_id"]).hashCode() and 0xFFF), n)
        } catch (_: SecurityException) {}
    }

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    @Suppress("unused")
    val ringtoneUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
}

/** Receives pushes from Firebase (works even when the app is closed). */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!Api.loggedIn) return
        CoroutineScope(Dispatchers.IO).launch { try { Api.savePushToken(token) } catch (_: Exception) {} }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "call" -> if (d["status"] == "ringing") Push.showIncoming(
                applicationContext, IncomingCall(
                    d["call_id"] ?: return, d["teacher_id"] ?: return, d["student_id"] ?: return,
                    d["caller_name"] ?: "", d["teacher_name"] ?: "", d["student_name"] ?: ""
                )
            )
            "call_end" -> d["call_id"]?.let { Push.cancelRing(applicationContext, it) }
            "message" -> Push.showMessage(applicationContext, d)
        }
    }
}
