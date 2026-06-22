package com.origin.launcher.Launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import com.origin.launcher.BuildConfig
import com.origin.launcher.utils.FeatureSettings
import xcrash.ICrashCallback
import xcrash.XCrash
import java.io.File
import android.content.Intent
import com.origin.launcher.activity.CrashActivity
import com.origin.launcher.manager.LogcatOverlayManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        FeatureSettings.init(applicationContext)
        LogcatOverlayManager.init(this)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)

        val callback: ICrashCallback = ICrashCallback { logPath, emergency ->
            try {
                val i = Intent(applicationContext, CrashActivity::class.java).apply {
                    putExtra("LOG_PATH", logPath)
                    putExtra("EMERGENCY", emergency)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                applicationContext.startActivity(i)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        XCrash.init(this, XCrash.InitParameters().apply {
            setAppVersion(BuildConfig.VERSION_NAME)
            setLogDir(File( Environment.getExternalStorageDirectory(), "games/xelo_client/crash_logs").absolutePath)
            setNativeCallback(callback)
            setJavaCallback(callback)
            setAnrCallback(callback)
            setJavaRethrow(false)
            setNativeRethrow(false)
            setAnrRethrow(false)
        })

        cleanupOldTombstones()

        try {
            System.loadLibrary("xelo_init")
            val modsDir = File(cacheDir, "mods")
            if (!modsDir.exists()) modsDir.mkdirs()
            Log.d("LauncherApplication", "Mods path: ${modsDir.absolutePath}")
            nativeSetupRuntime(modsDir.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Keep only the single most-recent xCrash tombstone, delete everything older. */
    private fun cleanupOldTombstones() {
        try {
            val crashDir = File(Environment.getExternalStorageDirectory(), "games/xelo_client/crash_logs")
            if (!crashDir.exists()) return
            val files = crashDir.listFiles() ?: return
            if (files.size <= 1) return
            // Sort newest first by lastModified, keep index 0, delete the rest
            files.sortedByDescending { it.lastModified() }
                .drop(1)
                .forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("LauncherApplication", "Failed to clean up old tombstones: ${e.message}")
        }
    }

    external fun nativeSetupRuntime(modsPath: String)

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}