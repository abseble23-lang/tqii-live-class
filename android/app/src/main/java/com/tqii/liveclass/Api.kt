package com.tqii.liveclass

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

/** Who is signed in (from the website database). role: super_admin | admin | teacher | null (student) */
data class Me(
    val profileId: String, val name: String, val role: String?, val active: Boolean,
    val teacherId: String?, val studentId: String?, val canJoinAny: Boolean
) {
    val isTeacher get() = teacherId != null
    val isStudent get() = studentId != null && teacherId == null
    val isAdmin get() = role == "super_admin" || role == "admin"
    val isSuper get() = role == "super_admin"
}

/** A teacher–student pair with the student's class schedule. */
data class Contact(
    val teacherId: String, val teacherName: String, val teacherProfile: String?,
    val studentId: String, val studentName: String, val studentProfile: String?,
    val course: String?, val classDays: List<String>, val classTime: String?, val status: String
)

data class CallInfo(
    val id: String, val teacherId: String, val teacherName: String, val studentId: String, val studentName: String,
    val callerId: String?, val status: String, val createdAt: Long, val answeredAt: Long, val endedAt: Long,
    val teacherMinutes: Int, val studentMinutes: Int, val inCall: Int, val reported: Boolean
)

data class ChatMsg(
    val id: Long, val teacherId: String, val studentId: String, val senderId: String, val body: String?,
    val filePath: String?, val fileName: String?, val fileMime: String?, val fileSize: Long, val createdAt: Long
)

data class StudentRow(
    val id: String, val name: String, val whatsapp: String?, val status: String, val profileId: String?,
    val teacherId: String?, val classDays: List<String>, val classTime: String?
)

data class Person(val id: String, val name: String, val role: String?, val phone: String?, val active: Boolean)

class ApiException(val code: Int, msg: String) : IOException(msg)

