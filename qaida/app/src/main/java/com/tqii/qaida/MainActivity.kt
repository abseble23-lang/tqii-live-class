package com.tqii.qaida

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * TQII Qaida: the Qaida book (web app in assets/web) inside a WebView.
 * The page's speech requests are sent to Android's TextToSpeech so Bangla and English
 * voices work like a native app, offline once the voices are installed.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private var askedForBangla = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#003B32")
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(Color.parseColor("#003B32"))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "AndroidTTS")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null
                return serve(u.path ?: "/")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                // open outside links (WhatsApp, website...) in other apps
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: ActivityNotFoundException) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) { pushVoices() }
        }
        initTts()
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://$HOST/index.html")
    }

    // ---------- serving the book from assets ----------
    private fun serve(path: String): WebResourceResponse? {
        val p = path.trimStart('/').ifEmpty { "index.html" }
        return try {
            when (p) {
                "index.html" -> {
                    val html = assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    // load the voice bridge before the book's own script runs
                    val injected = html.replaceFirst("<head>", "<head><script src=\"/tts-bridge.js\"></script>")
                        .replace("navigator.serviceWorker.register('sw.js')", "Promise.reject()")
                    WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)))
                }
                "tts-bridge.js" -> WebResourceResponse("text/javascript", "utf-8", assets.open("tts-bridge.js"))
                else -> WebResourceResponse(mime(p), null, assets.open("web/$p"))
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
        "css" -> "text/css"; "js" -> "text/javascript"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; "json", "webmanifest" -> "application/json"
        "mp3" -> "audio/mpeg"; "ogg", "opus" -> "audio/ogg"; else -> "application/octet-stream"
    }

    // ---------- text to speech ----------
    private fun initTts() {
        val listener = TextToSpeech.OnInitListener { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) = js("window.__ttsEvent&&__ttsEvent('$id','start')")
                    override fun onDone(id: String) = js("window.__ttsEvent&&__ttsEvent('$id','end')")
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String) = js("window.__ttsEvent&&__ttsEvent('$id','error')")
                    override fun onError(id: String, errorCode: Int) = js("window.__ttsEvent&&__ttsEvent('$id','error')")
                })
                pushVoices()
                main.postDelayed({ pushVoices() }, 1500)  // AI voice is built in, so no install prompt
            }
        }
        // prefer Google's engine (has Bangla); fall back to the phone's default engine
        tts = try {
            if (isInstalled(GOOGLE_TTS)) TextToSpeech(this, listener, GOOGLE_TTS) else TextToSpeech(this, listener)
        } catch (_: Exception) { TextToSpeech(this, listener) }
    }

    private fun isInstalled(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    private fun js(code: String) { main.post { if (::web.isInitialized) web.evaluateJavascript(code, null) } }

    private fun voicesJson(): String {
        val arr = JSONArray()
        val t = tts ?: return "[]"
        if (!ttsReady) return "[]"
        val seen = HashSet<String>()
        try {
            t.voices?.filter { v ->
                val tag = v.locale.language
                (tag == "bn" || tag == "en") && !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }?.sortedWith(compareBy<Voice>({ it.locale.language != "bn" }, { it.isNetworkConnectionRequired }, { it.name }))
                ?.forEach { v ->
                    if (seen.add(v.name)) arr.put(JSONObject().put("name", v.name).put("lang", v.locale.toLanguageTag()))
                }
        } catch (_: Exception) {}
        // engines that speak a language without listing voices
        for ((tag, label) in listOf("bn-BD" to "বাংলা (ফোনের কণ্ঠ)", "en-US" to "English (phone voice)")) {
            val lang = tag.substring(0, 2)
            val has = (0 until arr.length()).any { arr.getJSONObject(it).getString("lang").startsWith(lang) }
            if (!has && t.isLanguageAvailable(Locale.forLanguageTag(tag)) >= TextToSpeech.LANG_AVAILABLE)
                arr.put(JSONObject().put("name", label).put("lang", tag))
        }
        return arr.toString()
    }

    private fun pushVoices() {
        val json = voicesJson()
        js("window.__ttsVoices&&__ttsVoices(${JSONObject.quote(json)})")
    }

    private fun banglaAvailable(): Boolean {
        val t = tts ?: return false
        return listOf("bn-BD", "bn-IN", "bn").any { t.isLanguageAvailable(Locale.forLanguageTag(it)) >= TextToSpeech.LANG_AVAILABLE } ||
            (try { t.voices?.any { it.locale.language == "bn" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) } } catch (_: Exception) { null } == true)
    }

    /** If the phone has no Bangla voice, offer to install it (Google Speech Services). */
    private fun checkBangla() {
        if (askedForBangla || banglaAvailable() || isFinishing) return
        askedForBangla = true
        val hasGoogle = isInstalled(GOOGLE_TTS)
        AlertDialog.Builder(this)
            .setTitle("বাংলা কণ্ঠ ইনস্টল করুন")
            .setMessage(
                if (hasGoogle) "এই ফোনে বাংলা কণ্ঠ এখনো নামানো হয়নি। পরের পাতায় ‘বাংলা (বাংলাদেশ)’ কণ্ঠটি ডাউনলোড করুন, তারপর অ্যাপে ফিরে আসুন।\n\nEnglish voice works already."
                else "বাংলা কণ্ঠের জন্য Google-এর ‘Speech Services’ অ্যাপটি দরকার। Play Store থেকে ইনস্টল করে বাংলা কণ্ঠ ডাউনলোড করুন।\n\nEnglish voice works already."
            )
            .setPositiveButton(if (hasGoogle) "কণ্ঠ ডাউনলোড" else "Play Store") { _, _ ->
                try {
                    if (hasGoogle) startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(GOOGLE_TTS))
                    else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TTS")))
                } catch (_: Exception) {
                    try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {}
                }
            }
            .setNegativeButton("পরে", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // voices may have been installed while the app was in the background
        if (ttsReady) main.postDelayed({ pushVoices() }, 600)
    }

    inner class Bridge {
        @JavascriptInterface
        fun speak(id: String, text: String, lang: String, voiceName: String, rate: Float, pitch: Float) {
            main.post {
                val t = tts
                if (t == null || !ttsReady) { js("window.__ttsEvent&&__ttsEvent('$id','error')"); return@post }
                val v = try { t.voices?.firstOrNull { it.name == voiceName } } catch (_: Exception) { null }
                if (v != null) t.voice = v
                else if (lang.isNotBlank()) t.language = Locale.forLanguageTag(lang)
                t.setSpeechRate(rate.coerceIn(0.4f, 2f))
                t.setPitch(pitch.coerceIn(0.5f, 2f))
                t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            }
        }

        @JavascriptInterface
        fun stop() { main.post { tts?.stop() } }

        @JavascriptInterface
        fun voices(): String = voicesJson()
    }

    override fun onBackPressed() {
        // the book uses #hash pages: go back through them, then leave the app
        if (web.canGoBack()) web.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); tts = null
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val GOOGLE_TTS = "com.google.android.tts"
    }
}
