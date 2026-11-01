package com.pubgcards.collector

import android.content.Context
import android.graphics.PointF

/** Точка нажатия: координаты хранятся долями экрана (0..1), поэтому не зависят от разрешения. */
class PointDef(val key: String, val label: String, val x: Float, val y: Float)

object Points {
    val defs = listOf(
        PointDef("set1", "Набор 1", 0.195f, 0.40f),
        PointDef("set2", "Набор 2", 0.395f, 0.40f),
        PointDef("set3", "Набор 3", 0.595f, 0.40f),
        PointDef("set4", "Набор 4", 0.795f, 0.40f),
        PointDef("first", "Первая карта", 0.20f, 0.37f),
        PointDef("zoom", "Лупа (карта по центру)", 0.574f, 0.736f),
        PointDef("zoomU", "Лупа (карта слева)", 0.375f, 0.759f),
        PointDef("next", "Стрелка ›", 0.954f, 0.497f),
        PointDef("close", "Крестик ✕", 0.957f, 0.043f),
        PointDef("history", "Кнопка «История»", 0.138f, 0.036f),
        PointDef("h1", "Список: 2-й набор", 0.94f, 0.26f),
        PointDef("h2", "Список: 3-й набор", 0.94f, 0.377f)
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("points", Context.MODE_PRIVATE)

    fun def(key: String): PointDef = defs.first { it.key == key }

    fun get(ctx: Context, key: String): PointF {
        val d = def(key)
        val p = prefs(ctx)
        return PointF(p.getFloat(key + "_x", d.x), p.getFloat(key + "_y", d.y))
    }

    fun save(ctx: Context, key: String, x: Float, y: Float) {
        prefs(ctx).edit().putFloat(key + "_x", x).putFloat(key + "_y", y).apply()
    }
}

/** Общий статус, который показывает главный экран. */
object Status {
    @Volatile
    var text: String = "Готов к работе"
}
