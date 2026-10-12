package com.dripagency.mlbbdraft.data.repo

import com.dripagency.mlbbdraft.data.db.Direction
import com.dripagency.mlbbdraft.data.db.HeroEntity
import com.dripagency.mlbbdraft.data.db.MatchupDao
import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.HeroProfile
import com.dripagency.mlbbdraft.data.model.MatchupEdge
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.engine.MatchupIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

class HeroRepository(private val dao: MatchupDao) {

    /** Emits whenever the dataset or template files were replaced (self-update). */
    val dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val heroes: Flow<List<Hero>> = dao.observeHeroes().map { rows -> rows.map { it.toModel() } }
    val preferredIds: Flow<Set<String>> = dao.observePreferredIds().map { it.toSet() }

    suspend fun setPreferred(heroId: String, preferred: Boolean) = dao.setPreferred(heroId, preferred)
    suspend fun preferredIds(): Set<String> = dao.preferredIds().toSet()
    suspend fun heroCount(): Int = dao.heroCount()
    suspend fun heroIds(): Set<String> = dao.heroes().map { it.heroId }.toSet()

    /** Builds the in-memory index the engine queries. Cheap (~130 heroes, ~3k edges); rebuilt on data change. */
    suspend fun buildIndex(): MatchupIndex {
        val heroes = dao.heroes().associate { it.heroId to it.toModel() }
        val tips = dao.tips().groupBy { it.heroId }
        val edges = dao.matchups().groupBy { it.heroId }
        val profiles = heroes.values.associate { hero ->
            val mine = edges[hero.id].orEmpty()
            hero.id to HeroProfile(
                hero = hero,
                counters = mine.filter { it.direction == Direction.COUNTERED_BY }
                    .map { MatchupEdge(it.otherId, Role.parse(it.otherRole), it.edgeScore, it.reason, it.confidence) }
                    .sortedByDescending { it.edgeScore },
                strongAgainst = mine.filter { it.direction == Direction.STRONG_AGAINST }
                    .map { MatchupEdge(it.otherId, Role.parse(it.otherRole), it.edgeScore, it.reason, it.confidence) }
                    .sortedByDescending { it.edgeScore },
                laneTips = tips[hero.id].orEmpty().filter { it.kind == "tip" }.sortedBy { it.ordinal }.map { it.text },
                itemCounters = tips[hero.id].orEmpty().filter { it.kind == "item" }.sortedBy { it.ordinal }.map { it.text },
            )
        }
        return MatchupIndex(profiles)
    }

    private fun HeroEntity.toModel() = Hero(
        id = heroId,
        name = name,
        heroClass = heroClass,
        specialties = specialties.split(',').filter { it.isNotBlank() },
        primaryRole = Role.parse(primaryRole) ?: Role.EXP,
        secondaryRoles = secondaryRoles.split(',').mapNotNull { Role.parse(it) },
        portraitFile = portraitFile,
        winRate = winRate,
    )
}
