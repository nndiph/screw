package com.andre.screwbot

import kotlin.math.max

// Logika murni (tanpa Android/OpenCV): warna, pembacaan HUD, dan pemilihan target.
// Port 1:1 dari referensi Python (screwdetect.py + bot.py).

data class Screw(val x: Int, val y: Int, val score: Double, val color: String, val r: Int)

/** Kotak penampung yang sudah terbuka: warna dan jumlah lubang yang masih kosong. */
data class Box(val color: String, val free: Int)

/** Gambar HSV (skala OpenCV: H 0-179, S/V 0-255) dalam larik byte, tiga byte per piksel. */
class HsvImage(val w: Int, val h: Int, val data: ByteArray) {
    fun at(x: Int, y: Int): IntArray {
        val i = (y * w + x) * 3
        return intArrayOf(data[i].toInt() and 0xFF, data[i + 1].toInt() and 0xFF, data[i + 2].toInt() and 0xFF)
    }
}

object Colors {
    /** Palet tiap level berbeda, jadi semua warna dikenali. */
    val SCREW = setOf("merah", "oranye", "kuning", "hijau", "toska", "biru", "ungu", "pink", "coklat", "putih")

    fun classify(h: Double, s: Double, v: Double): String {
        if (s < 45) return if (v > 170) "putih" else "?"
        if (v < 175 && h >= 4 && h <= 24 && s > 90) return "coklat"
        if (h < 7 || h >= 168) return "merah"
        if (h < 19) return "oranye"
        if (h < 36) return "kuning"
        if (h < 85) return "hijau"
        if (h < 99) return "toska"
        if (h < 123) return "biru"
        if (h < 143) return "ungu"
        return "pink"
    }
}

object Hud {
    // Posisi sebagai pecahan layar, dikalibrasi dari screenshot 1159x2576.
    private val BOX_X = doubleArrayOf(0.134, 0.376, 0.620, 0.863)
    private const val BOX_Y = 0.097
    // tiga lubang di dalam kotak: (dx, dy) relatif terhadap pegangan
    private val BOX_HOLES = arrayOf(doubleArrayOf(0.002, 0.1332), doubleArrayOf(-0.0365, 0.1603), doubleArrayOf(0.041, 0.1603))
    private val HOLE_X = doubleArrayOf(0.265, 0.381, 0.499, 0.615, 0.732)
    private const val HOLE_Y = 0.2213
    private const val COIN_X = 0.062
    private const val COIN_Y = 0.064

    private fun px(img: HsvImage, fx: Double, fy: Double) = img.at((fx * img.w).toInt(), (fy * img.h).toInt())

    private fun holeEmpty(p: IntArray) = p[1] < 75 && p[2] > 90 && p[2] < 175

    /** Kotak terbuka beserta lubang kosongnya. Kotak terkunci (abu-abu gelap) dilewati. */
    fun readBoxes(img: HsvImage): List<Box> {
        val out = ArrayList<Box>()
        for (fx in BOX_X) {
            val p = px(img, fx, BOX_Y)
            val color: String? = when {
                p[1] < 30 && p[2] > 215 -> "putih"     // kotak putih: terang, hampir tanpa warna
                p[1] > 90 -> Colors.classify(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
                else -> null
            }
            if (color == null) continue
            var free = 0
            for (d in BOX_HOLES) if (holeEmpty(px(img, fx + d[0], d[1]))) free++
            out.add(Box(color, free))
        }
        return out
    }

    fun freeHoles(img: HsvImage): Int = HOLE_X.count { holeEmpty(px(img, it, HOLE_Y)) }

    /** Layar permainan normal: koin kuning di kiri atas dan lubang penyangga terbaca. */
    fun inGame(img: HsvImage): Boolean {
        val c = px(img, COIN_X, COIN_Y)
        val coin = c[0] in 15..30 && c[1] > 150 && c[2] > 150
        val ok = HOLE_X.count { val p = px(img, it, HOLE_Y); holeEmpty(p) || p[1] > 100 }
        return coin && ok >= 4
    }
}

object Strategy {
    private fun sq(v: Int) = v.toLong() * v

    /**
     * Pilih baut untuk diketuk. Hanya baut yang warnanya sama dengan kotak terbuka, sebanyak sisa lubang kotak
     * itu (dikurangi ketukan yang masih "terbang" menuju kotak). Penyangga diisi hanya bila [allowFill] benar.
     * Mengembalikan (daftar target, alasan).
     */
    fun chooseTargets(
        screws: List<Screw>, boxes: List<Box>, bufferFree: Int, reserve: Int, batch: Int,
        blacklist: List<Pair<Int, Int>>, minGap: Int, pending: Map<String, Int>, allowFill: Boolean,
    ): Pair<List<Screw>, String> {
        val cap = HashMap<String, Int>()
        for (b in boxes) cap[b.color] = (cap[b.color] ?: 0) + b.free
        for ((c, n) in pending) cap[c] = max(0, (cap[c] ?: 0) - n)

        val ok = screws.filter { s -> blacklist.none { (bx, by) -> sq(s.x - bx) + sq(s.y - by) < 30L * 30 } }
        val picks = ArrayList<Screw>()
        val used = HashMap<String, Int>()
        for (s in ok.filter { (cap[it.color] ?: 0) > 0 }.sortedByDescending { it.score }) {
            if ((used[s.color] ?: 0) >= (cap[s.color] ?: 0)) continue
            if (picks.all { sq(s.x - it.x) + sq(s.y - it.y) > minGap.toLong() * minGap }) {
                picks.add(s)
                used[s.color] = (used[s.color] ?: 0) + 1
            }
            if (picks.size >= batch) break
        }
        if (picks.isNotEmpty()) return Pair(picks, "cocok kotak")
        if (boxes.isNotEmpty() && cap.values.sum() <= 0) return Pair(emptyList(), "kotak penuh")
        if (allowFill && bufferFree > reserve) return Pair(ok.sortedByDescending { it.score }.take(1), "isi penyangga")
        return Pair(emptyList(), "tidak ada yang cocok")
    }
}
