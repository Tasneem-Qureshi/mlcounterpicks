package com.dripagency.mlbbdraft.vision

import android.content.Context
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Rectangle in normalised frame coordinates (0..1). */
data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun toRect(frameW: Int, frameH: Int): Rect {
        val l = (x * frameW).toInt().coerceIn(0, frameW - 2)
        val t = (y * frameH).toInt().coerceIn(0, frameH - 2)
        val r = ((x + w) * frameW).toInt().coerceIn(l + 2, frameW)
        val b = ((y + h) * frameH).toInt().coerceIn(t + 2, frameH)
        return Rect(l, t, r, b)
    }
}

data class LayoutProfile(
    val name: String,
    val minAspect: Float,
    val maxAspect: Float,
    val allySlots: List<NormRect>,
    val enemySlots: List<NormRect>,
    val allyBans: List<NormRect>,
    val enemyBans: List<NormRect>,
    val laneBadges: List<NormRect>,
)

object SlotLayouts {
    private const val TAG = "SlotLayouts"

    /** Reads assets/layouts.json; a user-edited copy in filesDir/layouts.json wins if present. */
    fun load(context: Context): List<LayoutProfile> {
        val override = java.io.File(context.filesDir, "layouts.json")
        val json = runCatching {
            if (override.exists()) override.readText() else context.assets.open("layouts.json").bufferedReader().readText()
        }.getOrElse { Log.w(TAG, "layouts.json missing; using built-in", it); return DEFAULTS }
        return runCatching { parse(json) }.getOrElse { Log.w(TAG, "layouts.json invalid; using built-in", it); DEFAULTS }
    }

    fun forAspect(profiles: List<LayoutProfile>, width: Int, height: Int): LayoutProfile {
        val aspect = maxOf(width, height).toFloat() / minOf(width, height).toFloat()
        return profiles.firstOrNull { aspect >= it.minAspect && aspect < it.maxAspect }
            ?: profiles.minByOrNull { kotlin.math.abs((it.minAspect + it.maxAspect) / 2 - aspect) }
            ?: DEFAULTS.first()
    }

    fun parse(json: String): List<LayoutProfile> {
        val arr = JSONObject(json).getJSONArray("profiles")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            LayoutProfile(
                name = p.getString("name"),
                minAspect = p.getDouble("min_aspect").toFloat(),
                maxAspect = p.getDouble("max_aspect").toFloat(),
                allySlots = rects(p.getJSONArray("ally_slots")),
                enemySlots = rects(p.getJSONArray("enemy_slots")),
                allyBans = rects(p.optJSONArray("ally_bans")),
                enemyBans = rects(p.optJSONArray("enemy_bans")),
                laneBadges = rects(p.optJSONArray("lane_badges")),
            )
        }
    }

    private fun rects(a: JSONArray?): List<NormRect> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            NormRect(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
        }
    }

    private fun column(x: Float, w: Float) = (0 until 5).map { NormRect(x, 0.190f + it * 0.148f, w, 0.130f) }

    val DEFAULTS: List<LayoutProfile> = listOf(
        LayoutProfile(
            name = "generic", minAspect = 1.0f, maxAspect = 3.0f,
            allySlots = column(0.012f, 0.075f), enemySlots = column(0.913f, 0.075f),
            allyBans = (0 until 5).map { NormRect(0.175f + it * 0.055f, 0.03f, 0.045f, 0.08f) },
            enemyBans = (0 until 5).map { NormRect(0.560f + it * 0.055f, 0.03f, 0.045f, 0.08f) },
            laneBadges = (0 until 5).map { NormRect(0.070f, 0.280f + it * 0.148f, 0.030f, 0.045f) },
        )
    )
}
