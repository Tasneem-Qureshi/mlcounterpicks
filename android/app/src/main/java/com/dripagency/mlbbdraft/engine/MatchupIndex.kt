package com.dripagency.mlbbdraft.engine

import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.HeroProfile
import com.dripagency.mlbbdraft.data.model.MatchupEdge
import com.dripagency.mlbbdraft.data.model.Role

/**
 * Immutable, pure-Kotlin view over the dataset.  No Android imports so the engine is
 * unit-testable on the JVM.
 */
class MatchupIndex(val profiles: Map<String, HeroProfile>) {

    val heroes: Collection<Hero> get() = profiles.values.map { it.hero }
    fun hero(id: String): Hero? = profiles[id]?.hero
    fun profile(id: String): HeroProfile? = profiles[id]

    /** Lane-agnostic signed edge in [-5, 5]: how favourable `a` is versus `b`. */
    fun edge(a: String, b: String): Double = edgeDetail(a, b).score

    data class EdgeDetail(val score: Double, val reason: String, val confidence: String)

    /**
     * Combines up to four signals (a.strongAgainst b, b.counters a, a.counters b, b.strongAgainst a).
     * The dataset is symmetrised by the scraper, so most pairs resolve from the first two.
     */
    fun edgeDetail(a: String, b: String): EdgeDetail {
        val pa = profiles[a] ?: return NEUTRAL
        val pb = profiles[b] ?: return NEUTRAL
        val positives = listOfNotNull(pa.strongAgainst.find { it.heroId == b }, pb.counters.find { it.heroId == a })
        val negatives = listOfNotNull(pa.counters.find { it.heroId == b }, pb.strongAgainst.find { it.heroId == a })
        if (positives.isEmpty() && negatives.isEmpty()) return NEUTRAL
        val pos = positives.maxOfOrNull { it.edgeScore } ?: 0.0
        val neg = negatives.maxOfOrNull { it.edgeScore } ?: 0.0
        val score = (pos - neg).coerceIn(-5.0, 5.0)
        val best: MatchupEdge? = if (score >= 0) positives.maxByOrNull { it.edgeScore } else negatives.maxByOrNull { it.edgeScore }
        return EdgeDetail(score, best?.reason.orEmpty(), best?.confidence ?: "derived")
    }

    fun candidatesForRole(role: Role): List<Hero> = heroes.filter { it.playsRole(role) }

    companion object {
        val NEUTRAL = EdgeDetail(0.0, "", "none")
    }
}
