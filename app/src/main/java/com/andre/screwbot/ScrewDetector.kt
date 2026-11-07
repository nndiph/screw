package com.andre.screwbot

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detektor baut. Port dari screwdetect.py (referensi Python yang sudah diuji pada screenshot).
 * Kandidat dari HoughCircles + puncak respons filter '+', skor = kekuatan tanda '+'.
 */
object ScrewDetector {
    private val ROI = doubleArrayOf(0.0, 0.31, 1.0, 0.83)
    private val EXCLUDE = arrayOf(
        doubleArrayOf(0.0, 0.72, 0.11, 0.83),   // slider zoom
        doubleArrayOf(0.93, 0.35, 1.0, 0.44),   // tombol melayang kanan
    )
    private val ANGLES = intArrayOf(0, 15, 30, 45)
    private val SCALES = doubleArrayOf(0.8, 1.0, 1.25)
    private const val REF_W = 1159.0
    private const val PROC_SCALE = 0.7
    private const val HOUGH_P2 = 13.0 * 0.6   // PROC_SCALE < 0.8

    private class Cand(val fromHough: Int, val score: Double, val cx: Int, val cy: Int, val r: Int)

    private fun rnd(v: Double): Int = floor(v + 0.5).toInt()

    fun hsvOf(bgr: Mat): HsvImage {
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val bytes = ByteArray(bgr.cols() * bgr.rows() * 3)
        hsv.get(0, 0, bytes)
        hsv.release()
        return HsvImage(bgr.cols(), bgr.rows(), bytes)
    }

