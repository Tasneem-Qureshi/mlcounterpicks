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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dripagency.mlbbdraft.DraftCompanionApp
import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.RecommendMode
import com.dripagency.mlbbdraft.data.model.Role

/**
 * "Preferred / Mastered heroes" setup.  Selections persist in Room (heroes.is_preferred) and
 * the Strict/Flexible switch in DataStore, so they survive data updates and reinstall-free upgrades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: MasteryViewModel = viewModel(factory = viewModelFactory { initializer { MasteryViewModel(DraftCompanionApp.graph(context)) } })
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mastered heroes · ${ui.preferred.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (ui.preferred.isNotEmpty()) TextButton(onClick = { vm.clearAll() }) { Text("Clear") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RecommendMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = ui.mode == m,
                            onClick = { vm.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, RecommendMode.entries.size),
                        ) { Text(if (m == RecommendMode.STRICT) "Strict" else "Flexible") }
                    }
                }
                Text(
                    if (ui.mode == RecommendMode.STRICT) "Only mastered heroes are listed." else "Best counters first; mastered heroes carry a ⭐.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Boost mastered heroes (+40%, moves them up)", ui.boost) { vm.setBoost(it) }
                OutlinedTextField(
                    value = ui.query, onValueChange = { vm.query.value = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search hero or class") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = ui.roleFilter == null, onClick = { vm.roleFilter.value = null }, label = { Text("All") })
                    Role.entries.forEach { r ->
                        FilterChip(
                            selected = ui.roleFilter == r, onClick = { vm.roleFilter.value = if (ui.roleFilter == r) null else r },
                            label = { Text("${r.short} ${ui.countFor(r)}") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                val grouped = if (ui.roleFilter == null) ui.visible.groupBy { it.primaryRole } else mapOf(ui.roleFilter!! to ui.visible)
                Role.entries.forEach { role ->
                    val list = grouped[role].orEmpty()
                    if (list.isEmpty()) return@forEach
                    item(key = "hdr-$role") {
                        Text(
                            role.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { "${role}-${it.id}" }) { hero -> HeroRow(hero, hero.id in ui.preferred) { vm.toggle(hero.id, it) } }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HeroRow(hero: Hero, preferred: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!preferred) }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            if (preferred) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null,
            tint = if (preferred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        HeroAvatar(hero.id, hero.name, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hero.name, fontWeight = if (preferred) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                hero.heroClass + (hero.winRate?.let { " · ${"%.1f".format(it)}% WR" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hero.roles.forEach { r ->
            AssistChip(onClick = { onToggle(!preferred) }, label = { Text(r.short, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(start = 4.dp))
        }
    }
}
