package dev.kanav.phonon

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kanav.phonon.engine.Diagnostics
import dev.kanav.phonon.ffi.NativeLib
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Debug info report, and the pause after a crashed load. Runs with or without a model. */
class DiagnosticsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportCoversDeviceCpuAndEngine() {
        val report = Diagnostics.report(context)
        // Logged in pieces, as logcat cuts long lines: read it with `adb logcat -s PhononDiag`.
        report.lines().forEach { Log.i("PhononDiag", it) }
        for (part in listOf("## Device", "## CPU", "## Engine", "lib${NativeLib.name}.so", "## Log")) {
            assertTrue("report has $part", part in report)
        }
    }

    @Test
    fun unblockLetsTheModelLoad() {
        Diagnostics.unblock(context)
        assertNull(Diagnostics.blockedLoad(context))
    }
}
