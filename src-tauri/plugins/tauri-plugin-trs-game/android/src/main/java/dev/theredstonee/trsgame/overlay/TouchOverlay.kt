package dev.theredstonee.trsgame.overlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.theredstonee.trs.game.engine.GameInput
import java.io.File
import java.util.Locale

/** Was die Engine beim Start übergibt. */
data class OverlayConfig(
    /** `<App-Daten>/controls` – dort liegen die Layouts. */
    val controlsDir: File,
    /** Layout-ID aus `GameLaunchSpec.touchProfile` (`null` = PvP). */
    val profileId: String?,
    /** Feste Ränder in Pixeln; `null` = aus den Fenster-Insets. */
    val insets: Insets? = null,
)

/** Griff auf ein angehängtes Overlay. */
interface OverlayHandle {
    val view: View
    /** Editor an/aus (Pause → „Steuerung bearbeiten“). */
    fun setEditing(editing: Boolean)
    /** Editor geöffnet/geschlossen (auch über „Abbrechen“/„Speichern“ in der Werkzeugleiste). */
    var onEditingChanged: ((Boolean) -> Unit)?
    /** Anderes Layout laden (z. B. nach Änderung im Launcher). */
    fun reload(profileId: String?)
    /** Engine meldet Controller-/Maus-/Tastatur-Eingabe → Overlay ausblenden. */
    fun onHardwareInput()
    /** Ränder als `l,t,r,b` (Pixel) für `-Dtrs.safeInsets`. */
    fun safeInsets(): String
    fun detach()
}

/** Einstieg für die Engine: Overlay über die Spielfläche legen. */
interface OverlayProvider {
    fun attach(parent: ViewGroup, input: GameInput, config: OverlayConfig): OverlayHandle
}

/** Verbindet die Engine-Schnittstelle mit der testbaren Overlay-Logik. */
class GameInputSink(private val input: GameInput) : InputSink {
    override fun sendKey(glfwKey: Int, scancode: Int, down: Boolean, mods: Int) = input.sendKey(glfwKey, scancode, down, mods)
    override fun sendChar(codepoint: Int) = input.sendChar(codepoint)
    override fun sendMouseButton(button: Int, down: Boolean) = input.sendMouseButton(button, down)
    override fun moveMouseRelative(dx: Float, dy: Float) = input.moveMouseRelative(dx, dy)
    override fun moveMouseAbsolute(x: Float, y: Float) = input.moveMouseAbsolute(x, y)
    override fun scroll(dx: Float, dy: Float) = input.scroll(dx, dy)
    override fun isGrabbed(): Boolean = input.isGrabbed()
    override fun showKeyboard(show: Boolean) = input.showKeyboard(show)
}

object TouchOverlay : OverlayProvider {
    override fun attach(parent: ViewGroup, input: GameInput, config: OverlayConfig): OverlayHandle =
        Attached(parent, GameInputSink(input), config)

    private class Attached(private val parent: ViewGroup, sink: InputSink, config: OverlayConfig) : OverlayHandle {
        private val context: Context = parent.context
        private val store = LayoutStore(config.controlsDir)
        private val touchView = TouchOverlayView(context, sink, store.load(config.profileId), config.insets)
        override var onEditingChanged: ((Boolean) -> Unit)? = null
        private val toolbar = Toolbar(context, touchView, store) { onEditingChanged?.invoke(false) }
        override val view: View = FrameLayout(context).apply {
            addView(touchView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(toolbar.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        private val devices = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val d = InputDevice.getDevice(deviceId) ?: return
                if (!d.isVirtual && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_MOUSE))) {
                    touchView.hardwareInput()
                }
            }
            override fun onInputDeviceRemoved(deviceId: Int) {}
            override fun onInputDeviceChanged(deviceId: Int) {}
        }

        init {
            toolbar.root.visibility = View.GONE
            touchView.onEditorChanged = { toolbar.refresh() }
            parent.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            inputManager?.registerInputDeviceListener(devices, Handler(Looper.getMainLooper()))
        }

        override fun setEditing(editing: Boolean) {
            if (editing) touchView.startEditing() else touchView.stopEditing()
            toolbar.root.visibility = if (editing) View.VISIBLE else View.GONE
            toolbar.refresh()
            onEditingChanged?.invoke(editing)
        }

        override fun reload(profileId: String?) {
            touchView.setLayout(store.load(profileId))
        }

        override fun onHardwareInput() = touchView.hardwareInput()

        override fun safeInsets(): String = touchView.safeInsetsText()

