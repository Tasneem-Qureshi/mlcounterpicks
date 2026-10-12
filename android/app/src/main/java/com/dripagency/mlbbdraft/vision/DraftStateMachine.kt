package com.dripagency.mlbbdraft.vision

import com.dripagency.mlbbdraft.data.model.Role

enum class DraftPhase { IDLE, DRAFTING, LOCKED, IN_GAME, COOLDOWN }

data class SlotReading(val heroId: String?, val confidence: Float)

data class FrameReadings(
    val allies: List<SlotReading>,
    val enemies: List<SlotReading>,
    val bans: List<SlotReading>,
    val myLane: Role?,
    val timestamp: Long = System.currentTimeMillis(),
)

data class DraftSnapshot(
    val phase: DraftPhase,
    val allies: List<String>,
    val enemies: List<String>,
    val bans: List<String>,
    val myLane: Role?,
    /** true when allies/enemies/bans/lane differ from the previous snapshot */
    val changed: Boolean,
)

/**
 * Temporal smoothing + phase detection.  A hero is "stable" in a slot once it has been seen in
 * 2 of the last 3 frames, which removes single-frame misreads without adding noticeable lag.
 */
class DraftStateMachine(
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val votes: Int = 3,
        val needed: Int = 2,
        val lockedCount: Int = 10,
        val draftGoneFrames: Int = 3,
        val minRecognisedForGame: Int = 6,
        val draftTimeoutMs: Long = 4 * 60_000L,
        val cooldownMs: Long = 90_000L,
    )

    private class VoteBuffer(private val size: Int, private val needed: Int) {
        private val ring = arrayOfNulls<String>(size)
        private var i = 0
        fun push(v: String?) { ring[i % size] = v; i++ }
        fun stable(): String? = ring.filterNotNull().groupingBy { it }.eachCount().filter { it.value >= needed }.maxByOrNull { it.value }?.key
        fun clear() { ring.fill(null); i = 0 }
    }

    private val allyVotes = List(5) { VoteBuffer(config.votes, config.needed) }
    private val enemyVotes = List(5) { VoteBuffer(config.votes, config.needed) }
    private val banVotes = List(10) { VoteBuffer(config.votes, config.needed) }
    private val laneVotes = VoteBuffer(5, 3)

    var phase: DraftPhase = DraftPhase.IDLE
        private set
    private var phaseSince = clock()
    private var goneFrames = 0
    private var maxRecognised = 0
    private var last: DraftSnapshot? = null

    fun onFrame(r: FrameReadings): DraftSnapshot {
        r.allies.forEachIndexed { i, s -> allyVotes.getOrNull(i)?.push(s.heroId) }
        r.enemies.forEachIndexed { i, s -> enemyVotes.getOrNull(i)?.push(s.heroId) }
        r.bans.forEachIndexed { i, s -> banVotes.getOrNull(i)?.push(s.heroId) }
        laneVotes.push(r.myLane?.name)

        val allies = allyVotes.mapNotNull { it.stable() }.distinct()
        val enemies = enemyVotes.mapNotNull { it.stable() }.distinct()
        val bans = banVotes.mapNotNull { it.stable() }.distinct()
        val lane = Role.parse(laneVotes.stable())
        val recognised = allies.size + enemies.size
        val rawRecognised = r.allies.count { it.heroId != null } + r.enemies.count { it.heroId != null }
        maxRecognised = maxOf(maxRecognised, recognised)
        val now = r.timestamp

        when (phase) {
            DraftPhase.IDLE -> if (recognised >= 1) enter(DraftPhase.DRAFTING, now)
            DraftPhase.DRAFTING -> {
                goneFrames = if (rawRecognised == 0) goneFrames + 1 else 0
                when {
                    recognised >= config.lockedCount -> enter(DraftPhase.LOCKED, now)
                    goneFrames >= config.draftGoneFrames ->
                        if (maxRecognised >= config.minRecognisedForGame) enter(DraftPhase.IN_GAME, now) else resetTo(DraftPhase.IDLE, now)
                    now - phaseSince > config.draftTimeoutMs -> resetTo(DraftPhase.IDLE, now)
                }
            }
            DraftPhase.LOCKED -> {
                goneFrames = if (rawRecognised == 0) goneFrames + 1 else 0
                if (goneFrames >= 2) enter(DraftPhase.IN_GAME, now)
            }
            DraftPhase.IN_GAME -> Unit // coordinator calls enterCooldown() after the briefing
            DraftPhase.COOLDOWN -> if (now - phaseSince > config.cooldownMs) resetTo(DraftPhase.IDLE, now)
        }

        val snap = DraftSnapshot(
            phase, allies, enemies, bans, lane,
            changed = last?.let { it.allies != allies || it.enemies != enemies || it.bans != bans || it.myLane != lane || it.phase != phase } ?: true,
        )
        last = snap
        return snap
    }

    fun enterCooldown() { enter(DraftPhase.COOLDOWN, clock()) }

    fun reset() { resetTo(DraftPhase.IDLE, clock()) }

    private fun enter(p: DraftPhase, now: Long) { phase = p; phaseSince = now; goneFrames = 0 }

    private fun resetTo(p: DraftPhase, now: Long) {
        enter(p, now)
        maxRecognised = 0
        allyVotes.forEach { it.clear() }; enemyVotes.forEach { it.clear() }; banVotes.forEach { it.clear() }
        laneVotes.clear()
    }
}
