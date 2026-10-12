package com.dripagency.mlbbdraft.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.engine.Recommendation
import com.dripagency.mlbbdraft.ui.HeroAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val CardBg = Color(0xEE0E131C)
private val Accent = Color(0xFF59C3FF)
private val Gold = Color(0xFFFFC95C)
private val Warn = Color(0xFFFF8A65)
private val Enemy = Color(0xFFFF5C5C)
private val Muted = Color(0xFF9AA4B2)

private enum class Mode { COUNTERS, BRIEFING }

@Composable
fun OverlayRoot(
    state: StateFlow<OverlayUiState>,
    visible: StateFlow<Boolean>,
    collapsed: StateFlow<Boolean>,
    opacity: StateFlow<Float>,
    catalog: StateFlow<List<HeroLite>>,
    onToggleCollapse: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onToggleEnemy: (String) -> Unit,
    onClearEnemies: () -> Unit,
    onSetLane: (Role) -> Unit,
) {
    val ui by state.collectAsState()
    val isVisible by visible.collectAsState()
    val isCollapsed by collapsed.collectAsState()
    val alpha by opacity.collectAsState()
    val heroes by catalog.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = isVisible && ui !is OverlayUiState.Hidden,
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.96f),
        exit = fadeOut(tween(OverlayManager.FADE_MS.toInt())) + scaleOut(targetScale = 0.96f),
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(min = 240.dp, max = 360.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
        ) {
            Header(ui, isCollapsed, onToggleCollapse, onClose, onDrag)
            // Animate only when the *kind* of content changes (counters ↔ briefing ↔ collapsed).
            // Data refreshes inside a mode update in place, which is what stops the flicker.
            val mode = when { isCollapsed -> null; ui is OverlayUiState.Briefing -> Mode.BRIEFING; ui is OverlayUiState.Counters -> Mode.COUNTERS; else -> null }
            Crossfade(targetState = mode, animationSpec = tween(200), label = "overlay-body") { m ->
                when (m) {
                    Mode.COUNTERS -> (ui as? OverlayUiState.Counters)?.let { s ->
                        if (pickerOpen) EnemyPicker(s, heroes, onToggleEnemy, onClearEnemies, onSetLane, onDone = { pickerOpen = false })
                        else CountersBody(s, onOpenPicker = { pickerOpen = true }, onToggleEnemy = onToggleEnemy)
                    }
                    Mode.BRIEFING -> (ui as? OverlayUiState.Briefing)?.let { BriefingBody(it) }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun Header(
    ui: OverlayUiState, collapsed: Boolean,
    onToggleCollapse: () -> Unit, onClose: () -> Unit, onDrag: (Float, Float) -> Unit,
) {
    val title = when (ui) {
        is OverlayUiState.Counters -> if (ui.locked) "Draft locked" else "Draft counters"
        is OverlayUiState.Briefing -> "Lane briefing"
        OverlayUiState.Hidden -> ""
    }
    val role = when (ui) { is OverlayUiState.Counters -> ui.myRole; is OverlayUiState.Briefing -> ui.myRole; else -> null }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) } }
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 2.dp),
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = "Drag", tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (role != null) {
            Spacer(Modifier.width(8.dp))
            Chip(role.short, Accent)
            if (ui is OverlayUiState.Counters && !ui.laneDetected && !ui.manualOnly) { Spacer(Modifier.width(4.dp)); Chip("default", Muted) }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleCollapse, modifier = Modifier.size(30.dp)) {
            Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, contentDescription = "Collapse", tint = Muted)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Muted)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CountersBody(s: OverlayUiState.Counters, onOpenPicker: () -> Unit, onToggleEnemy: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (s.enemies.isNotEmpty()) "vs " + s.enemies.joinToString(", ") + (s.directOpponent?.let { "  ·  lane: $it" } ?: "")
                else if (s.manualOnly) "Tap “Pick enemies” as they lock in." else "Waiting for enemy picks…",
                color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Chip(if (s.enemies.isEmpty()) "Pick enemies" else "Edit (${s.enemies.size}/5)", Enemy, onClick = onOpenPicker)
        }
        if (s.strictFallback) Text("No mastered hero fits this lane — showing all counters", color = Warn, fontSize = 11.sp)
        s.statusLine?.let { Text(it, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (s.picks.isEmpty()) Text("No candidates left for ${s.myRole.label}", color = Warn, fontSize = 12.sp)
        else {
            Text("${s.picks.size} picks, best first", color = Muted, fontSize = 10.sp)
            // Scrollable so a long ranked list never grows the card past the screen.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(s.picks, key = { _, r -> r.hero.id }) { i, r -> PickRow(i + 1, r) }
            }
        }
    }
}

