package dev.kanav.phonon.ffi

import android.os.Build
import com.sun.jna.NativeLibrary
import java.io.File

/**
 * Which build of libptts_ffi this phone runs. xn picks its kernels at compile time, so arm64
 * ships two (scripts/build-native.sh): `ptts_ffi`, which needs ARMv8.2's dot product and half
 * float instructions, and `ptts_ffi_v80` for older cores such as the Cortex-A73/A53 in the
 * Snapdragon 680 and 685, where the first dies of SIGILL as soon as a model loads.
 */
object NativeLib {
    /** Load the ARMv8.0 build even if the CPU reports dotprod and fp16. Set before the first load. */
    @Volatile var forceCompatible = false

    /** What the kernel reports in AT_HWCAP, or null when it cannot be read. */
    val hwcap: Long? by lazy {
        try {
            NativeLibrary.getInstance("c").getFunction("getauxval").invokeLong(arrayOf<Any>(AT_HWCAP))
        } catch (t: Throwable) {
            null
        }
    }

    private val arm64 get() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    /** Whether the CPU runs the ARMv8.2 build, or null when that cannot be told. */
    val hasArmV82: Boolean? by lazy {
        val caps = hwcap
        if (caps != null && caps != 0L) {
            caps and V82_CAPS == V82_CAPS
        } else {
            // No getauxval: /proc/cpuinfo lists each core's features; every core must have them.
            val lines = try {
                File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }
            } catch (e: Exception) {
                emptyList()
            }
            if (lines.isEmpty()) null
            else lines.all { line -> V82_NAMES.all { it in line.substringAfter(':').split(' ') } }
        }
    }

    /** The library [PhononTTS] loads, decided once per process. */
    val name: String by lazy {
        if (arm64 && (forceCompatible || hasArmV82 != true)) COMPATIBLE else FAST
    }

    /** Why [name] was picked, for the debug report. */
    val reason: String
        get() = when {
            !arm64 -> "not an arm64 CPU (${Build.SUPPORTED_ABIS.firstOrNull()})"
            forceCompatible -> "compatible build forced in Debug info"
            hasArmV82 == true -> "CPU has dotprod and fp16 (ARMv8.2)"
            hasArmV82 == false -> "CPU lacks dotprod or fp16 (older than ARMv8.2)"
            else -> "CPU features unknown, so the safe build"
        }

    const val FAST = "ptts_ffi"
    const val COMPATIBLE = "ptts_ffi_v80"

    private const val AT_HWCAP = 16L

    // arch/arm64/include/uapi/asm/hwcap.h: FPHP, ASIMDHP and ASIMDDP.
    private const val V82_CAPS = (1L shl 9) or (1L shl 10) or (1L shl 20)
    private val V82_NAMES = listOf("fphp", "asimdhp", "asimddp")
}
