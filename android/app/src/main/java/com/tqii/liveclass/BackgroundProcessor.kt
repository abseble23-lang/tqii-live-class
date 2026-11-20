package com.tqii.liveclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Background options shown in the call UI. resId == 0 means blur. */
data class BgOption(val key: String, val resId: Int?) // resId: null = none, 0 = blur, else image

val BG_OPTIONS = listOf(
    BgOption("bgNone", null),
    BgOption("bgBlur", 0),
    BgOption("bgOffice", R.drawable.bg_office),
    BgOption("bgLibrary", R.drawable.bg_library),
    BgOption("bgStudio", R.drawable.bg_studio),
    BgOption("bgBoard", R.drawable.bg_board),
)

/**
 * Replaces / blurs the camera background on-device with ML Kit selfie segmentation.
 * Plugged into the camera VideoSource with setVideoProcessor().
 */
class BackgroundProcessor(private val ctx: Context) : VideoProcessor {
    @Volatile var option: BgOption = BG_OPTIONS[0]
    @Volatile private var sink: VideoSink? = null
    @Volatile private var busy = false
    private val executor = Executors.newSingleThreadExecutor()
    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        )
    }
    private var cachedBg: Bitmap? = null
    private var cachedKey = ""

    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val opt = option
        if (opt.resId == null) { sink?.onFrame(frame); return }
        if (busy) return // drop frame while the previous one is still processing
        busy = true
        val i420 = frame.buffer.toI420()
        if (i420 == null) { busy = false; return }
        val rotation = frame.rotation
        val ts = frame.timestampNs
        executor.execute {
            try {
                val out = process(i420, rotation, opt)
                val vf = VideoFrame(out, 0, ts)
                sink?.onFrame(vf)
                vf.release()
            } catch (e: Exception) {
                Log.w("BgProcessor", "frame failed", e)
            } finally {
                i420.release()
                busy = false
            }
        }
    }

    private fun process(src: VideoFrame.I420Buffer, rotation: Int, opt: BgOption): VideoFrame.I420Buffer {
        // 1. I420 -> ARGB bitmap (upright)
        val w = src.width; val h = src.height
        val argb = IntArray(w * h)
        val y = src.dataY; val u = src.dataU; val v = src.dataV
        val sy = src.strideY; val su = src.strideU; val sv = src.strideV
        for (j in 0 until h) {
            val yRow = j * sy; val uRow = (j shr 1) * su; val vRow = (j shr 1) * sv
            for (i in 0 until w) {
                val yy = (y.get(yRow + i).toInt() and 0xff) - 16
                val uu = (u.get(uRow + (i shr 1)).toInt() and 0xff) - 128
                val vv = (v.get(vRow + (i shr 1)).toInt() and 0xff) - 128
                val c = 1192 * (if (yy < 0) 0 else yy)
                var r = (c + 1634 * vv) shr 10
                var g = (c - 833 * vv - 400 * uu) shr 10
                var b = (c + 2066 * uu) shr 10
                r = if (r < 0) 0 else if (r > 255) 255 else r
                g = if (g < 0) 0 else if (g > 255) 255 else g
                b = if (b < 0) 0 else if (b > 255) 255 else b
                argb[j * w + i] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        var bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
            bmp.recycle(); bmp = r
        }
        val W = bmp.width and 1.inv()
        val H = bmp.height and 1.inv()

        // 2. segmentation mask
        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)), 2, TimeUnit.SECONDS)
        val mb = mask.buffer.order(ByteOrder.nativeOrder()); mb.rewind()
        val mw = mask.width; val mh = mask.height

        // 3. background
        val bg = backgroundFor(opt, bmp, W, H)

        val fg = IntArray(W * H); bmp.getPixels(fg, 0, bmp.width, 0, 0, W, H)
        val bp = IntArray(W * H); bg.getPixels(bp, 0, W, 0, 0, W, H)
        if (bg !== cachedBg) bg.recycle()
        bmp.recycle()

        // 4. composite + ARGB -> I420
        val out = JavaI420Buffer.allocate(W, H)
        val oy = out.dataY; val ou = out.dataU; val ov = out.dataV
        val osy = out.strideY; val osu = out.strideU; val osv = out.strideV
        for (j in 0 until H) {
            val mj = if (mh == H) j else j * mh / H
            for (i in 0 until W) {
                val mi = if (mw == W) i else i * mw / W
                var a = mb.getFloat((mj * mw + mi) * 4)
                a = ((a - 0.35f) / 0.4f).coerceIn(0f, 1f)
                val p = fg[j * W + i]; val q = bp[j * W + i]
                val r = (((p shr 16) and 0xff) * a + ((q shr 16) and 0xff) * (1 - a)).toInt()
                val g = (((p shr 8) and 0xff) * a + ((q shr 8) and 0xff) * (1 - a)).toInt()
                val b = ((p and 0xff) * a + (q and 0xff) * (1 - a)).toInt()
                oy.put(j * osy + i, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    ou.put((j shr 1) * osu + (i shr 1), (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    ov.put((j shr 1) * osv + (i shr 1), (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
        return out
    }

    private fun backgroundFor(opt: BgOption, frame: Bitmap, W: Int, H: Int): Bitmap {
        if (opt.resId == 0) {
            // cheap strong blur: downscale then upscale with filtering
            val small = Bitmap.createScaledBitmap(frame, maxOf(1, W / 20), maxOf(1, H / 20), true)
            val mid = Bitmap.createScaledBitmap(small, maxOf(1, W / 6), maxOf(1, H / 6), true)
            val full = Bitmap.createScaledBitmap(mid, W, H, true)
            small.recycle(); mid.recycle()
            return full
        }
        val key = "${opt.resId}_${W}x$H"
        cachedBg?.let { if (key == cachedKey) return it }
        val raw = BitmapFactory.decodeResource(ctx.resources, opt.resId!!)
        // center-crop to W x H
        val scale = maxOf(W.toFloat() / raw.width, H.toFloat() / raw.height)
        val sw = (raw.width * scale).toInt().coerceAtLeast(W)
        val sh = (raw.height * scale).toInt().coerceAtLeast(H)
        val scaled = Bitmap.createScaledBitmap(raw, sw, sh, true)
        val cropped = Bitmap.createBitmap(scaled, (sw - W) / 2, (sh - H) / 2, W, H)
        if (scaled !== raw) raw.recycle()
        if (cropped !== scaled) scaled.recycle()
        cachedBg?.recycle()
        cachedBg = cropped; cachedKey = key
        return cropped
    }

    fun release() {
        executor.shutdown()
        try { segmenter.close() } catch (_: Exception) {}
    }
}
