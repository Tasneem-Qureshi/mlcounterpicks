package com.dripagency.mlbbdraft.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.ui.theme.MlbbDraftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the floating window.  State transitions:
 *   Hidden → Counters (draft / manual picks) → Briefing (loading screen) → fade-out → Hidden
 * The window is FLAG_NOT_FOCUSABLE so it never steals game input; only its own card area
 * consumes touches (drag / collapse / close / picker).
 */
class OverlayManager(private val context: Context, private val scope: CoroutineScope) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<OverlayUiState>(OverlayUiState.Hidden)
    val state: StateFlow<OverlayUiState> = _state
    private val _visible = MutableStateFlow(false)
    private val _collapsed = MutableStateFlow(false)
    private val _opacity = MutableStateFlow(0.92f)
    private val _catalog = MutableStateFlow<List<HeroLite>>(emptyList())

    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = 36 }

    val isShowing: Boolean get() = _visible.value

    /** Invoked when the user taps the card's close button (so the service can drop its "pinned" flag). */
    var onClosedByUser: (() -> Unit)? = null
    /** Picker callbacks, wired by the coordinator. */
    var onToggleEnemy: ((String) -> Unit)? = null
    var onClearEnemies: (() -> Unit)? = null
    var onSetLane: ((Role) -> Unit)? = null

    fun setOpacity(alpha: Float) { _opacity.value = alpha.coerceIn(0.4f, 1f) }
    fun setCatalog(heroes: List<HeroLite>) { _catalog.value = heroes }

    fun show(ui: OverlayUiState) = main.post {
        ensureAttached()
        _state.value = ui
        _visible.value = true
    }

    /** Fades out (handled by Compose), then detaches the window to free resources. */
    fun dismiss() = main.post {
        _visible.value = false
        main.postDelayed({ if (!_visible.value) detach() }, FADE_MS + 50)
    }

    fun destroy() = main.post { _visible.value = false; detach() }

    private fun ensureAttached() {
        if (view != null) return
        val o = OverlayLifecycleOwner().also { it.create() }
        val v = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            o.attachTo(this)
            setContent {
                MlbbDraftTheme {
                    OverlayRoot(
                        state = _state, visible = _visible, collapsed = _collapsed, opacity = _opacity, catalog = _catalog,
                        onToggleCollapse = { _collapsed.value = !_collapsed.value },
                        onClose = { onClosedByUser?.invoke(); dismiss() },
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onToggleEnemy = { onToggleEnemy?.invoke(it) },
                        onClearEnemies = { onClearEnemies?.invoke() },
                        onSetLane = { onSetLane?.invoke(it) },
                    )
                }
            }
        }
        runCatching { wm.addView(v, params) }
            .onFailure { Log.e(TAG, "addView failed (overlay permission?)", it); o.destroy(); return }
        view = v; owner = o
        o.resume()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        params.x += dx.toInt(); params.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun detach() {
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        owner?.destroy()
        view = null; owner = null
        _state.value = OverlayUiState.Hidden
        _collapsed.value = false
    }

    companion object {
        private const val TAG = "OverlayManager"
        const val FADE_MS = 350L
    }
}
