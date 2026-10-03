package dev.theredstonee.trsgame.overlay

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.theredstonee.trs.game.engine.GameInput
import java.io.File
import java.util.Locale

/**
 * Touch-Steuerung als Overlay der Spiel-Engine (im Manifest als
 * `dev.theredstonee.trs.game.OVERLAY_PROVIDER` eingetragen). Lädt das Layout der Instanz aus
 * `controlsDir`; in Menüs (Maus frei, z. B. Pause) gibt es „Steuerung bearbeiten“.
 */
class TouchOverlayProvider : dev.theredstonee.trs.game.engine.OverlayProvider {
    private var handle: OverlayHandle? = null
    private var editButton: TextView? = null
    private var editing = false

    override fun createOverlay(activity: Activity, input: GameInput, profile: String?): View =
        createOverlay(activity, input, profile, null)

    override fun createOverlay(activity: Activity, input: GameInput, profile: String?, controlsDir: File?): View {
        val root = FrameLayout(activity)
        val dir = controlsDir ?: File(activity.filesDir, "controls")
        val attached = TouchOverlay.attach(root, input, OverlayConfig(dir, profile))
        handle = attached
        attached.onEditingChanged = { on ->
            editing = on
            editButton?.visibility = if (on || input.isGrabbed()) View.GONE else View.VISIBLE
        }
        val dp = activity.resources.displayMetrics.density
        val lang = Locale.getDefault().toLanguageTag()
        editButton = TextView(activity).apply {
            text = "✎ " + OverlayStrings.get("editor.title", lang)
            setTextColor(TouchOverlayView.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = (40 * dp).toInt()
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(0xE617171E.toInt())
                setStroke((1 * dp).toInt(), TouchOverlayView.COLOR_BORDER)
            }
            visibility = if (input.isGrabbed()) View.GONE else View.VISIBLE
            setOnClickListener { setEditing(true) }
        }
        root.addView(
            editButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins((12 * dp).toInt(), 0, 0, (12 * dp).toInt())
            },
        )
        return root
    }

    override fun onGrabChanged(grabbed: Boolean) {
        editButton?.visibility = if (grabbed || editing) View.GONE else View.VISIBLE
    }

    override fun onHardwareInput(active: Boolean) {
        if (active) handle?.onHardwareInput()
    }

    override fun onSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        (editButton?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val dp = editButton?.resources?.displayMetrics?.density ?: 1f
            lp.setMargins(left + (12 * dp).toInt(), 0, 0, bottom + (12 * dp).toInt())
            editButton?.layoutParams = lp
        }
    }

    override fun setEditing(editing: Boolean) {
        handle?.setEditing(editing)
    }
}
