import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Nur 64 Bit: Telefone/Tablets (arm64) und der Emulator (x86_64).
val engineAbis = listOf("arm64-v8a", "x86_64")
// Nicht für Vanilla nötig und sehr groß (8,6 MB je ABI): Shader-Compiler für Vulkan-Mods.
val skippedNatives = setOf("libshaderc.so")

android {
    namespace = "dev.theredstonee.trs.game"
    compileSdk = 36
    // NDK r27d wie Amethyst (27.3.x). Anderer Pfad: `android.ndkPath` in local.properties
    // bzw. Umgebung ANDROID_NDK_HOME.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += engineAbis
        }
        externalNativeBuild {
            ndkBuild {
                // Windows: Kommandozeilen-Längengrenze
                arguments("APP_SHORT_COMMANDS=true")
                abiFilters(*engineAbis.toTypedArray())
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("vendor/amethyst/jni/Android.mk")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("vendor/amethyst/java")
            jniLibs.srcDir(layout.buildDirectory.dir("trs-engine-prebuilt/jniLibs"))
            assets.srcDir(layout.buildDirectory.dir("trs-engine-prebuilt/assets"))
        }
    }

    buildFeatures {
        prefab = true
    }

    packaging {
        jniLibs {
            // Kommt bereits über die bytehook-Abhängigkeit (ndk-build kopiert die Prefab-Kopie mit).
            excludes += "**/libbytehook.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    // Hook-Bibliothek für den Exit-Trap (MIT, prefab)
    implementation("com.bytedance:bytehook:1.0.10")
    // libjnidispatch für JNA im Spiel (Apache-2.0/LGPL-2.1)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(project(":tauri-android"))
    // Unit-Tests des Touch-Overlays (OverlayTest, ohne Gerät).
    testImplementation("junit:junit:4.13.2")
}

// --- Fertige Binärteile (prebuilt.lock) --------------------------------------------

val prebuiltOut = layout.buildDirectory.dir("trs-engine-prebuilt")
val prebuiltLock = file("prebuilt.lock")

val fetchEnginePrebuilt by tasks.registering {
    description = "Lädt die festgenagelten Engine-Binärteile (SHA-256) und entpackt sie."
    inputs.file(prebuiltLock)
    outputs.dir(prebuiltOut)
    val cacheDir = File(gradle.gradleUserHomeDir, "caches/trs-engine-prebuilt")
    doLast {
        val out = prebuiltOut.get().asFile
        out.deleteRecursively()
        prebuiltLock.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"))
                require(parts.size >= 3) { "prebuilt.lock: ungültige Zeile: $line" }
                val (kind, sha, url) = parts
                val target = parts.getOrNull(3)
                val file = cachedDownload(cacheDir, url, sha)
                when (kind) {
                    "aar-jni" -> extractZip(file, out) { name ->
                        val m = Regex("^jni/([^/]+)/([^/]+\\.so)$").find(name) ?: return@extractZip null
                        val (abi, so) = m.destructured
                        if (abi in engineAbis && so !in skippedNatives) "jniLibs/$abi/$so" else null
                    }
                    "aar-assets" -> extractZip(file, out) { name ->
                        val m = Regex("^assets/components/${Regex.escape(target!!)}/([^/]+)/([^/]+\\.so)$").find(name)
                            ?: return@extractZip null
                        val (abi, so) = m.destructured
                        if (abi in engineAbis && so !in skippedNatives) "assets/trs-engine/$target/$abi/$so" else null
                    }
                    "file" -> {
                        require(target != null && !target.contains("..")) { "prebuilt.lock: Ziel fehlt: $line" }
                        file.copyTo(File(out, "assets/trs-engine/$target"), overwrite = true)
                    }
                    else -> throw GradleException("prebuilt.lock: unbekannte Art $kind")
                }
            }
        // Liste für das Entpacken zur Laufzeit (AssetManager kann keine Ordner rekursiv auflisten).
        val engineAssets = File(out, "assets/trs-engine")
        val index = engineAssets.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(engineAssets).invariantSeparatorsPath }.sorted().toList()
        File(engineAssets, "index.txt").writeText(index.joinToString("\n") + "\n")
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun cachedDownload(cacheDir: File, url: String, sha: String): File {
    require(url.startsWith("https://")) { "prebuilt.lock: nur https ($url)" }
    val cached = File(cacheDir, sha)
    if (cached.isFile && sha256(cached) == sha) return cached
    cacheDir.mkdirs()
    val part = File(cacheDir, "$sha.part")
    logger.lifecycle("Lade $url")
    URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val actual = sha256(part)
    if (actual != sha) {
        part.delete()
        throw GradleException("SHA-256 stimmt nicht für $url: erwartet $sha, bekommen $actual")
    }
    part.renameTo(cached)
    return cached
}

fun extractZip(zip: File, out: File, map: (String) -> String?) {
    ZipFile(zip).use { archive ->
        for (entry in archive.entries()) {
            if (entry.isDirectory) continue
            val dest = map(entry.name) ?: continue
            val file = File(out, dest)
            file.parentFile.mkdirs()
            archive.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchEnginePrebuilt) }
