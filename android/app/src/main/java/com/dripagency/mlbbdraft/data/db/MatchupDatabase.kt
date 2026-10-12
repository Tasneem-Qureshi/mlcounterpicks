package com.dripagency.mlbbdraft.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HeroEntity::class, MatchupEntity::class, LaneTipEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MatchupDatabase : RoomDatabase() {
    abstract fun dao(): MatchupDao

    companion object {
        fun build(context: Context): MatchupDatabase =
            Room.databaseBuilder(context.applicationContext, MatchupDatabase::class.java, "matchups.db")
                .fallbackToDestructiveMigration(dropAllTables = true) // data is re-seeded from assets anyway
                .build()
    }
}
