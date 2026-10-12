package com.dripagency.mlbbdraft.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dripagency.mlbbdraft.AppGraph
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.data.model.UserSettings
import com.dripagency.mlbbdraft.data.update.DataUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    data class Ui(
        val settings: UserSettings = UserSettings(),
        val heroCount: Int = 0,
        val masteredCount: Int = 0,
        val updating: Boolean = false,
    )

    private val updating = MutableStateFlow(false)

    val ui: StateFlow<Ui> = combine(
        graph.prefs.settings,
        graph.repository.heroes.map { it.size },
        graph.repository.preferredIds.map { it.size },
        updating,
    ) { s, heroes, mastered, upd -> Ui(s, heroes, mastered, upd) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())

    fun setDefaultRole(r: Role) = viewModelScope.launch { graph.prefs.setDefaultRole(r) }
    fun setAutoDetectLane(v: Boolean) = viewModelScope.launch { graph.prefs.setAutoDetectLane(v) }
    fun setBriefingSeconds(v: Int) = viewModelScope.launch { graph.prefs.setBriefingSeconds(v) }
    fun setOverlayOpacity(v: Float) = viewModelScope.launch { graph.prefs.setOverlayOpacity(v) }
    fun setSamplingMillis(v: Long) = viewModelScope.launch { graph.prefs.setSamplingMillis(v) }
    fun setCalibrationDump(v: Boolean) = viewModelScope.launch { graph.prefs.setCalibrationDump(v) }
    fun setAutoShowOnMlbb(v: Boolean) = viewModelScope.launch { graph.prefs.setAutoShowOnMlbb(v) }
    fun setDataManifestUrl(v: String) = viewModelScope.launch { graph.prefs.setDataManifestUrl(v) }

    val backupMessage = MutableStateFlow("")
    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch {
        backupMessage.value = runCatching { "Exported ${graph.backup.export(uri)} mastered heroes" }.getOrElse { "Export failed: ${it.message}" }
    }
    fun importBackup(uri: android.net.Uri) = viewModelScope.launch {
        backupMessage.value = runCatching { "Imported ${graph.backup.import(uri)} mastered heroes" }.getOrElse { "Import failed: ${it.message}" }
    }

    fun checkForUpdates(force: Boolean = false) = viewModelScope.launch {
        updating.value = true
        try { graph.updater.checkAndUpdate(force) } finally { updating.value = false }
    }

    fun updateResultIsError(settings: UserSettings) = settings.lastUpdateMessage.startsWith("Update failed")
}
