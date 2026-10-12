package com.dripagency.mlbbdraft.engine

import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.HeroProfile
import com.dripagency.mlbbdraft.data.model.MatchupEdge
import com.dripagency.mlbbdraft.data.model.RecommendMode
import com.dripagency.mlbbdraft.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterEngineTest {

    private fun hero(id: String, role: Role, cls: String = "Fighter", secondary: List<Role> = emptyList()) =
        Hero(id, id.replaceFirstChar { it.uppercase() }, cls, emptyList(), role, secondary, null, null)

    private fun edge(id: String, role: Role, score: Double, reason: String = "") = MatchupEdge(id, role, score, reason, "ranked")

    /** Synthetic dataset: EXP candidates masha / x_borg / paquito / alice-flex; enemies yu_zhong (EXP), brody (GOLD), tigreal (ROAM). */
    private val index = MatchupIndex(
        mapOf(
            "yu_zhong" to HeroProfile(hero("yu_zhong", Role.EXP), counters = listOf(edge("masha", Role.EXP, 4.4, "wins the trade"), edge("x_borg", Role.EXP, 4.2)), strongAgainst = emptyList(), laneTips = listOf("Rush anti-heal"), itemCounters = emptyList()),
            "brody" to HeroProfile(hero("brody", Role.GOLD, "Marksman"), counters = listOf(edge("paquito", Role.EXP, 3.5, "dives Brody")), strongAgainst = listOf(edge("masha", Role.EXP, 3.0)), laneTips = emptyList(), itemCounters = emptyList()),
            "tigreal" to HeroProfile(hero("tigreal", Role.ROAM, "Tank"), counters = emptyList(), strongAgainst = emptyList(), laneTips = emptyList(), itemCounters = emptyList()),
            "masha" to HeroProfile(hero("masha", Role.EXP), emptyList(), emptyList(), emptyList(), emptyList()),
            "x_borg" to HeroProfile(hero("x_borg", Role.EXP), emptyList(), emptyList(), emptyList(), emptyList()),
            "paquito" to HeroProfile(hero("paquito", Role.EXP), emptyList(), emptyList(), emptyList(), emptyList()),
            "alice" to HeroProfile(hero("alice", Role.MID, "Mage", secondary = listOf(Role.EXP)), emptyList(), emptyList(), emptyList(), emptyList()),
            "ling" to HeroProfile(hero("ling", Role.JUNGLE, "Assassin"), emptyList(), emptyList(), emptyList(), emptyList()),
        ),
    )

    private val noBonus = EngineConfig(primaryRoleBonus = 1.0)

    @Test
    fun `only heroes of my role are candidates and picked heroes are excluded`() {
        val res = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("yu_zhong", "brody")), topN = 10)
        val ids = res.picks.map { it.hero.id }
        assertTrue(ids.containsAll(listOf("masha", "x_borg", "paquito", "alice")))
        assertFalse("ling" in ids)
        assertFalse("yu_zhong" in ids)
    }

    @Test
    fun `direct opponent is the enemy assigned to my lane`() {
        val res = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("brody", "yu_zhong", "tigreal")))
        assertEquals("yu_zhong", res.directOpponent?.id)
        assertEquals(Role.GOLD, res.enemyRoles["brody"])
        assertEquals(Role.ROAM, res.enemyRoles["tigreal"])
    }

    @Test
    fun `composite follows the 40 60 formula`() {
        // masha: direct edge +4.4 vs yu_zhong → 0.94 ; team: brody is strong against masha (-3.0 → 0.2), tigreal neutral 0.5 → mean 0.35
        val res = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("yu_zhong", "brody", "tigreal")), topN = 10)
        val masha = res.picks.first { it.hero.id == "masha" }
        assertEquals(0.94, masha.directScore, 1e-9)
        assertEquals(0.35, masha.teamScore, 1e-9)
        assertEquals(0.4 * 0.94 + 0.6 * 0.35, masha.composite, 1e-9)
        assertTrue(masha.reason.contains("Hard-counters Yu_zhong in lane"))
    }

    @Test
    fun `mastery multiplies composite by 1point4 in flexible mode and reorders`() {
        val ctx = DraftContext(Role.EXP, enemies = listOf("yu_zhong", "brody"), mastered = setOf("paquito"), mode = RecommendMode.FLEXIBLE, boostMastered = true)
        val res = CounterEngine(index, noBonus).recommend(ctx, topN = 10)
        val paq = res.picks.first { it.hero.id == "paquito" }
        // paquito: direct neutral 0.5, team vs brody +3.5 → 0.85 ; base = 0.2 + 0.51 = 0.71 ; ×1.4 = 0.994
        assertEquals(0.994, paq.composite, 1e-9)
        assertTrue(paq.mastered)
        assertEquals("paquito", res.picks.first().hero.id)
        assertFalse(res.strictFallback)
    }

    @Test
    fun `without boost mastered heroes keep their pure counter rank`() {
        val ctx = DraftContext(Role.EXP, enemies = listOf("yu_zhong", "brody"), mastered = setOf("paquito"))
        val res = CounterEngine(index, noBonus).recommend(ctx, topN = 10)
        val paq = res.picks.first { it.hero.id == "paquito" }
        assertEquals(0.71, paq.composite, 1e-9)
        assertTrue(paq.mastered)
        assertEquals("paquito", res.picks.first().hero.id) // still best here on merit (0.71 > x_borg 0.668)
    }

    @Test
    fun `strict mode only returns mastered heroes and flags fallback when none fit`() {
        val strict = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("yu_zhong"), mastered = setOf("paquito", "ling"), mode = RecommendMode.STRICT), topN = 10)
        assertEquals(listOf("paquito"), strict.picks.map { it.hero.id })
        assertFalse(strict.strictFallback)

        val fallback = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("yu_zhong"), mastered = setOf("ling"), mode = RecommendMode.STRICT), topN = 10)
        assertTrue(fallback.strictFallback)
        assertTrue(fallback.picks.size >= 3)
    }

    @Test
    fun `no lane opponent yet means direct term mirrors team term`() {
        val res = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("brody")), topN = 10)
        val paq = res.picks.first { it.hero.id == "paquito" }
        assertEquals(paq.teamScore, paq.directScore, 1e-9)
        assertEquals(null, res.directOpponent)
    }

    @Test
    fun `warning is raised for a strongly unfavourable team matchup`() {
        val res = CounterEngine(index, noBonus).recommend(DraftContext(Role.EXP, enemies = listOf("yu_zhong", "brody")), topN = 10)
        assertEquals("Weak into Brody", res.picks.first { it.hero.id == "masha" }.warning)
    }
}
