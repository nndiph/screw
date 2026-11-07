package com.andre.screwbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Layanan aksesibilitas: satu-satunya tugasnya mengirim ketukan dan geseran ke layar.
 * Harus diaktifkan pengguna di Pengaturan > Aksesibilitas.
 */
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Ketuk di piksel layar (x, y). Memblokir sampai selesai; panggil dari thread latar. */
    fun tap(x: Int, y: Int): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 50)).build(), 2000)
    }

    /**
     * Banyak ketukan berurutan dalam satu gestur (jauh lebih cepat daripada satu per satu).
     * Tiap ketukan dimulai [spacingMs] setelah sebelumnya dan berlangsung [tapMs].
     */
    fun tapMany(points: List<Pair<Int, Int>>, spacingMs: Long = 90, tapMs: Long = 40): Boolean {
        var ok = true
        val chunk = GestureDescription.getMaxStrokeCount()
        for (part in points.chunked(chunk)) {
            val b = GestureDescription.Builder()
            for ((i, pt) in part.withIndex()) {
                val p = Path()
                p.moveTo(pt.first.toFloat(), pt.second.toFloat())
                b.addStroke(GestureDescription.StrokeDescription(p, i * spacingMs, tapMs))
            }
            ok = dispatch(b.build(), part.size * spacingMs + tapMs + 2000) && ok
        }
        return ok
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 250): Boolean {
        val p = Path()
        p.moveTo(x1.toFloat(), y1.toFloat())
        p.lineTo(x2.toFloat(), y2.toFloat())
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), ms + 2000)
    }

    private fun dispatch(g: GestureDescription, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(g, cb, null)) return false
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ok
    }
}
