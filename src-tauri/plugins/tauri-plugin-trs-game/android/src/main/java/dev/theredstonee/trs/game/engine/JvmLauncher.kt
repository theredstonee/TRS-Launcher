package dev.theredstonee.trs.game.engine

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.oracle.dalvik.VMLauncher
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.JREUtils
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Startet die eingebettete JVM in diesem Prozess. Logik nach Amethyst-Android
 * (`JREUtils.launchJavaVM`, `Tools.launchMinecraft`, LGPL-3.0), angepasst an die
 * TRS-Startbeschreibung. Eine JVM pro Prozess – danach endet der Prozess.
 */
internal object JvmLauncher {
    private const val TAG = "TrsGameJvm"

    /** Argumente, die der Nutzer/Kern nicht setzen darf (Engine bestimmt sie). */
    private val PURGED = listOf(
        "-Xms", "-Xmx", "-d32", "-d64", "-Xint", "-XX:+UseTransparentHugePages",
        "-XX:+UseLargePagesInMetaspace", "-XX:+UseLargePages", "-Dorg.lwjgl.opengl.libname",
        "-Dorg.lwjgl.freetype.libname", "-XX:ActiveProcessorCount", "-Djava.library.path",
        "-Dorg.lwjgl.librarypath",
    )

    data class Window(val width: Int, val height: Int, val insets: IntArray)

    /** Engine-Natives laden (vor `Logger.begin`): exithook zieht pojavexec mit, dann JNI_OnLoad. */
    fun loadNatives() {
        System.loadLibrary("exithook")
        System.loadLibrary("pojavexec")
    }

    /** Renderer → (AMETHYST_RENDERER, Bibliothek, LIBGL_ES). Zink ist noch nicht dabei. */
    private fun renderer(name: String): Triple<String, String, String> = when (name) {
        "gl4es" -> Triple("opengles2", "libng_gl4es.so", "2")
        "zink" -> {
            Log.w(TAG, "Zink ist nicht enthalten – nehme MobileGlues")
            Triple("opengles_mobileglues", "libmobileglues.so", "3")
        }
        else -> Triple("opengles_mobileglues", "libmobileglues.so", "3")
    }

    /** Spiel starten (blockiert bis zum Ende der JVM, Rückgabe = Exit-Code). */
    fun launchGame(context: Context, config: LaunchConfig, window: Window): Int {
        EngineFiles.ensureComponents(context)
        val nativeDir = EngineFiles.nativeDir(context)
        val lwjglNatives = EngineFiles.lwjglNativesDir(context, config.lwjgl).absolutePath
        val (amethystRenderer, renderLib, glesVersion) = renderer(config.renderer)

        val env = baseEnv(context, config.javaHome, nativeDir, lwjglNatives, config.gameDir)
        env["AMETHYST_RENDERER"] = amethystRenderer
        env["LIBGL_ES"] = glesVersion
        env["FORCE_VSYNC"] = "false"
        env["LIBGL_MIPMAP"] = "3"
        env["LIBGL_NOERROR"] = "1"
        env["LIBGL_NOINTOVLHACK"] = "1"
        env["LIBGL_NORMALIZE"] = "1"
        env["MESA_GLSL_CACHE_DIR"] = context.cacheDir.absolutePath
        if (renderLib == "libmobileglues.so") {
            env["POJAVEXEC_EGL"] = renderLib
            env["MG_DIR_PATH"] = File(EngineFiles.root(context), "MobileGlues").apply { mkdirs() }.absolutePath
        }
        env["AWTSTUB_WIDTH"] = window.width.toString()
        env["AWTSTUB_HEIGHT"] = window.height.toString()
        env["DALVIK_APPLICATION"] = Tools.jObjectToString(context.applicationContext)
        env["DALVIK_JAVAVM"] = Tools.getJavaVMPointer().toString()
        env.putAll(config.extraEnv)
        applyEnv(env, config.javaHome)

        // Renderer laden (erst danach weiß LWJGL, welche GL-Bibliothek gilt).
        if (!JREUtils.dlopen(renderLib) && !JREUtils.dlopen("$nativeDir/$renderLib")) {
            Log.e(TAG, "Renderer $renderLib nicht ladbar")
        }

        val args = ArrayList<String>()
        args += commonJvmArgs(context, config.javaHome, nativeDir, config.gameDir)
        args += listOf(
            "-Xms${config.memoryMb}M",
            "-Xmx${config.memoryMb}M",
            "-Dorg.lwjgl.opengl.libname=$renderLib",
            "-Dorg.lwjgl.freetype.libname=$lwjglNatives/libfreetype.so",
            "-Dorg.lwjgl.system.allocator=system",
            "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
            "-Dglfwstub.windowWidth=${window.width}",
            "-Dglfwstub.windowHeight=${window.height}",
            "-Dglfwstub.initEgl=false",
            "-Dfml.earlyprogresswindow=false",
            "-Dloader.disable_forked_guis=true",
            "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Djava.awt.headless=true",
            // Sodium bricht sonst ab, weil die Engine ihre eigene LWJGL mitbringt (26.x will 3.4.3);
            // FCL und Zalith setzen das ebenso.
            "-Dsodium.checks.issue2561=false",
            "-Dtrs.mobile=android",
            "-Dtrs.touch=true",
            "-Dtrs.overlay.version=1",
            "-Dtrs.safeInsets=${window.insets.joinToString(",")}",
        )
        val libraryPath = listOfNotNull(lwjglNatives, nativeDir, config.nativesDir.takeIf { File(it).isDirectory })
        args += "-Djava.library.path=${libraryPath.joinToString(":")}"
        args += "-Dorg.lwjgl.librarypath=$lwjglNatives"
        args += config.jvmArgs.filterNot { arg -> PURGED.any { arg.startsWith(it) } }
        args += "-cp"
        args += classpath(context, config).joinToString(":")
        args += config.mainClass
        args += config.gameArgs

        return start(context, config.javaHome, config.gameDir, args)
    }

