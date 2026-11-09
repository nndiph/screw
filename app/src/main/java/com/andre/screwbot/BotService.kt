package com.andre.screwbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/** Lapisan gambar lingkaran hasil pindai di atas layar (tidak menangkap sentuhan). */
class DebugView(ctx: Context) : View(ctx) {
    private var screws: List<Screw> = emptyList()
    private var picks: List<Screw> = emptyList()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }

    fun set(s: List<Screw>, p: List<Screw>) {
        screws = s
        picks = p
        invalidate()
    }

    fun clear() = set(emptyList(), emptyList())

    private fun colorOf(name: String): Int = when (name) {
        "merah" -> Color.RED
        "oranye" -> Color.rgb(255, 140, 0)
        "kuning" -> Color.YELLOW
        "biru" -> Color.rgb(0, 140, 255)
        "ungu" -> Color.rgb(190, 0, 255)
        "coklat" -> Color.rgb(160, 90, 40)
        "khaki" -> Color.rgb(140, 130, 80)
        "hijau" -> Color.rgb(0, 200, 60)
        "toska" -> Color.rgb(0, 220, 230)
        "lavender" -> Color.rgb(150, 150, 255)
        "pink" -> Color.rgb(255, 80, 200)
        "putih" -> Color.WHITE
        else -> Color.DKGRAY
    }

    override fun onDraw(canvas: Canvas) {
        ring.strokeWidth = 4f
        for (s in screws) {
            ring.color = colorOf(s.color)
            canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), s.r + 6f, ring)
        }
        ring.color = Color.WHITE
        ring.strokeWidth = 8f
        for (s in picks) {
            canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), s.r + 14f, ring)
            canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 6f, dot)
        }
    }
}

/**
 * Layanan latar depan: panel melayang, tangkap layar (MediaProjection), pindai baut, dan ketuk
 * lewat ClickService. Bot hanya berjalan setelah tombol MULAI ditekan.
 */
class BotService : Service() {

    companion object {
        const val ACTION_STOP = "com.andre.screwbot.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screwbot"
        private const val TAG = "ScrewBot"
    }

    private class Cfg(val batch: Int, val reserve: Int, val delay: Long, val circles: Boolean, val fill: Boolean, val patience: Int)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var latest: Mat? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var debug: DebugView? = null
    private var statusTv: TextView? = null
    private var startBtn: Button? = null
    @Volatile private var panelRect: IntArray? = null