@Composable
private fun EnemyPicker(
    s: OverlayUiState.Counters, heroes: List<HeroLite>,
    onToggleEnemy: (String) -> Unit, onClearEnemies: () -> Unit, onSetLane: (Role) -> Unit, onDone: () -> Unit,
) {
    var filter by remember { mutableStateOf<Role?>(null) }
    val detected = s.detectedEnemyIds.toSet()
    val selected = detected + s.manualEnemyIds
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My lane", color = Muted, fontSize = 10.sp, modifier = Modifier.width(52.dp))
            Role.entries.forEach { r -> Chip(r.short, if (s.myRole == r) Accent else Muted, onClick = { onSetLane(r) }); Spacer(Modifier.width(4.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enemies", color = Muted, fontSize = 10.sp, modifier = Modifier.width(52.dp))
            Chip("All", if (filter == null) Enemy else Muted, onClick = { filter = null }); Spacer(Modifier.width(4.dp))
            Role.entries.forEach { r -> Chip(r.short, if (filter == r) Enemy else Muted, onClick = { filter = if (filter == r) null else r }); Spacer(Modifier.width(4.dp)) }
        }
        val list = heroes.filter { filter == null || it.role == filter }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list, key = { it.id }) { h ->
                val sel = h.id in selected
                val auto = h.id in detected
                val ring = if (auto) Accent else Enemy
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) ring.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { if (sel || selected.size < 5) onToggleEnemy(h.id) }.padding(2.dp),
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).border(2.dp, if (sel) ring else Color.Transparent, CircleShape)) { HeroAvatar(h.id, h.name, size = 38.dp) }
                    Text(h.name, color = if (sel) Color.White else Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size}/5 · ${detected.size} detected", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (selected.isNotEmpty()) { Chip("Clear", Muted, onClick = onClearEnemies); Spacer(Modifier.width(6.dp)) }
            Chip("Done", Accent, onClick = onDone)
        }
    }
}

@Composable
private fun PickRow(rank: Int, r: Recommendation) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
            HeroAvatar(r.hero.id, r.hero.name, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(r.hero.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (r.mastered) { Spacer(Modifier.width(6.dp)); Chip("⭐ Mastered", Gold) }
            Spacer(Modifier.weight(1f))
            Text(String.format("%.2f", r.composite), color = if (r.mastered) Gold else Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(start = 14.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScoreBar("lane", r.directScore, Modifier.weight(0.4f))
            ScoreBar("team", r.teamScore, Modifier.weight(0.6f))
        }
        Text(r.reason, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        r.warning?.let { Text(it, color = Warn, fontSize = 11.sp, modifier = Modifier.padding(start = 14.dp)) }
    }
}

@Composable
private fun ScoreBar(label: String, value: Double, modifier: Modifier) {
    Column(modifier) {
        Row { Text(label, color = Muted, fontSize = 9.sp); Spacer(Modifier.weight(1f)); Text(String.format("%.0f%%", value * 100), color = Muted, fontSize = 9.sp) }
        LinearProgressIndicator(
            progress = { value.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = if (value >= 0.6) Accent else if (value < 0.4) Warn else Muted,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
    }
}

@Composable
private fun BriefingBody(s: OverlayUiState.Briefing) {
    var remaining by remember(s) { mutableIntStateOf(s.totalSeconds) }
    LaunchedEffect(s) { while (remaining > 0) { delay(1000); remaining-- } }
    val progress by animateFloatAsState(remaining / s.totalSeconds.toFloat(), tween(900, easing = LinearEasing), label = "countdown")
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.opponent?.let { "Your lane: vs $it" } ?: "Lane opponent unknown", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        s.tips.forEach { tip ->
            Row { Text("•", color = Accent, fontSize = 12.sp, modifier = Modifier.width(12.dp)); Text(tip, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp) }
        }
        s.items.forEach { item ->
            Row { Text("⚔", color = Gold, fontSize = 11.sp, modifier = Modifier.width(14.dp)); Text(item, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = Muted, trackColor = Color.White.copy(alpha = 0.06f))
        }
        Text("closing in ${remaining}s", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
    }
}
