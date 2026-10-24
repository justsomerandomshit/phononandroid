package dev.kanav.phonon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import kotlin.concurrent.thread

/**
 * The one long task the app runs at a time (a download or an import), outside any activity so it
 * survives rotation and leaving the screen. Snapshot state, so the screen follows it.
 */
object Jobs {
    class Task(val label: String, val progress: Float?)

    var task: Task? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)

    /** Bumped when a task ends, so screens re-read what is on disk. */
    var revision by mutableIntStateOf(0)
        private set

    @Volatile var cancelled = false
        private set

    val busy get() = task != null

    /** Run [block] on a worker thread unless a task is running. It reports with label and progress (0..1 or null). */
    fun run(label: String, block: (report: (String, Float?) -> Unit) -> Unit): Boolean {
        synchronized(this) {
            if (task != null) return false
            task = Task(label, null)
        }
        error = null
        cancelled = false
        thread(name = "phonon-job") {
            try {
                block { l, p -> task = Task(l, p) }
            } catch (e: InterruptedException) {
                // Cancelled.
            } catch (e: Exception) {
                Log.w("PhononJobs", label, e)
                if (!cancelled) error = e.message ?: e.toString()
            } finally {
                task = null
                revision++
            }
        }
        return true
    }

    fun cancel() {
        cancelled = true
    }

    /** Re-read state from disk without running anything. */
    fun touch() {
        revision++
    }
}
