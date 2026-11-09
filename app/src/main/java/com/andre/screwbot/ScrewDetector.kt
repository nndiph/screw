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
 * Detektor baut. Port dari screwml.py (referensi Python yang diuji pada screenshot berlabel).
 * 1) kandidat longgar: HoughCircles + puncak respons filter '+' pada dua ukuran baut;
 * 2) tiap kandidat dinilai classifier (ScrewML.prob); 3) warna diambil dari dalam kepala baut.
 */
object ScrewDetector {
    private val ROI = doubleArrayOf(0.0, 0.31, 1.0, 0.83)
    private val EXCLUDE = arrayOf(
        doubleArrayOf(0.0, 0.72, 0.11, 0.83),   // slider zoom
        doubleArrayOf(0.93, 0.0, 1.0, 1.0),     // tepi kanan: tombol melayang sistem/ScrewBot
    )
    private val ANGLES = intArrayOf(0, 15, 30, 45)
    private val SCALES = doubleArrayOf(0.8, 1.0, 1.25)
    private const val REF_W = 1159.0
    private const val PROC_SCALE = 0.7
    private const val HOUGH_P2 = 13.0 * 0.6   // PROC_SCALE < 0.8

    private class Cand(val score: Double, val x: Int, val y: Int, val r: Int)

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
    private fun plusResponse(small: Mat, base: Double): Mat {
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

    // ------------------------------------------------------------------ kandidat
    private fun rawCands(bgr: Mat, minScore: Double = 5.0): List<Cand> {
        val W = bgr.cols()
        val H = bgr.rows()
        val x0 = (ROI[0] * W).toInt()
        val y0 = (ROI[1] * H).toInt()
        val x1 = (ROI[2] * W).toInt()
        val y1 = (ROI[3] * H).toInt()
        val roi = bgr.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val out = ArrayList<Cand>()
        for (z in doubleArrayOf(1.0, 1.75)) {
            val pe = PROC_SCALE / z
            val small = Mat()
            Imgproc.resize(roi, small, Size(), pe, pe, Imgproc.INTER_AREA)
            val base = small.cols() / REF_W * z
            val pm = plusResponse(small, base)
            val pw = pm.cols()
            val ph = pm.rows()
            val pmA = FloatArray(pw * ph)
            pm.get(0, 0, pmA)

            val cands = ArrayList<Cand>()   // koordinat gambar proses
            val gray = Mat()
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.medianBlur(gray, gray, 3)
            val circ = Mat()
            Imgproc.HoughCircles(
                gray, circ, Imgproc.HOUGH_GRADIENT, 1.2, 20 * base, 80.0, HOUGH_P2,
                (15 * base).toInt(), (36 * base).toInt(),
            )
            val w = rnd(8 * base)
            for (i in 0 until circ.cols()) {
                val c = circ.get(0, i) ?: continue
                val cx = rnd(c[0]); val cy = rnd(c[1]); val rr = rnd(c[2])
                var best = Float.NEGATIVE_INFINITY
                for (yy in max(0, cy - w)..min(ph - 1, cy + w)) for (xx in max(0, cx - w)..min(pw - 1, cx + w)) {
                    val v = pmA[yy * pw + xx]
                    if (v > best) best = v
                }
                if (best > Float.NEGATIVE_INFINITY) cands.add(Cand(best.toDouble(), cx, cy, rr))
            }
            val ksz = rnd(21 * base) or 1
            val dil = Mat()
            Imgproc.dilate(pm, dil, Mat.ones(ksz, ksz, CvType.CV_8U))
            val dA = FloatArray(pw * ph)
            dil.get(0, 0, dA)
            for (yy in 0 until ph) for (xx in 0 until pw) {
                val v = pmA[yy * pw + xx]
                if (v >= minScore && v == dA[yy * pw + xx]) cands.add(Cand(v.toDouble(), xx, yy, rnd(24 * base)))
            }
            for (c in cands) {
                if (c.score < minScore) continue
                val X = (c.x / pe).toInt() + x0
                val Y = (c.y / pe).toInt() + y0
                val R = max(8, (c.r / pe).toInt())
                if (EXCLUDE.any { e -> e[0] * W <= X && X <= e[2] * W && e[1] * H <= Y && Y <= e[3] * H }) continue
                out.add(Cand(c.score, X, Y, R))
            }
            small.release(); pm.release(); gray.release(); circ.release(); dil.release()
        }
        roi.release()
        out.sortWith(compareByDescending<Cand> { it.score }.thenByDescending { it.x }.thenByDescending { it.y }.thenByDescending { it.r })
        val kept = ArrayList<Cand>()
        for (c in out) {
            if (kept.all { k -> val lim = 0.45 * min(c.r, k.r); sq(c.x - k.x) + sq(c.y - k.y) > lim * lim }) kept.add(c)
        }
        return kept
    }

    private fun sq(v: Int): Double = v.toDouble() * v

    /** Potongan BGR S x S berpusat (x, y), sisi 2 * CROP_K * r, area di luar layar diisi hitam. */
    private fun crop(bgr: Mat, x: Int, y: Int, r: Int): ByteArray {
        val W = bgr.cols(); val H = bgr.rows()
        val h = max(4, ScrewML.rnd(r * ScrewML.CROP_K))
        val x0 = x - h; val y0 = y - h
        val c = Mat.zeros(2 * h, 2 * h, CvType.CV_8UC3)
        val xa = max(0, x0); val ya = max(0, y0); val xb = min(W, x0 + 2 * h); val yb = min(H, y0 + 2 * h)
        if (xb > xa && yb > ya) {
            val src = bgr.submat(Rect(xa, ya, xb - xa, yb - ya))
            val dst = c.submat(Rect(xa - x0, ya - y0, xb - xa, yb - ya))
            src.copyTo(dst)
            src.release(); dst.release()
        }
        val s = Mat()
        Imgproc.resize(c, s, Size(ScrewML.S.toDouble(), ScrewML.S.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val out = ByteArray(ScrewML.S * ScrewML.S * 3)
        s.get(0, 0, out)
        c.release(); s.release()
        return out
    }

    /** Baut terdeteksi (peluang >= MIN_P), urut peluang menurun. extraExclude: kotak piksel [x0, y0, x1, y1]. */
    fun detect(bgr: Mat, hsv: HsvImage, extraExclude: List<IntArray>, minP: Double = ScrewML.MIN_P): List<Screw> {
        val dets = ArrayList<Screw>()
        for (c in rawCands(bgr)) {
            if (extraExclude.any { e -> e[0] <= c.x && c.x <= e[2] && e[1] <= c.y && c.y <= e[3] }) continue
            val hc = ScrewML.headColor(hsv, c.x, c.y, c.r)
            val p = ScrewML.prob(ScrewML.features(crop(bgr, c.x, c.y, c.r), c.score, hc.conf))
            if (p >= minP) dets.add(Screw(c.x, c.y, p, hc.name ?: "?", c.r, hc.h, hc.s, hc.v, hc.conf))
        }
        // utamakan deteksi yang yakin baut DAN yakin warnanya
        dets.sortWith(compareByDescending<Screw> { it.score * (0.5 + 0.5 * it.conf) }.thenBy { it.x }.thenBy { it.y })
        val keep = ArrayList<Screw>()
        for (d in dets) {
            // deteksi ganda di satu baut (termasuk di tepinya) digabung
            if (keep.all { k -> val lim = 0.95 * min(max(d.r, k.r).toDouble(), 1.6 * min(d.r, k.r)); sq(d.x - k.x) + sq(d.y - k.y) > lim * lim }) keep.add(d)
        }
        return keep
    }
}
