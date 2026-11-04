package com.codebox.installer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

class DebInstallActivity : Activity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        logTextView = TextView(this).apply {
            setTextColor(Color.GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(8, 8, 8, 8)
        }

        scrollView.addView(logTextView)

        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        rootLayout.addView(closeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(rootLayout)

        val uri = intent.data
        if (uri == null) {
            appendLog("Error: No URI provided in intent data.")
            return
        }

        Thread {
            try {
                val tmpDir = File(filesDir, "rootfs/tmp")
                if (!tmpDir.exists()) {
                    tmpDir.mkdirs()
                }
                val destFile = File(tmpDir, "vscode.deb")

                appendLog("Copying file...")
                var size = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            size += bytesRead
                        }
                    }
                } ?: run {
                    appendLog("Failed to open input stream.")
                    return@Thread
                }

                val sizeMB = size / (1024.0 * 1024.0)
                appendLog("Copied ${sizeMB} MB to ${destFile.absolutePath}")

                val installer = VsCodeInstaller(this@DebInstallActivity, ProotRunner(this@DebInstallActivity))
                installer.install("/tmp/vscode.deb") { line ->
                    runOnUiThread {
                        appendLog(line)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun appendLog(message: String) {
        logTextView.append(message + "\n")
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
