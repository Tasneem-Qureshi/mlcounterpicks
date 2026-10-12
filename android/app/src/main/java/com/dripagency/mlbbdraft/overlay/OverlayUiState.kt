package com.dripagency.mlbbdraft.overlay

import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.engine.Recommendation

sealed interface OverlayUiState {
    data object Hidden : OverlayUiState

    data class Counters(
        val myRole: Role,
        val laneDetected: Boolean,
        val picks: List<Recommendation>,
        val enemies: List<String>,
        val directOpponent: String?,
        val strictFallback: Boolean,
        val locked: Boolean,
        val statusLine: String? = null,
        /** user opened the card manually (notification); do not auto-hide while idle */
        val pinned: Boolean = false,
    ) : OverlayUiState

    data class Briefing(
        val myRole: Role,
        val opponent: String?,
        val tips: List<String>,
        val items: List<String>,
        val totalSeconds: Int,
    ) : OverlayUiState
}
