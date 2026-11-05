package com.dripagency.mlbbdraft

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dripagency.mlbbdraft.capture.ScreenCaptureService
import java.util.concurrent.Executor

/** Quick Settings tile: tap opens ML Counter. The tile lights up while the assistant is running. */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (ScreenCaptureService.mode.value == ScreenCaptureService.Mode.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= 29) subtitle = getString(if (state == Tile.STATE_ACTIVE) R.string.tile_running else R.string.tile_open)
            updateTile()
        }
    }

    override fun onClick() {
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    companion object {
        /** Asks the system to add the tile (Android 13+). Returns false where the prompt is not available. */
        fun requestAdd(context: Context, onResult: (Boolean) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < 33) return false
            val sbm = context.getSystemService(StatusBarManager::class.java) ?: return false
            val main: Executor = Executor { r -> android.os.Handler(context.mainLooper).post(r) }
            sbm.requestAddTileService(
                ComponentName(context, QuickTileService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_overlay),
                main,
            ) { code ->
                onResult(code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
            }
            return true
        }
    }
}
