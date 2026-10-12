package com.dripagency.mlbbdraft.vision

import android.content.Context
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crop box in units of the frame height, anchored to the left or right screen edge.
 * MLBB pins its draft side panels to the edges and scales them with height, so these
 * boxes hold across 16:9, 19.5:9 and 20:9 without separate profiles.
 */
data class AnchoredRect(val fromRight: Boolean, val x: Float, val y: Float, val w: Float, val h: Float) {
    /** @param content the game's content area inside the frame (letterbox bars removed). */
    fun toRect(content: Rect): Rect {
        val hpx = content.height().toFloat()
        val wpx = w * hpx
        val l = (if (fromRight) content.right - (x * hpx) - wpx else content.left + x * hpx).toInt()
        val t = (content.top + y * hpx).toInt()
        val r = (l + wpx).toInt()
        val b = (t + h * hpx).toInt()
        return Rect(l.coerceIn(0, content.right - 2), t.coerceIn(0, content.bottom - 2), r.coerceIn(2, content.right), b.coerceIn(2, content.bottom))
    }
}

data class LayoutProfile(
    val name: String,
    val allySlots: List<AnchoredRect>,
    val enemySlots: List<AnchoredRect>,
    val allyBans: List<AnchoredRect>,
    val enemyBans: List<AnchoredRect>,
    val laneBadges: List<AnchoredRect>,
)

object SlotLayouts {
    private const val TAG = "SlotLayouts"

    fun load(context: Context): LayoutProfile {
        val override = File(context.filesDir, "layouts.json")
        val json = runCatching {
            if (override.exists()) override.readText() else context.assets.open("layouts.json").bufferedReader().readText()
        }.getOrElse { Log.w(TAG, "layouts.json missing; using built-in", it); return DEFAULT }
        return runCatching { parse(json).first() }.getOrElse { Log.w(TAG, "layouts.json invalid; using built-in", it); DEFAULT }
    }

    fun parse(json: String): List<LayoutProfile> {
        val arr = JSONObject(json).getJSONArray("profiles")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            LayoutProfile(
                name = p.getString("name"),
                allySlots = rects(p.optJSONArray("ally_slots")),
                enemySlots = rects(p.optJSONArray("enemy_slots")),
                allyBans = rects(p.optJSONArray("ally_bans")),
                enemyBans = rects(p.optJSONArray("enemy_bans")),
                laneBadges = rects(p.optJSONArray("lane_badges")),
            )
        }
    }

    private fun rects(a: JSONArray?): List<AnchoredRect> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            AnchoredRect(r.getString(0).equals("R", true), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat(), r.getDouble(4).toFloat())
        }
    }

    private fun column(fromRight: Boolean, x: Float, w: Float) = (0 until 5).map { AnchoredRect(fromRight, x, 0.105f + it * 0.160f, w, 0.136f) }
    private fun bans(fromRight: Boolean, x0: Float) = (0 until 5).map { AnchoredRect(fromRight, x0 + it * 0.102f, 0.007f, 0.075f, 0.075f) }

    val DEFAULT = LayoutProfile("built-in", column(false, 0.078f, 0.228f), column(true, 0.041f, 0.265f), bans(false, 0.099f), bans(true, 0.102f), emptyList())
}
