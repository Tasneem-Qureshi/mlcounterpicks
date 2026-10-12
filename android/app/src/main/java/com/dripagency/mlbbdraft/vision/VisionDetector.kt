package com.dripagency.mlbbdraft.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.dripagency.mlbbdraft.data.model.Role
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Resolves screen crops to hero ids.
 *
 * Pipeline per slot:  crop → gray → resize 64 → CLAHE → circular mask → dHash shortlist (Hamming)
 *                      → OpenCV matchTemplate (TM_CCOEFF_NORMED, 56px template inside 64px crop,
 *                        so ±4px misalignment is tolerated) → accept if score and margin pass.
 *
 * The hash pre-filter keeps per-frame cost at ~12 template matches instead of ~130.
 */
class VisionDetector private constructor(
    private val templates: List<HeroTemplate>,
    private val laneTemplates: List<LaneTemplate>,
    val config: Config = Config(),
) {
    data class Config(
        val slotSize: Int = 64,
        val templateSize: Int = 56,
        val shortlist: Int = 12,
        val maxHamming: Int = 26,
        val acceptScore: Float = 0.42f,
        val acceptMargin: Float = 0.035f,
        val emptyStdDev: Double = 14.0,
        val laneAcceptScore: Float = 0.55f,
        /** Template scales tried on the shortlist; draft cards are not always the icon's exact size. */
        val scales: FloatArray = floatArrayOf(0.85f, 1.0f, 1.15f),
    )

    class HeroTemplate(val heroId: String, val gray: Mat, val hash: Long)
    class LaneTemplate(val role: Role, val gray: Mat)
    data class Match(val heroId: String, val confidence: Float)

    val heroCount: Int get() = templates.size
    val hasLaneTemplates: Boolean get() = laneTemplates.isNotEmpty()

    /** Best (heroId, score) seen in the last identifyMat() call even when below threshold — for calibration UI. */
    @Volatile var lastCandidate: Pair<String, Float>? = null
        private set

    private val clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0))
    private val scaled = Mat()
    private val mask: Mat = circleMask(config.slotSize)
    private val templateMask: Mat = circleMask(config.templateSize)

    // scratch buffers reused across frames (no per-frame allocation on the hot path)
    private val slot = Mat(config.slotSize, config.slotSize, CvType.CV_8UC1)
    private val result = Mat()
    private val grayBuf = IntArray(config.slotSize * config.slotSize)
    private val byteBuf = ByteArray(config.slotSize * config.slotSize)

    /** Converts a full frame to a grayscale Mat once; callers then pass ROIs to [identifyRegion]. */
    fun toGray(frame: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(frame, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    fun identifyRegion(frameGray: Mat, region: Rect): Match? {
        val roi = frameGray.submat(region.top, region.bottom, region.left, region.right)
        return try { identifyMat(roi) } finally { roi.release() }
    }

    @Synchronized
    fun identifyMat(crop: Mat): Match? {
        Imgproc.resize(crop, slot, Size(config.slotSize.toDouble(), config.slotSize.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        lastCandidate = null
        if (isEmpty(slot)) return null
        clahe.apply(slot, slot)
        Core.bitwise_and(slot, mask, slot)

        slot.get(0, 0, byteBuf)
        for (i in byteBuf.indices) grayBuf[i] = byteBuf[i].toInt() and 0xFF
        val hash = PerceptualHash.dHash(grayBuf, config.slotSize, config.slotSize)

        val shortlist = templates.asSequence()
            .map { it to PerceptualHash.hamming(hash, it.hash) }
            .filter { it.second <= config.maxHamming }
            .sortedBy { it.second }
            .take(config.shortlist)
            .map { it.first }
            .toList()
            .ifEmpty { templates.take(config.shortlist) }

        var best: HeroTemplate? = null; var bestScore = -1f; var second = -1f
        for (t in shortlist) {
            var s = -1f
            for (sc in config.scales) {
                val tpl: Mat = if (sc == 1.0f) t.gray else {
                    val sz = (config.templateSize * sc).toInt().coerceIn(24, config.slotSize)
                    Imgproc.resize(t.gray, scaled, Size(sz.toDouble(), sz.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA); scaled
                }
                Imgproc.matchTemplate(slot, tpl, result, Imgproc.TM_CCOEFF_NORMED)
                s = maxOf(s, Core.minMaxLoc(result).maxVal.toFloat())
            }
            if (s > bestScore) { second = bestScore; bestScore = s; best = t } else if (s > second) second = s
        }
        val b = best ?: return null
        lastCandidate = b.heroId to bestScore
        if (bestScore < config.acceptScore || bestScore - second < config.acceptMargin) return null
        return Match(b.heroId, bestScore)
    }

    private fun isEmpty(gray: Mat): Boolean {
        val mean = MatOfDouble(); val std = MatOfDouble()
        Core.meanStdDev(gray, mean, std)
        val v = std.toArray().firstOrNull() ?: 0.0
        mean.release(); std.release()
        return v < config.emptyStdDev
    }

    @Synchronized
    fun detectLane(frameGray: Mat, region: Rect): Role? {
        if (laneTemplates.isEmpty()) return null
        val roi = frameGray.submat(region.top, region.bottom, region.left, region.right)
        val scaled = Mat()
        Imgproc.resize(roi, scaled, Size(32.0, 32.0), 0.0, 0.0, Imgproc.INTER_AREA)
        var best: Role? = null; var bestScore = -1f
        for (t in laneTemplates) {
            Imgproc.matchTemplate(scaled, t.gray, result, Imgproc.TM_CCOEFF_NORMED)
            val s = Core.minMaxLoc(result).maxVal.toFloat()
            if (s > bestScore) { bestScore = s; best = t.role }
        }
        roi.release(); scaled.release()
        return if (bestScore >= config.laneAcceptScore) best else null
    }

    companion object {
        private const val TAG = "VisionDetector"

        /** Downloaded templates (self-update) win over the ones bundled in assets. */
        fun load(context: Context, config: Config = Config()): VisionDetector {
            val downloaded = File(context.filesDir, "templates")
            val useDownloaded = File(downloaded, "index.json").exists()
            val names: List<String> = if (useDownloaded) downloaded.list().orEmpty().toList()
            else context.assets.list("templates").orEmpty().toList()

            val templates = names.filter { it.endsWith(".png") }.mapNotNull { file ->
                val bmp = runCatching {
                    if (useDownloaded) BitmapFactory.decodeFile(File(downloaded, file).path)
                    else context.assets.open("templates/$file").use { BitmapFactory.decodeStream(it) }
                }.getOrNull() ?: return@mapNotNull null
                val t = prepareTemplate(bmp, config)
                bmp.recycle()
                HeroTemplate(file.removeSuffix(".png"), t.first, t.second)
            }
            val lanes = Role.entries.mapNotNull { role ->
                val bmp = runCatching { context.assets.open("lanes/${role.name.lowercase()}.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
                    ?: return@mapNotNull null
                val m = Mat(); Utils.bitmapToMat(bmp, m); Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.resize(m, m, Size(28.0, 28.0), 0.0, 0.0, Imgproc.INTER_AREA)
                LaneTemplate(role, m)
            }
            Log.i(TAG, "loaded ${templates.size} hero templates (${if (useDownloaded) "downloaded" else "assets"}), ${lanes.size} lane badges")
            return VisionDetector(templates, lanes, config)
        }

        private fun prepareTemplate(bmp: Bitmap, config: Config): Pair<Mat, Long> {
            val m = Mat(); Utils.bitmapToMat(bmp, m)
            Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
            // Hash is computed at slot size so it is comparable with live crops.
            val forHash = Mat()
            Imgproc.resize(m, forHash, Size(config.slotSize.toDouble(), config.slotSize.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0)); clahe.apply(forHash, forHash)
            Core.bitwise_and(forHash, circleMask(config.slotSize), forHash)
            val buf = ByteArray(config.slotSize * config.slotSize); forHash.get(0, 0, buf)
            val hash = PerceptualHash.dHash(IntArray(buf.size) { buf[it].toInt() and 0xFF }, config.slotSize, config.slotSize)
            forHash.release()

            val tpl = Mat()
            Imgproc.resize(m, tpl, Size(config.templateSize.toDouble(), config.templateSize.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            clahe.apply(tpl, tpl)
            Core.bitwise_and(tpl, circleMask(config.templateSize), tpl)
            m.release()
            return tpl to hash
        }

        private fun circleMask(size: Int): Mat {
            val m = Mat.zeros(size, size, CvType.CV_8UC1)
            Imgproc.circle(m, Point(size / 2.0, size / 2.0), (size * 0.46).toInt(), Scalar(255.0), -1)
            return m
        }
    }
}