    /** Kopflose JVM (blockiert, Rückgabe = Exit-Code). */
    fun runHeadless(context: Context, config: JavaRunConfig): Int {
        val nativeDir = EngineFiles.nativeDir(context)
        val env = baseEnv(context, config.javaHome, nativeDir, null, config.cwd)
        applyEnv(env, config.javaHome)
        val args = ArrayList<String>()
        args += commonJvmArgs(context, config.javaHome, nativeDir, config.cwd)
        args += listOf("-Xms128M", "-Xmx${config.memoryMb}M", "-Djava.awt.headless=true", "-Dtrs.mobile=android")
        args += config.jvmArgs.filterNot { arg -> PURGED.any { arg.startsWith(it) } }
        if (config.mainClass.isNotEmpty()) {
            if (config.classpath.isNotEmpty()) {
                args += "-cp"
                args += config.classpath.joinToString(":")
            }
            args += config.mainClass
            args += config.args
        }
        return start(context, config.javaHome, config.cwd, args)
    }

    private fun start(context: Context, javaHome: String, cwd: String, args: List<String>): Int {
        initJavaRuntime(javaHome)
        JREUtils.setupExitMethod(context.applicationContext)
        JREUtils.initializeHooks()
        File(cwd).mkdirs()
        JREUtils.chdir(cwd)
        Log.i(TAG, "JVM-Argumente: ${redact(args)}")
        val argv = ArrayList<String>(args.size + 1)
        argv += "java" // argv[0]
        argv += args
        return VMLauncher.launchJVM(argv.toTypedArray())
    }

    /** LWJGL-Fork zuerst, dann die Libraries der Version, lwjglx zuletzt (LWJGL 2). */
    private fun classpath(context: Context, config: LaunchConfig): List<String> {
        val dir = EngineFiles.lwjglDir(context, config.lwjgl)
        val core = File(dir, "lwjgl.jar")
        val merged = File(dir, "lwjgl-${config.lwjgl}-merged-modules.jar")
        val lwjglx = File(dir, "lwjgl-lwjglx.jar")
        val modules = dir.listFiles { f ->
            f.name.endsWith(".jar") && f.name != core.name && f.name != merged.name && f.name != lwjglx.name
        }?.sortedBy { it.name } ?: emptyList()
        val out = ArrayList<String>()
        out += core.absolutePath
        out += merged.absolutePath
        out += modules.map { it.absolutePath }
        out += config.classpath
        if (config.lwjglx) out += lwjglx.absolutePath
        return out
    }

