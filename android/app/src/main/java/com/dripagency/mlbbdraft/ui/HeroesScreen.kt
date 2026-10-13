package com.dripagency.mlbbdraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.MatchupEdge
import com.dripagency.mlbbdraft.data.model.Role

private val Good = Color(0xFF5CD68A)
private val Bad = Color(0xFFFF5C5C)

/** Main screen: every hero with search, lane filter and sort; tap for the matchup page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroesScreen(vm: HeroesViewModel, onOpen: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Heroes · ${ui.heroes.size}") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.query, onValueChange = { vm.query.value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, placeholder = { Text("Search hero or class") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = ui.role == null, onClick = { vm.role.value = null }, label = { Text("All") })
                    Role.entries.forEach { r -> FilterChip(selected = ui.role == r, onClick = { vm.role.value = if (ui.role == r) null else r }, label = { Text(r.short) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = ui.sort == HeroesViewModel.Sort.NAME, onClick = { vm.sort.value = HeroesViewModel.Sort.NAME }, label = { Text("A–Z") })
                    FilterChip(selected = ui.sort == HeroesViewModel.Sort.WIN_RATE, onClick = { vm.sort.value = HeroesViewModel.Sort.WIN_RATE }, label = { Text("Win rate") })
                }
                Spacer(Modifier.height(2.dp))
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.visible, key = { it.id }) { hero ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(hero.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        HeroAvatar(hero.id, hero.name, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hero.name, fontWeight = FontWeight.SemiBold)
                                if (hero.id in ui.preferred) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Star, contentDescription = "Mastered", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(16.dp)) }
                            }
                            Text(
                                hero.heroClass + " · " + hero.roles.joinToString("/") { it.short } + (hero.winRate?.let { " · ${"%.1f".format(it)}% WR" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Hero page: counters, favourable matchups, weaknesses (how to beat it) and item answers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroDetailScreen(vm: HeroesViewModel, heroId: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val index by vm.indexState.collectAsStateWithLifecycle()
    val profile = index?.profile(heroId)
    val hero = profile?.hero
    val mastered = heroId in ui.preferred
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(hero?.name ?: "…") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { vm.togglePreferred(heroId, !mastered) }) {
                        Icon(if (mastered) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Mastered", tint = if (mastered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { pad ->
        if (hero == null || profile == null) {
            Text(if (index == null) "Loading hero data…" else "No data for this hero yet. Check for updates in Settings.", Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroAvatar(hero.id, hero.name, size = 72.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(hero.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(hero.heroClass + (hero.specialties.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString(", ") } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        hero.roles.forEach { r -> AssistChip(onClick = {}, label = { Text(r.label) }) }
                    }
                    hero.winRate?.let { Text("${"%.1f".format(it)}% ranked win rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Section("Countered by · pick these against ${hero.name}") {
                if (profile.counters.isEmpty()) Text("No data", color = MaterialTheme.colorScheme.onSurfaceVariant)
                profile.counters.take(10).forEach { e -> EdgeRow(e, index!!.hero(e.heroId), Bad, onOpen) }
            }
            Section("Strong against · ${hero.name} beats these") {
                if (profile.strongAgainst.isEmpty()) Text("No data", color = MaterialTheme.colorScheme.onSurfaceVariant)
                profile.strongAgainst.take(10).forEach { e -> EdgeRow(e, index!!.hero(e.heroId), Good, onOpen) }
            }
            Section("Weaknesses · how to beat ${hero.name}") {
                if (profile.laneTips.isEmpty()) Text("No tips yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                profile.laneTips.forEach { t -> Bullet(t) }
            }
            if (profile.itemCounters.isNotEmpty()) Section("Items that answer ${hero.name}") {
                profile.itemCounters.forEach { t -> Bullet(t) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EdgeRow(e: MatchupEdge, other: Hero?, color: Color, onOpen: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onOpen(e.heroId) }.padding(vertical = 4.dp)) {
        HeroAvatar(e.heroId, other?.name ?: e.heroId, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(other?.name ?: e.heroId, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                e.role?.let { Text(it.short, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                Text("%.1f".format(e.edgeScore), color = color, fontWeight = FontWeight.Bold)
            }
            if (e.reason.isNotBlank() && !e.reason.contains("in ranked data")) Text(e.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row { Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(14.dp)); Text(text, style = MaterialTheme.typography.bodyMedium) }
}
