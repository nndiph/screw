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
import android.widget.CheckBox
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
    private lateinit var circlesCb: CheckBox
    private lateinit var fillCb: CheckBox

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
        col.addView(label("Ketukan per pindai (1-8)", 12f))
        batchEt = numberField("6", cfg.getInt("batch2", 6))
        col.addView(batchEt)
        col.addView(label("Lubang penyangga yang disisakan (0-4)", 12f))
        reserveEt = numberField("2", cfg.getInt("reserve2", 2))
        col.addView(reserveEt)
        col.addView(label("Jeda setelah ketukan, milidetik (100-3000). Makin kecil makin cepat.", 12f))
        delayEt = numberField("350", cfg.getInt("delay2", 350))
        col.addView(delayEt)
        circlesCb = CheckBox(this)
        circlesCb.text = "Tampilkan lingkaran deteksi saat jalan (lebih lambat)"
        circlesCb.isChecked = cfg.getBoolean("circles2", false)
        col.addView(circlesCb)
        fillCb = CheckBox(this)
        fillCb.text = "Boleh isi penyangga bila buntu (tidak disarankan)"
        fillCb.isChecked = cfg.getBoolean("fill2", false)
        col.addView(fillCb)

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
        refreshA11y()
    }

    private fun a11yState(): Int {
        if (ClickService.instance != null) return 2
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return if (enabled.contains(packageName, ignoreCase = true)) 1 else 0
    }

    private fun refreshA11y() {
        val st = a11yState()
        a11yStatus.text = when (st) {
            2 -> "Aktif dan terhubung"
            1 -> "Dinyalakan tapi belum tersambung. Matikan lalu nyalakan lagi ScrewBot di pengaturan aksesibilitas (di Xiaomi: izinkan Mulai otomatis dan Tanpa batasan baterai)."
            else -> "Belum aktif (cari ScrewBot di daftar layanan terpasang)"
        }
        a11yStatus.setTextColor(if (st == 2) Color.rgb(0, 130, 60) else Color.rgb(190, 40, 40))
    }

    private fun saveCfg() {
        fun num(e: EditText, def: Int) = e.text.toString().toIntOrNull() ?: def
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putInt("batch2", num(batchEt, 6).coerceIn(1, 8))
            .putInt("reserve2", num(reserveEt, 2).coerceIn(0, 4))
            .putInt("delay2", num(delayEt, 350).coerceIn(100, 3000))
            .putBoolean("circles2", circlesCb.isChecked)
            .putBoolean("fill2", fillCb.isChecked)
            .apply()
    }

    private fun requestStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan langkah 1 dulu", Toast.LENGTH_LONG).show()
            return
        }
        refreshA11y()
        val st = a11yState()
        if (st != 2) {
            Toast.makeText(
                this,
                if (st == 1) "Aksesibilitas dinyalakan tapi belum tersambung: matikan lalu nyalakan lagi ScrewBot"
                else "Aktifkan layanan aksesibilitas ScrewBot dulu (langkah 2)",
                Toast.LENGTH_LONG,
            ).show()
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
