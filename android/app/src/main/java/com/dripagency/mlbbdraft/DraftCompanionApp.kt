package com.dripagency.mlbbdraft

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import com.dripagency.mlbbdraft.data.update.DataUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.opencv.android.OpenCVLoader

class DraftCompanionApp : Application(), Configuration.Provider {

    val graph: AppGraph by lazy { AppGraph(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("DraftCompanionApp", "OpenCV failed to initialise")
        appScope.launch { runCatching { graph.seeder.seedIfNeeded() }.onFailure { Log.e("DraftCompanionApp", "seed failed", it) } }
        DataUpdateWorker.schedulePeriodic(this)
        // Also check on every launch when the last check is older than a day (covers phones that
        // paused background work while the app sat unused for months).
        appScope.launch {
            val s = graph.prefs.current()
            if (System.currentTimeMillis() - s.lastUpdateCheck > 24 * 60 * 60 * 1000L) DataUpdateWorker.runNow(this@DraftCompanionApp)
        }
        startCloudAutoSync()
    }

    /** Pushes mastery/settings changes to the Google account ~8 s after the last edit (silent; needs prior consent). */
    @OptIn(FlowPreview::class)
    private fun startCloudAutoSync() {
        appScope.launch {
            graph.prefs.settings
                .filter { it.cloudEnabled }
                .map { it.masteryUpdatedAt }
                .distinctUntilChanged()
                .drop(1)
                .debounce(8_000)
                .collectLatest { runCatching { graph.cloud.sync() }.onFailure { Log.w("DraftCompanionApp", "auto-sync failed", it) } }
        }
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as DraftCompanionApp).graph
        fun scope(context: Context): CoroutineScope = (context.applicationContext as DraftCompanionApp).appScope
    }
}
