package com.privatemsg.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * Tracks whether any activity of the app is currently visible (started). Used by
 * the hidden section to detect when the app goes to the background or the screen
 * locks, so it can close itself and return to the main screen.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Older builds copied hidden-chat favorites into a plain-text database. That
        // file is no longer written; removing it deletes the leftover readable copies.
        Thread {
            try {
                deleteDatabase("favorites.db")
            } catch (_: Exception) {}
        }.start()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Save the reason a screen closed on its own, so the next start can show it. There
        // is no other way to learn where a crash came from without a PC attached, and a
        // stack trace is the difference between fixing a cause and guessing at a symptom.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sb = StringBuilder()
                sb.append(error.javaClass.name)
                error.message?.let { sb.append(": ").append(it) }
                sb.append('\n')
                for (frame in error.stackTrace.take(12)) sb.append("  at ").append(frame).append('\n')
                // A plain file, not preferences: a dying thread may already hold the
                // preference lock, and waiting on it would hang the app instead of closing it.
                crashFile(this).writeText("$thread.name: $sb")
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    companion object {
        private var started = 0
        /** True while at least one activity is visible (the app is in the foreground). */
        val inForeground: Boolean get() = started > 0

        private fun crashFile(context: Context) =
            java.io.File(context.applicationContext.filesDir, "crash_last.txt")

        /** The stored crash trace, removed as it is read so it is reported only once. */
        fun takeLastCrash(context: Context): String? {
            val file = crashFile(context)
            if (!file.exists()) return null
            val trace = runCatching { file.readText().take(3000) }.getOrNull()
            runCatching { file.delete() }
            return trace?.takeIf { it.isNotBlank() }
        }
    }
}
