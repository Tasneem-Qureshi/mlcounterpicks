package com.dripagency.mlbbdraft.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dripagency.mlbbdraft.engine.Recommendation
import com.dripagency.mlbbdraft.ui.HeroAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val CardBg = Color(0xEE0E131C)
private val Accent = Color(0xFF59C3FF)
private val Gold = Color(0xFFFFC95C)
private val Warn = Color(0xFFFF8A65)
private val Muted = Color(0xFF9AA4B2)

@Composable
fun OverlayRoot(
    state: StateFlow<OverlayUiState>,
    visible: StateFlow<Boolean>,
    collapsed: StateFlow<Boolean>,
    opacity: StateFlow<Float>,
    onToggleCollapse: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val ui by state.collectAsState()
    val isVisible by visible.collectAsState()
    val isCollapsed by collapsed.collectAsState()
    val alpha by opacity.collectAsState()

    AnimatedVisibility(
        visible = isVisible && ui !is OverlayUiState.Hidden,
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.96f),
        exit = fadeOut(tween(OverlayManager.FADE_MS.toInt())) + scaleOut(targetScale = 0.96f),
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(min = 220.dp, max = 360.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
        ) {
            Header(ui, isCollapsed, onToggleCollapse, onClose, onDrag)
            AnimatedContent(targetState = isCollapsed to ui, label = "overlay-body") { (collapsedNow, s) ->
                if (!collapsedNow) when (s) {
                    is OverlayUiState.Counters -> CountersBody(s)
                    is OverlayUiState.Briefing -> BriefingBody(s)
                    OverlayUiState.Hidden -> Unit
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
            if (ui is OverlayUiState.Counters && !ui.laneDetected) { Spacer(Modifier.width(4.dp)); Chip("default", Muted) }
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
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CountersBody(s: OverlayUiState.Counters) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.enemies.isNotEmpty()) {
            Text(
                "vs " + s.enemies.joinToString(", ") + (s.directOpponent?.let { "  ·  lane: $it" } ?: "  ·  lane opponent not yet picked"),
                color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("Waiting for enemy picks…", color = Muted, fontSize = 11.sp)
        }
        if (s.strictFallback) Text("No mastered hero fits this lane — showing all counters", color = Warn, fontSize = 11.sp)
        s.statusLine?.let { Text(it, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        s.picks.forEachIndexed { i, r -> PickRow(i + 1, r) }
        if (s.picks.isEmpty()) Text("No candidates left for ${s.myRole.label}", color = Warn, fontSize = 12.sp)
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
        Text(r.reason, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
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
        Text(
            s.opponent?.let { "Your lane: vs $it" } ?: "Lane opponent unknown",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        s.tips.forEach { tip ->
            Row { Text("•", color = Accent, fontSize = 12.sp, modifier = Modifier.width(12.dp)); Text(tip, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp) }
        }
        s.items.forEach { item ->
            Row { Text("⚔", color = Gold, fontSize = 11.sp, modifier = Modifier.width(14.dp)); Text(item, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Muted, trackColor = Color.White.copy(alpha = 0.06f),
            )
        }
        Text("closing in ${remaining}s", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
    }
}