    private fun jreLibDir(javaHome: String): String {
        for (arch in listOf("aarch64", "amd64", "x86_64")) {
            val dir = File(javaHome, "lib/$arch")
            if (File(dir, "libjava.so").isFile) return dir.absolutePath
        }
        return File(javaHome, "lib").absolutePath
    }

    private fun baseEnv(context: Context, javaHome: String, nativeDir: String, lwjglNatives: String?, home: String): MutableMap<String, String> {
        val jreLib = jreLibDir(javaHome)
        val ld = buildList {
            add("$jreLib/jli")
            add(jreLib)
            add("/system/lib64")
            add("/vendor/lib64")
            add("/vendor/lib64/hw")
            add(nativeDir)
            if (lwjglNatives != null) add(lwjglNatives)
        }.joinToString(":")
        return linkedMapOf(
            "POJAV_NATIVEDIR" to nativeDir,
            "JAVA_HOME" to javaHome,
            "HOME" to home,
            "TMPDIR" to context.cacheDir.absolutePath,
            "LD_LIBRARY_PATH" to ld,
            "PATH" to "$javaHome/bin:${Os.getenv("PATH") ?: "/system/bin"}",
        )
    }

    private fun applyEnv(env: Map<String, String>, javaHome: String) {
        for ((key, value) in env) {
            try {
                Os.setenv(key, value, true)
            } catch (e: Exception) {
                Log.w(TAG, "setenv $key fehlgeschlagen", e)
            }
        }
        val jreLib = jreLibDir(javaHome)
        val server = File(jreLib, "server/libjvm.so")
        val jvmDir = if (server.isFile) "$jreLib/server" else "$jreLib/client"
        JREUtils.setLdLibraryPath("$jvmDir:${env["LD_LIBRARY_PATH"]}")
    }

    /** Wie Amethyst `initJavaRuntime`: JRE-Bibliotheken vorab laden. */
    private fun initJavaRuntime(javaHome: String) {
        val jreLib = jreLibDir(javaHome)
        JREUtils.dlopen("$jreLib/jli/libjli.so")
        val server = File(jreLib, "server/libjvm.so")
        if (!JREUtils.dlopen("libjvm.so")) {
            JREUtils.dlopen(if (server.isFile) server.absolutePath else "$jreLib/client/libjvm.so")
        }
        for (lib in listOf("libverify.so", "libjava.so", "libnet.so", "libnio.so", "libawt.so", "libawt_headless.so", "libfreetype.so", "libfontmanager.so")) {
            val file = File(jreLib, lib)
            if (file.isFile) JREUtils.dlopen(file.absolutePath)
        }
        File(jreLib).walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { JREUtils.dlopen(it.absolutePath) }
    }

    private fun commonJvmArgs(context: Context, javaHome: String, nativeDir: String, home: String): List<String> {
        val resolv = File(EngineFiles.root(context), "resolv.conf")
        if (!resolv.isFile) {
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        return listOf(
            "-Djava.home=$javaHome",
            "-Djava.io.tmpdir=${context.cacheDir.absolutePath}",
            "-Djna.boot.library.path=$nativeDir",
            "-Duser.home=$home",
            "-Duser.language=${Locale.getDefault().language}",
            "-Dos.name=Linux",
            "-Dos.version=Android-${Build.VERSION.RELEASE}",
            "-Duser.timezone=${TimeZone.getDefault().id}",
            "-Dext.net.resolvPath=${resolv.absolutePath}",
            "-Dlog4j2.formatMsgNoLookups=true",
            "-Djdk.lang.Process.launchMechanism=FORK",
            "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}",
        )
    }

    /** Zugangsdaten nie ins Log. */
    private fun redact(args: List<String>): String {
        val out = ArrayList<String>(args.size)
        var hide = false
        for (arg in args) {
            out += if (hide) "***" else arg
            hide = arg == "--accessToken" || arg == "--session" || arg == "--uuid" || arg == "--xuid"
        }
        return out.joinToString(" ")
    }
}
