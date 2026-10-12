package com.dripagency.mlbbdraft.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dripagency.mlbbdraft.DraftCompanionApp
import java.util.concurrent.TimeUnit

/** Runs the updater once a day on unmetered-or-not network when the battery is not low. */
class DataUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val r = DraftCompanionApp.graph(applicationContext).updater.checkAndUpdate(force = inputData.getBoolean(KEY_FORCE, false))
        return if (r is DataUpdater.Result.Failed && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "data-update-periodic"
        private const val NOW = "data-update-now"
        private const val KEY_FORCE = "force"

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<DataUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context, force: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<DataUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
