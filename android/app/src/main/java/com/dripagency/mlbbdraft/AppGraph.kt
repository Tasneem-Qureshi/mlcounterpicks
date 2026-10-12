package com.dripagency.mlbbdraft

import android.content.Context
import com.dripagency.mlbbdraft.data.db.MatchupDatabase
import com.dripagency.mlbbdraft.data.prefs.UserPrefs
import com.dripagency.mlbbdraft.data.repo.HeroRepository
import com.dripagency.mlbbdraft.data.repo.MasteryBackup
import com.dripagency.mlbbdraft.data.cloud.GoogleDriveSync
import com.dripagency.mlbbdraft.data.seed.MatchupSeeder
import com.dripagency.mlbbdraft.data.update.DataUpdater

/**
 * Hand-rolled dependency graph.  The app has ~6 singletons; a DI framework would cost
 * more build time and APK size than it saves.  Everything is lazy so the capture service
 * can start without touching UI code.
 */
class AppGraph(private val context: Context) {
    val db: MatchupDatabase by lazy { MatchupDatabase.build(context) }
    val prefs: UserPrefs by lazy { UserPrefs(context) }
    val repository: HeroRepository by lazy { HeroRepository(db.dao()) }
    val seeder: MatchupSeeder by lazy { MatchupSeeder(context, db.dao(), prefs) }
    val updater: DataUpdater by lazy { DataUpdater(context, seeder, repository, prefs) }
    val backup: MasteryBackup by lazy { MasteryBackup(context, repository, prefs) }
    val cloud: GoogleDriveSync by lazy { GoogleDriveSync(context, repository, prefs) }
}
