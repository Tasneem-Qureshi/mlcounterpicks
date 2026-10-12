package com.dripagency.mlbbdraft.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Debug aid: writes the captured frame with the slot rectangles drawn on top so layouts.json can
 * be tuned per device.  Output: Android/data/<pkg>/files/calibration/frame_<n>.png (world-readable
 * via a file manager / adb pull).  Throttled and capped so it can be left on by mistake.
 */
class CalibrationDumper(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "calibration").apply { mkdirs() }
    private var lastDump = 0L
    private var counter = 0

    fun maybeDump(frame: Bitmap, layout: LayoutProfile, readings: FrameReadings?) {
        val now = System.currentTimeMillis()
        if (now - lastDump < 10_000) return
        lastDump = now
        runCatching {
            val out = frame.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            val text = Paint().apply { color = Color.YELLOW; textSize = 22f; style = Paint.Style.FILL }
            fun draw(rects: List<NormRect>, color: Int, labels: List<SlotReading>?) {
                paint.color = color
                rects.forEachIndexed { i, r ->
                    val rc = r.toRect(out.width, out.height)
                    c.drawRect(rc, paint)
                    val id = labels?.getOrNull(i)?.heroId ?: "-"
                    c.drawText(id, rc.left.toFloat(), (rc.bottom + 22).toFloat(), text)
                }
            }
            draw(layout.allySlots, Color.CYAN, readings?.allies)
            draw(layout.enemySlots, Color.RED, readings?.enemies)
            draw(layout.allyBans + layout.enemyBans, Color.MAGENTA, readings?.bans)
            draw(layout.laneBadges, Color.GREEN, null)
            val file = File(dir, "frame_${counter % 5}.png"); counter++
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
            out.recycle()
            Log.i("CalibrationDumper", "wrote ${file.path} (${layout.name})")
        }.onFailure { Log.w("CalibrationDumper", "dump failed", it) }
    }
}
