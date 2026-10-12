package com.dripagency.mlbbdraft.data.repo

import android.content.Context
import android.net.Uri
import com.dripagency.mlbbdraft.data.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit export / import of the user's selections to a file the user picks (Google Drive,
 * Downloads, …) via the system file picker.  Complements Android Auto Backup, which already
 * mirrors the database to the Google account silently.
 */
class MasteryBackup(private val context: Context, private val repository: HeroRepository, private val prefs: UserPrefs) {

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val s = prefs.current()
        val ids = repository.preferredIds().sorted()
        val doc = JSONObject()
            .put("app", "mlbb-draft-companion").put("version", 1)
            .put("exported_at", System.currentTimeMillis())
            .put("mastered", JSONArray(ids))
            .put("mode", s.mode.name)
            .put("default_role", s.defaultRole.name)
            .put("data_manifest_url", s.dataManifestUrl)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(doc.toString(2).toByteArray()) } ?: error("cannot open destination")
        ids.size
    }

    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: error("cannot open file")
        val doc = JSONObject(text)
        require(doc.optString("app") == "mlbb-draft-companion") { "not a Draft Companion backup" }
        val arr = doc.getJSONArray("mastered")
        val ids = (0 until arr.length()).map { arr.getString(it) }.toSet()
        val known = repository.heroIds()
        repository.preferredIds().forEach { if (it !in ids) repository.setPreferred(it, false) }
        var n = 0
        ids.forEach { if (it in known) { repository.setPreferred(it, true); n++ } }
        com.dripagency.mlbbdraft.data.model.RecommendMode.entries.firstOrNull { it.name == doc.optString("mode") }?.let { prefs.setMode(it) }
        com.dripagency.mlbbdraft.data.model.Role.parse(doc.optString("default_role"))?.let { prefs.setDefaultRole(it) }
        doc.optString("data_manifest_url").takeIf { it.isNotBlank() }?.let { prefs.setDataManifestUrl(it) }
        n
    }
}
