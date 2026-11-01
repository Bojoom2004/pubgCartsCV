package com.pubgcards.collector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Служба специальных возможностей: рисует розовые блоки для настройки точек,
 * сама нажимает на экран, делает скриншоты и запускает нарезку.
 */
class CardService : AccessibilityService() {

    class Step(val text: String, val keys: List<String>, val hints: List<Int>)

    companion object {
        @Volatile
        var instance: CardService? = null
        const val MAX_CARDS = 40
        val PINK: Int = Color.rgb(255, 20, 147)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val shotExec = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    // ---- калибровка ----
    private var calSteps: List<Step> = emptyList()
    private var calStep = 0
    private val calFrac = HashMap<String, PointF>()
    private val pins = HashMap<String, PinView>()
    private val pinLp = HashMap<String, WindowManager.LayoutParams>()
    private var pinsHidden = false
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelText: TextView? = null
    private var nextChip: TextView? = null
    private var hideChip: TextView? = null
    private var hintView: ImageView? = null
    private var hintIdx = 0

    // ---- запуск ----
    private var runPanel: LinearLayout? = null
    private var runLp: WindowManager.LayoutParams? = null
    private var runText: TextView? = null
    private var runRow: LinearLayout? = null

    // =====================================================================
    // Жизненный цикл службы
    // =====================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Status.text = "Служба включена"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        main.post { stopAll() }
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ relayout() }, 400)
    }

    // =====================================================================
    // Вспомогательное
    // =====================================================================

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun screen(): Point {
        val b = wm.maximumWindowMetrics.bounds
        return Point(b.width(), b.height())
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun rounded(color: Int, r: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        return lp
    }

    private fun addOv(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            toast("Не удалось показать окно: " + e.message)
        }
    }

    private fun rmOv(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun upOv(v: View?, lp: WindowManager.LayoutParams?) {
        if (v == null || lp == null) return
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
        }
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
        t.background = rounded(color, 10f)
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
        t.layoutParams = lp
        return t
    }

    private fun makeDraggable(handle: View, win: View, lp: WindowManager.LayoutParams) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    ox = lp.x
                    oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (ev.rawX - sx).toInt()
                    lp.y = oy + (ev.rawY - sy).toInt()
                    upOv(win, lp)
                }
            }
            true
        }
    }

    fun stopAll() {
        running = false
        endCalibration()
        removeRunPanel()
    }

    // =====================================================================
    // Розовый блок (точка нажатия)
    // =====================================================================

    private inner class PinView(val key: String, val label: String) : View(this@CardService) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 255, 20, 147)
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2f).toFloat()
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(11f).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(2f).toFloat()
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rr = RectF(2f, 2f, w - 2f, h - 2f)
            val rad = dp(8f).toFloat()
            c.drawRoundRect(rr, rad, rad, bg)
            c.drawRoundRect(rr, rad, rad, stroke)
            c.drawText(label, w / 2f, dp(15f).toFloat(), txt)
            val cx = w / 2f
            val cy = h / 2f + dp(4f)
            val a = dp(9f).toFloat()
            c.drawLine(cx - a, cy, cx + a, cy, cross)
            c.drawLine(cx, cy - a, cx, cy + a, cross)
        }
    }

    private fun pinCenterOffsetY(): Float = dp(4f).toFloat()

    private fun addPin(key: String) {
        val def = Points.def(key)
        val v = PinView(key, def.label)
        val lp = overlayParams(dp(130f), dp(56f))
        val s = screen()
        val f = calFrac.getOrPut(key) { Points.get(this, key) }
        // Центр креста = точка нажатия
        lp.x = (f.x * s.x - lp.width / 2f).toInt()
        lp.y = (f.y * s.y - lp.height / 2f - pinCenterOffsetY()).toInt()
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    ox = lp.x
                    oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (ev.rawX - sx).toInt()
                    lp.y = oy + (ev.rawY - sy).toInt()
                    upOv(v, lp)
                }
                MotionEvent.ACTION_UP -> {
                    val sc = screen()
                    calFrac[key] = PointF(
                        (lp.x + lp.width / 2f) / sc.x,
                        (lp.y + lp.height / 2f + pinCenterOffsetY()) / sc.y
                    )
                }
            }
            true
        }
        pins[key] = v
        pinLp[key] = lp
        addOv(v, lp)
    }

    private fun removePins() {
        for (v in pins.values) rmOv(v)
        pins.clear()
        pinLp.clear()
    }

    private fun showStepPins() {
        if (calSteps.isEmpty()) return
        for (k in calSteps[calStep].keys) addPin(k)
    }

    // =====================================================================
    // Калибровка
    // =====================================================================

    private fun stepsFor(mode: String): List<Step> {
        val viewer = Step(
            "Шаг 3/3 · Просмотр карты\nОткрой карту. Если она по центру – блок «по центру» на лупу. Если слева, а справа панель (уникальная или серая карта) – блок «слева» на лупу. Какого вида в игре нет – оставь блок как есть. Ещё: стрелка › и крестик ✕.",
            listOf("zoom", "zoomU", "next", "close"),
            listOf(R.drawable.hint_3, R.drawable.hint_12, R.drawable.hint_8, R.drawable.hint_9, R.drawable.hint_13, R.drawable.hint_4)
        )
        return if (mode == "current") {
            listOf(
                Step(
                    "Шаг 1/3 · Меню коллекции\nОткрой экран с наборами и поставь блоки на наборы. Центр креста = точка нажатия.",
                    listOf("set1", "set2", "set3", "set4"),
                    listOf(R.drawable.hint_1)
                ),
                Step(
                    "Шаг 2/3 · Внутри набора\nОткрой любой набор. Блок – на самую первую карту (уникальные стоят первыми).",
                    listOf("first"),
                    listOf(R.drawable.hint_11, R.drawable.hint_2)
                ),
                viewer
            )
        } else {
            listOf(
                Step(
                    "Шаг 1/3 · Меню коллекции\nБлок – на надпись «История» вверху слева.",
                    listOf("history"),
                    listOf(R.drawable.hint_5)
                ),
                Step(
                    "Шаг 2/3 · Экран «История»\nОткрой «Историю». Справа список наборов. Блоки списка – на 2-й и 3-й набор сверху (ровно по названиям). «Первая карта» – на первую карту в сетке.",
                    listOf("h1", "h2", "first"),
                    listOf(R.drawable.hint_10, R.drawable.hint_6)
                ),
                viewer
            )
        }
    }

    fun startCalibration(mode: String) {
        main.post {
            stopAll()
            calSteps = stepsFor(mode)
            calStep = 0
            calFrac.clear()
            buildCalPanel()
            showStep()
        }
    }

    private fun buildCalPanel() {
        val s = screen()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        box.background = rounded(Color.argb(238, 20, 20, 30), 14f)

        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        tv.setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        box.addView(tv)
        panelText = tv

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(chip("Подсказка", Color.rgb(60, 90, 200)) { nextHint() })
        val hc = chip("Скрыть блоки", Color.rgb(80, 80, 95)) { togglePins() }
        hideChip = hc
        r1.addView(hc)
        box.addView(r1)

        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(chip("◀ Назад", Color.rgb(80, 80, 95)) { calBack() })
        val nc = chip("Далее ▶", PINK) { calNext() }
        nextChip = nc
        r2.addView(nc)
        r2.addView(chip("✖", Color.rgb(160, 40, 40)) { endCalibration() })
        box.addView(r2)

        val width = minOf(dp(440f), (s.x * 0.9f).toInt())
        val lp = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = ((s.x - width) / 2).coerceAtLeast(0)
        lp.y = (s.y - dp(190f)).coerceAtLeast(0)
        makeDraggable(tv, box, lp)
        panel = box
        panelLp = lp
        addOv(box, lp)
    }

    private fun showStep() {
        removePins()
        closeHint()
        hintIdx = 0
        pinsHidden = false
        hideChip?.text = "Скрыть блоки"
        nextChip?.text = if (calStep == calSteps.lastIndex) "✔ Сохранить" else "Далее ▶"
        val s = screen()
        if (s.x < s.y) {
            panelText?.text = "Открой игру: она горизонтальная. Блоки появятся, когда экран станет горизонтальным."
            return
        }
        panelText?.text = calSteps[calStep].text
        showStepPins()
    }

    private fun togglePins() {
        closeHint()
        if (pinsHidden) {
            pinsHidden = false
            showStepPins()
            hideChip?.text = "Скрыть блоки"
        } else {
            pinsHidden = true
            removePins()
            hideChip?.text = "Показать блоки"
        }
    }

    private fun calNext() {
        if (calSteps.isEmpty()) return
        if (calStep < calSteps.lastIndex) {
            calStep++
            showStep()
        } else {
            saveCal()
        }
    }

    private fun calBack() {
        if (calStep > 0) {
            calStep--
            showStep()
        }
    }

    private fun saveCal() {
        for ((k, f) in calFrac) Points.save(this, k, f.x, f.y)
        endCalibration()
        Status.text = "Точки сохранены"
        toast("Точки сохранены")
    }

    private fun endCalibration() {
        removePins()
        closeHint()
        rmOv(panel)
        panel = null
        panelLp = null
        panelText = null
        nextChip = null
        hideChip = null
        calSteps = emptyList()
    }

    private fun nextHint() {
        if (calSteps.isEmpty()) return
        val hints = calSteps[calStep].hints
        if (hintIdx >= hints.size) {
            closeHint()
            hintIdx = 0
            return
        }
        val src = BitmapFactory.decodeResource(resources, hints[hintIdx])
        hintIdx++
        val tw = (screen().x * 0.6f).toInt().coerceAtLeast(300)
        val th = (src.height.toFloat() * tw / src.width).toInt()
        val bmp = Bitmap.createScaledBitmap(src, tw, th, true)
        val existing = hintView
        if (existing != null) {
            existing.setImageBitmap(bmp)
        } else {
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.alpha = 0.96f
            iv.setOnClickListener { nextHint() }
            val lp = overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            hintView = iv
            addOv(iv, lp)
        }
    }

    private fun closeHint() {
        rmOv(hintView)
        hintView = null
    }

    private fun relayout() {
        val s = screen()
        val p = panel
        val pl = panelLp
        if (p != null && pl != null) {
            pl.width = minOf(dp(440f), (s.x * 0.9f).toInt())
            pl.x = ((s.x - pl.width) / 2).coerceAtLeast(0)
            pl.y = (s.y - dp(190f)).coerceAtLeast(0)
            upOv(p, pl)
            if (!pinsHidden) showStep()
        }
        val rp = runPanel
        val rl = runLp
        if (rp != null && rl != null) {
            rl.x = dp(8f)
            rl.y = (s.y - dp(130f)).coerceAtLeast(0)
            upOv(rp, rl)
        }
    }

    // =====================================================================
    // Панель запуска (Старт / Стоп)
    // =====================================================================

    fun startRun(mode: String, sets: List<Int>, uniqueCounts: Map<Int, Int>) {
        uniques = uniqueCounts
        main.post {
            stopAll()
            showRunPanel(mode, sets)
        }
    }

    private fun showRunPanel(mode: String, sets: List<Int>) {
        val s = screen()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        box.background = rounded(Color.argb(238, 20, 20, 30), 12f)

        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setPadding(dp(4f), dp(2f), dp(4f), dp(4f))
        tv.text = "Открой в игре МЕНЮ коллекции (экран с наборами) и нажми «Старт»."
        box.addView(tv)
        runText = tv

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(chip("▶ Старт", PINK) { startWorker(mode, sets) })
        row.addView(chip("✖", Color.rgb(160, 40, 40)) { removeRunPanel() })
        box.addView(row)
        runRow = row

        val lp = overlayParams(dp(210f), WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = dp(8f)
        lp.y = (s.y - dp(130f)).coerceAtLeast(0)
        makeDraggable(tv, box, lp)
        runPanel = box
        runLp = lp
        addOv(box, lp)
    }

    private fun removeRunPanel() {
        rmOv(runPanel)
        runPanel = null
        runLp = null
        runText = null
        runRow = null
    }

    private fun startWorker(mode: String, sets: List<Int>) {
        if (running) return
        running = true
        runRow?.removeAllViews()
        runRow?.addView(chip("■ Стоп", Color.rgb(200, 40, 40)) {
            running = false
            setStatus("Останавливаю…")
        })
        Thread { runJob(mode, sets) }.start()
    }

    private fun setStatus(t: String) {
        Status.text = t
        main.post { runText?.text = t }
    }

    private fun finishRun(text: String) {
        running = false
        Status.text = text
        toast(text)
        main.post {
            runText?.text = text
            runRow?.removeAllViews()
            runRow?.addView(chip("Закрыть", Color.rgb(80, 80, 95)) { removeRunPanel() })
        }
    }

    // =====================================================================
    // Жесты и скриншоты
    // =====================================================================

    private fun dispatch(g: GestureDescription, timeoutSec: Long) {
        val latch = CountDownLatch(1)
        main.post {
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!ok) latch.countDown()
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
    }

    private fun tapAbs(fx: Float, fy: Float) {
        val s = screen()
        val p = Path()
        p.moveTo(fx * s.x, fy * s.y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 70))
            .build()
        dispatch(g, 5)
    }

    private fun tapPt(key: String) {
        val p = Points.get(this, key)
        tapAbs(p.x, p.y)
    }

    private fun swipeV(fx: Float, fy1: Float, fy2: Float, durMs: Long) {
        val s = screen()
        val p = Path()
        p.moveTo(fx * s.x, fy1 * s.y)
        p.lineTo(fx * s.x, fy2 * s.y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durMs))
            .build()
        dispatch(g, durMs / 1000 + 5)
    }

    private fun shot(): Bitmap? {
        for (attempt in 0 until 4) {
            val latch = CountDownLatch(1)
            val ref = AtomicReference<Bitmap?>(null)
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    shotExec,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val hb = result.hardwareBuffer
                                val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                ref.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                                hw?.recycle()
                                hb.close()
                            } catch (e: Exception) {
                            }
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                latch.countDown()
            }
            latch.await(6, TimeUnit.SECONDS)
            val b = ref.get()
            if (b != null) return b
            Thread.sleep(600)
        }
        return null
    }

    /** Пауза, которая прерывается кнопкой «Стоп». */
    private fun pause(ms: Long) {
        var t = 0L
        while (t < ms && running) {
            Thread.sleep(50)
            t += 50
        }
    }

    private fun saveRaw(bmp: Bitmap, f: File) {
        FileOutputStream(f).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // =====================================================================
    // Сбор карт
    // =====================================================================

    /** Сколько уникальных карт в каждом наборе (номер набора -> количество). */
    @Volatile
    private var uniques: Map<Int, Int> = emptyMap()

    /** Последний сработавший вид просмотра: true = карта слева, панель справа. */
    private var lastLeft = true

    private fun runJob(mode: String, sets: List<Int>) {
        var resultText = ""
        try {
            val s = screen()
            if (s.x < s.y) {
                finishRun("Экран вертикальный. Открой игру (она горизонтальная) и нажми «Старт» снова.")
                return
            }
            val base = getExternalFilesDir(null) ?: filesDir
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val rawDir = File(base, "raw_$stamp")
            rawDir.mkdirs()
            getSharedPreferences("state", Context.MODE_PRIVATE)
                .edit().putString("lastRaw", rawDir.absolutePath).apply()

            lastLeft = true
            when (mode) {
                "current" -> runCurrent(sets, rawDir)
                "debug" -> runHistory(rawDir, DebugProfile.sets)
                else -> runHistory(rawDir, null)
            }

            setStatus(if (running) "Снимки готовы. Нарезаю…" else "Остановлено. Нарезаю снятое…")
            val res = CardCrop.process(this, rawDir) { setStatus(it) }
            resultText = if (res.count == 0) {
                "Карт не найдено. Проверь, что открыто меню коллекции и точки настроены."
            } else {
                "Готово: ${res.count} карт → Загрузки/PUBG_cards/${res.name}"
            }
        } catch (e: Exception) {
            resultText = "Ошибка: " + e.javaClass.simpleName + ": " + e.message
        }
        finishRun(resultText)
    }

    /** Снимает экран и говорит, что на нём (null, если снимок не удался). */
    private fun lookScreen(): CardCrop.Screen? {
        val b = shot() ?: return null
        val s = CardCrop.classify(b)
        b.recycle()
        return s
    }

    private fun runCurrent(sets: List<Int>, rawDir: File) {
        for (n in sets) {
            if (!running) return
            val k = uniques[n] ?: 0
            setStatus("Набор $n: открываю…")
            tapPt("set$n")
            pause(2000)
            captureSet("current_set$n", rawDir, k, "Набор $n")
            if (!running) return
            tapPt("close") // из набора обратно в меню
            pause(1600)
        }
    }

    /**
     * История: справа список наборов. Для каждого набора: нажимаем название, смотрим,
     * не снимали ли уже такую сетку (по отпечатку), снимаем все карты стрелкой ›.
     * Потом список листается свайпом вверх, пока новых наборов не останется.
     * На аккаунте-госте все карты серые: просмотр «карта слева, панель справа».
     */
    private fun runHistory(rawDir: File, profile: List<DebugProfile.Entry>?) {
        setStatus("Открываю «История»…")
        tapPt("history")
        pause(2400)
        val p1 = Points.get(this, "h1")
        val p2 = Points.get(this, "h2")
        val step = p2.y - p1.y
        if (step < 0.04f) {
            setStatus("Проверь точки списка истории: расстояние между блоками слишком маленькое.")
            return
        }
        // Слоты списка: на один выше первой точки и дальше с шагом до низа экрана
        val slots = ArrayList<Float>()
        var y = p1.y - step
        while (y <= 0.93f) {
            if (y > 0.06f) slots.add(y)
            y += step
        }
        val known = ArrayList<FloatArray>()
        var sets = 0
        var cards = 0
        var emptyPages = 0
        val problems = ArrayList<String>()
        for (page in 0 until 60) {
            if (!running) return
            var fresh = 0
            for (sy in slots) {
                if (!running) return
                tapAbs(p1.x, sy)
                pause(1400)
                var g = shot() ?: continue
                var gs = CardCrop.gridSig(g)
                g.recycle()
                if (known.any { CardCrop.sameGrid(it, gs) }) {
                    // возможно, сетка ещё не сменилась: подождём и проверим ещё раз
                    pause(900)
                    g = shot() ?: continue
                    gs = CardCrop.gridSig(g)
                    g.recycle()
                    if (known.any { CardCrop.sameGrid(it, gs) }) continue
                }
                known.add(gs)
                sets++
                val entry = profile?.getOrNull(sets - 1)
                // гость: всё серое, просмотр «карта слева»; отладка: по данным аккаунта
                lastLeft = entry == null || entry.owned == 0
                val tag = "history_" + "%02d".format(sets) +
                    (if (entry != null) "_" + entry.name.replace(Regex("[^\\p{L}\\p{N}]+"), "_") else "")
                val label = "История: набор $sets" + (if (entry != null) " (${entry.name})" else "")
                val got = captureSet(tag, rawDir, 0, label)
                cards += got
                if (entry != null && got < entry.total) {
                    problems.add("набор $sets (${entry.name}): снято $got из ${entry.total}")
                }
                fresh++
                setStatus("История: наборов $sets, карт $cards")
            }
            if (fresh == 0) {
                emptyPages++
                if (emptyPages >= 2) break
            } else {
                emptyPages = 0
            }
            val yStart = (p1.y + step * 4f).coerceAtMost(0.90f)
            swipeV(p1.x, yStart, yStart - step * 3f, 1600)
            pause(1800)
        }
        var summary = "История: наборов $sets, карт $cards"
        if (profile != null) {
            if (sets < profile.size) problems.add("найдено наборов $sets из ${profile.size}")
            if (problems.isNotEmpty()) summary += ". Проверь: " + problems.joinToString("; ")
        }
        setStatus(summary)
    }

    /** Нажимает лупу и возвращает кадр с увеличенной картой (или null). */
    private fun zoomCard(): Bitmap? {
        val first = if (lastLeft) "zoomU" else "zoom"
        val second = if (lastLeft) "zoom" else "zoomU"
        val attempts = listOf(first, second, first)
        for ((i, key) in attempts.withIndex()) {
            if (!running) return null
            tapPt(key)
            pause(if (i == 2) 1900L else 1200L)
            var b = shot() ?: return null
            var s = CardCrop.classify(b)
            if (s == CardCrop.Screen.UNKNOWN) {
                b.recycle()
                pause(900)
                b = shot() ?: return null
                s = CardCrop.classify(b)
            }
            if (s == CardCrop.Screen.ZOOMED) {
                lastLeft = key == "zoomU"
                return b
            }
            b.recycle()
        }
        return null
    }

    /** Закрыть увеличение и просмотр, остаться на сетке карт набора. */
    private fun closeToGrid() {
        var unknown = 0
        for (i in 0 until 8) {
            val s = lookScreen() ?: return
            when (s) {
                CardCrop.Screen.OTHER -> return
                CardCrop.Screen.UNKNOWN -> {
                    unknown++
                    if (unknown > 3) return
                    Thread.sleep(700)
                }
                else -> {
                    tapPt("close")
                    Thread.sleep(950)
                }
            }
        }
    }

    /**
     * Открыта сетка набора. Открывает первую карту и листает стрелкой ›, на каждой карте
     * нажимает лупу и снимает увеличенный кадр. Первые [uniqueCount] карт – уникальные
     * (просмотр «карта слева, панель справа»), они идут первыми и кладутся в отдельную папку.
     * Останавливается, когда карты начинают повторяться. Возвращает число снятых карт.
     */
    private fun captureSet(tag: String, rawDir: File, uniqueCount: Int, label: String): Int {
        tapPt("first")
        pause(1500)
        val seen = ArrayList<FloatArray>()
        var skipped = 0
        var retried = false
        var index = 0
        while (running && index < MAX_CARDS) {
            val isUnique = index < uniqueCount

            // 1. убеждаемся, что открыт просмотр карты
            var scr = lookScreen() ?: break
            var wait = 0
            while (scr == CardCrop.Screen.UNKNOWN && wait < 3) {
                pause(800)
                scr = lookScreen() ?: break
                wait++
            }
            if (scr == CardCrop.Screen.ZOOMED) {
                tapPt("close")
                pause(900)
                scr = lookScreen() ?: break
            }
            if (scr != CardCrop.Screen.VIEWER) break

            // 2. лупа и снимок увеличенной карты
            val zoomed = zoomCard()
            if (zoomed == null) {
                skipped++
                setStatus("$label: карта ${index + 1} не увеличилась, пропускаю")
                if (skipped > 4) break
            } else {
                val card = CardCrop.cropCard(zoomed)
                val sg = CardCrop.signature(card)
                if (card !== zoomed) card.recycle()
                if (seen.any { CardCrop.same(it, sg) }) {
                    zoomed.recycle()
                    if (!retried) {
                        // возможно, стрелка › не сработала: нажмём ещё раз и проверим
                        retried = true
                        tapPt("close")
                        pause(900)
                        tapPt("next")
                        pause(1500)
                        continue
                    }
                    break // начались повторы: набор закончился
                }
                retried = false
                seen.add(sg)
                val num = if (isUnique) index + 1 else index - uniqueCount + 1
                val name = tag + (if (isUnique) "_unique" else "") + "_" + "%02d".format(num) + ".png"
                saveRaw(zoomed, File(rawDir, name))
                zoomed.recycle()
                setStatus("$label: карта ${index + 1}" + if (isUnique) " (уникальная)" else "")
                tapPt("close") // закрыть увеличение
                pause(900)
            }

            // 3. следующая карта
            tapPt("next")
            pause(1000)
            index++
        }
        closeToGrid()
        return seen.size
    }
}
