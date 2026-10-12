package com.dripagency.mlbbdraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dripagency.mlbbdraft.ui.HomeScreen
import com.dripagency.mlbbdraft.ui.MasterySettingsScreen
import com.dripagency.mlbbdraft.ui.theme.MlbbDraftTheme

class MainActivity : ComponentActivity() {
    private enum class Screen { HOME, MASTERY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MlbbDraftTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                AnimatedContent(targetState = screen, label = "nav") { s ->
                    when (s) {
                        Screen.HOME -> HomeScreen(onOpenMastery = { screen = Screen.MASTERY })
                        Screen.MASTERY -> MasterySettingsScreen(onBack = { screen = Screen.HOME })
                    }
                }
            }
        }
    }
}
