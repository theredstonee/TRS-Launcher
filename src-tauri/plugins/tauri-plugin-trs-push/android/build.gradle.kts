plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.theredstonee.trs.push"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        // Der Connector (Kotlin 2.2) liegt im Klassenpfad; unser Kotlin-Code nutzt ihn nicht (nur die Java-Klassen).
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    testOptions {
        // org.json/Log in Unit-Tests: Android-Attrappen liefern Standardwerte statt Fehler.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    // UnifiedPush (Apache-2.0, codeberg.org/UnifiedPush/android-connector): Verteiler, Web-Push-Schlüssel
    // im Android Keystore, Entschlüsselung nach RFC 8291. Der Connector ist mit Kotlin 2.2 gebaut, die App mit
    // 1.9: seine kotlin-stdlib 2.2 bleibt draußen (Kotlin 1.9 kann deren Metadaten nicht lesen, die App-Stdlib
    // reicht für seinen Bytecode), und nur die Java-Klassen dieses Moduls sprechen ihn an.
    implementation("org.unifiedpush.android:connector:3.3.5") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(project(":tauri-android"))
    testImplementation("junit:junit:4.13.2")
    // Testvektor RFC 8291 gegen die mitgelieferte Entschlüsselung (Tink fixed_webpush aus dem Connector).
    testImplementation("com.google.crypto.tink:tink:1.23.0")
    testImplementation("org.json:json:20250517")
}
