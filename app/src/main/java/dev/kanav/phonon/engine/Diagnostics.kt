package dev.kanav.phonon.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import dev.kanav.phonon.ffi.NativeLib
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Debug info screen reports, for users to paste into a bug report. A native crash (such
 * as SIGILL from a CPU instruction the phone lacks) kills the process with no Java exception, so
 * each model load leaves a marker that only a finished load removes. A marker found at the next
 * start means the load took the app down: say so, and do not load again on its own, or the app
 * and the system voice would crash every time they start.
 */
object Diagnostics {
    private fun dir(context: Context) = File(context.filesDir, "diag").apply { mkdirs() }
    private fun pendingFile(context: Context) = File(dir(context), "load.pending")
    private fun crashedFile(context: Context) = File(dir(context), "load.crashed")
    private fun lastLoadFile(context: Context) = File(dir(context), "load.last")
    private fun javaCrashFile(context: Context) = File(dir(context), "java-crash.txt")

    /** At process start, before anything loads the model. */
    fun init(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
                javaCrashFile(app).writeText("${now()} on thread ${thread.name}, ${versionName(app)}\n$trace")
            } catch (t: Throwable) {
                // Nothing more to do while crashing.
            }
            previous?.uncaughtException(thread, e)
        }
        val pending = pendingFile(app)
        if (!pending.isFile) return
        val record = try {
            JSONObject(pending.readText())
        } catch (e: Exception) {
            JSONObject()
        }
        pending.delete()
        if (Build.VERSION.SDK_INT >= 30) {
            val exit = exitFor(app, record.optInt("pid"))
            // Killed for a reason that is not the load's fault: swiped away, low memory, an update.
            if (exit != null && exit.reason !in CRASH_REASONS) return
            record.put("exit", exit?.let(::describe) ?: "no exit record")
        } else {
            record.put("exit", "the app stopped")
        }
        record.put("blocked", true)
        crashedFile(app).writeText(record.toString())
    }

    /**
     * Why the model must not load on its own, or null when it may: the last load in this
     * version took the app down and the user has not asked to try again.
     */
    fun blockedLoad(context: Context): String? {
        val record = crashed(context) ?: return null
        if (!record.optBoolean("blocked") || record.optLong("version") != versionCode(context)) return null
        return "Phonon closed while loading the model last time (${record.optString("exit")}). " +
            "Tap Try again, or Debug info for a report to share."
    }

    /** Let the next load go ahead after a crashed one; the record stays for the report. */
    fun unblock(context: Context) {
        val record = crashed(context) ?: return
        crashedFile(context).writeText(record.put("blocked", false).toString())
    }

    fun loadStarted(context: Context, threads: Int) {
        pendingFile(context).writeText(
            JSONObject()
                .put("pid", Process.myPid())
                .put("version", versionCode(context))
                .put("started", now())
                .put("library", NativeLib.name)
                .put("model", modelName(context))
                .put("threads", threads)
                .toString(),
        )
    }

    fun loadFinished(context: Context, error: String?, millis: Long) {
        pendingFile(context).delete()
        lastLoadFile(context).writeText(
            "${now()}: ${if (error == null) "loaded" else "failed: $error"} in $millis ms, " +
                "library ${NativeLib.name}, model ${modelName(context)}",
        )
    }

    /** Before the app exits on purpose mid-load, so the next start does not take it for a crash. */
    fun forgetPendingLoad(context: Context) {
        pendingFile(context).delete()
    }

    private fun crashed(context: Context) = crashedFile(context).takeIf { it.isFile }?.let {
        try {
            JSONObject(it.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** The report the Debug info screen shows and copies. Reads files, so off the main thread is better. */
    fun report(context: Context): String = buildString {
        fun line(key: String, value: Any?) = append(key).append(": ").append(value ?: "unknown").append('\n')
        fun section(title: String) = append("\n## ").append(title).append('\n')

        append("# Phonon TTS debug info\n")
        line("Generated", now())
        line("App", "${versionName(context)} (${versionCode(context)})")

        section("Device")
        line("Model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        line("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        if (Build.VERSION.SDK_INT >= 31) line("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        line("Hardware", Build.HARDWARE)
        line("ABIs", Build.SUPPORTED_ABIS.joinToString())
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        line("RAM", "${mem.totalMem / MB} MB, ${mem.availMem / MB} MB free${if (mem.lowMemory) ", low" else ""}")
        line("Page size", try { Os.sysconf(OsConstants._SC_PAGESIZE) } catch (e: Exception) { null })

        section("CPU")
        line("Cores", Runtime.getRuntime().availableProcessors())
        line("Core types", coreTypes())
        line("HWCAP", NativeLib.hwcap?.let { "0x" + it.toString(16) })
        line("ARMv8.2 (dotprod + fp16)", NativeLib.hasArmV82)
        line("Features", cpuinfo().firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim())

        section("Engine")
        line("Native library", "lib${NativeLib.name}.so (${NativeLib.reason})")
        line("Threads", Prefs(context).threads)
        line("Model state", when (val s = Model.state) {
            is Model.State.NotInstalled -> if (Model.installed(context)) "installed, not loaded" else "not installed"
            is Model.State.Loading -> "loading"
            is Model.State.Ready -> "ready, ${s.model.speakers.size} voices, ${s.model.languages.joinToString()}"
            is Model.State.Failed -> "failed: ${s.message}"
        })
        line("Last load", lastLoadFile(context).takeIf { it.isFile }?.readText() ?: "none recorded")
        crashed(context)?.let { c ->
            line(
                "Crashed load",
                "${c.optString("started")}, library lib${c.optString("library")}.so, model ${c.optString("model")}, " +
                    "${c.optInt("threads")} threads, app version ${c.optLong("version")}: ${c.optString("exit")}" +
                    if (c.optBoolean("blocked")) " (auto-load paused)" else "",
            )
        }

        section("Model files")
        line("Installed", modelName(context))
        val files = Model.dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) append("none\n")
        for (f in files) append("${f.name}: ${f.length()} bytes\n")
        line("Voice files", Model.voicesDir(context).listFiles()?.size ?: 0)

        if (Build.VERSION.SDK_INT >= 30) {
            section("Recent exits")
            val exits = try {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            } catch (e: Exception) {
                emptyList()
            }
            if (exits.isEmpty()) append("none recorded\n")
            for (e in exits) append("${describe(e)}\n")
        }

        javaCrashFile(context).takeIf { it.isFile }?.let {
            section("Last Java crash")
            append(it.readLines().take(40).joinToString("\n")).append('\n')
        }

        section("Log (warnings and errors)")
        append(logcat().ifEmpty { "empty\n" })
    }

    @RequiresApi(30)
    private fun describe(e: ApplicationExitInfo): String {
        val reason = EXIT_REASONS[e.reason] ?: "reason ${e.reason}"
        // For a native crash or a kill, status is the signal: 4 is SIGILL, 11 SIGSEGV, 6 SIGABRT.
        val status = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_SIGNALED) {
            "signal ${e.status}${SIGNALS[e.status]?.let { " ($it)" } ?: ""}"
        } else {
            "status ${e.status}"
        }
        return "${format(e.timestamp)} $reason, $status, pid ${e.pid}${e.description?.let { ", $it" } ?: ""}"
    }

    @RequiresApi(30)
    private fun exitFor(context: Context, pid: Int): ApplicationExitInfo? {
        if (pid == 0) return null
        return try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun cpuinfo(): List<String> = try {
        File("/proc/cpuinfo").readLines()
    } catch (e: Exception) {
        emptyList()
    }

    /** Each distinct core design and how many of it, from /proc/cpuinfo. */
    private fun coreTypes(): String? {
        var implementer = ""
        val cores = mutableListOf<String>()
        for (l in cpuinfo()) {
            val value = l.substringAfter(':').trim()
            if (l.startsWith("CPU implementer")) implementer = value
            if (l.startsWith("CPU part")) {
                val key = "${implementer.removePrefix("0x")}:${value.removePrefix("0x")}"
                cores += CORES[key]?.let { "$it ($key)" } ?: key
            }
        }
        if (cores.isEmpty()) return null
        return cores.groupingBy { it }.eachCount().entries.joinToString { "${it.value}× ${it.key}" }
    }

    /** This app's recent warnings and errors, which include a crashed run's "Fatal signal" line. */
    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "400", "*:W").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines.takeLast(120).joinToString("\n", postfix = "\n") { it.take(300) }
    } catch (e: Exception) {
        "could not read: $e\n"
    }

    private fun modelName(context: Context): String = when {
        !Model.installed(context) -> "none"
        else -> Model.installedSpec(context)?.title ?: "imported checkpoint"
    }

    private fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun now() = format(System.currentTimeMillis())
    private fun format(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))

    private const val MB = 1024 * 1024

    private val CRASH_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_UNKNOWN,
    )

    private val EXIT_REASONS = mapOf(
        0 to "unknown", 1 to "exited itself", 2 to "signaled", 3 to "low memory", 4 to "Java crash",
        5 to "native crash", 6 to "ANR", 7 to "init failure", 8 to "permission change",
        9 to "excessive resources", 10 to "user requested", 11 to "user stopped", 12 to "dependency died",
        13 to "other", 14 to "freezer", 15 to "package state change", 16 to "package updated",
    )

    private val SIGNALS = mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 9 to "SIGKILL", 11 to "SIGSEGV")

    // implementer:part from /proc/cpuinfo. 41 is Arm, 51 Qualcomm's Kryo (named by the Arm core inside).
    private val CORES = mapOf(
        "41:d03" to "Cortex-A53", "41:d04" to "Cortex-A35", "41:d05" to "Cortex-A55", "41:d07" to "Cortex-A57",
        "41:d08" to "Cortex-A72", "41:d09" to "Cortex-A73", "41:d0a" to "Cortex-A75", "41:d0b" to "Cortex-A76",
        "41:d0d" to "Cortex-A77", "41:d41" to "Cortex-A78", "41:d44" to "Cortex-X1", "41:d46" to "Cortex-A510",
        "41:d47" to "Cortex-A710", "41:d48" to "Cortex-X2", "41:d4d" to "Cortex-A715", "41:d4e" to "Cortex-X3",
        "41:d80" to "Cortex-A520", "41:d81" to "Cortex-A720", "41:d82" to "Cortex-X4", "41:d85" to "Cortex-X925",
        "41:d87" to "Cortex-A725", "51:800" to "Kryo Gold (A73)", "51:801" to "Kryo Silver (A53)",
        "51:802" to "Kryo Gold (A75)", "51:803" to "Kryo Silver (A55)", "51:804" to "Kryo Gold (A76)",
        "51:805" to "Kryo Silver (A55)",
    )
}
