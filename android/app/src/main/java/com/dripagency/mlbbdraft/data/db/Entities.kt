package com.dripagency.mlbbdraft.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "heroes")
data class HeroEntity(
    @PrimaryKey @ColumnInfo(name = "hero_id") val heroId: String,
    val name: String,
    @ColumnInfo(name = "hero_class") val heroClass: String,
    /** comma separated */
    val specialties: String,
    @ColumnInfo(name = "primary_role") val primaryRole: String,
    /** comma separated */
    @ColumnInfo(name = "secondary_roles") val secondaryRoles: String,
    @ColumnInfo(name = "portrait_file") val portraitFile: String?,
    @ColumnInfo(name = "win_rate") val winRate: Double?,
    @ColumnInfo(name = "is_preferred") val isPreferred: Boolean = false,
)

object Direction {
    const val COUNTERED_BY = "COUNTERED_BY"     // other beats hero
    const val STRONG_AGAINST = "STRONG_AGAINST" // hero beats other
}

@Entity(
    tableName = "matchups",
    primaryKeys = ["hero_id", "other_id", "direction"],
    indices = [Index("other_id"), Index("hero_id")],
)
data class MatchupEntity(
    @ColumnInfo(name = "hero_id") val heroId: String,
    @ColumnInfo(name = "other_id") val otherId: String,
    val direction: String,
    @ColumnInfo(name = "other_role") val otherRole: String,
    @ColumnInfo(name = "edge_score") val edgeScore: Double,
    val reason: String,
    val confidence: String,
)

@Entity(tableName = "lane_tips", primaryKeys = ["hero_id", "kind", "ordinal"])
data class LaneTipEntity(
    @ColumnInfo(name = "hero_id") val heroId: String,
    /** tip | item */
    val kind: String,
    val ordinal: Int,
    val text: String,
)
