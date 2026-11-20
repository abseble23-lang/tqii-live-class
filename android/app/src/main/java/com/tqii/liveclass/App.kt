package com.tqii.liveclass

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Config {
    const val SUPABASE_URL = "https://vxhmjftlxmpreolktwya.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_YoTBdksIVKVb0IB5yGzrtw_nTHYiR8h"
    const val USER_DOMAIN = "users.tqiilive.app"
    const val CALL_CHANNEL = "live_call"
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.init(this)
        L.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Config.CALL_CHANNEL, "Live class", NotificationManager.IMPORTANCE_LOW)
        )
    }
}

/** Simple bilingual strings (Bangla / English). */
object L {
    var lang by mutableStateOf("bn")
        private set
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx
        lang = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "bn") ?: "bn"
    }

    fun set(l: String) {
        lang = l
        appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", l).apply()
    }

    fun t(key: String): String = (if (lang == "bn") bn[key] else en[key]) ?: key

    private val bn = mapOf(
        "app" to "TQII লাইভ ক্লাস",
        "username" to "ইউজার আইডি",
        "password" to "পাসওয়ার্ড",
        "login" to "লগইন",
        "logout" to "লগআউট",
        "wrongLogin" to "ইউজার আইডি বা পাসওয়ার্ড ভুল",
        "netError" to "ইন্টারনেট সংযোগ পরীক্ষা করুন",
        "adminUseWeb" to "অ্যাডমিন অ্যাকাউন্ট দিয়ে অ্যাডমিন প্যানেল ব্যবহার করুন",
        "disabled" to "আপনার অ্যাকাউন্ট বন্ধ আছে",
        "hello" to "আসসালামু আলাইকুম",
        "myClasses" to "আমার ক্লাস",
        "noClasses" to "এখনো কোনো ক্লাস নেই",
        "teacher" to "শিক্ষক",
        "student" to "ছাত্র",
        "students" to "ছাত্র-ছাত্রী",
        "live" to "লাইভ",
        "scheduled" to "নির্ধারিত",
        "ended" to "শেষ",
        "startClass" to "ক্লাস শুরু করুন",
        "join" to "ক্লাসে যোগ দিন",
        "rejoin" to "আবার যোগ দিন",
        "waiting" to "শিক্ষক এখনো ক্লাস শুরু করেননি",
        "chat" to "চ্যাট",
        "typeMessage" to "মেসেজ লিখুন...",
        "noMessages" to "এখনো কোনো মেসেজ নেই",
        "minutes" to "মিনিট",
        "refresh" to "রিফ্রেশ",
        "mic" to "মাইক",
        "camera" to "ক্যামেরা",
        "flip" to "ক্যামেরা ঘোরান",
        "share" to "স্ক্রিন শেয়ার",
        "stopShare" to "শেয়ার বন্ধ",
        "background" to "ব্যাকগ্রাউন্ড",
        "leave" to "বের হন",
        "endClass" to "ক্লাস শেষ করুন",
        "endForAll" to "সবার জন্য ক্লাস শেষ করবেন?",
        "yes" to "হ্যাঁ",
        "no" to "না",
        "connecting" to "সংযোগ হচ্ছে...",
        "waitingOthers" to "অন্যদের জন্য অপেক্ষা করা হচ্ছে",
        "classEnded" to "ক্লাস শেষ হয়েছে",
        "you" to "আপনি",
        "sharingScreen" to "স্ক্রিন শেয়ার করছেন",
        "bgNone" to "কোনোটি না",
        "bgBlur" to "ঝাপসা",
        "bgOffice" to "অফিস",
        "bgLibrary" to "লাইব্রেরি",
        "bgStudio" to "স্টুডিও",
        "bgBoard" to "বোর্ড",
        "permsNeeded" to "ক্লাসের জন্য ক্যামেরা ও মাইক্রোফোনের অনুমতি দরকার",
        "close" to "বন্ধ",
        "send" to "পাঠান",
        "today" to "আজ",
        "reconnecting" to "পুনরায় সংযোগ হচ্ছে..."
    )

    private val en = mapOf(
        "app" to "TQII Live Class",
        "username" to "User ID",
        "password" to "Password",
        "login" to "Log in",
        "logout" to "Log out",
        "wrongLogin" to "Wrong user ID or password",
        "netError" to "Check your internet connection",
        "adminUseWeb" to "Admin accounts should use the admin panel",
        "disabled" to "Your account is disabled",
        "hello" to "Welcome",
        "myClasses" to "My classes",
        "noClasses" to "No classes yet",
        "teacher" to "Teacher",
        "student" to "Student",
        "students" to "Students",
        "live" to "Live",
        "scheduled" to "Scheduled",
        "ended" to "Ended",
        "startClass" to "Start class",
        "join" to "Join class",
        "rejoin" to "Rejoin",
        "waiting" to "The teacher hasn't started yet",
        "chat" to "Chat",
        "typeMessage" to "Type a message...",
        "noMessages" to "No messages yet",
        "minutes" to "min",
        "refresh" to "Refresh",
        "mic" to "Mic",
        "camera" to "Camera",
        "flip" to "Flip camera",
        "share" to "Share screen",
        "stopShare" to "Stop sharing",
        "background" to "Background",
        "leave" to "Leave",
        "endClass" to "End class",
        "endForAll" to "End the class for everyone?",
        "yes" to "Yes",
        "no" to "No",
        "connecting" to "Connecting...",
        "waitingOthers" to "Waiting for others to join",
        "classEnded" to "The class has ended",
        "you" to "You",
        "sharingScreen" to "You are sharing your screen",
        "bgNone" to "None",
        "bgBlur" to "Blur",
        "bgOffice" to "Office",
        "bgLibrary" to "Library",
        "bgStudio" to "Studio",
        "bgBoard" to "Board",
        "permsNeeded" to "Camera and microphone permission is needed for class",
        "close" to "Close",
        "send" to "Send",
        "today" to "Today",
        "reconnecting" to "Reconnecting..."
    )
}
