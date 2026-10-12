package com.dripagency.mlbbdraft.overlay

import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.engine.Recommendation

/** Minimal hero info for the in-overlay enemy picker. */
data class HeroLite(val id: String, val name: String, val role: Role, val heroClass: String)

sealed interface OverlayUiState {
    data object Hidden : OverlayUiState

    data class Counters(
        val myRole: Role,
        val laneDetected: Boolean,
        val picks: List<Recommendation>,
        /** display names of all enemies considered (detected ∪ manual) */
        val enemies: List<String>,
        /** ids of enemies the user picked by hand */
        val manualEnemyIds: List<String>,
        /** ids recognised on screen and currently in effect (pre-selected in the picker) */
        val detectedEnemyIds: List<String> = emptyList(),
        val directOpponent: String?,
        val strictFallback: Boolean,
        val locked: Boolean,
        val statusLine: String? = null,
        /** user opened the card manually (notification); do not auto-hide while idle */
        val pinned: Boolean = false,
        /** screen detection is off → the picker is the primary input */
        val manualOnly: Boolean = false,
    ) : OverlayUiState

    data class Briefing(
        val myRole: Role,
        val opponent: String?,
        val tips: List<String>,
        val items: List<String>,
        val totalSeconds: Int,
    ) : OverlayUiState
}
