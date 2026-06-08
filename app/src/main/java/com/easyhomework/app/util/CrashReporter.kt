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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crash"
    private const val LATEST_CRASH_FILE = "latest_crash.txt"
    private const val PREFS_NAME = "easyhomework_crash_reporter"
    private const val KEY_STAGE = "last_launch_stage"

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
    }

    fun latestCrashPath(context: Context): String {
        return internalCrashFile(context.applicationContext).absolutePath
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
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
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = packageInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toString()
            }
        } ?: "unknown"
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

    private fun externalCrashFile(context: Context): File? {
        return context.getExternalFilesDir(CRASH_DIR)?.let { File(it, LATEST_CRASH_FILE) }
    }
}
