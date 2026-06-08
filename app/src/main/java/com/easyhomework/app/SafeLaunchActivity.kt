package com.easyhomework.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.easyhomework.app.ui.theme.neutralPalette
import com.easyhomework.app.util.CrashReporter

class SafeLaunchActivity : Activity() {
    private lateinit var crashInfo: TextView
    private lateinit var crashPreview: TextView
    private var diagnosticsVisible = false
    private val palette by lazy { neutralPalette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.setStage(this, "safe_launch_on_create")
        super.onCreate(savedInstanceState)
        if (!CrashReporter.shouldShowSafeLaunch(this)) {
            routeToMain("safe_launch_route_to_main")
            return
        }
        diagnosticsVisible = true
        buildUi()
        refreshCrashInfo()
        CrashReporter.setStage(this, "safe_launch_ready")
    }

    override fun onResume() {
        super.onResume()
        if (diagnosticsVisible) {
            refreshCrashInfo()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(palette.background)
        }

        val title = TextView(this).apply {
            text = "EasyHomework 安全启动"
            setTextColor(palette.onSurface)
            textSize = 24f
            gravity = Gravity.START
        }
        root.addView(title, matchWrapParams())

        val subtitle = TextView(this).apply {
            text = "如果主界面仍闪退，重新打开 App 后可在这里复制诊断日志。"
            setTextColor(palette.onSurfaceVariant)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(subtitle, matchWrapParams())

        root.addView(
            createButton("进入主界面") {
                routeToMain("safe_launch_open_main")
            },
            matchWrapParams(),
        )

        root.addView(
            createButton("复制诊断日志") {
                copyCrashReport()
            },
            matchWrapParams(),
        )

        root.addView(
            createButton("清除诊断日志") {
                CrashReporter.clearLatestCrash(this)
                refreshCrashInfo()
                Toast.makeText(this, "诊断日志已清除", Toast.LENGTH_SHORT).show()
            },
            matchWrapParams(),
        )

        crashInfo = TextView(this).apply {
            setTextColor(palette.onSurface)
            textSize = 13f
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(crashInfo, matchWrapParams())

        crashPreview = TextView(this).apply {
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(palette.surfaceContainer)
        }

        val scrollView = ScrollView(this).apply {
            addView(crashPreview)
        }
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refreshCrashInfo() {
        val report = CrashReporter.readLatestCrash(this)
        val path = CrashReporter.latestCrashPath(this)
        if (report.isNullOrBlank()) {
            crashInfo.text = "暂无诊断日志\n$path"
            crashPreview.text = "没有记录到 Java 崩溃栈。"
        } else {
            crashInfo.text = "已记录诊断日志\n$path"
            crashPreview.text = report.take(8_000)
        }
    }

    private fun copyCrashReport() {
        val report = CrashReporter.readLatestCrash(this)
        if (report.isNullOrBlank()) {
            Toast.makeText(this, "暂无诊断日志", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EasyHomework crash report", report))
        Toast.makeText(this, "诊断日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun routeToMain(stage: String) {
        CrashReporter.setStage(this, stage)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun createButton(text: String, onClick: View.OnClickListener): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            setTextColor(palette.onPrimary)
            background = GradientDrawable().apply {
                setColor(palette.primary)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener(onClick)
        }
    }

    private fun matchWrapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(8)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
