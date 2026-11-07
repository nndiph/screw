package com.andre.screwbot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Layar pengaturan: tiga langkah izin, tiga angka pengaturan, lalu tombol Mulai. */
class MainActivity : Activity() {

    private val reqProjection = 1001
    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var batchEt: EditText
    private lateinit var reserveEt: EditText
    private lateinit var delayEt: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.rgb(30, 35, 45))
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.setPadding(0, dp(6), 0, dp(2))
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun numberField(hint: String, value: Int): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value.toString())
        e.hint = hint
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(28), dp(20), dp(28))

        col.addView(label("ScrewBot", 26f, true))
        col.addView(label("Pemindai dan pengetuk baut otomatis. Ikuti 3 langkah di bawah, lalu buka game."))

        col.addView(label("1. Tampil di atas aplikasi lain", 16f, true))
        overlayStatus = label("")
        col.addView(overlayStatus)
        col.addView(button("Buka pengaturan izin") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        col.addView(label("2. Layanan aksesibilitas (untuk mengetuk)", 16f, true))
        a11yStatus = label("")
        col.addView(a11yStatus)
        col.addView(label("Android 13+: jika tombolnya abu-abu, buka Info aplikasi ScrewBot, menu titik tiga, lalu \"Izinkan setelan terbatas\".", 12f))
        col.addView(button("Buka pengaturan aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(label("Pengaturan", 16f, true))
        col.addView(label("Ketukan per pindai (1-4)", 12f))
        batchEt = numberField("2", cfg.getInt("batch", 2))
        col.addView(batchEt)
        col.addView(label("Lubang penyangga yang disisakan (0-4)", 12f))
        reserveEt = numberField("2", cfg.getInt("reserve", 2))
        col.addView(reserveEt)
        col.addView(label("Jeda setelah ketukan, milidetik (200-3000)", 12f))
        delayEt = numberField("700", cfg.getInt("delay", 700))
        col.addView(delayEt)

        col.addView(label("3. Mulai", 16f, true))
        col.addView(label("Android akan meminta izin merekam layar. Setelah itu panel melayang muncul; buka game lalu tekan MULAI di panel.", 12f))
        col.addView(button("Tampilkan panel ScrewBot") { requestStart() })
        col.addView(button("Hentikan ScrewBot") {
            startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP))
        })

        val sv = ScrollView(this)
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlay) "Aktif" else "Belum aktif"
        overlayStatus.setTextColor(if (overlay) Color.rgb(0, 130, 60) else Color.rgb(190, 40, 40))
        val a11y = ClickService.instance != null
        a11yStatus.text = if (a11y) "Aktif" else "Belum aktif (cari ScrewBot di daftar layanan terpasang)"
        a11yStatus.setTextColor(if (a11y) Color.rgb(0, 130, 60) else Color.rgb(190, 40, 40))
    }

    private fun saveCfg() {
        fun num(e: EditText, def: Int) = e.text.toString().toIntOrNull() ?: def
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putInt("batch", num(batchEt, 2).coerceIn(1, 4))
            .putInt("reserve", num(reserveEt, 2).coerceIn(0, 4))
            .putInt("delay", num(delayEt, 700).coerceIn(200, 3000))
            .apply()
    }

    private fun requestStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan langkah 1 dulu", Toast.LENGTH_LONG).show()
            return
        }
        if (ClickService.instance == null) {
            Toast.makeText(this, "Aktifkan langkah 2 dulu", Toast.LENGTH_LONG).show()
            return
        }
        saveCfg()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, BotService::class.java)
                i.putExtra(BotService.EXTRA_CODE, resultCode)
                i.putExtra(BotService.EXTRA_DATA, data)
                startForegroundService(i)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Izin rekam layar ditolak", Toast.LENGTH_LONG).show()
            }
        }
    }
}
