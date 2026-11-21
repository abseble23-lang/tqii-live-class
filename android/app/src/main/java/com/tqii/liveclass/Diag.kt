package com.tqii.liveclass

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Sends call diagnostics to the call_logs table so problems can be investigated remotely. */
object Diag {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buf = mutableListOf<String>()
    private var classId: String? = null
    private var version = 0
    private var running = false

    fun start(ctx: Context, cid: String) {
        classId = cid
        version = try { Updater.currentVersion(ctx) } catch (_: Exception) { 0 }
        if (running) return
        running = true
        scope.launch { while (true) { delay(3000); flush() } }
    }

    fun log(msg: String) {
        Log.i("Diag", msg)
        synchronized(buf) { if (buf.size < 500) buf += msg }
    }

    suspend fun flush() {
        val lines = synchronized(buf) { val l = buf.toList(); buf.clear(); l }
        if (lines.isEmpty()) return
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("call_id", classId).put("app_version", version).put("msg", it.take(3900))) }
        try { Api.insertLogs(arr.toString()) } catch (e: Exception) { Log.w("Diag", "flush failed", e) }
    }
}