    // ------------------------------------------------------------------ filter '+'
    private fun plusTemplate(arm: Int, thick: Int, ring: Int): FloatArray {
        val size = ring * 2 + 1
        val t = FloatArray(size * size)
        val c = ring
        for (y in c - arm..c + arm) for (x in c - thick / 2..c + thick / 2) t[y * size + x] = 1f
        for (y in c - thick / 2..c + thick / 2) for (x in c - arm..c + arm) t[y * size + x] = 1f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - c
            val dy = y - c
            if (abs(dx) > thick && abs(dy) > thick && dx * dx + dy * dy < ring * ring) t[y * size + x] = -0.35f
        }
        return t
    }

    private fun rotated(tpl: FloatArray, size: Int, ang: Int): FloatArray {
        if (ang == 0) return tpl
        val src = Mat(size, size, CvType.CV_32F)
        src.put(0, 0, tpl)
        val dst = Mat()
        val m = Imgproc.getRotationMatrix2D(Point(size / 2.0 - 0.5, size / 2.0 - 0.5), ang.toDouble(), 1.0)
        Imgproc.warpAffine(src, dst, m, Size(size.toDouble(), size.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
        val out = FloatArray(size * size)
        dst.get(0, 0, out)
        src.release(); dst.release(); m.release()
        return out
    }

    /** Peta respons '+' pada gambar proses (BGR, sudah diperkecil). */
    private fun plusResponse(small: Mat): Mat {
        val base = small.cols() / REF_W
        val lab = Mat()
        Imgproc.cvtColor(small, lab, Imgproc.COLOR_BGR2Lab)
        val l = Mat()
        Core.extractChannel(lab, l, 0)
        val ks = max(3, rnd(9 * base)) or 1
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(ks.toDouble(), ks.toDouble()))
        val bh8 = Mat()
        Imgproc.morphologyEx(l, bh8, Imgproc.MORPH_BLACKHAT, k)
        val bh = Mat()
        bh8.convertTo(bh, CvType.CV_32F)
        Imgproc.GaussianBlur(bh, bh, Size(3.0, 3.0), 0.0)

        val res = Mat()
        var first = true
        for (s in SCALES) {
            val arm = max(3, rnd(10 * base * s))
            val thick = max(2, rnd(4 * base * s))
            val ring = (arm * 1.7).toInt()
            val size = ring * 2 + 1
            val tpl = plusTemplate(arm, thick, ring)
            for (a in ANGLES) {
                val tr = rotated(tpl, size, a)
                var np = 0
                var nn = 0
                for (v in tr) { if (v > 0.5f) np++ else if (v < -0.05f) nn++ }
                val kern = FloatArray(size * size)
                for (i in tr.indices) {
                    kern[i] = if (tr[i] > 0.5f) 1f / max(1, np) else if (tr[i] < -0.05f) -1f / max(1, nn) else 0f
                }
                val km = Mat(size, size, CvType.CV_32F)
                km.put(0, 0, kern)
                val r = Mat()
                Imgproc.filter2D(bh, r, -1, km)
                if (first) { r.copyTo(res); first = false } else Core.max(res, r, res)
                km.release(); r.release()
            }
        }
        lab.release(); l.release(); k.release(); bh8.release(); bh.release()
        return res
    }

    // ------------------------------------------------------------------ warna
    private fun median(v: DoubleArray): Double {
        val s = v.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun std(v: DoubleArray): Double {
        val m = v.average()
        var acc = 0.0
        for (x in v) acc += (x - m) * (x - m)
        return sqrt(acc / v.size)
    }

    /** Cincin badan baut: baris [h, s, v]; kosong bila terlalu sedikit titik valid. */
    private fun ring(img: HsvImage, cx: Int, cy: Int, r: Int): List<IntArray> {
        val pts = ArrayList<IntArray>()
        for (rf in doubleArrayOf(0.45, 0.6)) {
            for (i in 0 until 16) {
                val ang = i * 2.0 * Math.PI / 16 + Math.PI / 8
                val px = (cx + cos(ang) * r * rf).toInt()
                val py = (cy + sin(ang) * r * rf * 0.85).toInt()
                if (px in 0 until img.w && py in 0 until img.h) pts.add(img.at(px, py))
            }
        }
        return pts
    }

    /** Geser pusat (+-2 langkah) ke posisi dengan cincin paling seragam, lalu baca warnanya. */
    private fun bodyColor(img: HsvImage, cx: Int, cy: Int, r: Int): Triple<String, Int, Int> {
        var bestCost = Double.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        var bestPts: List<IntArray>? = null
        val step = max(3, (r * 0.25).toInt())
        for (iy in -2..2) for (ix in -2..2) {
            val dy = iy * step
            val dx = ix * step
            val a = ring(img, cx + dx, cy + dy, r)
            if (a.size < 16) continue
            val hh = DoubleArray(a.size) { val h = a[it][0].toDouble(); if (h >= 165) h - 180 else h }
            val vv = DoubleArray(a.size) { a[it][2].toDouble() }
            val ss = DoubleArray(a.size) { a[it][1].toDouble() }
            val cost = std(hh) * 3 + std(vv) * 0.5 - median(ss) * 0.05 + (abs(dx) + abs(dy)) * 0.3
            if (cost < bestCost) { bestCost = cost; bestDx = dx; bestDy = dy; bestPts = a }
        }
        val a = bestPts ?: return Triple("?", cx, cy)
        val hh = DoubleArray(a.size) { val h = a[it][0].toDouble(); if (h >= 165) h - 180 else h }
        var h = median(hh) % 180
        if (h < 0) h += 180
        val s = median(DoubleArray(a.size) { a[it][1].toDouble() })
        val v = median(DoubleArray(a.size) { a[it][2].toDouble() })
        return Triple(Colors.classify(h, s, v), cx + bestDx, cy + bestDy)
    }

    // ------------------------------------------------------------------ deteksi
    /**
     * @param bgr gambar layar penuh (BGR)
     * @param extraExclude persegi [x0,y0,x1,y1] dalam piksel yang tidak boleh berisi baut (mis. panel melayang)
     */
    fun detect(bgr: Mat, hsv: HsvImage, extraExclude: List<IntArray>, minScore: Double = 6.5): List<Screw> {
        val W = bgr.cols()
        val H = bgr.rows()
        val x0 = (ROI[0] * W).toInt()
        val y0 = (ROI[1] * H).toInt()
        val x1 = (ROI[2] * W).toInt()
        val y1 = (ROI[3] * H).toInt()

        val roi = Mat(bgr, Rect(x0, y0, x1 - x0, y1 - y0))
        val small = Mat()
        Imgproc.resize(roi, small, Size(), PROC_SCALE, PROC_SCALE, Imgproc.INTER_AREA)
        val base = small.cols() / REF_W

        val pm = plusResponse(small)
        val pw = pm.cols()
        val ph = pm.rows()
        val pmArr = FloatArray(pw * ph)
        pm.get(0, 0, pmArr)

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.medianBlur(gray, gray, 3)

        val cands = ArrayList<Cand>()
        val circles = Mat()
        Imgproc.HoughCircles(
            gray, circles, Imgproc.HOUGH_GRADIENT, 1.2, 26 * base, 80.0, HOUGH_P2,
            (17 * base).toInt(), (34 * base).toInt(),
        )
        val w = rnd(8 * base)
        for (i in 0 until circles.cols()) {
            val c = circles.get(0, i) ?: continue
            val cx = Math.rint(c[0]).toInt()
            val cy = Math.rint(c[1]).toInt()
            val rr = Math.rint(c[2]).toInt()
            var best = -Float.MAX_VALUE
            for (yy in max(0, cy - w)..min(ph - 1, cy + w)) for (xx in max(0, cx - w)..min(pw - 1, cx + w)) {
                best = max(best, pmArr[yy * pw + xx])
            }
            if (best > -Float.MAX_VALUE) cands.add(Cand(1, best.toDouble(), cx, cy, rr))
        }

        // puncak respons '+' yang tidak tertangkap Hough
        val ksz = rnd(27 * base) or 1
        val dil = Mat()
        Imgproc.dilate(pm, dil, Mat.ones(ksz, ksz, CvType.CV_8U))
        val dilArr = FloatArray(pw * ph)
        dil.get(0, 0, dilArr)
        val peakThr = max(minScore + 2, 8.5)
        for (py in 0 until ph) for (px in 0 until pw) {
            val v = pmArr[py * pw + px]
            if (v == dilArr[py * pw + px] && v >= peakThr) cands.add(Cand(0, v.toDouble(), px, py, rnd(24 * base)))
        }

        cands.sortWith(
            compareByDescending<Cand> { it.fromHough }.thenByDescending { it.score }
                .thenByDescending { it.cx }.thenByDescending { it.cy }.thenByDescending { it.r },
        )
        val kept = ArrayList<Cand>()
        val minD = 28 * base
        for (c in cands) {
            if (c.score < minScore) continue
            if (kept.all { sq(c.cx - it.cx) + sq(c.cy - it.cy) > minD * minD }) kept.add(c)
        }

        val out = ArrayList<Screw>()
        for (c in kept) {
            val cx = (c.cx / PROC_SCALE).toInt() + x0
            val cy = (c.cy / PROC_SCALE).toInt() + y0
            val r = max(8, (c.r / PROC_SCALE).toInt())
            if (EXCLUDE.any { cx >= it[0] * W && cx <= it[2] * W && cy >= it[1] * H && cy <= it[3] * H }) continue
            if (extraExclude.any { cx >= it[0] && cx <= it[2] && cy >= it[1] && cy <= it[3] }) continue
            val (color, fx, fy) = bodyColor(hsv, cx, cy, r)
            if (color in Colors.SCREW) out.add(Screw(fx, fy, c.score, color, r))
        }
        small.release(); roi.release(); pm.release(); gray.release(); circles.release(); dil.release()
        return out
    }

    private fun sq(v: Int): Double = v.toDouble() * v

    /** Apakah area di sekitar (x,y) berubah antara dua gambar? Dipakai untuk membuang ketukan sia-sia. */
    fun regionChanged(before: Mat, after: Mat, x: Int, y: Int, r: Int = 45, thr: Double = 6.0): Boolean {
        val xa = max(0, x - r)
        val ya = max(0, y - r)
        val xb = min(min(before.cols(), after.cols()), x + r)
        val yb = min(min(before.rows(), after.rows()), y + r)
        if (xb <= xa || yb <= ya) return true
        val a = Mat(before, Rect(xa, ya, xb - xa, yb - ya))
        val b = Mat(after, Rect(xa, ya, xb - xa, yb - ya))
        val d = Mat()
        Core.absdiff(a, b, d)
        val m = Core.mean(d)
        d.release()
        return (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3.0 > thr
    }
}
