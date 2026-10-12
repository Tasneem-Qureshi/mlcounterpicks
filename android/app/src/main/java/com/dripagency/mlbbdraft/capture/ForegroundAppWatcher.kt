package com.dripagency.mlbbdraft.capture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Tells whether one of the MLBB packages is the foreground app, using UsageStats events
 * (needs the user to grant "Usage access"; no root, no accessibility service).
 * Returns null when the permission is missing so callers can fall back to "unknown".
 */
class ForegroundAppWatcher(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastPackage: String? = null
    private var lastQuery = 0L

    fun hasPermission(): Boolean = hasUsageAccess(context)

    /** Package currently in front, or null if unknown / not permitted. Cheap enough to call every few seconds. */
    fun foregroundPackage(): String? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 60_000 else lastQuery - 2_000
        lastQuery = now
        val events = usm.queryEvents(from, now) ?: return lastPackage
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastPackage = e.packageName
        }
        return lastPackage
    }

    fun isMlbbInFront(): Boolean? = foregroundPackage()?.let { it in MLBB_PACKAGES }

    companion object {
        /** Global, VNG (Vietnam) and Chinese builds. */
        val MLBB_PACKAGES = setOf("com.mobile.legends", "com.vng.mlbbvn", "com.mobilelegends.mi", "com.moonton.mobilehero")

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
