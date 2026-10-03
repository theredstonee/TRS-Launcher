package dev.theredstonee.trslauncher

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.crates.keyring.Keyring

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    // Token-Schluessel im Android Keystore: Der Rust-Kern braucht den App-Kontext, bevor er startet.
    System.loadLibrary("trs_launcher_lib")
    Keyring.initializeNdkContext(applicationContext)
    super.onCreate(savedInstanceState)
    keepClearOfSystemBars()
  }

  /**
   * Android 15 zeichnet immer randlos, das WebView meldet die Systemleisten aber nicht ueber
   * env(safe-area-inset-*): Statusleiste, Gestenleiste und Tastatur deshalb als Abstand um die
   * Seite (Hintergrund in der Launcher-Farbe, helle Symbole).
   */
  private fun keepClearOfSystemBars() {
    window.decorView.setBackgroundColor(BAR_COLOR)
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
      WindowInsetsCompat.CONSUMED
    }
  }

  private companion object {
    val BAR_COLOR = Color.rgb(20, 20, 25)
  }
}
