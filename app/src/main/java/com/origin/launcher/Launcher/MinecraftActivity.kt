package com.origin.launcher.Launcher

import android.content.Intent
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.mojang.minecraftpe.MainActivity
import com.origin.launcher.Launcher.inbuilt.overlay.InbuiltOverlayManager
import com.origin.launcher.versions.GameVersion
import com.origin.launcher.utils.FeatureSettings
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MinecraftActivity : MainActivity() {

    private lateinit var gameManager: GamePackageManager
    private var overlayManager: InbuiltOverlayManager? = null

    // ------------------------------------------------------------------
    // Helpers: write a crash/log snapshot to the app's external files dir
    // so the user can grab it from Files without root or ADB.
    // ------------------------------------------------------------------
    private fun logDir(): File {
        val dir = File("/storage/emulated/0/Android/data/com.origin.launcher.beta/files/xelo_logs")
        dir.mkdirs()
        return dir
    }

    private fun writeLog(tag: String, message: String, throwable: Throwable? = null) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(logDir(), "xelo_crash_${stamp}.txt")
            FileWriter(file, true).use { fw ->
                fw.write("=== Xelo Client Crash Log ===\n")
                fw.write("Time   : $stamp\n")
                fw.write("Tag    : $tag\n")
                fw.write("Message: $message\n")
                if (throwable != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    fw.write("Trace  :\n$sw\n")
                }
                fw.write("=============================\n")
            }
            Log.i(TAG, "Crash log written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log: ${e.message}")
        }
    }

    private fun dumpLogcat() {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(logDir(), "xelo_logcat_${stamp}.txt")
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "*:W")
            )
            proc.inputStream.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
            proc.waitFor()
            Log.i(TAG, "Logcat dump written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump logcat: ${e.message}")
        }
    }

    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val versionDir = intent.getStringExtra("MC_PATH")
            val versionCode = intent.getStringExtra("MINECRAFT_VERSION") ?: ""
            val versionDirName = intent.getStringExtra("MINECRAFT_VERSION_DIR") ?: ""
            val isInstalled = intent.getBooleanExtra("IS_INSTALLED", false)
            val isIsolated = FeatureSettings.getInstance().isVersionIsolationEnabled()

            val version = if (isIsolated && !versionDir.isNullOrEmpty()) {
                GameVersion(
                    versionDirName,
                    versionCode,
                    versionCode,
                    File(versionDir),
                    isInstalled,
                    MinecraftLauncher.MC_PACKAGE_NAME,
                    ""
                )
            } else if (!versionCode.isNullOrEmpty()) {
                GameVersion(
                    versionDirName,
                    versionCode,
                    versionCode,
                    File(versionDir ?: ""),
                    true,
                    MinecraftLauncher.MC_PACKAGE_NAME,
                    ""
                )
            } else {
                null
            }

            gameManager = GamePackageManager.getInstance(applicationContext, version)

            try {
                System.loadLibrary("preloader")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load preloader: ${e.message}")
            }

            // Minecraft 26.30+ links libminecraftpe.so against libfmod.so (and
            // friends) directly, instead of the engine dlopen'ing them lazily
            // at its own pace like older builds did. They must already be
            // resident in the process before we dlopen libminecraftpe.so or
            // it fails with UnsatisfiedLinkError ("needed by libminecraftpe.so").
            for (dep in listOf("c++_shared", "fmod", "MediaDecoders_Android", "HttpClient.Android")) {
                if (!gameManager.loadLibrary(dep)) {
                    Log.w(TAG, "Dependency lib$dep.so failed to load, continuing anyway")
                }
            }

            if (!gameManager.loadLibrary("minecraftpe")) {
                throw RuntimeException("Failed to load libminecraftpe.so")
            }
        } catch (e: Exception) {
            writeLog("onCreate/loadLibrary", e.message ?: "unknown error", e)
            dumpLogcat()
            Toast.makeText(this, "Failed to load game: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Wrap super.onCreate so any engine-level crash is also captured
        try {
            super.onCreate(savedInstanceState)
        } catch (e: Exception) {
            writeLog("super.onCreate (engine crash)", e.message ?: "engine threw", e)
            dumpLogcat()
            finish()
            return
        } catch (e: Throwable) {
            // UnsatisfiedLinkError, OutOfMemoryError, etc.
            writeLog("super.onCreate (engine fatal)", e.message ?: "engine fatal", e)
            dumpLogcat()
            finish()
            return
        }

        MinecraftActivityState.onCreated(this)
    }

    private fun startInbuiltModServices() {
        // ThemeManager.getInstance() (no-arg) throws if never seeded with a
        // Context. BaseOverlayButton.applyThemedIcon() calls the no-arg form,
        // so we must seed it here before creating any overlays.
        try {
            com.origin.launcher.manager.ThemeManager.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "ThemeManager init failed: ${e.message}")
        }
        overlayManager = InbuiltOverlayManager(this)
        overlayManager?.showEnabledOverlays()
    }

    private fun stopInbuiltModServices() {
        overlayManager?.hideAllOverlays()
        overlayManager = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MinecraftActivityState.onResumed()

        if (overlayManager == null) {
            startInbuiltModServices()
        }
    }
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        overlayManager?.let { manager ->
            if (manager.handleKeyEvent(event.keyCode, event.action)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        overlayManager?.handleTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_BUTTON_PRESS || 
                event.action == MotionEvent.ACTION_BUTTON_RELEASE) {
            overlayManager?.handleMouseEvent(event)
        }
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                overlayManager?.let { manager ->
                    if (manager.handleScrollEvent(vScroll)) {
                        return true
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        MinecraftActivityState.onPaused()
        super.onPause()
    }

    override fun onDestroy() {
        // Dump logcat before killing the process so the log survives.
        dumpLogcat()

        MinecraftActivityState.onDestroyed()
        stopInbuiltModServices()
        super.onDestroy()

        val intent = Intent(applicationContext, com.origin.launcher.activity.MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)

        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun getAssets(): AssetManager {
        return if (::gameManager.isInitialized) {
            gameManager.getAssets()
        } else {
            super.getAssets()
        }
    }

    override fun getFilesDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val filesDir = File(mcPath, "games/com.mojang")
            if (!filesDir.exists()) {
                filesDir.mkdirs()
            }
            filesDir
        } else {
            super.getFilesDir()
        }
    }
    
    override fun tick() {
        super.tick()
        overlayManager?.tick()
    }

    override fun getDataDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val dataDir = File(mcPath)
            if (!dataDir.exists()) {
                dataDir.mkdirs()
            }
            dataDir
        } else {
            super.getDataDir()
        }
    }

    override fun getExternalFilesDir(type: String?): File? {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val externalDir = if (type != null) {
                File(mcPath, "games/com.mojang/$type")
            } else {
                File(mcPath, "games/com.mojang")
            }
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            externalDir
        } else {
            super.getExternalFilesDir(type)
        }
    }

    override fun getDatabasePath(name: String): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val dbDir = File(mcPath, "databases")
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }
            File(dbDir, name)
        } else {
            super.getDatabasePath(name)
        }
    }

    override fun getCacheDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val cacheDir = File(mcPath, "cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            cacheDir
        } else {
            super.getCacheDir()
        }
    }

    companion object {
        private const val TAG = "MinecraftActivity"
    }
}
