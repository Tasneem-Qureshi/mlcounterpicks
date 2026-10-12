package com.dripagency.mlbbdraft.engine

import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.RecommendMode
import com.dripagency.mlbbdraft.data.model.Role

data class EngineConfig(
    val directWeight: Double = 0.40,
    val teamWeight: Double = 0.60,
    val masteryMultiplier: Double = 1.40,
    /** Candidates whose *primary* lane is the user's lane get a small nudge over flex picks. */
    val primaryRoleBonus: Double = 1.03,
    /** Below this many enemies, the "team" term is mostly noise; blend toward neutral. */
    val minEnemiesForTeamTerm: Int = 1,
    /** Tiny meta prior from the hero's ranked win rate so an empty draft still ranks sensibly. */
    val metaPriorWeight: Double = 0.03,
)

data class DraftContext(
    val myRole: Role,
    val enemies: List<String>,
    val allies: List<String> = emptyList(),
    val banned: List<String> = emptyList(),
    val mastered: Set<String> = emptySet(),
    val mode: RecommendMode = RecommendMode.FLEXIBLE,
    /** multiply mastered heroes' score (reorders the list); off = badge only, pure counter ranking */
    val boostMastered: Boolean = false,
)

/** One enemy as seen from a candidate: signed edge, the full reason, and how to play it when it is bad. */
data class EnemyMatchup(
    val enemy: Hero,
    val edge: Double,             // -5..5, positive = candidate is favoured
    val reason: String,
    val laneOpponent: Boolean,
    /** advice for playing against this enemy (shown when edge is unfavourable) */
    val advice: List<String>,
)

data class Recommendation(
    val hero: Hero,
    val composite: Double,        // 0..~1.4
    val directScore: Double,      // 0..1 (0.5 = neutral)
    val teamScore: Double,        // 0..1
    val mastered: Boolean,
    val reason: String,
    val directOpponent: Hero?,
    val warning: String?,
    val matchups: List<EnemyMatchup> = emptyList(),
) {
    val beats: List<EnemyMatchup> get() = matchups.filter { it.edge >= 1.0 }.sortedByDescending { it.edge }
    val weakInto: List<EnemyMatchup> get() = matchups.filter { it.edge <= -1.0 }.sortedBy { it.edge }
}

data class RecommendationResult(
    val picks: List<Recommendation>,
    val enemyRoles: Map<String, Role>,
    val directOpponent: Hero?,
    /** true when STRICT mode had no mastered candidate for the role and FLEXIBLE ranking was used instead */
    val strictFallback: Boolean,
)

/**
 * Pure scoring.  composite = (0.4 * direct + 0.6 * team) * (1.4 if mastered) * primaryRoleBonus
 * where direct/team are normalised edges (edge/10 + 0.5) in [0, 1].
 */
class CounterEngine(private val index: MatchupIndex, private val config: EngineConfig = EngineConfig()) {
    private val GENERIC = Regex("wins the matchup against|in ranked data|favourable matchup", RegexOption.IGNORE_CASE)

    fun recommend(ctx: DraftContext, topN: Int = 3): RecommendationResult {
        val enemies = ctx.enemies.distinct().mapNotNull { index.hero(it) }
        val enemyRoles = RoleAssigner.assign(enemies)
        val opponent = enemyRoles.entries.firstOrNull { it.value == ctx.myRole }?.key?.let { index.hero(it) }
        val taken = (ctx.enemies + ctx.allies + ctx.banned).toHashSet()

        val pool = index.candidatesForRole(ctx.myRole).filter { it.id !in taken }
        val strictPool = pool.filter { it.id in ctx.mastered }
        val strictFallback = ctx.mode == RecommendMode.STRICT && strictPool.isEmpty()
        val candidates = if (ctx.mode == RecommendMode.STRICT && !strictFallback) strictPool else pool

        val scored = candidates.map { score(it, enemies, opponent, ctx) }
            .sortedWith(compareByDescending<Recommendation> { it.composite }.thenByDescending { it.hero.winRate ?: 50.0 }.thenBy { it.hero.name })
        return RecommendationResult(scored.take(topN), enemyRoles, opponent, strictFallback)
    }

