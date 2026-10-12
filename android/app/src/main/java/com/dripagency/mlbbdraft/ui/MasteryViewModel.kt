package com.dripagency.mlbbdraft.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dripagency.mlbbdraft.AppGraph
import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.RecommendMode
import com.dripagency.mlbbdraft.data.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MasteryViewModel(private val graph: AppGraph) : ViewModel() {
    data class Ui(
        val heroes: List<Hero> = emptyList(),
        val preferred: Set<String> = emptySet(),
        val mode: RecommendMode = RecommendMode.FLEXIBLE,
        val boost: Boolean = false,
        val query: String = "",
        val roleFilter: Role? = null,
    ) {
        val visible: List<Hero>
            get() = heroes.filter { h ->
                (roleFilter == null || h.playsRole(roleFilter)) &&
                    (query.isBlank() || h.name.contains(query, ignoreCase = true) || h.heroClass.contains(query, ignoreCase = true))
            }
        fun countFor(role: Role) = heroes.count { it.id in preferred && it.playsRole(role) }
    }

    val query = MutableStateFlow("")
    val roleFilter = MutableStateFlow<Role?>(null)

    val ui: StateFlow<Ui> = combine(graph.repository.heroes, graph.repository.preferredIds, graph.prefs.settings, query, roleFilter) { h, p, s, q, r ->
        Ui(h, p, s.mode, s.boostMastered, q, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())

    fun toggle(heroId: String, preferred: Boolean) = viewModelScope.launch { graph.repository.setPreferred(heroId, preferred); graph.prefs.touchMastery() }
    fun setBoost(v: Boolean) = viewModelScope.launch { graph.prefs.setBoostMastered(v) }
    fun setMode(mode: RecommendMode) = viewModelScope.launch { graph.prefs.setMode(mode) }
    fun clearAll() = viewModelScope.launch { ui.value.preferred.forEach { graph.repository.setPreferred(it, false) }; graph.prefs.touchMastery() }
}
