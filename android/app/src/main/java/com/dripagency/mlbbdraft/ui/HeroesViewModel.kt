package com.dripagency.mlbbdraft.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dripagency.mlbbdraft.AppGraph
import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.HeroProfile
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.engine.MatchupIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HeroesViewModel(private val graph: AppGraph) : ViewModel() {
    enum class Sort { NAME, WIN_RATE }

    data class Ui(
        val heroes: List<Hero> = emptyList(),
        val preferred: Set<String> = emptySet(),
        val query: String = "",
        val role: Role? = null,
        val sort: Sort = Sort.NAME,
    ) {
        val visible: List<Hero>
            get() = heroes.filter { h ->
                (role == null || h.playsRole(role)) &&
                    (query.isBlank() || h.name.contains(query, true) || h.heroClass.contains(query, true))
            }.let { l -> if (sort == Sort.WIN_RATE) l.sortedByDescending { it.winRate ?: 0.0 } else l }
    }

    val query = MutableStateFlow("")
    val role = MutableStateFlow<Role?>(null)
    val sort = MutableStateFlow(Sort.NAME)
    private val index = MutableStateFlow<MatchupIndex?>(null)

    init {
        // Rebuild the matchup index whenever the hero table changes: first seed on a fresh install,
        // the weekly data update, or a re-import.  (Building once at start raced the initial seed.)
        viewModelScope.launch {
            graph.repository.heroes.map { it.size }.distinctUntilChanged().collect { index.value = graph.repository.buildIndex() }
        }
        viewModelScope.launch { graph.repository.dataChanged.collect { index.value = graph.repository.buildIndex() } }
    }

    val ui: StateFlow<Ui> = combine(graph.repository.heroes, graph.repository.preferredIds, query, role, sort) { h, p, q, r, s -> Ui(h, p, q, r, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())

    fun profile(id: String): HeroProfile? = index.value?.profile(id)
    fun hero(id: String): Hero? = index.value?.hero(id)
    val indexState: StateFlow<MatchupIndex?> = index

    fun togglePreferred(id: String, on: Boolean) = viewModelScope.launch { graph.repository.setPreferred(id, on); graph.prefs.touchMastery() }
}
