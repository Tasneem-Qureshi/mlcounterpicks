package com.dripagency.mlbbdraft.data.seed

import com.dripagency.mlbbdraft.data.db.Direction
import com.dripagency.mlbbdraft.data.db.HeroEntity
import com.dripagency.mlbbdraft.data.db.LaneTipEntity
import com.dripagency.mlbbdraft.data.db.MatchupEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the scraper's matchups.json (schema v1) into Room rows.
 * Deliberately uses org.json: zero extra dependencies, zero annotation processing,
 * and the schema is small enough that a typed parser is clearer than reflection.
 */
object MatchupsParser {
    const val SUPPORTED_SCHEMA = 1

    data class Parsed(
        val schemaVersion: Int,
        val dataVersion: Long,
        val generatedAt: String,
        val heroes: List<HeroEntity>,
        val matchups: List<MatchupEntity>,
        val tips: List<LaneTipEntity>,
    )

    fun parse(json: String): Parsed {
        val root = JSONObject(json)
        val schema = root.optInt("schema_version", -1)
        require(schema == SUPPORTED_SCHEMA) { "Unsupported matchups schema $schema (app supports $SUPPORTED_SCHEMA)" }
        val heroes = ArrayList<HeroEntity>()
        val matchups = ArrayList<MatchupEntity>()
        val tips = ArrayList<LaneTipEntity>()
        val arr = root.getJSONArray("heroes")
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            val id = h.getString("hero_id")
            require(ids.add(id)) { "duplicate hero_id $id" }
            heroes += HeroEntity(
                heroId = id,
                name = h.getString("name"),
                heroClass = h.optString("hero_class", ""),
                specialties = h.optJSONArray("specialties").toStringList().joinToString(","),
                primaryRole = h.getString("primary_role"),
                secondaryRoles = h.optJSONArray("secondary_roles").toStringList().joinToString(","),
                portraitFile = h.optString("portrait_file", "").ifBlank { null },
                winRate = if (h.isNull("win_rate")) null else h.optDouble("win_rate"),
            )
            matchups += edges(id, h.optJSONArray("counters"), Direction.COUNTERED_BY)
            matchups += edges(id, h.optJSONArray("strong_against"), Direction.STRONG_AGAINST)
            h.optJSONArray("lane_tips").toStringList().forEachIndexed { n, t -> tips += LaneTipEntity(id, "tip", n, t) }
            h.optJSONArray("item_counters").toStringList().forEachIndexed { n, t -> tips += LaneTipEntity(id, "item", n, t) }
        }
        // Referential integrity: the DB has no FK (speed), so enforce it here.
        val valid = matchups.filter { it.otherId in ids }
        return Parsed(
            schemaVersion = schema,
            dataVersion = root.optLong("data_version", 0L),
            generatedAt = root.optString("generated_at", ""),
            heroes = heroes, matchups = valid, tips = tips,
        )
    }

    private fun edges(heroId: String, arr: JSONArray?, direction: String): List<MatchupEntity> {
        if (arr == null) return emptyList()
        val out = ArrayList<MatchupEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            out += MatchupEntity(
                heroId = heroId,
                otherId = e.getString("hero_id"),
                direction = direction,
                otherRole = e.optString("role", ""),
                edgeScore = e.optDouble("edge_score", 2.5).coerceIn(0.0, 5.0),
                reason = e.optString("reason", ""),
                confidence = e.optString("confidence", "ranked"),
            )
        }
        return out
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