    private var worker: Thread? = null
    @Volatile private var alive = false
    @Volatile private var botOn = false
    @Volatile private var scanReq = false
    private var idle = 0
    private val blacklist = ArrayList<Pair<Int, Int>>()
    private val pending = ArrayList<Pair<Long, String>>()   // ketukan yang masih "terbang" ke kotak: (waktu, warna)
    private var lastPicks: List<Screw> = emptyList()
    private val fails = HashMap<Pair<Int, Int>, Int>()
    private var noMatchRot = 0
    private var rotIndex = 0
    private var fullWaits = 0
    private val bad = ArrayList<Screw>()                // contoh baut yang ternyata masuk penyangga (salah baca warna)
    private var lastTap: List<Screw> = emptyList()      // kelompok ketukan terakhir
    private var lastFillTime = 0L                       // kapan terakhir sengaja mengisi penyangga
    private var lastFree = -1                           // sisa lubang penyangga pada pindaian sebelumnya
    private var lastBoxColors: List<String> = emptyList()
    private var cautious = 0                            // >0: ketuk satu-satu agar penyebab salah masuk jelas
    private var unexpected = 0                          // jumlah baut yang salah masuk penyangga

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (alive) return START_NOT_STICKY

        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            toast("Izin tampil di atas aplikasi lain belum aktif")
            shutdown()
            return START_NOT_STICKY
        }
        if (!OpenCVLoader.initLocal()) {
            toast("OpenCV gagal dimuat")
            shutdown()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            shutdown()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        readScreenSize()
        try {
            startCapture(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "capture gagal", e)
            toast("Gagal memulai perekaman layar: ${e.message}")
            shutdown()
            return START_NOT_STICKY
        }
        buildOverlay()
        alive = true
        worker = Thread { loop() }.also { it.start() }
        status("Siap. Buka game lalu tekan MULAI.")
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ setup
    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ScrewBot", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("ScrewBot aktif")
            .setContentText("Panel melayang tampil di atas layar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            capW = b.width()
            capH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            capW = dm.widthPixels
            capH = dm.heightPixels
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ui.post { shutdown() }
            }
        }, ui)
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "screwbot", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildOverlay() {
        // lapisan debug layar penuh, tidak bisa disentuh
        val dv = DebugView(this)
        val dlp = WindowManager.LayoutParams(
            capW, capH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        dlp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            dlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(dv, dlp)
        debug = dv

        // panel kontrol kecil yang bisa digeser
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(4), dp(8), dp(6))
        root.background = GradientDrawable().apply {
            setColor(0xE6202830.toInt())
            cornerRadius = dp(12).toFloat()
        }
        val handle = TextView(this)
        handle.text = "ScrewBot  ☰ geser"
        handle.setTextColor(Color.WHITE)
        handle.textSize = 11f
        val st = TextView(this)
        st.setTextColor(0xFFB8E0FF.toInt())
        st.textSize = 11f
        st.maxWidth = dp(260)
        statusTv = st
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val start = smallButton("MULAI")
        val scan = smallButton("PINDAI")
        val close = smallButton("X")
        startBtn = start
        row.addView(start)
        row.addView(scan)
        row.addView(close)
        root.addView(handle)
        root.addView(st)
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (0.28 * capW).toInt()
        lp.y = (0.245 * capH).toInt()
        wm.addView(root, lp)
        panel = root
        panelParams = lp
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePanelRect() }
        root.post { updatePanelRect() }

        handle.setOnTouchListener(object : View.OnTouchListener {
            var ox = 0
            var oy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = ox + (e.rawX - tx).toInt()
                        lp.y = oy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(root, lp)
                        updatePanelRect()
                    }
                }
                return true
            }
        })

        start.setOnClickListener {
            if (botOn) {
                stopBot("Dijeda")
            } else {
                if (ClickService.instance == null) {
                    status("Aktifkan layanan aksesibilitas ScrewBot dulu")
                } else {
                    resetRun()
                    botOn = true
                    start.text = "JEDA"
                    status("Berjalan...")
                }
            }
        }
        scan.setOnClickListener {
            if (!botOn) scanReq = true
        }
        close.setOnClickListener { shutdown() }
    }

    private fun smallButton(text: String): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(10), dp(6), dp(10), dp(6))
        return b
    }

    private fun updatePanelRect() {
        val p = panel ?: return
        val lp = panelParams ?: return
        val m = dp(12)
        panelRect = intArrayOf(lp.x - m, lp.y - m, lp.x + p.width + m, lp.y + p.height + m)
    }

    // ------------------------------------------------------------------ util UI
    private fun status(msg: String) {
        ui.post { statusTv?.text = msg }
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun stopBot(reason: String) {
        botOn = false
        ui.post { startBtn?.text = "MULAI" }
        status(reason)
    }

    private fun clearDebugAndWait() {
        ui.post { debug?.clear() }
        Thread.sleep(120)
    }

    // ------------------------------------------------------------------ tangkap layar
    private fun grab(): Mat? {
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try {
                val plane = img.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * capW
                val bmp = Bitmap.createBitmap(capW + rowPadding / plane.pixelStride, capH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                bmp.recycle()
                val crop = Mat(rgba, Rect(0, 0, capW, capH))
                val bgr = Mat()
                Imgproc.cvtColor(crop, bgr, Imgproc.COLOR_RGBA2BGR)
                rgba.release()
                latest?.release()
                latest = bgr
            } finally {
                img.close()
            }
        }
        // layar yang diam tidak menghasilkan frame baru: pakai frame terakhir
        return latest?.clone()
    }

    private fun cfg(): Cfg {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return Cfg(
            batch = p.getInt("batch2", 6).coerceIn(1, 8),
            reserve = p.getInt("reserve2", 2).coerceIn(0, 4),
            delay = p.getInt("delay2", 350).coerceIn(100, 3000).toLong(),
            circles = p.getBoolean("circles2", false),
            fill = p.getBoolean("fill2", false),
            patience = 8,
        )
    }

    // ------------------------------------------------------------------ loop bot
    private fun loop() {
        while (alive) {
            try {
                if (scanReq) {
                    scanReq = false
                    doScan()
                } else if (botOn) {
                    cycle()
                } else {
                    Thread.sleep(120)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "loop", e)
                stopBot("Error: ${e.message}")
            }
        }
    }

    private fun resetRun() {
        idle = 0
        noMatchRot = 0
        rotIndex = 0
        fullWaits = 0
        blacklist.clear()
        fails.clear()
        pending.clear()
        lastPicks = emptyList()
        bad.clear()
        lastTap = emptyList()
        lastFillTime = 0L
        lastFree = -1
        lastBoxColors = emptyList()
        cautious = 0
        unexpected = 0
    }

    private fun doScan() {
        clearDebugAndWait()
        val bgr = grab()
        if (bgr == null) {
            status("Belum ada gambar layar, coba lagi")
            return
        }
        try {
            val t0 = System.currentTimeMillis()
            val hsv = ScrewDetector.hsvOf(bgr)
            val screws = ScrewDetector.detect(bgr, hsv, extraExclude())
            val ms = System.currentTimeMillis() - t0
            val boxes = Hud.readBoxes(hsv)
            val counts = screws.groupingBy { it.color }.eachCount().entries.joinToString(" ") { "${it.key.take(3)}${it.value}" }
            ui.post { debug?.set(screws, emptyList()) }
            ui.postDelayed({ debug?.clear() }, 3000)
            val tag = if (Hud.inGame(hsv)) "" else " (bukan layar game?)"
            val bx = boxes.joinToString("/") { "${it.color}${it.free}" }
            status("Pindai ${ms}ms: ${screws.size} baut $counts | kotak $bx$tag")
        } finally {
            bgr.release()
        }
    }

    private fun extraExclude(): List<IntArray> {
        val r = panelRect
        return if (r == null) emptyList() else listOf(r)
    }

    private fun sq(v: Int): Long = v.toLong() * v

    /** Baut yang sudah diketuk tapi masih terlihat di tempat yang sama dua kali berturut-turut = tidak bisa dilepas. */
    private fun updateFails(now: List<Screw>) {
        for (p in lastPicks) {
            val still = now.any { it.color == p.color && sq(it.x - p.x) + sq(it.y - p.y) < 900L }
            val key = Pair(p.x / 10, p.y / 10)
            if (still) {
                val n = (fails[key] ?: 0) + 1
                fails[key] = n
                if (n >= 2) blacklist.add(Pair(p.x, p.y))
            } else {
                fails.remove(key)
            }
        }
        lastPicks = emptyList()
    }

    /** Putar objek: geser horizontal; tiap keempat kali geser vertikal bergantian agar atas/bawah terlihat. */
    private fun rotate(click: ClickService) {
        val i = rotIndex++
        if (i % 4 == 3) {
            val up = (i / 4) % 2 == 0
            val y1 = (if (up) 0.64 else 0.44) * capH
            val y2 = (if (up) 0.44 else 0.64) * capH
            click.swipe((0.5 * capW).toInt(), y1.toInt(), (0.5 * capW).toInt(), y2.toInt())
        } else {
            click.swipe((0.25 * capW).toInt(), (0.57 * capH).toInt(), (0.75 * capW).toInt(), (0.57 * capH).toInt())
        }
        blacklist.clear()
        fails.clear()
    }

    private fun cycle() {
        val c = cfg()
        val click = ClickService.instance
        if (click == null) {
            stopBot("Aksesibilitas mati. Aktifkan lagi.")
            return
        }
        if (c.circles) clearDebugAndWait()
        val t0 = System.currentTimeMillis()
        val bgr = grab()
        if (bgr == null) {
            Thread.sleep(150)
            return
        }
        try {
            val hsv = ScrewDetector.hsvOf(bgr)
            if (!Hud.inGame(hsv)) {
                idle++
                if (idle >= c.patience) {
                    stopBot("Berhenti: bukan layar game (selesai/iklan/popup)")
                } else {
                    status("Bukan layar game ($idle/${c.patience}), menunggu")
                    Thread.sleep(c.delay * 2)
                }
                return
            }
            val boxes = Hud.readBoxes(hsv)
            val free = Hud.freeHoles(hsv)
            val now0 = System.currentTimeMillis()
            if (lastFree >= 0 && free < lastFree) {
                // penyangga bertambah isi padahal tidak sengaja diisi: ada ketukan yang warnanya salah baca
                if (now0 - lastFillTime > 2500) {
                    unexpected += lastFree - free
                    if (lastTap.size == 1) bad.add(lastTap[0]) else cautious = 8
                }
            } else if (cautious > 0 && lastFree >= 0 && lastTap.isNotEmpty()) {
                cautious--
            }
            lastFree = free
            if (unexpected >= 3 || (!c.fill && free <= 1)) {
                stopBot("Berhenti demi aman: penyangga terisi $unexpected baut tak terduga (sisa $free). Kirim screenshot ke pengembang.")
                return
            }
            // kotak baru sedang muncul (animasi): tunggu sampai warna kotak sama di dua pindaian berturut-turut
            val colors = boxes.map { it.color }
            if (colors != lastBoxColors) {
                lastBoxColors = colors
                status("Kotak berubah: ${colors.joinToString("/")}, menunggu stabil")
                Thread.sleep(350)
                return
            }
            val screws = ScrewDetector.detect(bgr, hsv, extraExclude()).filter { !Strategy.looksBad(it, bad) }
            updateFails(screws)
            val now = System.currentTimeMillis()
            pending.removeAll { now - it.first > 1500 }
            val pend = pending.groupingBy { it.second }.eachCount()
            val (picks, why) = Strategy.chooseTargets(
                screws, boxes, free, c.reserve, if (cautious > 0) 1 else c.batch, blacklist, 60, pend,
                c.fill && noMatchRot >= 12,
            )
            val ms = System.currentTimeMillis() - t0
            if (c.circles) ui.post { debug?.set(screws, picks) }
            val bx = boxes.joinToString("/") { "${it.color}${it.free}" }
            status("${screws.size} baut | $why | ${ms}ms | kotak $bx | sisa penyangga $free")

            if (picks.isEmpty()) {
                if (why == "kotak penuh") {
                    // kotak sedang terisi/berganti: tunggu sebentar, jangan putar objek
                    fullWaits++
                    if (fullWaits >= 20) stopBot("Berhenti: kotak tidak terbaca atau tidak berubah")
                    else Thread.sleep(300)
                    return
                }
                idle++
                noMatchRot++
                if (idle >= c.patience + 2) {
                    stopBot("Berhenti: tidak ada baut yang bisa diketuk")
                    return
                }
                status("Tidak ada yang cocok, memutar objek ($noMatchRot)")
                rotate(click)
                Thread.sleep(c.delay + 150)
                return
            }
            idle = 0
            fullWaits = 0
            noMatchRot = 0
            lastPicks = picks
            lastTap = picks
            if (why == "isi penyangga") lastFillTime = System.currentTimeMillis()
            val t = System.currentTimeMillis()
            for (s in picks) pending.add(Pair(t, s.color))
            click.tapMany(picks.map { Pair(it.x, it.y) })
            Thread.sleep(if (cautious > 0) maxOf(c.delay, 900L) else c.delay)
        } finally {
            bgr.release()
        }
    }

    // ------------------------------------------------------------------ keluar
    private fun shutdown() {
        alive = false
        botOn = false
        worker?.interrupt()
        try { panel?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { debug?.let { wm.removeView(it) } } catch (e: Exception) {}
        panel = null
        debug = null
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        display = null
        reader = null
        projection = null
        latest?.release()
        latest = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (alive) shutdown()
        super.onDestroy()
    }
}
