package com.andre.screwbot

// Logika murni (tanpa Android/OpenCV): warna, pembacaan HUD, dan pemilihan target.
// Port 1:1 dari referensi Python (screwdetect.py + bot.py).

data class Screw(val x: Int, val y: Int, val score: Double, val color: String, val r: Int)

/** Gambar HSV (skala OpenCV: H 0-179, S/V 0-255) dalam larik byte, tiga byte per piksel. */
class HsvImage(val w: Int, val h: Int, val data: ByteArray) {
    fun at(x: Int, y: Int): IntArray {
        val i = (y * w + x) * 3
        return intArrayOf(data[i].toInt() and 0xFF, data[i + 1].toInt() and 0xFF, data[i + 2].toInt() and 0xFF)
    }
}

object Colors {
    val SCREW = setOf("merah", "oranye", "kuning", "biru", "ungu", "coklat")

    fun classify(h: Double, s: Double, v: Double): String {
        if (s < 70 && v > 170) return "putih"
        if (v < 175 && h >= 4 && h <= 24 && s > 90) return "coklat"
        if (h < 7 || h >= 165) return "merah"
        if (h < 19) return "oranye"
        if (h < 34) return "kuning"
        if (h < 85) return "hijau"
        if (h < 122) return "biru"
        if (h < 165) return "ungu"
        return "?"
    }
}

object Hud {
    // Posisi sebagai pecahan layar, dikalibrasi dari screenshot 1159x2576.
    private val BOX_X = doubleArrayOf(0.134, 0.376, 0.620, 0.863)
    private const val BOX_Y = 0.097
    private val HOLE_X = doubleArrayOf(0.265, 0.381, 0.499, 0.615, 0.732)
    private const val HOLE_Y = 0.2213
    private const val COIN_X = 0.062
    private const val COIN_Y = 0.064

    private fun px(img: HsvImage, fx: Double, fy: Double) = img.at((fx * img.w).toInt(), (fy * img.h).toInt())

    private fun holeEmpty(p: IntArray) = p[1] < 75 && p[2] > 90 && p[2] < 175

    /** Warna pegangan kotak yang sudah terbuka; null = terkunci (abu-abu). */
    fun readBoxes(img: HsvImage): List<String?> = BOX_X.map { fx ->
        val p = px(img, fx, BOX_Y)
        if (p[1] > 90) Colors.classify(p[0].toDouble(), p[1].toDouble(), p[2].toDouble()) else null
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

    /** Mengembalikan (daftar target, alasan). */
    fun chooseTargets(
        screws: List<Screw>, boxColors: List<String?>, free: Int, reserve: Int, batch: Int,
        blacklist: List<Pair<Int, Int>>, minGap: Int,
    ): Pair<List<Screw>, String> {
        val want = boxColors.filterNotNull().toSet()
        val ok = screws.filter { s -> blacklist.none { (bx, by) -> sq(s.x - bx) + sq(s.y - by) < 30L * 30 } }
        val match = ok.filter { it.color in want }.sortedByDescending { it.score }
        val picks = ArrayList<Screw>()
        for (s in match) {
            if (picks.all { sq(s.x - it.x) + sq(s.y - it.y) > minGap.toLong() * minGap }) picks.add(s)
            if (picks.size >= batch) return Pair(picks, "cocok kotak")
        }
        if (picks.isNotEmpty()) return Pair(picks, "cocok kotak")
        // tidak ada yang cocok: isi penyangga selama sisa lubang melebihi cadangan
        if (free > reserve) return Pair(ok.sortedByDescending { it.score }.take(1), "isi penyangga")
        return Pair(emptyList(), "tidak ada")
    }
}
