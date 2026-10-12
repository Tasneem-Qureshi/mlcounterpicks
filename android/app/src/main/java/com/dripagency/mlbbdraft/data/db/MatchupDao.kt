package com.dripagency.mlbbdraft.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchupDao {
    @Query("SELECT * FROM heroes ORDER BY name")
    fun observeHeroes(): Flow<List<HeroEntity>>

    @Query("SELECT * FROM heroes ORDER BY name")
    suspend fun heroes(): List<HeroEntity>

    @Query("SELECT hero_id FROM heroes WHERE is_preferred = 1")
    fun observePreferredIds(): Flow<List<String>>

    @Query("SELECT hero_id FROM heroes WHERE is_preferred = 1")
    suspend fun preferredIds(): List<String>

    @Query("UPDATE heroes SET is_preferred = :preferred WHERE hero_id = :heroId")
    suspend fun setPreferred(heroId: String, preferred: Boolean)

    @Query("SELECT * FROM matchups")
    suspend fun matchups(): List<MatchupEntity>

    @Query("SELECT * FROM lane_tips ORDER BY hero_id, kind, ordinal")
    suspend fun tips(): List<LaneTipEntity>

    @Query("SELECT COUNT(*) FROM heroes")
    suspend fun heroCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHeroes(rows: List<HeroEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatchups(rows: List<MatchupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTips(rows: List<LaneTipEntity>)

    @Query("DELETE FROM heroes") suspend fun clearHeroes()
    @Query("DELETE FROM matchups") suspend fun clearMatchups()
    @Query("DELETE FROM lane_tips") suspend fun clearTips()

    /**
     * Replace the whole dataset atomically while preserving the user's mastery flags.
     * Heroes that disappear from the dataset drop their flag; new heroes start unflagged.
     */
    @Transaction
    suspend fun replaceAll(heroes: List<HeroEntity>, matchups: List<MatchupEntity>, tips: List<LaneTipEntity>) {
        val preferred = preferredIds().toHashSet()
        clearTips(); clearMatchups(); clearHeroes()
        insertHeroes(heroes.map { if (it.heroId in preferred) it.copy(isPreferred = true) else it })
        insertMatchups(matchups)
        insertTips(tips)
    }
}
