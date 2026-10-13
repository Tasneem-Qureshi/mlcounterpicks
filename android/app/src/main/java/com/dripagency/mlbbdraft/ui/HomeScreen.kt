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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dripagency.mlbbdraft.capture.ScreenCaptureService.Mode
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
    val mode by ScreenCaptureService.mode.collectAsStateWithLifecycle()
    val backupMessage by vm.backupMessage.collectAsStateWithLifecycle()
    val cloudConsent by vm.cloudConsent.collectAsStateWithLifecycle()
    val cloudBusy by vm.cloudBusy.collectAsStateWithLifecycle()
    val s = ui.settings
    val running = mode != Mode.OFF

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember { mutableStateOf(notificationsGranted(context)) }
    var usageGranted by remember { mutableStateOf(ForegroundAppWatcher.hasUsageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context); notifGranted = notificationsGranted(context); usageGranted = ForegroundAppWatcher.hasUsageAccess(context)
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
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res -> vm.onGoogleConsent(res.data) }
    LaunchedEffect(cloudConsent) { cloudConsent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }

    var urlDraft by remember { mutableStateOf("") }
    LaunchedEffect(s.dataManifestUrl) { if (urlDraft.isBlank()) urlDraft = s.dataManifestUrl }

    Scaffold(topBar = { TopAppBar(title = { Text("ML Counter · Settings") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Assistant") {
                Text(
                    when (mode) { Mode.OFF -> "Stopped"; Mode.OVERLAY_ONLY -> "Running · overlay only"; Mode.CAPTURE -> "Running · screen detection on" },
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = overlayGranted && !running, onClick = { ScreenCaptureService.startOverlayOnly(context) }) { Text("Start") }
                    OutlinedButton(enabled = running, onClick = { ScreenCaptureService.stop(context) }) { Text("Stop") }
                    OutlinedButton(enabled = running, onClick = { ScreenCaptureService.showOverlay(context) }) { Text("Show overlay") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == Mode.CAPTURE) OutlinedButton(onClick = { ScreenCaptureService.stopCapture(context) }) { Text("Screen detection off") }
                    else OutlinedButton(enabled = running, onClick = { projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }) { Text("Screen detection on") }
                }
                Hint("Detection turns off by itself after the draft.")
                OutlinedButton(onClick = onOpenMastery, modifier = Modifier.fillMaxWidth()) { Text("Mastered heroes (${ui.masteredCount}) →") }
            }

            Section("Permissions") {
                PermissionRow("Draw over other apps", overlayGranted) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                PermissionRow("Notifications", notifGranted) { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                PermissionRow("Usage access (auto-show when MLBB opens)", usageGranted) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                SwitchRow("Auto-show overlay when MLBB opens", s.autoShowOnMlbb) { vm.setAutoShowOnMlbb(it) }
            }

            Section("My lane") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Role.entries.forEach { r -> FilterChip(selected = s.defaultRole == r, onClick = { vm.setDefaultRole(r) }, label = { Text(r.short) }) }
                }
            }

            Section("Overlay") {
                Text("Briefing: ${s.briefingSeconds}s")
                Slider(value = s.briefingSeconds.toFloat(), onValueChange = { vm.setBriefingSeconds(it.toInt()) }, valueRange = 10f..30f, steps = 19)
                Text("Opacity: ${(s.overlayOpacity * 100).toInt()}%")
                Slider(value = s.overlayOpacity, onValueChange = { vm.setOverlayOpacity(it) }, valueRange = 0.5f..1f)
                Text("Detection interval: ${s.samplingMillis} ms")
                Slider(value = s.samplingMillis.toFloat(), onValueChange = { vm.setSamplingMillis((it / 100).toLong() * 100) }, valueRange = 1000f..3000f, steps = 19)
                SwitchRow("Auto-detect lane badge", s.autoDetectLane) { vm.setAutoDetectLane(it) }
                SwitchRow("Detect in any app (test with videos)", s.detectAnywhere) { vm.setDetectAnywhere(it) }
                SwitchRow("Calibration dump", s.calibrationDump) { vm.setCalibrationDump(it) }
            }

            Section("Google account") {
                if (s.cloudEnabled) Text("Connected" + (if (s.cloudEmail.isNotBlank()) " · ${s.cloudEmail}" else ""), color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !cloudBusy, onClick = { vm.connectGoogle() }) { Text(if (cloudBusy) "Syncing…" else if (s.cloudEnabled) "Sync now" else "Connect Google account") }
                    if (s.cloudEnabled) OutlinedButton(enabled = !cloudBusy, onClick = { vm.disconnectGoogle() }) { Text("Disconnect") }
                }
                if (s.cloudMessage.isNotBlank()) Status(s.cloudMessage, s.cloudLastSync, error = s.cloudMessage.startsWith("Sync failed"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("mlbb-draft-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json") }) { Text("Export file") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import file") }
                }
                if (backupMessage.isNotBlank()) Hint(backupMessage)
            }

            Section("Hero data") {
                val v = s.seededDataVersion
                Text("${ui.heroCount} heroes · ${if (v > 0) DateFormat.getDateInstance().format(Date(v * 1000)) else "bundled"}")
                OutlinedTextField(value = urlDraft, onValueChange = { urlDraft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("manifest.json URL") })
                Button(enabled = !ui.updating, onClick = { vm.setDataManifestUrl(urlDraft); vm.checkForUpdates() }) { Text(if (ui.updating) "Checking…" else "Check for updates") }
                if (s.lastUpdateMessage.isNotBlank()) Status(s.lastUpdateMessage, s.lastUpdateCheck, error = vm.updateResultIsError(s))
            }
        }
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Status(message: String, at: Long, error: Boolean) = Text(
    message + (if (at > 0) " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) else ""),
    style = MaterialTheme.typography.bodySmall,
    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (granted) Text("Granted", color = MaterialTheme.colorScheme.primary) else OutlinedButton(onClick = onRequest) { Text("Grant") }
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
