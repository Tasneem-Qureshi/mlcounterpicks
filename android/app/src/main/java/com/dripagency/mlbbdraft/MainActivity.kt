package com.dripagency.mlbbdraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dripagency.mlbbdraft.ui.HeroDetailScreen
import com.dripagency.mlbbdraft.ui.HeroesScreen
import com.dripagency.mlbbdraft.ui.HeroesViewModel
import com.dripagency.mlbbdraft.ui.HomeScreen
import com.dripagency.mlbbdraft.ui.MasterySettingsScreen
import com.dripagency.mlbbdraft.ui.theme.MlbbDraftTheme

class MainActivity : ComponentActivity() {
    private enum class Tab(val label: String) { HEROES("Heroes"), MASTERED("Mastered"), SETTINGS("Settings") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MlbbDraftTheme {
                val context = LocalContext.current
                val heroesVm: HeroesViewModel = viewModel(factory = viewModelFactory { initializer { HeroesViewModel(DraftCompanionApp.graph(context)) } })
                var tab by rememberSaveable { mutableStateOf(Tab.HEROES) }
                var detail by rememberSaveable { mutableStateOf<String?>(null) }   // hero id open on the Heroes tab
                BackHandler(enabled = detail != null) { detail = null }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t; if (t != Tab.HEROES) detail = null },
                                    icon = { Icon(when (t) { Tab.HEROES -> Icons.Default.Person; Tab.MASTERED -> Icons.Default.Star; Tab.SETTINGS -> Icons.Default.Settings }, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                        when (tab) {
                            Tab.HEROES -> detail?.let { id -> HeroDetailScreen(heroesVm, id, onBack = { detail = null }, onOpen = { detail = it }) }
                                ?: HeroesScreen(heroesVm, onOpen = { detail = it })
                            Tab.MASTERED -> MasterySettingsScreen(onBack = { tab = Tab.HEROES })
                            Tab.SETTINGS -> HomeScreen(onOpenMastery = { tab = Tab.MASTERED })
                        }
                    }
                }
            }
        }
    }
}
