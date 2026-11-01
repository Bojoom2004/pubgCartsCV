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

/** Распознавание экрана, поиск карточки на скриншоте, нарезка и сборка архива. */
object CardCrop {

    class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    /**
     * ZOOMED  – карта увеличена лупой (тёмный фон, большая карта по центру)
     * VIEWER  – просмотр карты со стрелками ‹ › (тёмный фон, карта по центру или слева с панелью справа)
     * UNKNOWN – тёмный фон, но ни то ни другое (идёт анимация)
     * OTHER   – светлый экран: меню или сетка карт
     */
    enum class Screen { ZOOMED, VIEWER, UNKNOWN, OTHER }

    class Result(val count: Int, val uri: Uri?, val name: String)

    private fun lum(c: Int): Int =
        (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    private fun meanLum(bmp: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val w = bmp.width
        val h = bmp.height
        val px0 = (x0 * w).toInt().coerceIn(0, w - 1)
        val px1 = (x1 * w).toInt().coerceIn(px0 + 1, w)
        val py0 = (y0 * h).toInt().coerceIn(0, h - 1)
        val py1 = (y1 * h).toInt().coerceIn(py0 + 1, h)
        val rw = px1 - px0
        val line = IntArray(rw)
        var sum = 0L
        var n = 0
        var y = py0
        while (y < py1) {
            bmp.getPixels(line, 0, rw, px0, y, rw, 1)
            var x = 0
            while (x < rw) {
                sum += lum(line[x])
                n++
                x += 3
            }
            y += 3
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun brightRatio(bmp: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, thr: Int): Float {
        val w = bmp.width
        val h = bmp.height
        val px0 = (x0 * w).toInt().coerceIn(0, w - 1)
        val px1 = (x1 * w).toInt().coerceIn(px0 + 1, w)
        val py0 = (y0 * h).toInt().coerceIn(0, h - 1)
        val py1 = (y1 * h).toInt().coerceIn(py0 + 1, h)
        val rw = px1 - px0
        val line = IntArray(rw)
        var hit = 0
        var n = 0
        for (y in py0 until py1) {
            bmp.getPixels(line, 0, rw, px0, y, rw, 1)
            for (x in 0 until rw) {
                if (lum(line[x]) > thr) hit++
                n++
            }
        }
        return if (n == 0) 0f else hit.toFloat() / n
    }

    /** Ищет большую светлую рамку карточки по центру (увеличенная карта). */
    fun detectZoom(bmp: Bitmap): Box? {
        val w = bmp.width
        val h = bmp.height
        val step = 2
        val x0 = (w * 0.2f).toInt()
        val x1 = (w * 0.8f).toInt()
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
        if (ratio < 0.5f || ratio > 0.95f || bh < h * 0.82f) return null
        val cx = (l + r) / 2f
        if (abs(cx - w / 2f) > w * 0.06f) return null
        return Box(l, t, r, b)
    }

    /** Что сейчас на экране. */
    fun classify(bmp: Bitmap): Screen {
        // Просмотр и увеличение лежат на затемнённом фоне, меню и сетки светлые.
        if (meanLum(bmp, 0.0f, 0.15f, 0.10f, 0.90f) >= 95f) return Screen.OTHER
        if (detectZoom(bmp) != null) return Screen.ZOOMED
        val arrows = brightRatio(bmp, 0.025f, 0.44f, 0.068f, 0.56f, 200) +
            brightRatio(bmp, 0.932f, 0.44f, 0.975f, 0.56f, 200)
        return if (arrows > 0.03f) Screen.VIEWER else Screen.UNKNOWN
    }

    private fun fallback(bmp: Bitmap): Box {
        val t = (bmp.height * 0.048f).toInt()
        val ch = (bmp.height * 0.907f).toInt()
        val cw = (ch * 0.682f).toInt()
        val l = (bmp.width - cw) / 2
        return Box(l, t, l + cw, t + ch)
    }

    /** Вырезает увеличенную карточку из скриншота. */
    fun cropCard(bmp: Bitmap): Bitmap {
        val box = detectZoom(bmp) ?: fallback(bmp)
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

    fun same(a: FloatArray, b: FloatArray): Boolean = diff(a, b) < 4f

    private fun diff(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    /** Отпечаток сетки набора (заголовок + карты), чтобы узнавать уже снятые наборы истории. */
    fun gridSig(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val x = (w * 0.10f).toInt()
        val y = (h * 0.02f).toInt()
        val cw = (w * 0.70f).toInt().coerceAtMost(w - x)
        val ch = (h * 0.93f).toInt().coerceAtMost(h - y)
        val part = Bitmap.createBitmap(bmp, x, y, cw, ch)
        val s = Bitmap.createScaledBitmap(part, 48, 24, true)
        val px = IntArray(48 * 24)
        s.getPixels(px, 0, 48, 0, 0, 48, 24)
        val out = FloatArray(px.size) { lum(px[it]).toFloat() }
        if (s !== part) s.recycle()
        part.recycle()
        return out
    }

    fun sameGrid(a: FloatArray, b: FloatArray): Boolean = diff(a, b) < 5f

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
                    // имя файла: <папка>_<номер>.png, папка = всё до последнего "_"
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
