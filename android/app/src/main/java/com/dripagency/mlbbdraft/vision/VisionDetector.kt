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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Resolves draft-screen crops to hero ids.
 *
 * The draft shows locked heroes as bust-art cards while bans show circular head icons.  Both
 * are crops of the same official painting, so the *face patch* of the head icon (its inner 64 %)
 * can be found INSIDE either crop with normalised cross-correlation at a few scales.
 * Validated on real draft frames (scraper/testdata, scraper/vision_lab.py): correct heroes score
 * 0.70–0.94, wrong heroes stay ≤0.65.
 *
 * Cost control:
 *  - per-slot change detection (dHash of the crop) → a slot that has not changed since the last
 *    frame returns the cached answer without any matching;
 *  - coarse pass at half resolution over all heroes → top 12 → fine pass at 5 scales.
 */
class VisionDetector private constructor(
    private val templates: List<HeroTemplate>,
    private val laneTemplates: List<LaneTemplate>,
    val config: Config = Config(),
) {
    data class Config(
        /** crops are normalised to this height before matching */
        val workHeight: Int = 80,
        /** template face sizes relative to the crop height (fine pass) */
        val fineScales: FloatArray = floatArrayOf(0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f),
        val coarseScales: FloatArray = floatArrayOf(0.40f, 0.55f, 0.70f),
        /** coarse pass runs at this fraction of workHeight (0.6 keeps every validated hero at rank 1) */
        val coarseFactor: Double = 0.6,
        val shortlist: Int = 12,
        /** shortlisted heroes beyond this rank are refined with their best coarse art only */
        val fullRefine: Int = 4,
        val acceptScore: Float = 0.74f,
        val acceptMargin: Float = 0.08f,
        /** at or above this score the margin requirement is waived (wrong heroes never score this high) */
        val sureScore: Float = 0.80f,
        val emptyStdDev: Double = 14.0,
        val laneAcceptScore: Float = 0.55f,
        val cacheHamming: Int = 3,
    )

    /** One art of a hero (official icon, in-game portrait, skin icon …) prepared at every search scale. */
    class ArtTemplate(val fine: List<Mat>, val coarse: List<Mat>)
    class HeroTemplate(val heroId: String, val arts: List<ArtTemplate>)
    class LaneTemplate(val role: Role, val gray: Mat)
    data class Match(val heroId: String, val confidence: Float)

    private class SlotCache(var hash: Long, var match: Match?, var best: Pair<String, Float>?)

    val heroCount: Int get() = templates.size
    val hasLaneTemplates: Boolean get() = laneTemplates.isNotEmpty()

    /** Best (heroId, score) of the last identify call even when rejected — shown as calibration diagnostics. */
    @Volatile var lastCandidate: Pair<String, Float>? = null
        private set

    private val clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0))
    private val work = Mat()
    private val half = Mat()
    private val mirrored = Mat()
    private val result = Mat()
    private val cache = HashMap<Int, SlotCache>()
    private val hashBuf = IntArray(9 * 8)
    private val hashMat = Mat(8, 9, CvType.CV_8UC1)
    private val hashBytes = ByteArray(72)

    fun toGray(frame: Bitmap): Mat {
        val rgba = Mat(); Utils.bitmapToMat(frame, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); rgba.release()
        return gray
    }

    /** Content area of the frame with black letterbox / pillarbox bars removed (e.g. a video inside a player). */
    fun contentRect(gray: Mat): Rect {
        val rows = Mat(); val cols = Mat()
        Core.reduce(gray, rows, 1, Core.REDUCE_AVG, CvType.CV_32F)   // mean per row
        Core.reduce(gray, cols, 0, Core.REDUCE_AVG, CvType.CV_32F)   // mean per column
        val r = FloatArray(gray.rows()); rows.get(0, 0, r)
        val c = FloatArray(gray.cols()); cols.get(0, 0, c)
        rows.release(); cols.release()
        val thr = 10f
        var top = 0; while (top < r.size - 1 && r[top] < thr) top++
        var bottom = r.size; while (bottom > top + 1 && r[bottom - 1] < thr) bottom--
        var left = 0; while (left < c.size - 1 && c[left] < thr) left++
        var right = c.size; while (right > left + 1 && c[right - 1] < thr) right--
        // ignore tiny bars (rounded corners, UI shadows); only real letterboxing counts
        if (top < gray.rows() * 0.02) top = 0
        if (gray.rows() - bottom < gray.rows() * 0.02) bottom = gray.rows()
        if (left < gray.cols() * 0.02) left = 0
        if (gray.cols() - right < gray.cols() * 0.02) right = gray.cols()
        return Rect(left, top, right, bottom)
    }

    /**
     * @param tryMirror the game mirrors some pick cards so heroes face the centre; when true both
     *                  orientations are searched and the better one wins (ban icons are never mirrored).
     */
    fun identifyRegion(frameGray: Mat, region: Rect, slotKey: Int, tryMirror: Boolean = false): Match? {
        if (region.width() < 12 || region.height() < 12) return null
        val roi = frameGray.submat(region.top, region.bottom, region.left, region.right)
        return try { identify(roi, slotKey, tryMirror) } finally { roi.release() }
    }

    @Synchronized
    private fun identify(crop: Mat, slotKey: Int, tryMirror: Boolean): Match? {
        lastCandidate = null
        val hash = hashOf(crop)
        cache[slotKey]?.let { if (PerceptualHash.hamming(it.hash, hash) <= config.cacheHamming) { lastCandidate = it.best; return it.match } }

        var match: Match? = null
        if (!isEmpty(crop)) {
            match = search(crop)
            val plain = lastCandidate
            if (tryMirror) {
                Core.flip(crop, mirrored, 1)
                val m2 = search(mirrored)
                val flipped = lastCandidate
                if ((flipped?.second ?: -1f) > (plain?.second ?: -1f)) { match = m2 } else { lastCandidate = plain; match = match ?: m2.takeIf { plain == null } }
            }
        }
        cache[slotKey] = SlotCache(hash, match, lastCandidate)
        return match
    }

    private fun search(crop: Mat): Match? {
        val scale = config.workHeight.toDouble() / crop.rows()
        Imgproc.resize(crop, work, Size(crop.cols() * scale, config.workHeight.toDouble()), 0.0, 0.0, if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        clahe.apply(work, work)
        Imgproc.resize(work, half, Size(work.cols() * config.coarseFactor, work.rows() * config.coarseFactor), 0.0, 0.0, Imgproc.INTER_AREA)

        // coarse pass: every art of every hero at reduced resolution → shortlist + best art per hero
        class Coarse(val t: HeroTemplate, val score: Float, val artIndex: Int)
        val coarse = templates.map { t ->
            var bs = -1f; var bi = 0
            t.arts.forEachIndexed { i, a -> val s = a.coarse.maxOf { score(half, it) }; if (s > bs) { bs = s; bi = i } }
            Coarse(t, bs, bi)
        }.sortedByDescending { it.score }.take(config.shortlist)

        var best: HeroTemplate? = null; var bestScore = -1f; var second = -1f
        coarse.forEachIndexed { rank, c ->
            val t = c.t
            val arts = if (rank < config.fullRefine) t.arts else listOf(t.arts[c.artIndex])
            val s = arts.maxOf { a -> a.fine.maxOf { score(work, it) } }
            if (s > bestScore) { second = bestScore; bestScore = s; best = t } else if (s > second) second = s
        }
        val b = best ?: return null
        lastCandidate = b.heroId to bestScore
        val sure = bestScore >= config.sureScore
        if (bestScore < config.acceptScore || (!sure && bestScore - second < config.acceptMargin)) return null
        return Match(b.heroId, bestScore)
    }

    private fun score(image: Mat, tpl: Mat): Float {
        if (tpl.rows() >= image.rows() || tpl.cols() >= image.cols()) return -1f
        Imgproc.matchTemplate(image, tpl, result, Imgproc.TM_CCOEFF_NORMED)
        return Core.minMaxLoc(result).maxVal.toFloat()
    }

    private fun hashOf(crop: Mat): Long {
        Imgproc.resize(crop, hashMat, Size(9.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
        hashMat.get(0, 0, hashBytes)
        for (i in hashBytes.indices) hashBuf[i] = hashBytes[i].toInt() and 0xFF
        return PerceptualHash.dHash(hashBuf, 9, 8)
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
        for (t in laneTemplates) { val s = score(scaled, t.gray); if (s > bestScore) { bestScore = s; best = t.role } }
        roi.release(); scaled.release()
        return if (bestScore >= config.laneAcceptScore) best else null
    }

    fun clearCache() = cache.clear()

    companion object {
        private const val TAG = "VisionDetector"

        /**
         * Downloaded templates (self-update) win over the ones bundled in assets.  index.json maps each
         * hero to its base file plus art variants ("<id>_1.png" …); without it, file names are used.
         */
        fun load(context: Context, config: Config = Config()): VisionDetector {
            val downloaded = File(context.filesDir, "templates")
            val useDownloaded = File(downloaded, "index.json").exists()
            fun readText(name: String): String? = runCatching {
                if (useDownloaded) File(downloaded, name).readText() else context.assets.open("templates/$name").bufferedReader().readText()
            }.getOrNull()
            fun decode(name: String): Bitmap? = runCatching {
                if (useDownloaded) BitmapFactory.decodeFile(File(downloaded, name).path)
                else context.assets.open("templates/$name").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()

            // hero id -> art files
            val byHero = LinkedHashMap<String, MutableList<String>>()
            val index = readText("index.json")?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (index != null) {
                for (id in index.keys()) {
                    val e = index.getJSONObject(id)
                    val files = mutableListOf(e.optString("file", "$id.png"))
                    e.optJSONArray("variants")?.let { v -> for (i in 0 until v.length()) files += v.getString(i) }
                    byHero[id] = files
                }
            } else {
                val names = if (useDownloaded) downloaded.list().orEmpty().toList() else context.assets.list("templates").orEmpty().toList()
                for (f in names.filter { it.endsWith(".png") }) {
                    val id = f.removeSuffix(".png").replace(Regex("_\\d+$"), "")
                    byHero.getOrPut(id) { mutableListOf() } += f
                }
            }

            val clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0))
            fun prepare(bmp: Bitmap): ArtTemplate {
                val m = Mat(); Utils.bitmapToMat(bmp, m); bmp.recycle()
                Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
                val n = m.rows(); val c = (n * 0.18).toInt()
                val face = m.submat(c, n - c, c, n - c)              // inner 64 %: no black corners
                fun scaled(px: Int): Mat {
                    val out = Mat(); Imgproc.resize(face, out, Size(px.toDouble(), px.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA); clahe.apply(out, out); return out
                }
                val fine = config.fineScales.map { scaled((config.workHeight * it).toInt().coerceAtLeast(12)) }
                val coarse = config.coarseScales.map { scaled((config.workHeight * config.coarseFactor * it).toInt().coerceAtLeast(8)) }
                face.release(); m.release()
                return ArtTemplate(fine, coarse)
            }
            var artCount = 0
            val templates = byHero.mapNotNull { (id, files) ->
                val arts = files.mapNotNull { f -> decode(f)?.let { prepare(it) } }
                if (arts.isEmpty()) null else { artCount += arts.size; HeroTemplate(id, arts) }
            }
            val lanes = Role.entries.mapNotNull { role ->
                val bmp = runCatching { context.assets.open("lanes/${role.name.lowercase()}.png").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@mapNotNull null
                val m = Mat(); Utils.bitmapToMat(bmp, m); Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.resize(m, m, Size(28.0, 28.0), 0.0, 0.0, Imgproc.INTER_AREA)
                LaneTemplate(role, m)
            }
            Log.i(TAG, "loaded ${templates.size} heroes / $artCount arts (${if (useDownloaded) "downloaded" else "assets"}), ${lanes.size} lane badges")
            return VisionDetector(templates, lanes, config)
        }
    }
}
