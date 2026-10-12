package com.dripagency.mlbbdraft.data.seed

import android.content.Context
import android.util.Log
import com.dripagency.mlbbdraft.data.db.MatchupDao
import com.dripagency.mlbbdraft.data.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seeds Room from the newest dataset available:
 *   1. a downloaded dataset in filesDir/data/matchups.json (from the self-updater), else
 *   2. the bundled assets/matchups.json.
 * Only re-seeds when data_version is newer than what is already in the DB.
 */
class MatchupSeeder(private val context: Context, private val dao: MatchupDao, private val prefs: UserPrefs) {

    suspend fun seedIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val seeded = prefs.current().seededDataVersion
        val downloaded = File(context.filesDir, "data/matchups.json")
        val candidates = buildList {
            if (downloaded.exists()) add("downloaded" to { downloaded.readText() })
            add("assets" to { context.assets.open("matchups.json").bufferedReader().readText() })
        }
        var best: Pair<String, MatchupsParser.Parsed>? = null
        for ((label, read) in candidates) {
            val parsed = runCatching { MatchupsParser.parse(read()) }
                .onFailure { Log.w(TAG, "cannot parse $label dataset", it) }
                .getOrNull() ?: continue
            if (best == null || parsed.dataVersion > best!!.second.dataVersion) best = label to parsed
        }
        val (label, parsed) = best ?: return@withContext false
        if (parsed.dataVersion <= seeded && dao.heroCount() > 0) return@withContext false
        apply(parsed)
        Log.i(TAG, "seeded ${parsed.heroes.size} heroes from $label (v${parsed.dataVersion})")
        true
    }

    suspend fun apply(parsed: MatchupsParser.Parsed) {
        dao.replaceAll(parsed.heroes, parsed.matchups, parsed.tips)
        prefs.setSeededDataVersion(parsed.dataVersion)
    }

    private companion object { const val TAG = "MatchupSeeder" }
}
