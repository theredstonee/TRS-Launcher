package dev.theredstonee.trs.game.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File

/**
 * Vollbild-Spielfläche (eigener Prozess `:trsgame`): Querformat, immersiv,
 * Bildschirm bleibt an. Startet die JVM, sobald die Fläche bereit ist.
 */
class GameActivity : Activity() {
    companion object {
        private const val TAG = "TrsGameActivity"
        internal const val EXTRA_CONFIG = "dev.theredstonee.trs.game.CONFIG"

        @Volatile private var jvmStarted = false
    }

    private lateinit var config: LaunchConfig
    private lateinit var input: EngineGameInput
    private lateinit var overlay: OverlayProvider
    private lateinit var surfaceView: TextureView
    private lateinit var keyboardSink: KeyboardSink
    private var overlayView: View? = null
    private var surface: Surface? = null
    private var insets = IntArray(4)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configFile = intent.getStringExtra(EXTRA_CONFIG)?.let(::File)
        if (configFile == null || !isOwnFile(configFile) || !configFile.isFile) {
            Log.e(TAG, "Keine gültige Startbeschreibung")
            finish()
            return
        }
        config = try {
            LaunchConfig.read(configFile)
        } catch (e: Exception) {
            Log.e(TAG, "Startbeschreibung unlesbar", e)
            finish()
            return
        }
        JvmLauncher.loadNatives()
        EngineEvents.init(this, config.session)
        ExitBridge.install(ExitBridge.Mode.GAME)
        EngineEvents.state("starting")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        input = EngineGameInput { show -> main.post { setKeyboard(show) } }
        overlay = OverlayProviders.load(this)

        val root = FrameLayout(this)
        surfaceView = TextureView(this).apply { isOpaque = true }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        keyboardSink = KeyboardSink(this, input)
        root.addView(keyboardSink, FrameLayout.LayoutParams(1, 1))
        overlayView = overlay.createOverlay(this, input, config.touchProfile)
        root.addView(overlayView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()

        root.setOnApplyWindowInsetsListener { v, windowInsets ->
            readInsets(windowInsets)
            v.onApplyWindowInsets(windowInsets)
        }

        CallbackBridge.setListener(object : CallbackBridge.Listener {
            override fun onGrabStateChanged(grabbing: Boolean) {
                main.post {
                    overlay.onGrabChanged(grabbing)
                    pointerCapture(grabbing)
                }
            }

            override fun onClipboard(type: Int, text: String?): String? = clipboard(type, text)

            override fun androidDpi(): Float = resources.displayMetrics.density
        })

        surfaceView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val s = Surface(st)
                surface = s
                val (w, h) = resize(st, width, height)
                JREUtils.setupBridgeWindow(s)
                startJvm(w, h)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                resize(st, width, height)
            }

            // Fläche behalten: Die JVM rendert weiter in dieselbe Surface.
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = false

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun isOwnFile(file: File): Boolean {
        val path = file.canonicalPath
        return path.startsWith(cacheDir.canonicalPath + File.separator) || path.startsWith(filesDir.canonicalPath + File.separator)
    }

    /**
     * Spielauflösung = View-Größe × Skalierung (`TRS_RESOLUTION_SCALE` in `extraEnv`,
     * 0.25–1.0; schwache Geräte rendern kleiner, die Fläche skaliert hoch).
     */
    private fun resize(st: SurfaceTexture, width: Int, height: Int): Pair<Int, Int> {
        if (width < 1 || height < 1) return width to height
        val scale = config.extraEnv["TRS_RESOLUTION_SCALE"]?.toFloatOrNull()?.coerceIn(0.25f, 1f) ?: 1f
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        st.setDefaultBufferSize(w, h)
        CallbackBridge.windowWidth = w
        CallbackBridge.windowHeight = h
        CallbackBridge.physicalWidth = width
        CallbackBridge.physicalHeight = height
        input.scaleX = w.toFloat() / width
        input.scaleY = h.toFloat() / height
        CallbackBridge.sendUpdateWindowSize(w, h)
        return w to h
    }

    private fun startJvm(width: Int, height: Int) {
        if (jvmStarted) return
        jvmStarted = true
        val logFile = File(cacheDir, "game-${config.session}.log").apply { writeText("") }
        Logger.begin(logFile.absolutePath)
        Logger.addLogListener { chunk ->
            for (line in chunk.split('\n')) if (line.isNotEmpty()) EngineEvents.log(line)
        }
        val window = JvmLauncher.Window(width, height, insets.copyOf())
        Thread({
            var code = 1
            try {
                EngineEvents.state("running")
                code = JvmLauncher.launchGame(applicationContext, config, window)
            } catch (t: Throwable) {
                Log.e(TAG, "JVM-Start fehlgeschlagen", t)
                EngineEvents.log("[TRS] Engine-Fehler: ${t.javaClass.simpleName}: ${t.message}")
            }
            ExitBridge.report(code, false)
            main.post { finish() }
            main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        }, "JVM Main thread").start()
    }