    private fun score(c: Hero, enemies: List<Hero>, opponent: Hero?, ctx: DraftContext): Recommendation {
        val others = enemies.filter { it.id != opponent?.id }
        val teamEdges = others.map { it to index.edgeDetail(c.id, it.id) }
        val team = if (others.size >= config.minEnemiesForTeamTerm) teamEdges.map { norm(it.second.score) }.average() else 0.5
        val directDetail = opponent?.let { index.edgeDetail(c.id, it.id) }
        // No lane opponent visible yet: let the team term stand in so the weights still sum to 1.
        val direct = directDetail?.let { norm(it.score) } ?: team

        val mastered = c.id in ctx.mastered
        var composite = config.directWeight * direct + config.teamWeight * team
        c.winRate?.let { wr -> composite += config.metaPriorWeight * ((wr - 50.0) / 3.0).coerceIn(-1.0, 1.0) }
        if (mastered && ctx.boostMastered) composite *= config.masteryMultiplier
        if (c.primaryRole == ctx.myRole) composite *= config.primaryRoleBonus

        val reason = buildReason(c, opponent, directDetail, teamEdges)
        val all = (if (opponent != null && directDetail != null) listOf(opponent to directDetail) else emptyList()) + teamEdges
        val matchups = all.map { (e, d) ->
            val profile = index.profile(e.id)
            EnemyMatchup(
                enemy = e, edge = d.score, laneOpponent = e.id == opponent?.id,
                reason = d.reason.takeUnless { it.isBlank() || GENERIC.containsMatchIn(it) || d.confidence == "derived" }
                    ?: if (d.score >= 1.0) "${c.name} wins this matchup in ranked data" else if (d.score <= -1.0) "${e.name} wins this matchup in ranked data" else "Even matchup",
                advice = if (d.score <= -1.0) (profile?.laneTips.orEmpty().take(2) + profile?.itemCounters.orEmpty().take(1)) else emptyList(),
            )
        }
        val weak = matchups.filter { it.edge <= -1.0 }.sortedBy { it.edge }
        val warning = weak.takeIf { it.isNotEmpty() }?.let { w -> "Weak into " + w.joinToString(", ") { it.enemy.name } }
        return Recommendation(c, composite, direct, team, mastered, reason, opponent, warning, matchups)
    }

    private fun norm(edge: Double) = (edge / 10.0 + 0.5).coerceIn(0.0, 1.0)

    private fun buildReason(
        c: Hero, opponent: Hero?, direct: MatchupIndex.EdgeDetail?,
        teamEdges: List<Pair<Hero, MatchupIndex.EdgeDetail>>,
    ): String {
        val parts = ArrayList<String>(3)
        if (opponent != null && direct != null) {
            parts += when {
                direct.score >= 3.5 -> "Hard-counters ${opponent.name} in lane"
                direct.score >= 1.0 -> "Counters ${opponent.name} in lane"
                direct.score <= -1.0 -> "Loses lane to ${opponent.name}"
                else -> "Even lane vs ${opponent.name}"
            }
        }
        val beats = teamEdges.filter { it.second.score >= 1.0 }.sortedByDescending { it.second.score }.map { it.first.name }
        if (beats.isNotEmpty()) parts += "beats " + beats.take(3).joinToString(", ")
        val detail = (listOfNotNull(direct) + teamEdges.map { it.second })
            .filter { it.score >= 1.0 && it.reason.isNotBlank() && it.confidence != "derived" && !GENERIC.containsMatchIn(it.reason) }
            .maxByOrNull { it.score }?.reason
        if (detail != null) parts += detail.take(90).trimEnd('.')
        return if (parts.isEmpty()) "Solid ${c.primaryRole.label} pick with no bad matchups" else parts.joinToString(" · ")
    }
}
