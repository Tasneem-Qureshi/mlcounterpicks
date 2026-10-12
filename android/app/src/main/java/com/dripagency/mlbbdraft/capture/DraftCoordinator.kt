package com.dripagency.mlbbdraft.capture

import android.util.Log
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.data.model.UserSettings
import com.dripagency.mlbbdraft.data.repo.HeroRepository
import com.dripagency.mlbbdraft.engine.CounterEngine
import com.dripagency.mlbbdraft.engine.DraftContext
import com.dripagency.mlbbdraft.engine.MatchupIndex
import com.dripagency.mlbbdraft.engine.RoleAssigner
import com.dripagency.mlbbdraft.overlay.OverlayManager
import com.dripagency.mlbbdraft.overlay.OverlayUiState
import com.dripagency.mlbbdraft.vision.DraftPhase
import com.dripagency.mlbbdraft.vision.DraftSnapshot
import com.dripagency.mlbbdraft.vision.DraftStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glue between the vision state machine, the counter engine and the overlay:
 *   IDLE (pinned)    → status card: lane, detection diagnostics, best general picks
 *   DRAFTING/LOCKED  → recompute + show counters whenever the detected draft changes
 *   IN_GAME          → lane briefing for the direct opponent, auto-dismissed after N seconds
 *   IDLE/COOLDOWN    → overlay hidden unless the user pinned it
 */
class DraftCoordinator(
    private val scope: CoroutineScope,
    private val repository: HeroRepository,
    private val overlay: OverlayManager,
    private val stateMachine: DraftStateMachine,
) {
    private var index: MatchupIndex? = null
    private var engine: CounterEngine? = null
    private var briefingJob: Job? = null
    private var lastEnemies: List<String> = emptyList()
    private var lastRole: Role? = null
    private var lastDirectOpponentId: String? = null
    private var lastDiagAt = 0L

    suspend fun prepare() {
        index = repository.buildIndex().also { engine = CounterEngine(it) }
        Log.i(TAG, "engine ready with ${index?.profiles?.size} heroes")
    }

    /** Status card for IDLE: general best picks for the lane plus live detection diagnostics. */
    fun showStatus(settings: UserSettings, mastered: Set<String>, pinned: Boolean, diag: String? = null) {
        val eng = engine ?: return
        val role = settings.defaultRole
        val result = eng.recommend(DraftContext(myRole = role, enemies = emptyList(), mastered = mastered, mode = settings.mode))
        overlay.show(
            OverlayUiState.Counters(
                myRole = role, laneDetected = false, picks = result.picks, enemies = emptyList(),
                directOpponent = null, strictFallback = result.strictFallback, locked = false,
                statusLine = diag ?: "Watching for a draft… open a ranked/classic draft in MLBB.",
                pinned = pinned,
            ),
        )
    }

    fun onSnapshot(snap: DraftSnapshot, settings: UserSettings, mastered: Set<String>, pinned: Boolean, diag: String?) {
        val idx = index ?: return
        val eng = engine ?: return
        when (snap.phase) {
            DraftPhase.DRAFTING, DraftPhase.LOCKED -> {
                val role = (if (settings.autoDetectLane) snap.myLane else null) ?: settings.defaultRole
                if (!snap.changed && role == lastRole && overlay.isShowing) return
                lastRole = role
                val result = eng.recommend(
                    DraftContext(myRole = role, enemies = snap.enemies, allies = snap.allies, banned = snap.bans, mastered = mastered, mode = settings.mode),
                )
                lastEnemies = snap.enemies
                lastDirectOpponentId = result.directOpponent?.id
                overlay.show(
                    OverlayUiState.Counters(
                        myRole = role,
                        laneDetected = settings.autoDetectLane && snap.myLane != null,
                        picks = result.picks,
                        enemies = snap.enemies.mapNotNull { idx.hero(it)?.name },
                        directOpponent = result.directOpponent?.name,
                        strictFallback = result.strictFallback,
                        locked = snap.phase == DraftPhase.LOCKED,
                        statusLine = "${snap.allies.size} ally · ${snap.enemies.size} enemy · ${snap.bans.size} bans recognised",
                        pinned = pinned,
                    ),
                )
            }
            DraftPhase.IN_GAME -> {
                if (briefingJob?.isActive == true) return
                val role = lastRole ?: settings.defaultRole
                val enemies = (snap.enemies.ifEmpty { lastEnemies }).mapNotNull { idx.hero(it) }
                val opponentId = lastDirectOpponentId ?: RoleAssigner.assign(enemies).entries.firstOrNull { it.value == role }?.key
                val opponent = opponentId?.let { idx.profile(it) }
                val seconds = settings.briefingSeconds.coerceIn(8, 40)
                overlay.show(
                    OverlayUiState.Briefing(
                        myRole = role,
                        opponent = opponent?.hero?.name,
                        tips = opponent?.laneTips?.take(3).orEmpty().ifEmpty { listOf("No lane opponent identified — play the wave safely and track their roamer.") },
                        items = opponent?.itemCounters?.take(2).orEmpty(),
                        totalSeconds = seconds,
                    ),
                )
                briefingJob = scope.launch {
                    delay(seconds * 1000L)
                    overlay.dismiss()
                    stateMachine.enterCooldown()
                }
            }
            DraftPhase.IDLE, DraftPhase.COOLDOWN -> {
                if (briefingJob?.isActive == true) return
                if (pinned) {
                    // Refresh the diagnostics line at most every 3 s so the card does not flicker.
                    val now = System.currentTimeMillis()
                    if (diag != null && now - lastDiagAt > 3_000) { lastDiagAt = now; showStatus(settings, mastered, pinned = true, diag = diag) }
                } else if (overlay.isShowing && snap.phase == DraftPhase.COOLDOWN) {
                    overlay.dismiss()
                }
            }
        }
    }

    fun reset() {
        briefingJob?.cancel(); briefingJob = null
        lastEnemies = emptyList(); lastRole = null; lastDirectOpponentId = null
    }

    private companion object { const val TAG = "DraftCoordinator" }
}
