package com.example.stackbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

class BotService : Service() {
    private lateinit var wm: WindowManager
    private var overlay: TextView? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("scan").apply { start() }
    private val handler by lazy { Handler(thread.looper) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var prev: IntArray? = null
    private var fullW = 0
    private var fullH = 0
    private var tx = 0f
    private var lastX = -1f
    private var lastT = 0L
    private var cooldownUntil = 0L
    private var lastShown = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) return START_NOT_STICKY
        val ch = "scan"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "StackBot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("StackBot")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(i.getIntExtra("code", 0), data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        setupCapture()
        showOverlay()
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(real)
        fullW = real.widthPixels
        fullH = real.heightPixels
        val w = fullW / Config.SCALE
        val h = fullH / Config.SCALE
        tx = w * Config.TARGET_X
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay(
            "scan", w, h, real.densityDpi / Config.SCALE,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { if (running) onFrame(img, w, h) } finally { img.close() }
        }, handler)
    }

    private fun showOverlay() {
        val tv = TextView(this).apply {
            text = "▶"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                running = !running
                prev = null
                lastX = -1f
                text = if (running) "⏸" else "▶"
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }
        wm.addView(tv, lp)
        overlay = tv
    }

    private fun sample(img: Image, w: Int, h: Int): IntArray {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val y0 = (h * Config.BAND_TOP).toInt()
        val y1 = (h * Config.BAND_BOTTOM).toInt()
        val rows = (y1 - y0) / 8
        val cols = w / 2
        val out = IntArray(rows * cols)
        for (r in 0 until rows) {
            val base = (y0 + r * 8) * rs
            for (c in 0 until cols) {
                val o = base + c * 2 * ps
                out[r * cols + c] = ((buf.get(o).toInt() and 255) +
                        (buf.get(o + 1).toInt() and 255) +
                        (buf.get(o + 2).toInt() and 255)) / 3
            }
        }
        return out
    }

    private fun moverX(a: IntArray, b: IntArray, w: Int): Float? {
        val cols = w / 2
        val rows = b.size / cols
        val colCount = IntArray(cols)
        var total = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (abs(b[r * cols + c] - a[r * cols + c]) > Config.DIFF_T) {
                    colCount[c]++
                    total++
                }
            }
        }
        if (total > b.size / 5) return null
        var l = -1
        var rr = -1
        for (c in 0 until cols) {
            if (colCount[c] >= 2) {
                if (l < 0) l = c
                rr = c
            }
        }
        if (l < 0) return null
        return (l + rr).toFloat()
    }

    private fun show(mx: Float) {
        val n = SystemClock.uptimeMillis()
        if (n - lastShown < 200) return
        lastShown = n
        val t = "⏸ ${mx.toInt()}|${tx.toInt()}"
        main.post { overlay?.text = t }
    }

    private fun onFrame(img: Image, w: Int, h: Int) {
        val cur = sample(img, w, h)
        val p = prev
        prev = cur
        if (p == null || p.size != cur.size) return
        val now = SystemClock.uptimeMillis()
        if (now < cooldownUntil) { lastX = -1f; return }
        val mx = moverX(p, cur, w) ?: return
        show(mx)
        val dist = tx - mx
        if (abs(dist) < Config.TOL_PX) { fire(); return }
        if (lastX >= 0f && now > lastT && now - lastT < 120) {
            val v = (mx - lastX) / (now - lastT)
            if (v != 0f && dist * v > 0f) {
                val eta = dist / v
                if (eta <= Config.LATENCY_MS + 30f) {
                    val delay = max(0L, (eta - Config.LATENCY_MS).toLong())
                    cooldownUntil = now + delay + Config.COOLDOWN_MS
                    handler.postDelayed({ fire() }, delay)
                    lastX = -1f
                    return
                }
            }
        }
        lastX = mx
        lastT = now
    }

    private fun fire() {
        cooldownUntil = SystemClock.uptimeMillis() + Config.COOLDOWN_MS
        lastX = -1f
        TapService.instance?.tap(Config.TAP_X * fullW, Config.TAP_Y * fullH)
    }

    override fun onDestroy() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        projection?.stop()
        thread.quitSafely()
        super.onDestroy()
    }
}
