package dev.kanav.phonon

import android.app.Application
import dev.kanav.phonon.engine.Diagnostics
import dev.kanav.phonon.engine.Prefs
import dev.kanav.phonon.ffi.NativeLib

/** Runs first in the one process the engine and the settings screen share. */
class PhononApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything reads NativeLib.name, which is fixed once read.
        NativeLib.forceCompatible = Prefs(this).compatibleCpu
        Diagnostics.init(this)
    }
}
