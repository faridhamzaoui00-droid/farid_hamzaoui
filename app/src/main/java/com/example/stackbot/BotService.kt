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
    @Volatile private var running = false
    private var fullW = 0
    private var fullH = 0
    private var lastX = -1f
    private var lastT = 0L
    private var cooldownUntil = 0L

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
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay(
            "scan", w, h, real.densityDpi / Config.SCALE,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { if (running) onFrame(img, h) } finally { img.close() }
        }, handler)
    }

    private fun showOverlay() {
        val tv = TextView(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                running = !running
                text = if (running) "⏸" else "▶"
                lastX = -1f
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

    private fun onFrame(img: Image, h: Int) {
        val now = SystemClock.uptimeMillis()
        if (now < cooldownUntil) return
        val top = Detector.span(img, (h * Config.MOVING_ROW).toInt())
        val tower = Detector.span(img, (h * Config.TOWER_ROW).toInt())
        if (top == null || tower == null) { lastX = -1f; return }
        val cx = (top.first + top.second) / 2f
        val tx = (tower.first + tower.second) / 2f
        val dist = tx - cx
        if (abs(dist) < Config.TOL_PX) { tap(); return }
        if (lastX >= 0f && now > lastT) {
            val v = (cx - lastX) / (now - lastT)
            if (v != 0f && dist * v > 0f) {
                val eta = dist / v
                if (eta <= Config.LATENCY_MS + 40f) {
                    val delay = max(0L, (eta - Config.LATENCY_MS).toLong())
                    cooldownUntil = now + delay + Config.COOLDOWN_MS
                    handler.postDelayed({ tap() }, delay)
                    lastX = -1f
                    return
                }
            }
        }
        lastX = cx
        lastT = now
    }

    private fun tap() {
        cooldownUntil = SystemClock.uptimeMillis() + Config.COOLDOWN_MS
        lastX = -1f
        TapService.instance?.tap(Config.BTN_X * fullW, Config.BTN_Y * fullH)
    }

    override fun onDestroy() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        projection?.stop()
        thread.quitSafely()
        super.onDestroy()
    }
}
