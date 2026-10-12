package com.dripagency.mlbbdraft.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dripagency.mlbbdraft.DraftCompanionApp
import com.dripagency.mlbbdraft.capture.ForegroundAppWatcher
import com.dripagency.mlbbdraft.capture.ScreenCaptureService
import com.dripagency.mlbbdraft.data.model.Role
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenMastery: () -> Unit) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(DraftCompanionApp.graph(context)) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val running by ScreenCaptureService.running.collectAsStateWithLifecycle()
    val backupMessage by vm.backupMessage.collectAsStateWithLifecycle()

    // Permission state is re-read whenever the screen resumes (user may come back from Settings).
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember { mutableStateOf(notificationsGranted(context)) }
    var usageGranted by remember { mutableStateOf(ForegroundAppWatcher.hasUsageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                notifGranted = notificationsGranted(context)
                usageGranted = ForegroundAppWatcher.hasUsageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) ScreenCaptureService.start(context, res.resultCode, data)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportBackup(it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importBackup(it) } }

    var urlDraft by remember { mutableStateOf("") }
    LaunchedEffect(ui.settings.dataManifestUrl) { if (urlDraft.isBlank()) urlDraft = ui.settings.dataManifestUrl }

    Scaffold(topBar = { TopAppBar(title = { Text("MLBB Draft Companion") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Assistant --------------------------------------------------------------
            Section("Draft assistant") {
                Text(
                    if (running) "Running. A status card shows for 10 s, then the overlay appears automatically when a draft is detected. Use the notification's “Show overlay” any time."
                    else "Stopped. Press Start, accept the screen-capture prompt, then open Mobile Legends.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = overlayGranted && !running,
                        onClick = { projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) },
                    ) { Text("Start") }
                    OutlinedButton(enabled = running, onClick = { ScreenCaptureService.stop(context) }) { Text("Stop") }
                    OutlinedButton(enabled = running, onClick = { ScreenCaptureService.showOverlay(context) }) { Text("Show overlay") }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onOpenMastery, modifier = Modifier.fillMaxWidth()) { Text("Mastered heroes (${ui.masteredCount})") }
            }

            // ---- Permissions ------------------------------------------------------------
            Section("Permissions") {
                PermissionRow("Draw over other apps (required)", overlayGranted) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
                PermissionRow("Notifications (required for the service)", notifGranted) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermissionRow("Usage access (optional: detect when MLBB opens, pause capture otherwise)", usageGranted) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                SwitchRow("Show the overlay automatically when MLBB comes to the front (otherwise a tap-to-open notification is posted)", ui.settings.autoShowOnMlbb) { vm.setAutoShowOnMlbb(it) }
                Text(
                    "Screen capture consent is asked every time you press Start (Android requirement). Only pixels are read; the game process is never touched.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Lane / behaviour --------------------------------------------------------
            Section("My lane") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Role.entries.forEach { r ->
                        FilterChip(selected = ui.settings.defaultRole == r, onClick = { vm.setDefaultRole(r) }, label = { Text(r.short) })
                    }
                }
                SwitchRow("Auto-detect lane badge in draft (falls back to the lane above)", ui.settings.autoDetectLane) { vm.setAutoDetectLane(it) }
            }

            Section("Overlay & battery") {
                Text("Briefing auto-dismiss: ${ui.settings.briefingSeconds}s")
                Slider(value = ui.settings.briefingSeconds.toFloat(), onValueChange = { vm.setBriefingSeconds(it.toInt()) }, valueRange = 10f..30f, steps = 19)
                Text("Opacity: ${(ui.settings.overlayOpacity * 100).toInt()}%")
                Slider(value = ui.settings.overlayOpacity, onValueChange = { vm.setOverlayOpacity(it) }, valueRange = 0.5f..1f)
                Text("Sampling while drafting: every ${ui.settings.samplingMillis} ms (higher = cooler phone)")
                Slider(value = ui.settings.samplingMillis.toFloat(), onValueChange = { vm.setSamplingMillis((it / 100).toLong() * 100) }, valueRange = 1000f..3000f, steps = 19)
                Text(
                    "Capture runs at reduced resolution and is fully paused while MLBB is not in front (needs Usage access) or after 3 min without a draft.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Calibration dump (saves annotated frames for tuning layouts.json)", ui.settings.calibrationDump) { vm.setCalibrationDump(it) }
            }

            // ---- Data -------------------------------------------------------------------
            Section("Hero data") {
                val v = ui.settings.seededDataVersion
                Text("${ui.heroCount} heroes · dataset ${if (v > 0) DateFormat.getDateInstance().format(Date(v * 1000)) else "bundled"}")
                OutlinedTextField(
                    value = urlDraft, onValueChange = { urlDraft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("manifest.json URL (https://…)") },
                    placeholder = { Text("https://raw.githubusercontent.com/<user>/<repo>/data/manifest.json") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.setDataManifestUrl(urlDraft) }) { Text("Save URL") }
                    Button(enabled = !ui.updating, onClick = { vm.setDataManifestUrl(urlDraft); vm.checkForUpdates() }) { Text(if (ui.updating) "Checking…" else "Check for updates") }
                }
                if (ui.settings.lastUpdateMessage.isNotBlank()) {
                    Text(
                        ui.settings.lastUpdateMessage + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ui.settings.lastUpdateCheck)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vm.updateResultIsError(ui.settings)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Once a URL is set, new heroes and counters are fetched automatically once a day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- Backup -----------------------------------------------------------------
            Section("Backup") {
                Text(
                    "Mastered heroes and settings are included in Android's automatic Google backup for this device. For an explicit copy, export a file to Google Drive and import it on any phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("mlbb-draft-backup-$stamp.json")
                    }) { Text("Export to Drive / file") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
                }
                if (backupMessage.isNotBlank()) Text(backupMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        if (granted) Text("Granted", color = MaterialTheme.colorScheme.primary)
        else OutlinedButton(onClick = onRequest) { Text("Grant") }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
