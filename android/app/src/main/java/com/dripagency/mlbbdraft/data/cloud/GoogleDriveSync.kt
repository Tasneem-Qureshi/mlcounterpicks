package com.dripagency.mlbbdraft.data.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dripagency.mlbbdraft.data.model.RecommendMode
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.data.prefs.UserPrefs
import com.dripagency.mlbbdraft.data.repo.HeroRepository
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Syncs mastered heroes + settings to a private file in the user's Google Drive app-data folder
 * (invisible in Drive, deleted with the app's data, no quota worries at 2 KB).
 *
 * Auth: Play Services AuthorizationClient with the drive.appdata scope.  No web client id, no
 * Firebase, no backend — only an Android OAuth client (package + SHA-1) in Google Cloud Console.
 * Merge: last-write-wins on `updated_at`; local edits bump the timestamp on every change.
 */
class GoogleDriveSync(
    private val context: Context,
    private val repository: HeroRepository,
    private val prefs: UserPrefs,
) {
    sealed class Auth {
        data class Token(val accessToken: String) : Auth()
        data class NeedsConsent(val pendingIntent: PendingIntent) : Auth()
        data class Failed(val message: String) : Auth()
    }

    sealed class SyncResult {
        data class Done(val message: String) : SyncResult()
        data class NeedsConsent(val pendingIntent: PendingIntent) : SyncResult()
        data class Failed(val message: String) : SyncResult()
    }

    private val client get() = Identity.getAuthorizationClient(context)
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_APPDATA))).build()

    /** Silent if the user already consented, otherwise returns the consent PendingIntent for the UI to launch. */
    suspend fun authorize(): Auth = try {
        val r = client.authorize(request).await()
        when {
            r.hasResolution() -> Auth.NeedsConsent(r.pendingIntent!!)
            r.accessToken != null -> Auth.Token(r.accessToken!!)
            else -> Auth.Failed("no access token returned")
        }
    } catch (e: ApiException) {
        Auth.Failed(describe(e))
    } catch (e: Exception) {
        Auth.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Call with the Intent returned by the consent activity. */
    fun tokenFromConsent(data: Intent?): String? = runCatching {
        data?.let { client.getAuthorizationResultFromIntent(it).accessToken }
    }.getOrNull()

    suspend fun sync(token: String? = null): SyncResult = withContext(Dispatchers.IO) {
        val t = token ?: when (val a = authorize()) {
            is Auth.Token -> a.accessToken
            is Auth.NeedsConsent -> return@withContext SyncResult.NeedsConsent(a.pendingIntent)
            is Auth.Failed -> return@withContext fail(a.message)
        }
        runCatching { doSync(t) }.getOrElse { fail(it.message ?: it.javaClass.simpleName) }
    }

    private suspend fun doSync(token: String): SyncResult {
        val local = localSnapshot()
        val remoteFile = findRemote(token)
        val remote = remoteFile?.let { JSONObject(get("$FILES/${it.first}?alt=media", token)) }
        val localAt = local.optLong("updated_at")
        val remoteAt = remote?.optLong("updated_at") ?: -1L

        val message: String
        if (remote != null && remoteAt > localAt) {
            val n = applyRemote(remote)
            message = "Restored $n mastered heroes from Google account"
        } else {
            upload(token, remoteFile?.first, local)
            message = if (remote == null) "Saved ${local.getJSONArray("mastered").length()} mastered heroes to Google account"
            else "Google account is in sync (${local.getJSONArray("mastered").length()} mastered heroes)"
        }
        val email = runCatching { JSONObject(get("$ABOUT?fields=user", token)).getJSONObject("user").optString("emailAddress") }.getOrDefault("")
        prefs.setCloudState(enabled = true, email = email, message = message)
        Log.i(TAG, message)
        return SyncResult.Done(message)
    }

    // ---- payload ---------------------------------------------------------------------------
    private suspend fun localSnapshot(): JSONObject {
        val s = prefs.current()
        return JSONObject()
            .put("app", "mlbb-draft-companion").put("version", 1)
            .put("updated_at", s.masteryUpdatedAt)
            .put("mastered", JSONArray(repository.preferredIds().sorted()))
            .put("mode", s.mode.name)
            .put("default_role", s.defaultRole.name)
            .put("data_manifest_url", s.dataManifestUrl)
    }

    private suspend fun applyRemote(doc: JSONObject): Int {
        val arr = doc.getJSONArray("mastered")
        val ids = (0 until arr.length()).map { arr.getString(it) }.toSet()
        val known = repository.heroIds()
        repository.preferredIds().forEach { if (it !in ids) repository.setPreferred(it, false) }
        var n = 0
        ids.forEach { if (it in known) { repository.setPreferred(it, true); n++ } }
        RecommendMode.entries.firstOrNull { it.name == doc.optString("mode") }?.let { prefs.setMode(it) }
        Role.parse(doc.optString("default_role"))?.let { prefs.setDefaultRole(it) }
        doc.optString("data_manifest_url").takeIf { it.isNotBlank() }?.let { prefs.setDataManifestUrl(it) }
        prefs.setMasteryUpdatedAt(doc.optLong("updated_at"))
        return n
    }

    // ---- Drive REST (appDataFolder) ----------------------------------------------------------
    private fun findRemote(token: String): Pair<String, String>? {
        val q = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val json = JSONObject(get("$FILES?spaces=appDataFolder&q=$q&fields=files(id,name,modifiedTime)", token))
        val files = json.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        return f.getString("id") to f.optString("modifiedTime")
    }

    private fun upload(token: String, existingId: String?, doc: JSONObject) {
        val body = doc.toString()
        if (existingId != null) {
            request("PATCH", "$UPLOAD/$existingId?uploadType=media", token, "application/json", body.toByteArray())
        } else {
            val boundary = "mlbb-" + System.currentTimeMillis()
            val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder")).toString()
            val multipart = buildString {
                append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
                append("--$boundary\r\nContent-Type: application/json\r\n\r\n$body\r\n--$boundary--")
            }
            request("POST", "$UPLOAD?uploadType=multipart", token, "multipart/related; boundary=$boundary", multipart.toByteArray())
        }
    }

    private fun get(url: String, token: String): String = request("GET", url, token, null, null)

    private fun request(method: String, url: String, token: String, contentType: String?, body: ByteArray?): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) { doOutput = true }
        }
        try {
            body?.let { c.outputStream.use { os -> os.write(it) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) error("Drive HTTP $code: ${text.take(160)}")
            return text
        } finally { c.disconnect() }
    }

    private suspend fun fail(msg: String): SyncResult {
        prefs.setCloudMessage("Sync failed: $msg")
        Log.w(TAG, msg)
        return SyncResult.Failed(msg)
    }

    private fun describe(e: ApiException): String = when (e.statusCode) {
        10 -> "app not registered in Google Cloud (DEVELOPER_ERROR): add an Android OAuth client with this package + SHA-1 and enable the Drive API"
        16 -> "sign-in cancelled"
        7 -> "network error"
        else -> "Google error ${e.statusCode}: ${e.message}"
    }

    companion object {
        private const val TAG = "GoogleDriveSync"
        private const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val ABOUT = "https://www.googleapis.com/drive/v3/about"
        const val FILE_NAME = "mlbb-draft-companion.json"
    }
}
