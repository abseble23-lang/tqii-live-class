package com.tqii.liveclass

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Full-screen "incoming call" screen with Accept / Decline, shown over the lock screen. */
class IncomingCallActivity : ComponentActivity() {
    companion object {
        private var current: WeakReference<IncomingCallActivity>? = null
        fun closeFor(callId: String) {
            current?.get()?.let { a -> if (a.callId == callId) a.runOnUiThread { a.finish() } }
        }
    }

    private var callId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        callId = i.getStringExtra("callId") ?: return finish()
        current = WeakReference(this)
        val teacherId = i.getStringExtra("teacherId") ?: return finish()
        val studentId = i.getStringExtra("studentId") ?: return finish()
        val name = i.getStringExtra("name") ?: ""
        when (i.getStringExtra("action")) {
            "accept" -> { accept(teacherId, studentId, name); return }
            "decline" -> { decline(); return }
        }
        setContent {
            AppTheme {
                IncomingScreen(name, onAccept = { accept(teacherId, studentId, name) }, onDecline = { decline() })
            }
        }
    }

    private fun accept(teacherId: String, studentId: String, name: String) {
        Push.cancelRing(this, callId)
        // ask the keyguard to unlock so the call screen can show
        if (Build.VERSION.SDK_INT >= 26) getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        startActivity(
            Intent(this, CallActivity::class.java)
                .putExtra("callId", callId).putExtra("teacherId", teacherId).putExtra("studentId", studentId)
                .putExtra("title", name).putExtra("outgoing", false)
        )
        finish()
    }

    private fun decline() {
        val id = callId
        Push.cancelRing(this, id)
        CoroutineScope(Dispatchers.IO).launch { try { Api.rejectCall(id) } catch (_: Exception) {} }
        finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}

@Composable
fun IncomingScreen(name: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Column(
        Modifier.fillMaxSize().background(NightGradient).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Logo(70.dp)
        Spacer(Modifier.height(8.dp))
        Text(L.t("incomingCall"), color = GoldLight, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.scale(s).size(140.dp).clip(CircleShape).background(GoldGradient), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), color = Night, fontSize = 60.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(name, color = Ivory, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("TQII Live Class", color = Color(0x99FFFFFF), fontSize = 14.sp)
        Spacer(Modifier.weight(1.2f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(LiveRed).clickable(onClick = onDecline), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(8.dp)); Text(L.t("decline"), color = Ivory)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.scale(s).size(76.dp).clip(CircleShape).background(Success).clickable(onClick = onAccept), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(8.dp)); Text(L.t("accept"), color = Ivory)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
