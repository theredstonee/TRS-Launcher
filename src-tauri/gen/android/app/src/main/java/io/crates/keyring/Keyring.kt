package io.crates.keyring

import android.content.Context

/**
 * JNI-Gegenstueck von `android-native-keyring-store` (Rust): Der Token-Schluessel des Launchers liegt im
 * Android Keystore, dafuer braucht die Rust-Seite den App-Kontext (ndk-context). Die Funktion steckt in
 * libtrs_launcher_lib.so; MainActivity ruft sie vor dem Start des Rust-Kerns auf.
 */
class Keyring {
    companion object {
        external fun initializeNdkContext(context: Context)
    }
}