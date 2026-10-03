package dev.theredstonee.trslauncher

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import io.crates.keyring.Keyring

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    // Token-Schluessel im Android Keystore: Der Rust-Kern braucht den App-Kontext, bevor er startet.
    System.loadLibrary("trs_launcher_lib")
    Keyring.initializeNdkContext(applicationContext)
    super.onCreate(savedInstanceState)
  }
}
