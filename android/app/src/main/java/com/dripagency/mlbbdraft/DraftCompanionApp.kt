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
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as DraftCompanionApp).graph
        fun scope(context: Context): CoroutineScope = (context.applicationContext as DraftCompanionApp).appScope
    }
}
