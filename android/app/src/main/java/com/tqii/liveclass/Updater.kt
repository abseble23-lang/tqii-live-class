package com.tqii.liveclass

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Checks GitHub Releases for a newer APK, downloads it and opens the system installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/abseble23-lang/tqii-live-class/releases/latest"

    data class Update(val versionCode: Int, val name: String, val url: String, val notes: String)

    fun currentVersion(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    suspend fun check(ctx: Context): Update? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            Api.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val j = JSONObject(r.body?.string() ?: return@withContext null)
                val tag = j.optString("tag_name") // v1.0.N
                val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
                if (code <= currentVersion(ctx)) return@withContext null
                val assets = j.optJSONArray("assets") ?: return@withContext null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        return@withContext Update(code, j.optString("name", tag), a.getString("browser_download_url"), j.optString("body"))
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    /** Downloads the APK; [progress] gets 0..100. Returns the file. */
    suspend fun download(ctx: Context, u: Update, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "TQII-Live-Class-${u.versionCode}.apk")
        Api.http.newCall(Request.Builder().url(u.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("empty")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress(pct) } }
                        }
                    }
                }
            }
        }
        out
    }

    /** True if we may launch the installer; otherwise opens the "install unknown apps" setting. */
    fun ensureInstallPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        return true
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