    private fun readInsets(windowInsets: WindowInsets) {
        val cutout = if (Build.VERSION.SDK_INT >= 28) windowInsets.displayCutout else null
        insets = if (cutout != null) {
            intArrayOf(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom)
        } else {
            IntArray(4)
        }
        overlay.onSafeInsets(insets[0], insets[1], insets[2], insets[3])
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setKeyboard(show: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            keyboardSink.requestFocus()
            imm.showSoftInput(keyboardSink, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(keyboardSink.windowToken, 0)
        }
    }

    private fun clipboard(type: Int, text: String?): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (type) {
            CallbackBridge.CLIPBOARD_COPY -> {
                main.post { cm.setPrimaryClip(ClipData.newPlainText("Minecraft", text ?: "")) }
                null
            }
            CallbackBridge.CLIPBOARD_PASTE -> cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            CallbackBridge.CLIPBOARD_OPEN -> {
                // Links nur http(s) öffnen.
                val uri = android.net.Uri.parse(text ?: "")
                if (uri.scheme == "https" || uri.scheme == "http") {
                    main.post {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            Log.w(TAG, "Link nicht öffnbar", e)
                        }
                    }
                }
                null
            }
            else -> null
        }
    }

    // --- Hardware-Eingabe ---------------------------------------------------

    private fun pointerCapture(grabbed: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        if (grabbed && hardwareMouse) surfaceView.requestPointerCapture() else surfaceView.releasePointerCapture()
    }

    private var hardwareMouse = false

    private fun hardware(active: Boolean) {
        overlay.onHardwareInput(active)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Zurück = Escape (Pause-Menü), nie die Activity schließen.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) input.sendKey(Glfw.KEY_ESCAPE, 0, true, 0)
            if (event.action == KeyEvent.ACTION_UP) input.sendKey(Glfw.KEY_ESCAPE, 0, false, 0)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val glfw = HardwareKeys.toGlfw(event.keyCode)
        val fromKeyboard = event.device?.let { !it.isVirtual } ?: false
        if (glfw == 0 && event.unicodeChar == 0) return super.dispatchKeyEvent(event)
        if (fromKeyboard) hardware(true)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (glfw != 0) input.sendKey(glfw, event.scanCode, true, HardwareKeys.mods(event))
                val ch = event.unicodeChar
                if (ch != 0 && !Character.isISOControl(ch)) input.sendChar(ch)
            }
            KeyEvent.ACTION_UP -> if (glfw != 0) input.sendKey(glfw, event.scanCode, false, HardwareKeys.mods(event))
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            hardwareMouse = true
            hardware(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> if (!input.isGrabbed()) input.moveMouseAbsolute(event.x, event.y)
                MotionEvent.ACTION_SCROLL -> input.scroll(
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                )
                MotionEvent.ACTION_BUTTON_PRESS -> mouseButton(event.actionButton, true)
                MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event.actionButton, false)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // Maus-Klicks kommen teils als Touch: Position folgen lassen, Knöpfe über BUTTON_PRESS.
            if (!input.isGrabbed()) input.moveMouseAbsolute(event.x, event.y)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hardware(false)
        return super.dispatchTouchEvent(event)
    }

    private fun mouseButton(button: Int, down: Boolean) {
        val glfw = when (button) {
            MotionEvent.BUTTON_PRIMARY -> Glfw.MOUSE_LEFT
            MotionEvent.BUTTON_SECONDARY -> Glfw.MOUSE_RIGHT
            MotionEvent.BUTTON_TERTIARY -> Glfw.MOUSE_MIDDLE
            else -> return
        }
        input.sendMouseButton(glfw, down)
    }

    /** Eingefangene Maus (Kamera): relative Bewegungen. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 26) {
            surfaceView.setOnCapturedPointerListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    input.moveMouseRelative(event.x, event.y)
                } else if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
                    mouseButton(event.actionButton, true)
                } else if (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
                    mouseButton(event.actionButton, false)
                } else if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    input.scroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                }
                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallbackBridge.setListener(null)
        // Activity weg, JVM läuft noch (z. B. vom System beendet): Prozess beenden, Ende melden.
        if (jvmStarted && isFinishing) {
            ExitBridge.report(0, false)
            Process.killProcess(Process.myPid())
        }
    }

    /** Unsichtbares Textfeld: Soft-Tastatur → Zeichen/Tasten ins Spiel. */
    @SuppressLint("ViewConstructor")
    private class KeyboardSink(context: Context, private val input: GameInput) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    text.codePoints().forEach { cp ->
                        if (cp == '\n'.code) tap(Glfw.KEY_ENTER) else input.sendChar(cp)
                    }
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(maxOf(1, beforeLength)) { tap(Glfw.KEY_BACKSPACE) }
                    return true
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    tap(Glfw.KEY_ENTER)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    val glfw = HardwareKeys.toGlfw(event.keyCode)
                    if (glfw != 0) input.sendKey(glfw, 0, event.action == KeyEvent.ACTION_DOWN, 0)
                    return true
                }
            }
        }

        private fun tap(key: Int) {
            input.sendKey(key, 0, true, 0)
            input.sendKey(key, 0, false, 0)
        }
    }
}
