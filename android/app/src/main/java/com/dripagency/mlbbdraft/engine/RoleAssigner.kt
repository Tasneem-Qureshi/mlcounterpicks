package com.dripagency.mlbbdraft.engine

import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.Role

/**
 * Guesses which lane each detected enemy will play.  Exhaustive over permutations
 * (max 5! = 120) so the assignment is optimal and deterministic.
 */
object RoleAssigner {

    private val CLASS_FALLBACK = mapOf(
        "Tank" to Role.ROAM, "Support" to Role.ROAM, "Marksman" to Role.GOLD,
        "Mage" to Role.MID, "Assassin" to Role.JUNGLE, "Fighter" to Role.EXP,
    )

    fun fit(hero: Hero, role: Role): Double = when {
        hero.primaryRole == role -> 3.0
        role in hero.secondaryRoles -> 2.0
        CLASS_FALLBACK[hero.heroClass] == role -> 0.8
        else -> 0.0
    }

    fun assign(enemies: List<Hero>): Map<String, Role> {
        if (enemies.isEmpty()) return emptyMap()
        val roles = Role.entries
        var best: Map<String, Role> = emptyMap()
        var bestScore = -1.0
        fun recurse(i: Int, used: Set<Role>, acc: Map<String, Role>, score: Double) {
            if (i == enemies.size) {
                if (score > bestScore) { bestScore = score; best = acc }
                return
            }
            val h = enemies[i]
            for (r in roles) {
                if (r in used) continue
                recurse(i + 1, used + r, acc + (h.id to r), score + fit(h, r))
            }
        }
        recurse(0, emptySet(), emptyMap(), 0.0)
        return best
    }
}