object Api {
    private val JSON = "application/json".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
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
    var me: Me? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("session_web", Context.MODE_PRIVATE)
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
                "PUT" -> b.put(rb ?: "{}".toRequestBody(JSON))
                "DELETE" -> b.delete(rb)
            }
            http.newCall(b.build()).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) throw ApiException(r.code, s)
                s
            }
        }

    // ---------- login ----------
    /** Same rule as the website: 01712345678 → +8801712345678 (Bangla digits accepted too). */
    fun normalizePhone(rawIn: String): String {
        var v = rawIn.replace(Regex("[\\s().-]"), "")
        v = v.map { c -> val i = "০১২৩৪৫৬৭৮৯".indexOf(c); if (i >= 0) ('0' + i) else c }.joinToString("")
        if (Regex("^01\\d{9}$").matches(v)) v = "+88$v"
        else if (Regex("^8801\\d{9}$").matches(v)) v = "+$v"
        else if (Regex("^00\\d+$").matches(v)) v = "+" + v.substring(2)
        else if (Regex("^\\d{10,15}$").matches(v)) v = "+$v"
        return if (Regex("^\\+\\d{8,15}$").matches(v)) v else ""
    }

    private suspend fun passwordLogin(email: String, password: String): Boolean {
        return try {
            val body = JSONObject().put("email", email).put("password", password)
            saveSession(JSONObject(raw("POST", "${Config.SUPABASE_URL}/auth/v1/token?grant_type=password", body.toString(), false)))
            true
        } catch (e: ApiException) {
            if (e.code in 400..499) false else throw e
        }
    }

    /** Email (admins) or mobile number (teachers / students). Throws ApiException(401) if wrong. */
    suspend fun login(id: String, password: String) {
        val t = id.trim()
        if (t.contains("@")) {
            if (passwordLogin(t.lowercase(), password)) return
            throw ApiException(401, "wrong")
        }
        val phone = normalizePhone(t)
        if (phone.isEmpty()) throw ApiException(401, "wrong")
        val digits = phone.drop(1)
        if (passwordLogin("t$digits@${Config.TEACHER_DOMAIN}", password)) return
        if (passwordLogin("s$digits@${Config.STUDENT_DOMAIN}", password)) return
        throw ApiException(401, "wrong")
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

    private suspend fun rpc(fn: String, args: JSONObject = JSONObject()): String = rest("POST", "rpc/$fn", args.toString())

    private suspend fun fn(name: String, body: JSONObject): JSONObject {
        token()
        return try {
            JSONObject(raw("POST", "${Config.SUPABASE_URL}/functions/v1/$name", body.toString(), true))
        } catch (e: ApiException) {
            val code = try { JSONObject(e.message ?: "").optString("error") } catch (_: Exception) { "" }
            throw ApiException(e.code, code.ifBlank { e.message ?: "error" })
        }
    }

    suspend fun logout() {
        try { Push.unregister() } catch (_: Exception) {}
        try { raw("POST", "${Config.SUPABASE_URL}/auth/v1/logout", null, true) } catch (_: Exception) {}
        clearSession()
    }

    // ---------- helpers ----------
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
    private fun JSONObject.list(k: String): List<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    // ---------- identity & contacts ----------
    suspend fun loadMe(): Me? {
        val res = rpc("live_me").trim()
        if (res.isBlank() || res == "null") { me = null; return null }
        val o = JSONObject(res)
        me = Me(
            o.getString("profile_id"), o.optString("name"), o.str("role"), o.optBoolean("active", true),
            o.str("teacher_id"), o.str("student_id"), o.optBoolean("can_join_any", false)
        )
        return me
    }

    suspend fun contacts(): List<Contact> {
        val arr = JSONArray(rpc("live_contacts"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Contact(
                o.getString("teacher_id"), o.optString("teacher_name"), o.str("teacher_profile"),
                o.getString("student_id"), o.optString("student_name"), o.str("student_profile"),
                o.str("course_slug"), o.list("class_days"), o.str("class_time")?.take(5), o.optString("student_status")
            )
        }
    }

    // ---------- calls ----------
    suspend fun startCall(teacherId: String, studentId: String): String =
        rpc("live_start_call", JSONObject().put("p_teacher", teacherId).put("p_student", studentId)).trim().trim('"')

    /** Returns {ok, status: ringing|active|ended|missed|declined|cancelled|full, role} */
    suspend fun heartbeat(callId: String): JSONObject = JSONObject(rpc("live_heartbeat", JSONObject().put("p_call", callId)))
    suspend fun leaveCall(callId: String) { rpc("live_leave", JSONObject().put("p_call", callId)) }
    suspend fun rejectCall(callId: String) { rpc("live_reject", JSONObject().put("p_call", callId)) }
    suspend fun endCall(callId: String) { rpc("live_end_call", JSONObject().put("p_call", callId)) }

    suspend fun recentCalls(limit: Int = 50): List<CallInfo> {
        val arr = JSONArray(rpc("live_recent_calls", JSONObject().put("p_limit", limit)))
        return (0 until arr.length()).map { i -> parseCall(arr.getJSONObject(i)) }
    }

    suspend fun callById(id: String): CallInfo? = recentCalls(200).firstOrNull { it.id == id }

    private fun parseCall(o: JSONObject) = CallInfo(
        o.getString("id"), o.getString("teacher_id"), o.optString("teacher_name"), o.getString("student_id"), o.optString("student_name"),
        o.str("caller_id"), o.optString("status"), o.str("created_at")?.let { parseTime(it) } ?: 0,
        o.str("answered_at")?.let { parseTime(it) } ?: 0, o.str("ended_at")?.let { parseTime(it) } ?: 0,
        o.optInt("teacher_minutes"), o.optInt("student_minutes"), o.optInt("in_call"), o.optBoolean("reported")
    )

    // ---------- chat ----------
    suspend fun messages(teacherId: String, studentId: String): List<ChatMsg> {
        val arr = JSONArray(rest("GET", "live_messages?select=*&teacher_id=eq.$teacherId&student_id=eq.$studentId&order=created_at.asc&limit=500"))
        return (0 until arr.length()).map { parseMsg(arr.getJSONObject(it)) }
    }

    fun parseMsg(o: JSONObject) = ChatMsg(
        o.getLong("id"), o.getString("teacher_id"), o.getString("student_id"), o.getString("sender_id"), o.str("body"),
        o.str("file_path"), o.str("file_name"), o.str("file_mime"), o.optLong("file_size"), parseTime(o.getString("created_at"))
    )

    suspend fun sendMessage(teacherId: String, studentId: String, body: String?, filePath: String? = null,
                            fileName: String? = null, fileMime: String? = null, fileSize: Long = 0) {
        val o = JSONObject().put("teacher_id", teacherId).put("student_id", studentId).put("sender_id", userId)
        if (body != null) o.put("body", body)
        if (filePath != null) o.put("file_path", filePath).put("file_name", fileName).put("file_mime", fileMime).put("file_size", fileSize)
        rest("POST", "live_messages", o.toString(), "return=minimal")
    }

    /** Uploads bytes to the chat storage bucket. Returns the stored path. */
    suspend fun uploadFile(teacherId: String, studentId: String, name: String, mime: String, bytes: ByteArray): String {
        token()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "file" }
        val path = "$teacherId/$studentId/${java.util.UUID.randomUUID()}-$safe"
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("${Config.SUPABASE_URL}/storage/v1/object/live-files/$path")
                .header("apikey", Config.SUPABASE_KEY).header("Authorization", "Bearer $accessToken")
                .header("x-upsert", "false")
                .post(bytes.toRequestBody(mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()))
                .build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw ApiException(r.code, r.body?.string() ?: "upload failed") }
        }
        return path
    }

    /** Temporary download link for a stored chat file. */
    suspend fun fileUrl(path: String): String {
        token()
        val res = raw("POST", "${Config.SUPABASE_URL}/storage/v1/object/sign/live-files/$path", JSONObject().put("expiresIn", 3600).toString(), true)
        val signed = JSONObject(res).getString("signedURL")
        return Config.SUPABASE_URL + "/storage/v1" + (if (signed.startsWith("/")) signed else "/$signed")
    }

    // ---------- push ----------
    suspend fun savePushToken(t: String) {
        rest("POST", "live_push_tokens?on_conflict=token",
            JSONObject().put("token", t).put("profile_id", userId).put("app", if (BuildConfig.IS_ADMIN) "admin" else "live")
                .put("updated_at", isoNow()).toString(), "resolution=merge-duplicates,return=minimal")
    }

    suspend fun deletePushToken(t: String) { rest("DELETE", "live_push_tokens?token=eq.${enc(t)}") }

    // ---------- admin ----------
    suspend fun students(): List<StudentRow> {
        val arr = JSONArray(rest("GET", "students?select=id,full_name,whatsapp,status,profile_id,teacher_id,class_days,class_time&order=full_name.asc"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StudentRow(o.getString("id"), o.optString("full_name"), o.str("whatsapp"), o.optString("status"), o.str("profile_id"),
                o.str("teacher_id"), o.list("class_days"), o.str("class_time")?.take(5))
        }
    }

    suspend fun people(): List<Person> {
        val arr = JSONArray(rest("GET", "profiles?select=id,full_name,role,phone,is_active&order=full_name.asc"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Person(o.getString("id"), o.optString("full_name"), o.str("role"), o.str("phone"), o.optBoolean("is_active", true))
        }
    }

    suspend fun accessList(): Set<String> {
        val arr = JSONArray(rest("GET", "live_access?select=profile_id"))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("profile_id") }.toSet()
    }

    suspend fun studentLogin(studentId: String, phone: String, password: String) =
        fn("live-admin", JSONObject().put("action", "student_login").put("student_id", studentId).put("phone", phone).put("password", password))

    suspend fun setAccess(profileId: String, allow: Boolean) =
        fn("live-admin", JSONObject().put("action", "set_access").put("profile_id", profileId).put("allow", allow))

    suspend fun changeMyPassword(pw: String) {
        token()
        raw("PUT", "${Config.SUPABASE_URL}/auth/v1/user", JSONObject().put("password", pw).toString(), true)
    }

    // ---------- misc ----------
    suspend fun insertLogs(json: String) { rest("POST", "live_logs", json, "return=minimal") }

    /** STUN + TURN servers from the "live-ice" edge function (falls back to STUN only). */
    suspend fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf<PeerConnection.IceServer>()
        try {
            val servers = fn("live-ice", JSONObject()).getJSONArray("iceServers")
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
        if (list.isEmpty()) list += PeerConnection.IceServer.builder(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")).createIceServer()
        return list
    }

    fun parseTime(s: String): Long {
        val clean = s.replace(Regex("\\.(\\d+)"), "").replace("Z", "+00:00")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return try { fmt.parse(clean)?.time ?: 0 } catch (_: Exception) { 0 }
    }

    fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
