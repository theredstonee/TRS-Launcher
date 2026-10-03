package dev.theredstonee.trs.game.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Dateien der Engine im App-Ordner: LWJGL-Jars/-Natives aus den APK-Assets und –
 * falls die App ihre `.so` nicht entpackt – die Engine-Natives selbst.
 */
internal object EngineFiles {
    private const val TAG = "TrsGameFiles"
    private const val ASSET_ROOT = "trs-engine"

    fun root(context: Context): File = File(context.filesDir, "engine")

    /** LWJGL-Jars des Forks (`3.3.3` oder `3.4.1`). */
    fun lwjglDir(context: Context, version: String) = File(root(context), "components/lwjgl3/$version")

    /** LWJGL-Natives für diese ABI. */
    fun lwjglNativesDir(context: Context, version: String) =
        File(root(context), "components/lwjgl-$version-natives/${abi()}")

    fun abi(): String = Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: "arm64-v8a"

    /**
     * Assets nach `files/engine/components` kopieren – einmal pro App-Version
     * (Marker = versionCode + Größe der Index-Datei).
     */
    @Synchronized
    fun ensureComponents(context: Context) {
        val dest = File(root(context), "components")
        val stamp = File(dest, ".stamp")
        val index = context.assets.open("$ASSET_ROOT/index.txt").use { it.readBytes().decodeToString() }
        val expected = "${appVersion(context)}:${index.hashCode()}"
        if (stamp.isFile && stamp.readText() == expected) return
        dest.deleteRecursively()
        val abi = abi()
        for (rel in index.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (rel.contains("..") || rel == "index.txt") continue
            // Natives anderer ABIs nicht kopieren.
            val parts = rel.split('/')
            if (parts.size == 3 && parts[0].endsWith("-natives") && parts[1] != abi) continue
            val out = File(dest, rel)
            out.parentFile?.mkdirs()
            context.assets.open("$ASSET_ROOT/$rel").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        stamp.writeText(expected)
        Log.i(TAG, "Engine-Komponenten entpackt nach $dest")
    }

    /**
     * Ordner mit libpojavexec & Co. Normalerweise `nativeLibraryDir`; packt die App
     * ihre Natives nicht aus (extractNativeLibs=false), kopieren wir sie aus dem APK.
     */
    @Synchronized
    fun nativeDir(context: Context): String {
        val info = context.applicationInfo
        val system = info.nativeLibraryDir
        if ((info.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0 && File(system, "libpojavexec.so").isFile) {
            return system
        }
        val dest = File(root(context), "natives/${abi()}")
        val stamp = File(dest, ".stamp")
        val expected = appVersion(context)
        if (stamp.isFile && stamp.readText() == expected) return dest.absolutePath
        dest.deleteRecursively()
        dest.mkdirs()
        val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val prefix = "lib/${abi()}/"
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith(prefix) || !entry.name.endsWith(".so")) continue
                    val name = entry.name.removePrefix(prefix)
                    if (name.contains('/')) continue
                    zip.getInputStream(entry).use { input -> File(dest, name).outputStream().use { input.copyTo(it) } }
                }
            }
        }
        stamp.writeText(expected)
        Log.i(TAG, "Natives aus dem APK entpackt nach $dest")
        return dest.absolutePath
    }

    private fun appVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "$code-${info.lastUpdateTime}"
    }
}
