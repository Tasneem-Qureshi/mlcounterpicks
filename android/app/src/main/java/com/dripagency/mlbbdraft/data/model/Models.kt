package com.dripagency.mlbbdraft.data.model

enum class Role(val label: String, val short: String) {
    EXP("EXP Lane", "EXP"),
    GOLD("Gold Lane", "GOLD"),
    MID("Mid Lane", "MID"),
    ROAM("Roam", "ROAM"),
    JUNGLE("Jungle", "JG");

    companion object {
        fun parse(s: String?): Role? = s?.let { v -> entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } }
    }
}

data class Hero(
    val id: String,
    val name: String,
    val heroClass: String,
    val specialties: List<String>,
    val primaryRole: Role,
    val secondaryRoles: List<Role>,
    val portraitFile: String?,
    val winRate: Double?,
) {
    val roles: List<Role> get() = listOf(primaryRole) + secondaryRoles
    fun playsRole(role: Role) = role == primaryRole || role in secondaryRoles
}

/** One directed matchup fact. `heroId` is the *other* hero; `role` the lane that hero usually plays. */
data class MatchupEdge(
    val heroId: String,
    val role: Role?,
    val edgeScore: Double,      // 0..5
    val reason: String,
    val confidence: String,     // ranked | kit | community | curated | derived
)

data class HeroProfile(
    val hero: Hero,
    val counters: List<MatchupEdge>,       // heroes that beat this hero
    val strongAgainst: List<MatchupEdge>,  // heroes this hero beats
    val laneTips: List<String>,            // how to play AGAINST this hero
    val itemCounters: List<String>,
)

enum class RecommendMode { STRICT, FLEXIBLE }

data class UserSettings(
    val mode: RecommendMode = RecommendMode.FLEXIBLE,
    val defaultRole: Role = Role.EXP,
    val autoDetectLane: Boolean = true,
    val overlayOpacity: Float = 0.92f,
    val briefingSeconds: Int = 18,
    val samplingMillis: Long = 1200,
    val calibrationDump: Boolean = false,
    val seededDataVersion: Long = 0,
    val lastUpdateCheck: Long = 0,
    val lastUpdateMessage: String = "",
    /** empty = use BuildConfig.DATA_MANIFEST_URL */
    val dataManifestUrl: String = "",
    val autoShowOnMlbb: Boolean = true,
    /** run detection in any app (testing with recorded drafts) instead of only while MLBB is in front */
    val detectAnywhere: Boolean = false,
    /** last local change to mastered heroes / synced settings (epoch ms) — drives cloud merge */
    val masteryUpdatedAt: Long = 0,
    val cloudEnabled: Boolean = false,
    val cloudEmail: String = "",
    val cloudLastSync: Long = 0,
    val cloudMessage: String = "",
)
