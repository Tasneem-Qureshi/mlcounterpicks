package com.dripagency.mlbbdraft.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Debug aid: writes the captured frame with the crop boxes drawn on top so layouts.json can be
 * tuned per device.  Output: Android/data/<pkg>/files/calibration/frame_<n>.png.
 */
class CalibrationDumper(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "calibration").apply { mkdirs() }
    private var lastDump = 0L
    private var counter = 0

    fun maybeDump(frame: Bitmap, layout: LayoutProfile, content: Rect, readings: FrameReadings?, note: String) {
        val now = System.currentTimeMillis()
        if (now - lastDump < 10_000) return
        lastDump = now
        runCatching {
            val out = frame.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            val text = Paint().apply { color = Color.YELLOW; textSize = 20f; style = Paint.Style.FILL }
            paint.color = Color.WHITE; c.drawRect(content, paint)
            fun draw(rects: List<AnchoredRect>, color: Int, labels: List<SlotReading>?) {
                paint.color = color
                rects.forEachIndexed { i, r ->
                    val rc = r.toRect(content)
                    c.drawRect(rc, paint)
                    c.drawText(labels?.getOrNull(i)?.heroId ?: "-", rc.left.toFloat(), (rc.bottom + 20).toFloat(), text)
                }
            }
            draw(layout.allySlots, Color.CYAN, readings?.allies)
            draw(layout.enemySlots, Color.RED, readings?.enemies)
            draw(layout.allyBans + layout.enemyBans, Color.MAGENTA, readings?.bans)
            draw(layout.laneBadges, Color.GREEN, null)
            c.drawText(note, 10f, (out.height - 12).toFloat(), text)
            val file = File(dir, "frame_${counter % 5}.png"); counter++
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
            out.recycle()
            Log.i("CalibrationDumper", "wrote ${file.path}")
        }.onFailure { Log.w("CalibrationDumper", "dump failed", it) }
    }
}
