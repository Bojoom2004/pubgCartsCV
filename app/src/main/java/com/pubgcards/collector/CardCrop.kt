package com.pubgcards.collector

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Поиск карточки на скриншоте, нарезка и сборка архива. */
object CardCrop {

    class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    enum class Screen { ZOOMED, VIEWER, OTHER }

    class Result(val count: Int, val uri: Uri?, val name: String)

    private fun lum(c: Int): Int =
        (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    /** Ищет светлую рамку карточки в центральной части экрана. */
    fun detect(bmp: Bitmap): Box? {
        val w = bmp.width
        val h = bmp.height
        val step = 2
        val x0 = (w * 0.25f).toInt()
        val x1 = (w * 0.75f).toInt()
        val rw = x1 - x0
        val cols = rw / step
        val rows = h / step
        if (cols <= 0 || rows <= 0) return null
        val colCount = IntArray(cols)
        val rowCount = IntArray(rows)
        val line = IntArray(rw)
        for (ry in 0 until rows) {
            bmp.getPixels(line, 0, rw, x0, ry * step, rw, 1)
            var cnt = 0
            for (cx in 0 until cols) {
                if (lum(line[cx * step]) > 85) {
                    colCount[cx]++
                    cnt++
                }
            }
            rowCount[ry] = cnt
        }
        var cMin = -1
        var cMax = -1
        for (cx in 0 until cols) {
            if (colCount[cx] > rows * 0.15f) {
                if (cMin < 0) cMin = cx
                cMax = cx
            }
        }
        var rMin = -1
        var rMax = -1
        for (ry in 0 until rows) {
            if (rowCount[ry] > cols * 0.10f) {
                if (rMin < 0) rMin = ry
                rMax = ry
            }
        }
        if (cMin < 0 || rMin < 0) return null
        val l = x0 + cMin * step
        val r = x0 + (cMax + 1) * step
        val t = rMin * step
        val b = (rMax + 1) * step
        val bw = r - l
        val bh = b - t
        if (bw <= 0 || bh <= 0) return null
        val ratio = bw.toFloat() / bh
        if (ratio < 0.5f || ratio > 0.9f || bh < h * 0.4f) return null
        return Box(l, t, r, b)
    }

    /** Что сейчас на экране: увеличенная карта, просмотр карты или что-то другое (меню/сетка). */
    fun classify(bmp: Bitmap): Screen {
        val b = detect(bmp) ?: return Screen.OTHER
        val r = b.h.toFloat() / bmp.height
        return when {
            r >= 0.82f -> Screen.ZOOMED
            r >= 0.40f && r < 0.82f -> Screen.VIEWER
            else -> Screen.OTHER
        }
    }

    private fun fallback(bmp: Bitmap): Box {
        val ch = (bmp.height * 0.905f).toInt()
        val cw = (ch * 0.682f).toInt()
        val l = (bmp.width - cw) / 2
        val t = (bmp.height * 0.045f).toInt()
        return Box(l, t, l + cw, t + ch)
    }

    /** Вырезает увеличенную карточку из скриншота. */
    fun cropCard(bmp: Bitmap): Bitmap {
        val d = detect(bmp)
        val box = if (d != null && d.h >= bmp.height * 0.8f) d else fallback(bmp)
        val l = box.l.coerceIn(0, bmp.width - 1)
        val t = box.t.coerceIn(0, bmp.height - 1)
        val w = box.w.coerceAtMost(bmp.width - l).coerceAtLeast(1)
        val h = box.h.coerceAtMost(bmp.height - t).coerceAtLeast(1)
        return Bitmap.createBitmap(bmp, l, t, w, h)
    }

    /** Маленький «отпечаток» карточки, чтобы узнавать повторы. */
    fun signature(card: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(card, 24, 32, true)
        val px = IntArray(24 * 32)
        s.getPixels(px, 0, 24, 0, 0, 24, 32)
        val out = FloatArray(px.size) { lum(px[it]).toFloat() }
        if (s !== card) s.recycle()
        return out
    }

    fun same(a: FloatArray, b: FloatArray): Boolean {
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size < 4f
    }

    /** Режет все снимки из папки и кладёт zip в Загрузки/PUBG_cards. */
    fun process(ctx: Context, rawDir: File, progress: (String) -> Unit): Result {
        val files = (rawDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        if (files.isEmpty()) return Result(0, null, "")

        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val zipName = "pubg_cards_$stamp.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zipName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PUBG_cards")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result(0, null, "")

        var n = 0
        val os = resolver.openOutputStream(uri) ?: return Result(0, null, "")
        os.use { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                for (f in files) {
                    val raw = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                    val card = cropCard(raw)
                    val baos = ByteArrayOutputStream()
                    card.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    val base = f.nameWithoutExtension
                    val i = base.lastIndexOf('_')
                    val entry = if (i > 0) {
                        base.substring(0, i) + "/" + base.substring(i + 1) + ".png"
                    } else {
                        "$base.png"
                    }
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(baos.toByteArray())
                    zip.closeEntry()
                    if (card !== raw) card.recycle()
                    raw.recycle()
                    n++
                    progress("Нарезка: $n из ${files.size}")
                }
            }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
            .edit().putString("lastZip", uri.toString()).apply()
        return Result(n, uri, zipName)
    }
}
