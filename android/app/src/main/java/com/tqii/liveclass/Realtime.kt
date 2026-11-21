package com.tqii.liveclass

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Which database changes a channel listens to. filter like "id=eq.<uuid>" (optional). */
data class PgListen(val table: String, val event: String = "*", val filter: String? = null)

/**
 * Minimal Supabase Realtime client (Phoenix protocol) for ONE private channel.
 * Supports broadcast, presence and postgres_changes. Callbacks arrive on the main thread.
 */
class RtChannel(
    private val channelTopic: String,
    private val presence: JSONObject?,                 // null = don't track presence
    private val listens: List<PgListen>,
    private val listener: Listener
) {
    interface Listener {
        fun onJoined() {}
        fun onBroadcast(event: String, payload: JSONObject) {}
        fun onPresence(online: Map<String, JSONObject>) {}
        fun onChange(table: String, type: String, record: JSONObject) {}
        fun onConnection(connected: Boolean) {}
    }

    private val topic = "realtime:$channelTopic"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ref = AtomicInteger(1)
    private var joinRef = "1"
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    private var joined = false
    private var retry = 0
    private val online = linkedMapOf<String, JSONObject>()

    fun connect() {
        closed = false
        connectSocket()
        startHeartbeat()
    }

    private fun onDrop(sock: WebSocket) {
        if (sock !== ws || closed) return
        ws = null
        joined = false
        main.post { listener.onConnection(false) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (closed) return
        val wait = minOf(10_000L, 1000L * (1 shl minOf(retry, 4)))
        retry++
        scope.launch { delay(wait); if (!closed) connectSocket() }
    }

    private fun connectSocket() {
        scope.launch {
            val token = try { Api.token() } catch (e: Exception) { scheduleReconnect(); return@launch }
            val url = Config.SUPABASE_URL.replace("https://", "wss://") +
                "/realtime/v1/websocket?apikey=${Config.SUPABASE_KEY}&vsn=1.0.0"
            ws = Api.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { retry = 0; join(webSocket, token) }
                override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) handle(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { Log.w("Realtime", "failure", t); onDrop(webSocket) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDrop(webSocket)
            })
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            var tick = 0
            while (isActive && !closed) {
                delay(25_000)
                tick++
                ws?.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", ref.getAndIncrement().toString()).toString())
                if (tick % 48 == 0 && joined) {
                    try { push("access_token", JSONObject().put("access_token", Api.token())) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun join(sock: WebSocket, token: String) {
        joinRef = ref.getAndIncrement().toString()
        val pg = JSONArray()
        listens.forEach { l ->
            val o = JSONObject().put("event", l.event).put("schema", "public").put("table", l.table)
            if (l.filter != null) o.put("filter", l.filter)
            pg.put(o)
        }
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", Api.userId ?: "").put("enabled", presence != null))
            .put("postgres_changes", pg)
            .put("private", true)
        val payload = JSONObject().put("config", config).put("access_token", token)
        sock.send(JSONObject().put("topic", topic).put("event", "phx_join").put("payload", payload)
            .put("ref", joinRef).put("join_ref", joinRef).toString())
    }

    private fun push(event: String, payload: JSONObject) {
        ws?.send(JSONObject().put("topic", topic).put("event", event).put("payload", payload)
            .put("ref", ref.getAndIncrement().toString()).put("join_ref", joinRef).toString())
    }

    fun broadcast(event: String, payload: JSONObject) {
        push("broadcast", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload))
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        if (m.optString("topic") != topic) return
        val payload = m.optJSONObject("payload") ?: JSONObject()
        when (m.optString("event")) {
            "phx_reply" -> {
                if (m.optString("ref") == joinRef) {
                    if (payload.optString("status") == "ok") {
                        joined = true
                        presence?.let { push("presence", JSONObject().put("type", "presence").put("event", "track").put("payload", it)) }
                        main.post { listener.onConnection(true); listener.onJoined() }
                    } else {
                        Log.w("Realtime", "join failed: $text")
                        Diag.log("realtime join failed $channelTopic: ${text.take(300)}")
                        ws?.let { it.cancel(); onDrop(it) }
                    }
                }
            }
            "phx_error", "phx_close" -> ws?.let { it.cancel(); onDrop(it) }
            "broadcast" -> {
                val ev = payload.optString("event")
                val p = payload.optJSONObject("payload") ?: JSONObject()
                main.post { listener.onBroadcast(ev, p) }
            }
            "presence_state" -> {
                synchronized(online) {
                    online.clear()
                    payload.keys().forEach { k -> metaOf(payload.optJSONObject(k))?.let { online[k] = it } }
                }
                emitPresence()
            }
            "presence_diff" -> {
                synchronized(online) {
                    payload.optJSONObject("leaves")?.let { lv -> lv.keys().forEach { online.remove(it) } }
                    payload.optJSONObject("joins")?.let { jn -> jn.keys().forEach { k -> metaOf(jn.optJSONObject(k))?.let { online[k] = it } } }
                }
                emitPresence()
            }
            "postgres_changes" -> {
                val data = payload.optJSONObject("data") ?: return
                val rec = data.optJSONObject("record") ?: return
                val table = data.optString("table"); val type = data.optString("type")
                main.post { listener.onChange(table, type, rec) }
            }
        }
    }

    private fun metaOf(o: JSONObject?): JSONObject? {
        val metas = o?.optJSONArray("metas") ?: return null
        return if (metas.length() > 0) metas.getJSONObject(metas.length() - 1) else null
    }

    private fun emitPresence() {
        val snap = synchronized(online) { LinkedHashMap(online) }
        main.post { listener.onPresence(snap) }
    }

    fun close() {
        closed = true
        try {
            if (presence != null) push("presence", JSONObject().put("type", "presence").put("event", "untrack"))
            push("phx_leave", JSONObject())
            ws?.close(1000, "bye")
        } catch (_: Exception) {}
        scope.cancel()
    }
}
