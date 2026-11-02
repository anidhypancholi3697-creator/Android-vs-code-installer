package com.codebox.installer

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.ArrayDeque
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var runner: ProotRunner
    private lateinit var setupManager: SetupManager

    private lateinit var outputTextView: TextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var commandEditText: EditText
    private lateinit var bottomStatusBar: TextView

    private val outputLines = ArrayDeque<String>()
    private var isSessionReady = false

    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusMetrics()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runner = ProotRunner(this)
        setupManager = SetupManager(this, runner)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // TOP bar: buttons INSTALL, START, STOP, RESET
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 8, 8, 8)
        }

        val btnInstall = createTopButton("INSTALL") {
            appendOutput("[*] Starting installation...")
            thread {
                try {
                    setupManager.install { line -> appendOutput(line) }
                    appendOutput("[*] Installation completed.")
                } catch (e: Exception) {
                    appendOutput("[!] Installation error: ${e.message}")
                }
            }
        }

        val btnStart = createTopButton("START") {
            isSessionReady = true
            appendOutput("[*] Bash shell session ready. You can now execute commands.")
        }

        val btnStop = createTopButton("STOP") {
            runner.stopAll()
            isSessionReady = false
            appendOutput("[*] Stopped all running processes.")
        }

        val btnReset = createTopButton("RESET") {
            AlertDialog.Builder(this)
                .setTitle("Confirm Reset")
                .setMessage("Are you sure you want to delete the rootfs and all marker files?")
                .setPositiveButton("Reset") { _, _ ->
                    isSessionReady = false
                    setupManager.reset()
                    appendOutput("[*] Reset complete. rootfs and marker files deleted.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        topBar.addView(btnInstall)
        topBar.addView(btnStart)
        topBar.addView(btnStop)
        topBar.addView(btnReset)
        rootLayout.addView(topBar)

        // MIDDLE: takes all remaining space
        val middleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(8, 0, 8, 0)
        }

        outputScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.BLACK)
        }

        outputTextView = TextView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(0, 255, 0))
            textSize = 12f
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
        }
        outputScrollView.addView(outputTextView)
        middleLayout.addView(outputScrollView)

        // Command input bar: one-line EditText and RUN button
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 4, 0, 4)
        }

        commandEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Enter bash command..."
            isSingleLine = true
            textSize = 14f
        }

        val runButton = Button(this).apply {
            text = "RUN"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            setOnClickListener {
                val command = commandEditText.text.toString().trim()
                if (command.isEmpty()) return@setOnClickListener

                if (!isSessionReady) {
                    appendOutput("[!] Session not ready. Press START first.")
                    return@setOnClickListener
                }

                commandEditText.text.clear()
                appendOutput("$ $command")
                thread {
                    try {
                        val exitCode = runner.run(command) { line -> appendOutput(line) }
                        appendOutput("[exit code: $exitCode]")
                    } catch (e: Exception) {
                        appendOutput("[!] Error executing command: ${e.message}")
                    }
                }
            }
        }

        inputBar.addView(commandEditText)
        inputBar.addView(runButton)
        middleLayout.addView(inputBar)

        rootLayout.addView(middleLayout)

        // BOTTOM bar: battery percent and RAM used/total
        bottomStatusBar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setPadding(12, 12, 12, 12)
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        rootLayout.addView(bottomStatusBar)

        setContentView(rootLayout)

        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            val crashContent = crashFile.readText()
            crashFile.delete()
            appendOutput("LAST CRASH: $crashContent")
        }

        appendOutput("Codebox Installer ready.")
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun createTopButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            textSize = 12f
            setOnClickListener { onClick() }
        }
    }

    private fun appendOutput(line: String) {
        runOnUiThread {
            val lines = line.split("\n")
            for (l in lines) {
                if (outputLines.size >= 500) {
                    outputLines.removeFirst()
                }
                outputLines.addLast(l)
            }
            outputTextView.text = outputLines.joinToString("\n")
            outputScrollView.post {
                outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun updateStatusMetrics() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalMb = memoryInfo.totalMem / (1024 * 1024)
        val availMb = memoryInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val freeRatio = if (memoryInfo.totalMem > 0) {
            (memoryInfo.availMem.toDouble() / memoryInfo.totalMem.toDouble()) * 100.0
        } else {
            100.0
        }

        val isLowRam = freeRatio < 15.0
        bottomStatusBar.setTextColor(if (isLowRam) Color.RED else Color.WHITE)
        val batteryText = if (batteryPercent >= 0) "$batteryPercent%" else "N/A"
        bottomStatusBar.text = "Battery: $batteryText | RAM: ${usedMb}MB / ${totalMb}MB (${String.format("%.1f", freeRatio)}% free)"
    }
}