        override fun detach() {
            inputManager?.unregisterInputDeviceListener(devices)
            parent.removeView(view)
        }
    }

    /** Werkzeugleiste des Editors im Spiel (oben, waagerecht scrollbar). */
    private class Toolbar(
        private val context: Context,
        private val touchView: TouchOverlayView,
        private val store: LayoutStore,
        private val onClosed: () -> Unit,
    ) {
        private val lang = Locale.getDefault().toLanguageTag()
        private fun s(key: String) = OverlayStrings.get(key, lang)
        private val dp = context.resources.displayMetrics.density
        private val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        val root = HorizontalScrollView(context).apply {
            setBackgroundColor(0xE617171E.toInt())
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        private val selectionOnly = ArrayList<TextView>()
        private lateinit var gridButton: TextView

        init {
            button(s("editor.add")) { pickAction { a, label, icon -> editor()?.add(a, label, icon); changed() } }
            selectionOnly += button(s("editor.action")) { pickAction { a, label, icon -> editor()?.setAction(a, label, icon); changed() } }
            selectionOnly += button(s("editor.shape")) { editor()?.cycleShape(); changed() }
            selectionOnly += button("−") { editor()?.scale(0.9f); changed() }.also { it.contentDescription = s("editor.smaller") }
            selectionOnly += button("+") { editor()?.scale(1.1f); changed() }.also { it.contentDescription = s("editor.bigger") }
            selectionOnly += button("◐−") { editor()?.let { e -> e.selected?.let { b -> e.setOpacity(b.opacity - 0.1f) } }; changed() }.also { it.contentDescription = s("editor.fainter") }
            selectionOnly += button("◐+") { editor()?.let { e -> e.selected?.let { b -> e.setOpacity(b.opacity + 0.1f) } }; changed() }.also { it.contentDescription = s("editor.stronger") }
            selectionOnly += button(s("editor.remove")) { editor()?.remove(); changed() }
            gridButton = button(s("editor.grid")) { editor()?.let { it.grid = !it.grid }; changed() }
            button(s("editor.cancel")) { close() }
            button(s("editor.save"), primary = true) { save() }
        }

        private fun editor() = touchView.editor

        private fun changed() {
            touchView.invalidate()
            refresh()
        }

        fun refresh() {
            val ed = editor()
            val hasSelection = ed?.selected != null
            selectionOnly.forEach { it.isEnabled = hasSelection; it.alpha = if (hasSelection) 1f else 0.4f }
            if (::gridButton.isInitialized) gridButton.alpha = if (ed?.grid == true) 1f else 0.6f
        }

        private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): TextView {
            val v = TextView(context).apply {
                this.text = text
                setTextColor(TouchOverlayView.COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = (44 * dp).toInt()
                minHeight = (40 * dp).toInt()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(if (primary) TouchOverlayView.COLOR_ACCENT else 0xFF252531.toInt())
                    setStroke((1 * dp).toInt(), if (primary) TouchOverlayView.COLOR_ACCENT_LIGHT else TouchOverlayView.COLOR_BORDER)
                }
                setOnClickListener { onClick() }
            }
            row.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * dp).toInt()
            })
            return v
        }

        private fun pickAction(done: (Action, String?, String?) -> Unit) {
            val presets = ActionPresets.ALL
            val names = presets.map { s("preset.${it.key}") } + s("editor.otherKey")
            AlertDialog.Builder(context)
                .setTitle(s("editor.pickAction"))
                .setItems(names.toTypedArray()) { _, which ->
                    if (which < presets.size) {
                        val p = presets[which]
                        done(p.action, p.label, p.icon)
                    } else {
                        pickKey { code -> done(Action.Key(code), ActionPresets.keyName(code), null) }
                    }
                }
                .setNegativeButton(s("editor.cancel"), null)
                .show()
        }

        private fun pickKey(done: (Int) -> Unit) {
            val keys = ActionPresets.KEYS
            AlertDialog.Builder(context)
                .setTitle(s("editor.pickKey"))
                .setItems(keys.map { ActionPresets.keyName(it) }.toTypedArray()) { _, which -> done(keys[which]) }
                .setNegativeButton(s("editor.cancel"), null)
                .show()
        }

        private fun close() {
            touchView.stopEditing()
            root.visibility = View.GONE
            onClosed()
        }

        private fun save() {
            val ed = editor() ?: return
            try {
                val layout = ed.result()
                store.save(layout)
                touchView.setLayout(layout)
                close()
                Toast.makeText(context, s("editor.saved"), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, s("editor.saveFailed"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
