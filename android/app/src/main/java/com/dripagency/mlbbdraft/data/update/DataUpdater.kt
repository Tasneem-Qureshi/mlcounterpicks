package com.dripagency.mlbbdraft.data.update

import android.content.Context
import android.util.Log
import com.dripagency.mlbbdraft.BuildConfig
import com.dripagency.mlbbdraft.data.prefs.UserPrefs
import com.dripagency.mlbbdraft.data.repo.HeroRepository
import com.dripagency.mlbbdraft.data.seed.MatchupSeeder
import com.dripagency.mlbbdraft.data.seed.MatchupsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Self-update of hero data + portrait templates.
 *
 * Flow: GET manifest.json → compare data_version → download matchups.json (+ templates.zip)
 *       → verify sha256 → parse/validate → atomic swap on disk → re-seed Room → notify listeners.
 * Nothing is applied unless every step succeeds, so a half-downloaded update can never brick
 * the dataset; the bundled assets remain the fallback forever.
 */
class DataUpdater(
    private val context: Context,
    private val seeder: MatchupSeeder,
    private val repository: HeroRepository,
    private val prefs: UserPrefs,
) {
    sealed class Result {
        data object UpToDate : Result()
        data class Updated(val dataVersion: Long, val heroes: Int, val templates: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun checkAndUpdate(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val result = runCatching { doUpdate(force) }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
        prefs.setUpdateStatus(
            when (result) {
                Result.UpToDate -> "Up to date"
                is Result.Updated -> "Updated to v${result.dataVersion}: ${result.heroes} heroes, ${result.templates} portraits"
                is Result.Failed -> "Update failed: ${result.message}"
            },
        )
        if (result is Result.Failed) Log.w(TAG, result.message)
        result
    }

    private suspend fun doUpdate(force: Boolean): Result {
        val manifestUrl = prefs.current().dataManifestUrl.ifBlank { BuildConfig.DATA_MANIFEST_URL }
        if (manifestUrl.isBlank() || manifestUrl.contains("YOUR_GH_USER")) return Result.Failed("no data URL set — enter the manifest.json URL under Hero data")
        if (!manifestUrl.startsWith("https://")) return Result.Failed("data URL must start with https://")
        val manifest = JSONObject(fetchText(manifestUrl))
        val schema = manifest.optInt("schema_version", -1)
        if (schema != MatchupsParser.SUPPORTED_SCHEMA) return Result.Failed("server schema $schema not supported; update the app")
        val remoteVersion = manifest.optLong("data_version", 0L)
        val local = prefs.current().seededDataVersion
        if (!force && remoteVersion <= local) return Result.UpToDate

        val tmp = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val m = manifest.getJSONObject("matchups")
        val matchupsFile = File(tmp, "matchups.json")
        download(m.getString("url"), matchupsFile)
        verify(matchupsFile, m.optString("sha256"))
        val parsed = MatchupsParser.parse(matchupsFile.readText())

        var templateCount = 0
        val templatesDir = File(context.filesDir, "templates")
        val stagedTemplates = File(tmp, "templates")
        if (manifest.has("templates")) {
            val t = manifest.getJSONObject("templates")
            val zip = File(tmp, "templates.zip")
            download(t.getString("url"), zip)
            verify(zip, t.optString("sha256"))
            templateCount = unzip(zip, stagedTemplates)
            if (!File(stagedTemplates, "index.json").exists()) return Result.Failed("templates.zip has no index.json")
        }

        // Commit: everything validated, now swap files atomically-ish (rename is atomic per file on ext4/f2fs).
        val dataDir = File(context.filesDir, "data").apply { mkdirs() }
        val finalMatchups = File(dataDir, "matchups.json")
        if (!matchupsFile.renameTo(finalMatchups)) matchupsFile.copyTo(finalMatchups, overwrite = true)
        if (stagedTemplates.exists()) {
            val old = File(context.filesDir, "templates.old")
            old.deleteRecursively()
            if (templatesDir.exists()) templatesDir.renameTo(old)
            if (!stagedTemplates.renameTo(templatesDir)) stagedTemplates.copyRecursively(templatesDir, overwrite = true)
            old.deleteRecursively()
        }
        seeder.apply(parsed)
        repository.dataChanged.tryEmit(Unit)
        tmp.deleteRecursively()
        Log.i(TAG, "updated to v$remoteVersion (${parsed.heroes.size} heroes, $templateCount templates)")
        return Result.Updated(remoteVersion, parsed.heroes.size, templateCount)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "MLBBDraftCompanion/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun fetchText(url: String): String = open(url).run {
        try { if (responseCode != 200) error("HTTP $responseCode for manifest"); inputStream.bufferedReader().readText() } finally { disconnect() }
    }

    private fun download(url: String, dest: File) = open(url).run {
        try {
            if (responseCode != 200) error("HTTP $responseCode downloading ${dest.name}")
            inputStream.use { input -> FileOutputStream(dest).use { out -> input.copyTo(out, 64 * 1024) } }
        } finally { disconnect() }
    }

    private fun verify(file: File, expectedSha: String) {
        if (expectedSha.isBlank()) return
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(64 * 1024); var n: Int; while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n) }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha, ignoreCase = true)) error("sha256 mismatch for ${file.name}")
    }

    /** Flat zip only; rejects path traversal. */
    private fun unzip(zip: File, dest: File): Int {
        dest.deleteRecursively(); dest.mkdirs()
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                if (!entry.isDirectory && name.isNotBlank() && !entry.name.contains("..")) {
                    FileOutputStream(File(dest, name)).use { zin.copyTo(it) }
                    if (name.endsWith(".png")) count++
                }
                zin.closeEntry(); entry = zin.nextEntry
            }
        }
        return count
    }

    private companion object { const val TAG = "DataUpdater" }
}
