package com.pubgcards.collector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var svcView: TextView
    private lateinit var statusView: TextView
    private val setBoxes = ArrayList<CheckBox>()
    private val uniqueViews = ArrayList<TextView>()

    private val games = listOf(
        "com.tencent.ig", "com.pubg.krmobile", "com.vng.pubgmobile",
        "com.rekoo.pubgm", "com.pubg.imobile", "com.tencent.tmgp.pubgmhd"
    )

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 700)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val pink = Color.rgb(255, 20, 147)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.rgb(22, 22, 30))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(36))
        sv.addView(root)

        fun head(t: String) {
            val v = TextView(this)
            v.text = t
            v.setTextColor(Color.WHITE)
            v.textSize = 19f
            v.setPadding(0, dp(22), 0, dp(6))
            root.addView(v)
        }

        fun body(t: String): TextView {
            val v = TextView(this)
            v.text = t
            v.setTextColor(Color.rgb(200, 200, 212))
            v.textSize = 14f
            v.setPadding(0, dp(2), 0, dp(6))
            root.addView(v)
            return v
        }

        fun btn(t: String, color: Int, onClick: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setTextColor(Color.WHITE)
            b.isAllCaps = false
            val g = GradientDrawable()
            g.setColor(color)
            g.cornerRadius = dp(12).toFloat()
            b.background = g
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(4), 0, dp(4))
            root.addView(b, lp)
        }

        val title = TextView(this)
        title.text = "PUBG Cards"
        title.setTextColor(pink)
        title.textSize = 28f
        root.addView(title)
        val uc = "52099665966"
        val credits = SpannableString("Над проектом работал byjoom, dangerping\n(пожертвовать uc $uc)")
        val ucStart = credits.indexOf(uc)
        credits.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                copyUc(uc)
            }
        }, ucStart, ucStart + uc.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        credits.setSpan(UnderlineSpan(), ucStart, ucStart + uc.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val creditsView = body("")
        creditsView.text = credits
        creditsView.movementMethod = LinkMovementMethod.getInstance()
        creditsView.setLinkTextColor(pink)
        creditsView.textSize = 15f
        btn("Скопировать номер UC", Color.rgb(80, 80, 95)) { copyUc(uc) }
        body("Сбор карточек коллекции PUBG Mobile: сам листает карты, делает скриншоты, вырезает и складывает в zip.")

        head("0. Служба")
        svcView = body("")
        btn("Открыть настройки специальных возможностей", Color.rgb(60, 90, 200)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        body("Найди в списке «PUBG Cards Collector» и включи. Если Android пишет «Ограниченная настройка»: открой «Сведения о приложении» → ⋮ → «Разрешить ограниченные настройки», потом включи службу.")
        btn("Сведения о приложении", Color.rgb(80, 80, 95)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        head("1. Актуальные наборы")
        body("Отметь наборы из главного меню коллекции (слева направо) и укажи, сколько в каждом УНИКАЛЬНЫХ карт. Уникальные стоят в сетке первыми (особая рамка, при открытии карта слева, справа панель). Приложение снимет их первыми и положит в отдельную папку, остальные карты пойдут следом.")
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        for (i in 1..4) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val c = CheckBox(this)
            c.text = "Набор $i"
            c.isChecked = true
            c.setTextColor(Color.WHITE)
            c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setBoxes.add(c)
            row.addView(c)

            val lab = TextView(this)
            lab.text = "уникальных:"
            lab.setTextColor(Color.rgb(200, 200, 212))
            row.addView(lab)

            val cnt = TextView(this)
            cnt.text = prefs.getInt("uniq_$i", 1).toString()
            cnt.setTextColor(Color.WHITE)
            cnt.textSize = 18f
            cnt.gravity = Gravity.CENTER
            cnt.layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
            uniqueViews.add(cnt)

            fun stepBtn(label: String, delta: Int): Button {
                val b = Button(this)
                b.text = label
                b.setTextColor(Color.WHITE)
                b.isAllCaps = false
                val g = GradientDrawable()
                g.setColor(Color.rgb(80, 80, 95))
                g.cornerRadius = dp(10).toFloat()
                b.background = g
                b.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                b.setOnClickListener {
                    val v = ((cnt.text.toString().toIntOrNull() ?: 0) + delta).coerceIn(0, 12)
                    cnt.text = v.toString()
                    prefs.edit().putInt("uniq_$i", v).apply()
                }
                return b
            }
            row.addView(stepBtn("−", -1))
            row.addView(cnt)
            row.addView(stepBtn("+", 1))
            root.addView(row)
        }
        btn("Настроить точки (розовые блоки)", Color.rgb(80, 80, 95)) { calibrate("current") }
        btn("▶ Собрать актуальные наборы", pink) { run("current") }

        head("2. История карт (гость)")
        body("Для аккаунта-гостя: карты серые и открываются слева, справа панель. Особые карты (с перками) приложение узнаёт само. Снимает ВСЕ наборы из «Истории» подряд: нажимает названия в списке справа, листает список сам и пропускает уже снятые наборы. Это долго: около 1–2 минут на набор.")
        btn("Настроить точки (розовые блоки)", Color.rgb(80, 80, 95)) { calibrate("history") }
        btn("▶ История карт (гость)", pink) { run("history") }
        btn("(функция отладки)", Color.rgb(55, 55, 70)) { run("debug") }

        head("3. Результат")
        statusView = body("")
        btn("Поделиться архивом", Color.rgb(60, 90, 200)) { share() }
        btn("Нарезать заново из последних снимков", Color.rgb(80, 80, 95)) { recrop() }
        btn("Удалить временные снимки", Color.rgb(120, 50, 50)) { clean() }
        body("Архивы лежат в папке «Загрузки/PUBG_cards».")

        head("Как это работает")
        body("1) Включи службу. 2) Нажми «Настроить точки»: откроется игра, поверх неё появятся розовые блоки с подписями. Перетащи центр креста на нужное место, нажимай «Далее». Фото-подсказки есть кнопкой «Подсказка». 3) Нажми «Собрать»: открой в игре меню коллекции и нажми «Старт» в розовой панели.")

        head("Подсказки")
        val hints = listOf(
            R.drawable.hint_1 to "1 · Меню коллекции. Отсюда начинается сбор актуальных наборов.",
            R.drawable.hint_2 to "2 · Внутри набора: сетка карт. Блок «Первая карта» ставится на самую первую карту.",
            R.drawable.hint_3 to "3 · Просмотр карты. Нужны лупа (обведена), стрелки ‹ › и крестик ✕.",
            R.drawable.hint_4 to "4 · Увеличенная карта. Именно с этого кадра приложение вырезает карточку.",
            R.drawable.hint_5 to "5 · Кнопка «История» (обведена) открывает старые наборы.",
            R.drawable.hint_6 to "6 · История: список наборов справа. Блоки списка ставятся на 2-й и 3-й набор (под выбранным).",
            R.drawable.hint_7 to "7 · В истории карты вырезаются так же: с увеличенного кадра.",
            R.drawable.hint_11 to "8 · Набор с уникальной картой: она стоит первой, у неё особая рамка.",
            R.drawable.hint_8 to "9 · Просмотр уникальной карты: карта слева, справа панель. Лупа внизу справа от карты (блок «слева»).",
            R.drawable.hint_9 to "10 · Просмотр серой карты (аккаунт-гость): тоже карта слева, справа панель «Как получить».",
            R.drawable.hint_10 to "11 · История: справа список наборов. Блоки списка ставятся на 2-й и 3-й набор сверху.",
            R.drawable.hint_12 to "12 · Просмотр полученной карты: карта по центру, лупа справа внизу (блок «по центру»).",
            R.drawable.hint_13 to "13 · Особая карта с перками: карта слева, справа «Эксклюзивные перки» и «Как получить»."
        )
        for ((res, cap) in hints) {
            body(cap)
            val iv = ImageView(this)
            iv.setImageResource(res)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(
                iv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val link = TextView(this)
        link.text = "набор истории карт на 1.11.26. byJoom"
        link.setTextColor(Color.rgb(120, 170, 255))
        link.textSize = 15f
        link.paintFlags = link.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        link.setPadding(0, dp(28), 0, dp(8))
        link.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://drive.google.com/file/d/1p4AVAtClioYakUlzS_shazUOj5J-TdNF/view?usp=drivesdk")
                    )
                )
            } catch (e: Exception) {
                toast("Не удалось открыть ссылку")
            }
        }
        root.addView(link)

        setContentView(sv)
    }

    private fun copyUc(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UC", text))
        toast("Скопировано: $text")
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        svcView.text = if (CardService.instance != null) {
            "✅ Служба включена"
        } else {
            "❌ Служба выключена. Без неё приложение не умеет нажимать и снимать экран."
        }
        statusView.text = Status.text
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun launchGame() {
        for (pkg in games) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            }
        }
        moveTaskToBack(true)
    }

    private fun calibrate(mode: String) {
        val s = CardService.instance
        if (s == null) {
            toast("Сначала включи службу (блок 0).")
            return
        }
        s.startCalibration(mode)
        toast("Открываю игру. Поверх появятся розовые блоки.")
        launchGame()
    }

    private fun run(mode: String) {
        val s = CardService.instance
        if (s == null) {
            toast("Сначала включи службу (блок 0).")
            return
        }
        val sets = ArrayList<Int>()
        for ((i, c) in setBoxes.withIndex()) if (c.isChecked) sets.add(i + 1)
        if (mode == "current" && sets.isEmpty()) {
            toast("Отметь хотя бы один набор.")
            return
        }
        val uniq = HashMap<Int, Int>()
        for ((i, v) in uniqueViews.withIndex()) uniq[i + 1] = v.text.toString().toIntOrNull() ?: 0
        s.startRun(mode, sets, uniq)
        toast("Открой МЕНЮ коллекции в игре и нажми «Старт» в розовой панели.")
        launchGame()
    }

    private fun share() {
        val u = getSharedPreferences("state", MODE_PRIVATE).getString("lastZip", null)
        if (u == null) {
            toast("Архива пока нет.")
            return
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "application/zip"
        i.putExtra(Intent.EXTRA_STREAM, Uri.parse(u))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Отправить архив"))
    }

    private fun recrop() {
        val p = getSharedPreferences("state", MODE_PRIVATE).getString("lastRaw", null)
        if (p == null || !File(p).exists()) {
            toast("Нет сохранённых снимков.")
            return
        }
        Status.text = "Нарезка…"
        Thread {
            try {
                val r = CardCrop.process(applicationContext, File(p)) { Status.text = it }
                Status.text = if (r.count == 0) "Карт не найдено." else
                    "Готово: ${r.count} карт → Загрузки/PUBG_cards/${r.name}"
            } catch (e: Exception) {
                Status.text = "Ошибка: " + e.message
            }
        }.start()
    }

    private fun clean() {
        val base = getExternalFilesDir(null) ?: filesDir
        var n = 0
        base.listFiles()?.forEach {
            if (it.isDirectory && it.name.startsWith("raw_")) {
                it.deleteRecursively()
                n++
            }
        }
        getSharedPreferences("state", MODE_PRIVATE).edit().remove("lastRaw").apply()
        toast("Удалено папок со снимками: $n")
    }
}
