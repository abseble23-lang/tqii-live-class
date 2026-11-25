package com.tqii.liveclass

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * "প্রতিষ্ঠান পরিচালনা" tab: the TQII website's own admin panel, shown inside the admin app.
 * The panel itself is not changed; this is only a window onto it. Pages of the website open
 * here, everything else (WhatsApp, phone, other sites) opens in its own app.
 */
object Panel {
    const val HOST = "talimulquraninstitute.vercel.app"
    const val URL = "https://$HOST/admin"

    fun isOurs(uri: Uri): Boolean = uri.scheme == "https" && uri.host == HOST

    /** Sign the website out on this phone too (called on app logout). */
    fun clearSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}

/** Holds the one WebView so switching tabs does not reload the panel. */
class PanelState {
    var web: WebView? = null
    var loading by mutableStateOf(true)
    var progress by mutableIntStateOf(0)
    var offline by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var pendingFiles: ValueCallback<Array<Uri>>? = null

    fun reload() { offline = false; web?.reload() }
    fun openOutside(ctx: Context) { web?.url?.let { openExternal(ctx, Uri.parse(it)) } }
}

fun openExternal(ctx: Context, uri: Uri) {
    try {
        val i = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        else Intent(Intent.ACTION_VIEW, uri)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, L.t("noAppForLink"), Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(ctx, L.t("noAppForLink"), Toast.LENGTH_SHORT).show()
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildPanelWebView(ctx: Context, st: PanelState): WebView {
    val main = Handler(Looper.getMainLooper())
    val wv = WebView(ctx)
    wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    CookieManager.getInstance().setAcceptCookie(true)
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = false
        allowContentAccess = false
        userAgentString = "$userAgentString TQIIAdminApp/1"
    }

    // window.print() does nothing in a WebView: send it to Android's print / "Save as PDF".
    wv.addJavascriptInterface(PrintBridge(ctx, st, main), "TQIIApp")

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (Panel.isOurs(uri)) return false
            openExternal(ctx, uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            st.loading = true
            st.offline = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            st.loading = false
            st.canGoBack = view.canGoBack()
            CookieManager.getInstance().flush()
            if (url != null && Panel.isOurs(Uri.parse(url))) {
                view.evaluateJavascript("window.print=function(){TQIIApp.print()};", null)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            st.canGoBack = view.canGoBack()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) { st.offline = true; st.loading = false }
        }
    }

    wv.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { st.progress = newProgress }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            st.pendingFiles?.onReceiveValue(null)
            st.pendingFiles = callback
            val launch = panelFileLauncher
            if (launch == null) { st.pendingFiles = null; callback.onReceiveValue(null); return false }
            return try { launch(params.createIntent()); true } catch (_: Exception) {
                st.pendingFiles = null; callback.onReceiveValue(null); false
            }
        }
    }

    wv.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            Toast.makeText(ctx, L.t("downloadInBrowser"), Toast.LENGTH_LONG).show()
            return@setDownloadListener
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(uri)
                .setMimeType(mime)
                .setTitle(name)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            else req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(ctx, L.t("downloading"), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            openExternal(ctx, uri)
        }
    }

    wv.loadUrl(Panel.URL)
    return wv
}

/** window.print() bridge: opens Android's print dialog (print or "Save as PDF") for the current page. */
class PrintBridge(private val ctx: Context, private val st: PanelState, private val main: Handler) {
    @JavascriptInterface
    fun print() {
        main.post {
            val w = st.web ?: return@post
            try {
                val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = "TQII-" + (w.title ?: "document").take(40)
                pm.print(name, w.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            } catch (_: Exception) { }
        }
    }
}

/** Set while the panel tab is on screen; the WebView's file chooser uses it. */
private var panelFileLauncher: ((Intent) -> Unit)? = null

@Composable
fun AdminPanelWeb(st: PanelState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        st.pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        st.pendingFiles = null
    }
    DisposableEffect(Unit) {
        panelFileLauncher = { picker.launch(it) }
        onDispose { panelFileLauncher = null }
    }

    BackHandler(enabled = st.canGoBack) { st.web?.goBack() }

    Box(Modifier.fillMaxSize().background(Ivory)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val w = st.web ?: buildPanelWebView(c, st).also { st.web = it }
                (w.parent as? ViewGroup)?.removeView(w)
                w
            }
        )
        if (st.loading && st.progress < 100) {
            LinearProgressIndicator(
                progress = { st.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
                color = Gold, trackColor = GoldSoft
            )
        }
        if (st.offline) {
            Column(
                Modifier.fillMaxSize().background(Ivory).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.CloudOff, null, tint = GoldDeep, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(L.t("panelOffline"), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(L.t("panelOfflineHint"), fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { st.reload() }, colors = ButtonDefaults.buttonColors(containerColor = Night, contentColor = Ivory)) {
                    Text(L.t("tryAgain"))
                }
            }
        }
    }
}
