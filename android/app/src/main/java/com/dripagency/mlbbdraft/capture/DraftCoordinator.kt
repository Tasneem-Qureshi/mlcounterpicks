package com.dripagency.mlbbdraft.capture

import android.util.Log
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.data.model.UserSettings
import com.dripagency.mlbbdraft.data.repo.HeroRepository
import com.dripagency.mlbbdraft.engine.CounterEngine
import com.dripagency.mlbbdraft.engine.DraftContext
import com.dripagency.mlbbdraft.engine.MatchupIndex
import com.dripagency.mlbbdraft.engine.RoleAssigner
import com.dripagency.mlbbdraft.overlay.HeroLite
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
 * Glue between inputs (screen detection and/or the in-overlay enemy picker), the counter engine
 * and the overlay.  Detected and hand-picked enemies are merged; the hand-picked lane wins over
 * the detected/default lane.
 */
class DraftCoordinator(
    private val scope: CoroutineScope,
    private val repository: HeroRepository,
    private val overlay: OverlayManager,
    private val stateMachine: DraftStateMachine,
) {
    /** Supplied by the service so the picker callbacks can re-render with current settings. */
    var settings: () -> UserSettings = { UserSettings() }
    var mastered: () -> Set<String> = { emptySet() }
    var pinned: () -> Boolean = { false }
    var captureActive: () -> Boolean = { false }
    /** Called when the post-draft briefing has finished — the service turns screen detection off. */
    var onDraftFinished: () -> Unit = {}

    private var index: MatchupIndex? = null
    private var engine: CounterEngine? = null
    private var briefingJob: Job? = null
    private var lastSnapshot: DraftSnapshot? = null
    private var lastRole: Role? = null
    private var lastDirectOpponentId: String? = null
    private var lastDiag: String? = null
    private var lastDiagAt = 0L

    private val manualEnemies = LinkedHashSet<String>()
    private var manualRole: Role? = null

    suspend fun prepare() {
        val idx = repository.buildIndex()
        index = idx; engine = CounterEngine(idx)
        overlay.setCatalog(idx.heroes.sortedBy { it.name }.map { HeroLite(it.id, it.name, it.primaryRole, it.heroClass) })
        overlay.onToggleEnemy = { id -> if (!manualEnemies.remove(id)) manualEnemies.add(id); render() }
        overlay.onClearEnemies = { manualEnemies.clear(); render() }
        overlay.onSetLane = { r -> manualRole = r; render() }
        Log.i(TAG, "engine ready with ${idx.profiles.size} heroes")
    }

    /** Show (or refresh) the counters card from whatever inputs exist right now. */
    fun render(diag: String? = null) {
        val idx = index ?: return
        val eng = engine ?: return
        val s = settings()
        val snap = lastSnapshot
        val detectedEnemies = snap?.enemies.orEmpty()
        val role = manualRole ?: (if (s.autoDetectLane) snap?.myLane else null) ?: s.defaultRole
        val enemies = (detectedEnemies + manualEnemies).distinct()
        val result = eng.recommend(
            DraftContext(myRole = role, enemies = enemies, allies = snap?.allies.orEmpty(), banned = snap?.bans.orEmpty(), mastered = mastered(), mode = s.mode),
        )
        lastRole = role
        lastDirectOpponentId = result.directOpponent?.id
        val drafting = snap?.phase == DraftPhase.DRAFTING || snap?.phase == DraftPhase.LOCKED
        if (diag != null) lastDiag = diag
        val status = when {
            drafting -> "${snap!!.allies.size} ally · ${snap.enemies.size} enemy · ${snap.bans.size} bans recognised"
            captureActive() -> lastDiag ?: "Watching the screen for a draft…"
            else -> null
        }
        overlay.show(
            OverlayUiState.Counters(
                myRole = role,
                laneDetected = manualRole != null || (s.autoDetectLane && snap?.myLane != null),
                picks = result.picks,
                enemies = enemies.mapNotNull { idx.hero(it)?.name },
                manualEnemyIds = manualEnemies.toList(),
                directOpponent = result.directOpponent?.name,
                strictFallback = result.strictFallback,
                locked = snap?.phase == DraftPhase.LOCKED,
                statusLine = status,
                pinned = pinned(),
                manualOnly = !captureActive(),
            ),
        )
    }

    fun onSnapshot(snap: DraftSnapshot, diag: String?) {
        val idx = index ?: return
        val s = settings()
        val previous = lastSnapshot
        lastSnapshot = snap
        when (snap.phase) {
            DraftPhase.DRAFTING, DraftPhase.LOCKED -> {
                if (previous?.phase == DraftPhase.IDLE || previous?.phase == DraftPhase.COOLDOWN) manualEnemies.clear() // new draft
                if (snap.changed || !overlay.isShowing) render(diag)
            }
            DraftPhase.IN_GAME -> {
                if (briefingJob?.isActive == true) return
                val role = lastRole ?: s.defaultRole
                val enemies = (snap.enemies + manualEnemies).distinct().mapNotNull { idx.hero(it) }
                val opponentId = lastDirectOpponentId ?: RoleAssigner.assign(enemies).entries.firstOrNull { it.value == role }?.key
                val opponent = opponentId?.let { idx.profile(it) }
                val seconds = s.briefingSeconds.coerceIn(8, 40)
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
                    manualEnemies.clear()
                    stateMachine.enterCooldown()
                    onDraftFinished()
                }
            }
            DraftPhase.IDLE, DraftPhase.COOLDOWN -> {
                if (briefingJob?.isActive == true) return
                if (pinned()) {
                    val now = System.currentTimeMillis()
                    if (diag != null && now - lastDiagAt > 3_000) { lastDiagAt = now; render(diag) }
                } else if (overlay.isShowing && snap.phase == DraftPhase.COOLDOWN) {
                    overlay.dismiss()
                }
            }
        }
    }

    fun reset() {
        briefingJob?.cancel(); briefingJob = null
        lastSnapshot = null; lastRole = null; lastDirectOpponentId = null; lastDiag = null
        manualEnemies.clear(); manualRole = null
    }

    private companion object { const val TAG = "DraftCoordinator" }
}
