package com.easyhomework.app.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crash"
    private const val LATEST_CRASH_FILE = "latest_crash.txt"
    private const val SAFE_LAUNCH_MARKER_FILE = "safe_launch_marker.properties"
    private const val PREFS_NAME = "easyhomework_crash_reporter"
    private const val KEY_STAGE = "last_launch_stage"
    private const val MARKER_CRASH_ID = "crashId"
    private const val MARKER_STAGE = "stage"
    private const val MARKER_TIME = "time"
    private const val MARKER_VERSION_CODE = "versionCode"
    private const val MARKER_REQUIRES_SAFE_LAUNCH = "requiresSafeLaunch"
    private const val STAGE_MAIN_LAUNCH_STABLE = "main_activity_stable"

    private val installed = AtomicBoolean(false)
    private val currentStage = AtomicReference("process_start")

    fun install(context: Context) {
        val appContext = context.applicationContext
        if (!installed.compareAndSet(false, true)) return

        currentStage.set(readPersistedStage(appContext))
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(appContext, thread, throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun shouldShowSafeLaunch(context: Context): Boolean {
        return runCatching {
            val marker = safeLaunchMarkerFile(context.applicationContext)
            if (!marker.exists()) return@runCatching false

            val properties = Properties()
            marker.inputStream().use { properties.load(it) }
            properties.getProperty(MARKER_REQUIRES_SAFE_LAUNCH)?.toBooleanStrictOrNull() == true
        }.getOrDefault(false)
    }

    fun markLaunchAttempt(context: Context) {
        setStage(context, "main_launch_attempt")
    }

    fun markMainLaunchStable(context: Context) {
        setStage(context, STAGE_MAIN_LAUNCH_STABLE)
        runCatching { safeLaunchMarkerFile(context.applicationContext).delete() }
    }

    fun setStage(context: Context, stage: String) {
        currentStage.set(stage)
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STAGE, stage)
                .apply()
        }.onFailure {
            Log.w(TAG, "Could not persist launch stage.", it)
        }
    }

    fun readLatestCrash(context: Context): String? {
        return runCatching {
            val file = internalCrashFile(context.applicationContext)
            if (file.exists()) file.readText() else null
        }.getOrNull()
    }

    fun clearLatestCrash(context: Context) {
        val appContext = context.applicationContext
        runCatching { internalCrashFile(appContext).delete() }
        runCatching { externalCrashFile(appContext)?.delete() }
        runCatching { safeLaunchMarkerFile(appContext).delete() }
    }

    fun latestCrashPath(context: Context): String {
        return internalCrashFile(context.applicationContext).absolutePath
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val stage = currentStage.get()
        val versionCode = packageVersionCode(context)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val report = buildReport(context, thread, throwable)
        runCatching {
            val file = internalCrashFile(context)
            file.parentFile?.mkdirs()
            file.writeText(report)
        }.onFailure {
            Log.e(TAG, "Could not write internal crash report.", it)
        }
        runCatching {
            val file = externalCrashFile(context) ?: return@runCatching
            file.parentFile?.mkdirs()
            file.writeText(report)
        }.onFailure {
            Log.e(TAG, "Could not write external crash report.", it)
        }
        if (requiresSafeLaunch(stage)) {
            writeSafeLaunchMarker(
                context = context,
                crashId = "${System.currentTimeMillis()}-${Process.myPid()}",
                stage = stage,
                timestamp = timestamp,
                versionCode = versionCode,
            )
        }
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = packageVersionCode(context)
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())

        return buildString {
            appendLine("EasyHomework crash report")
            appendLine("time=$timestamp")
            appendLine("package=${context.packageName}")
            appendLine("versionName=${packageInfo?.versionName ?: "unknown"}")
            appendLine("versionCode=$versionCode")
            appendLine("thread=${thread.name}")
            appendLine("stage=${currentStage.get()}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine(stackTrace)
        }
    }

    private fun writeSafeLaunchMarker(
        context: Context,
        crashId: String,
        stage: String,
        timestamp: String,
        versionCode: String,
    ) {
        runCatching {
            val file = safeLaunchMarkerFile(context.applicationContext)
            file.parentFile?.mkdirs()
            val properties = Properties().apply {
                setProperty(MARKER_CRASH_ID, crashId)
                setProperty(MARKER_STAGE, stage)
                setProperty(MARKER_TIME, timestamp)
                setProperty(MARKER_VERSION_CODE, versionCode)
                setProperty(MARKER_REQUIRES_SAFE_LAUNCH, true.toString())
            }
            file.outputStream().use { properties.store(it, "EasyHomework safe launch marker") }
        }.onFailure {
            Log.e(TAG, "Could not write safe launch marker.", it)
        }
    }

    private fun requiresSafeLaunch(stage: String): Boolean {
        return when (stage) {
            "process_start",
            "crash_init_provider",
            "application_on_create",
            "application_ready",
            "safe_launch_on_create",
            "safe_launch_route_to_main",
            "safe_launch_ready",
            "safe_launch_open_main",
            "main_launch_attempt",
            "main_activity_on_create",
            "main_activity_set_content",
            "main_activity_ready",
            -> true
            STAGE_MAIN_LAUNCH_STABLE -> false
            else -> false
        }
    }

    private fun packageVersionCode(context: Context): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return packageInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toString()
            }
        } ?: "unknown"
    }

    private fun readPersistedStage(context: Context): String {
        return runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STAGE, "process_start")
                ?: "process_start"
        }.getOrDefault("process_start")
    }

    private fun internalCrashFile(context: Context): File {
        return File(File(context.filesDir, CRASH_DIR), LATEST_CRASH_FILE)
    }

    private fun safeLaunchMarkerFile(context: Context): File {
        return File(File(context.filesDir, CRASH_DIR), SAFE_LAUNCH_MARKER_FILE)
    }

    private fun externalCrashFile(context: Context): File? {
        return context.getExternalFilesDir(CRASH_DIR)?.let { File(it, LATEST_CRASH_FILE) }
    }
}
