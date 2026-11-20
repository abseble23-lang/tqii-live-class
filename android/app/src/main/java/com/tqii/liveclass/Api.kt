package com.tqii.liveclass

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class Profile(val id: String, val username: String, val fullName: String, val role: String, val active: Boolean)
data class ClassItem(
    val id: String, val title: String, val subject: String?, val teacherId: String?,
    val scheduledAt: Long, val durationMin: Int, val status: String
)
data class ChatMsg(val id: Long, val classId: String, val senderId: String, val body: String, val createdAt: Long)

class ApiException(val code: Int, msg: String) : IOException(msg)

object Api {
    private val JSON = "application/json".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences
    private val refreshLock = Mutex()

    var accessToken: String? = null
        private set
    private var refreshToken: String? = null
    private var expiresAt: Long = 0
    var userId: String? = null
        private set
    var me: Profile? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
        accessToken = prefs.getString("at", null)
        refreshToken = prefs.getString("rt", null)
        expiresAt = prefs.getLong("exp", 0)
        userId = prefs.getString("uid", null)
    }

    val loggedIn get() = refreshToken != null && userId != null

    private fun saveSession(j: JSONObject) {
        accessToken = j.getString("access_token")
        refreshToken = j.getString("refresh_token")
        expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        userId = j.getJSONObject("user").getString("id")
        prefs.edit().putString("at", accessToken).putString("rt", refreshToken)
            .putLong("exp", expiresAt).putString("uid", userId).apply()
    }

    fun clearSession() {
        accessToken = null; refreshToken = null; userId = null; me = null; expiresAt = 0
        prefs.edit().clear().apply()
    }

    private suspend fun raw(method: String, url: String, body: String?, auth: Boolean, prefer: String? = null): String =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url).header("apikey", Config.SUPABASE_KEY)
            if (auth && accessToken != null) b.header("Authorization", "Bearer $accessToken")
            if (prefer != null) b.header("Prefer", prefer)
            val rb = body?.toRequestBody(JSON)
            when (method) {
                "GET" -> b.get()
                "POST" -> b.post(rb ?: "{}".toRequestBody(JSON))
                "PATCH" -> b.patch(rb ?: "{}".toRequestBody(JSON))
                "DELETE" -> b.delete(rb)
            }
            http.newCall(b.build()).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) throw ApiException(r.code, s)
                s
            }
        }

    suspend fun login(username: String, password: String) {
        val body = JSONObject().put("email", "${username.trim().lowercase()}@${Config.USER_DOMAIN}").put("password", password)
        val res = raw("POST", "${Config.SUPABASE_URL}/auth/v1/token?grant_type=password", body.toString(), false)
        saveSession(JSONObject(res))
    }

    /** Returns a valid access token, refreshing if it expires within 5 minutes. */
    suspend fun token(force: Boolean = false): String = refreshLock.withLock {
        if (!force && accessToken != null && System.currentTimeMillis() < expiresAt - 5 * 60_000) return accessToken!!
        val rt = refreshToken ?: throw ApiException(401, "no session")
        try {
            val res = raw("POST", "${Config.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", rt).toString(), false)
            saveSession(JSONObject(res))
        } catch (e: ApiException) {
            if (e.code in 400..499) clearSession()
            throw e
        }
        accessToken!!
    }

    private suspend fun rest(method: String, path: String, body: String? = null, prefer: String? = null): String {
        token()
        return try {
            raw(method, "${Config.SUPABASE_URL}/rest/v1/$path", body, true, prefer)
        } catch (e: ApiException) {
            if (e.code == 401) { token(force = true); raw(method, "${Config.SUPABASE_URL}/rest/v1/$path", body, true, prefer) } else throw e
        }
    }

    suspend fun logout() {
        try { raw("POST", "${Config.SUPABASE_URL}/auth/v1/logout", null, true) } catch (_: Exception) {}
        clearSession()
    }

    // ---------- data ----------
    private fun parseProfile(o: JSONObject) = Profile(
        o.getString("id"), o.optString("username"), o.optString("full_name"), o.optString("role"), o.optBoolean("active", true)
    )

    suspend fun loadMe(): Profile? {
        val arr = JSONArray(rest("GET", "profiles?select=*&id=eq.$userId"))
        me = if (arr.length() > 0) parseProfile(arr.getJSONObject(0)) else null
        return me
    }

    suspend fun profiles(): Map<String, Profile> {
        val arr = JSONArray(rest("GET", "profiles?select=id,username,full_name,role,active"))
        return (0 until arr.length()).map { parseProfile(arr.getJSONObject(it)) }.associateBy { it.id }
    }

    suspend fun classes(): List<ClassItem> {
        val arr = JSONArray(rest("GET", "classes?select=*&order=scheduled_at.asc"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ClassItem(
                o.getString("id"), o.getString("title"), o.optString("subject").takeIf { it.isNotBlank() && it != "null" },
                o.optString("teacher_id").takeIf { it.isNotBlank() && it != "null" },
                parseTime(o.getString("scheduled_at")), o.optInt("duration_min", 60), o.getString("status")
            )
        }
    }

    suspend fun classById(id: String): ClassItem? = classes().firstOrNull { it.id == id }

    suspend fun roster(): Map<String, List<String>> {
        val arr = JSONArray(rest("GET", "class_students?select=class_id,student_id"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .groupBy({ it.getString("class_id") }, { it.getString("student_id") })
    }

    suspend fun setStatus(classId: String, status: String) {
        val o = JSONObject().put("status", status)
        if (status == "live") o.put("started_at", isoNow())
        if (status == "ended") o.put("ended_at", isoNow())
        rest("PATCH", "classes?id=eq.$classId", o.toString())
    }

    suspend fun messages(classId: String): List<ChatMsg> {
        val arr = JSONArray(rest("GET", "messages?select=*&class_id=eq.$classId&order=created_at.asc&limit=300"))
        return (0 until arr.length()).map { parseMsg(arr.getJSONObject(it)) }
    }

    fun parseMsg(o: JSONObject) = ChatMsg(
        o.getLong("id"), o.getString("class_id"), o.getString("sender_id"), o.getString("body"), parseTime(o.getString("created_at"))
    )

    suspend fun sendMessage(classId: String, body: String) {
        rest("POST", "messages", JSONObject().put("class_id", classId).put("sender_id", userId).put("body", body).toString())
    }

    /** STUN + TURN servers from the "ice-servers" edge function (falls back to STUN only). */
    suspend fun insertLogs(json: String) {
        rest("POST", "call_logs", json, "return=minimal")
    }

    suspend fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf<PeerConnection.IceServer>()
        try {
            token()
            val res = raw("POST", "${Config.SUPABASE_URL}/functions/v1/ice-servers", "{}", true)
            val servers = JSONObject(res).getJSONArray("iceServers")
            for (i in 0 until servers.length()) {
                val s = servers.getJSONObject(i)
                val urlsAny = s.get("urls")
                val urls = if (urlsAny is JSONArray) (0 until urlsAny.length()).map { urlsAny.getString(it) } else listOf(urlsAny.toString())
                val b = PeerConnection.IceServer.builder(urls)
                if (s.has("username")) b.setUsername(s.getString("username"))
                if (s.has("credential")) b.setPassword(s.getString("credential"))
                list += b.createIceServer()
            }
        } catch (e: Exception) {
            android.util.Log.w("Api", "ice servers", e)
        }
        if (list.isEmpty()) {
            list += PeerConnection.IceServer.builder(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")).createIceServer()
        }
        return list
    }

    // ---------- time helpers ----------
    fun parseTime(s: String): Long {
        // e.g. 2026-11-21T10:00:00+00:00 or 2026-11-21T10:00:00.123456+00:00
        val clean = s.replace(Regex("\\.(\\d+)"), "").replace("Z", "+00:00")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return try { fmt.parse(clean)?.time ?: 0 } catch (_: Exception) { 0 }
    }

    fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    @Suppress("unused")
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
