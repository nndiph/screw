package com.andre.screwbot

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bagian murni (tanpa OpenCV) dari pipeline deteksi: nama warna, warna kepala baut, fitur, model, pengaman.
 * Port 1:1 dari screwml.py (diuji paritas terhadap Python).
 */
object ScrewML {
    const val S = 24            // sisi potongan yang dinormalisasi
    const val CROP_K = 1.25     // setengah sisi potongan = CROP_K * r
    const val MIN_P = 0.7       // ambang peluang "ini baut"
    const val MIN_CONF = 0.5    // ambang kepercayaan warna untuk diketuk
    private val VOTE_R = doubleArrayOf(0.0, 0.2, 0.32, 0.45)

    fun rnd(v: Double): Int = floor(v + 0.5).toInt()

    /** BGR -> HSV seperti cv2 (H 0-179). */
    fun hsvPx(b: Int, g: Int, r: Int): IntArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val s = if (mx == 0) 0 else rnd(255.0 * d / mx)
        var h = when {
            d == 0 -> 0.0
            mx == r -> 60.0 * (g - b) / d
            mx == g -> 120.0 + 60.0 * (b - r) / d
            else -> 240.0 + 60.0 * (r - g) / d
        }
        if (h < 0) h += 360.0
        return intArrayOf(Math.floorMod(rnd(h / 2.0), 180), s, mx)
    }

    /** Nama warna dari HSV. null = ragu (di celah antar warna). Dipakai untuk baut dan kotak. */
    fun nameOf(h: Int, s: Int, v: Int): String? {
        if (s < 40) return if (v > 200) "putih" else null
        if ((h >= 170 || h <= 5) && s >= 120) return "merah"
        if (h in 10..14 && s >= 120 && v < 200) return "coklat"
        if (h in 16..19 && s >= 150 && v >= 185) return "oranye"
        if (h in 17..22 && s in 50..120 && v < 175) return "khaki"
        if (h in 22..30 && s >= 130) return "kuning"
        if (h in 45..80 && s >= 100) return "hijau"
        if (h in 86..99 && s >= 120) return "toska"
        if (h in 101..113 && s >= 150) return "biru"
        if (h in 115..127 && s in 80..160) return "lavender"
        if (h in 129..146 && s >= 120) return "ungu"
        if (h in 147..169 && s >= 100) return "pink"
        return null
    }

    fun median(a: DoubleArray): Double {
        val s = a.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    class HeadColor(val name: String?, val conf: Double, val h: Int, val s: Int, val v: Int)

    /** Warna kepala baut dari sampel di dalam kepala; kepercayaan = porsi sampel dekat median. */
    fun headColor(img: HsvImage, x: Int, y: Int, r: Int): HeadColor {
        val hs = ArrayList<Double>(); val ss = ArrayList<Double>(); val vs = ArrayList<Double>()
        for (f in VOTE_R) {
            val steps = if (f == 0.0) 1 else 12
            for (k in 0 until steps) {
                val a = 2 * Math.PI * k / steps
                val px = min(img.w - 1, max(0, rnd(x + f * r * cos(a))))
                val py = min(img.h - 1, max(0, rnd(y + f * r * sin(a))))
                val p = img.at(px, py)
                hs.add((if (p[0] >= 165) p[0] - 180 else p[0]).toDouble()); ss.add(p[1].toDouble()); vs.add(p[2].toDouble())
            }
        }
        val mh = median(hs.toDoubleArray()); val ms = median(ss.toDoubleArray()); val mv = median(vs.toDoubleArray())
        var near = 0
        for (i in hs.indices) {
            if (ms < 40) { if (ss[i] < 55) near++ } else if (Math.abs(hs[i] - mh) <= 4 && ss[i] >= 0.6 * ms) near++
        }
        val conf = near.toDouble() / hs.size
        val h = Math.floorMod(rnd(mh), 180)
        return HeadColor(nameOf(h, rnd(ms), rnd(mv)), conf, h, rnd(ms), rnd(mv))
    }

    /** Fitur dari potongan S x S BGR (byte, 3 per piksel): HOG 4x4x6, pita radial, kepercayaan warna, skor '+'. */
    fun features(crop: ByteArray, score: Double, conf: Double): DoubleArray {
        val g = DoubleArray(S * S)
        val sat = DoubleArray(S * S)
        for (i in 0 until S * S) {
            val b = crop[i * 3].toInt() and 0xFF
            val gg = crop[i * 3 + 1].toInt() and 0xFF
            val r = crop[i * 3 + 2].toInt() and 0xFF
            g[i] = 0.114 * b + 0.587 * gg + 0.299 * r
            sat[i] = hsvPx(b, gg, r)[1].toDouble()
        }
        val nb = 6; val cs = 6; val nc = S / cs
        val hog = DoubleArray(nc * nc * nb)
        for (y in 0 until S) for (x in 0 until S) {
            val gx = if (x in 1 until S - 1) g[y * S + x + 1] - g[y * S + x - 1] else 0.0
            val gy = if (y in 1 until S - 1) g[(y + 1) * S + x] - g[(y - 1) * S + x] else 0.0
            val mag = sqrt(gx * gx + gy * gy)
            val ang = (Math.toDegrees(atan2(gy, gx)) + 180.0) % 180.0
            val bin = (ang / (180.0 / nb)).toInt() % nb
            hog[((y / cs) * nc + x / cs) * nb + bin] += mag
        }
        var ss = 0.0
        for (v in hog) ss += v * v
        val nrm = sqrt(ss) + 1.0
        for (i in hog.indices) hog[i] /= nrm

        val c = S / 2.0 - 0.5
        val bands = arrayOf(doubleArrayOf(0.0, 0.25), doubleArrayOf(0.25, 0.5), doubleArrayOf(0.5, 0.75), doubleArrayOf(0.75, 1.0), doubleArrayOf(1.0, 1.5))
        val rad = DoubleArray(bands.size * 3)
        for ((bi, bd) in bands.withIndex()) {
            var n = 0; var sg = 0.0; var ssat = 0.0
            for (y in 0 until S) for (x in 0 until S) {
                val rr = sqrt((x - c) * (x - c) + (y - c) * (y - c)) / (S / 2.0)
                if (rr >= bd[0] && rr < bd[1]) { n++; sg += g[y * S + x]; ssat += sat[y * S + x] }
            }
            val mean = sg / n
            var acc = 0.0
            for (y in 0 until S) for (x in 0 until S) {
                val rr = sqrt((x - c) * (x - c) + (y - c) * (y - c)) / (S / 2.0)
                if (rr >= bd[0] && rr < bd[1]) { val d = g[y * S + x] - mean; acc += d * d }
            }
            rad[bi * 3] = mean / 255.0
            rad[bi * 3 + 1] = sqrt(acc / n) / 255.0
            rad[bi * 3 + 2] = ssat / n / 255.0
        }
        val out = DoubleArray(hog.size + rad.size + 2)
        hog.copyInto(out); rad.copyInto(out, hog.size)
        out[hog.size + rad.size] = conf
        out[hog.size + rad.size + 1] = min(score, 20.0) / 20.0
        return out
    }

    fun prob(f: DoubleArray): Double {
        val m = ModelWeights
        val x = DoubleArray(m.NF) { (f[it] - m.MEAN[it]) / m.STD[it] }
        var z = m.B2
        for (j in 0 until m.NH) {
            var a = m.B1[j]
            for (i in 0 until m.NF) a += x[i] * m.W1[i * m.NH + j]
            if (a > 0) z += a * m.W2[j]
        }
        z = max(-30.0, min(30.0, z))
        return 1.0 / (1.0 + exp(-z))
    }

    /** Titik ketuk aman: tidak jatuh di/terlalu dekat kepala baut lain yang warnanya berbeda. */
    fun safe(t: Screw, all: List<Screw>): Boolean {
        for (d in all) {
            if (d === t || d.color == t.color || d.conf < 0.35) continue   // kepercayaan sangat rendah = bukan pusat kepala
            val dx = (d.x - t.x).toDouble(); val dy = (d.y - t.y).toDouble()
            val lim = 1.8 * min(d.r.toDouble(), 1.4 * t.r) + 4   // deteksi palsu besar tidak memblokir terlalu luas
            if (dx * dx + dy * dy < lim * lim) return false
        }
        return true
    }
}
