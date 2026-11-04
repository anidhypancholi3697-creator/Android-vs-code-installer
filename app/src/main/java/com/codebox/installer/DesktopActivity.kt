package com.codebox.installer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class DesktopActivity : Activity() {
    private lateinit var screen: ScreenView
    private lateinit var status: TextView
    private lateinit var ctrlBtn: Button
    private var kbOn = false
    private val runner by lazy { ProotRunner(this) }

    private val vnc = VncClient(
        {
            runOnUiThread {
                if (::status.isInitialized && status.visibility == View.VISIBLE) status.visibility = View.GONE
                if (::screen.isInitialized) screen.invalidate()
            }
        },
        { msg ->
            runOnUiThread {
                if (::status.isInitialized) {
                    status.visibility = View.VISIBLE
                    status.text = "Screen band: $msg\nCLOSE dabao, phir VS Code icon dobara kholo."
                }
            }
        }
    )

    private fun btn(t: String, f: () -> Unit): Button = Button(this).apply {
        text = t
        textSize = 12f
        setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dm = resources.displayMetrics
        val big = maxOf(dm.widthPixels, dm.heightPixels)
        val small = minOf(dm.widthPixels, dm.heightPixels)
        val dw = 1280
        val dh = ((dw.toDouble() * (small - 52 * dm.density) / big).toInt().coerceIn(480, 1000)) / 2 * 2

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val frame = FrameLayout(this)
        screen = ScreenView(this, vnc)
        status = TextView(this)
        status.text = "VS Code start ho raha hai… (pehli baar 30-60 sec)"
        status.setTextColor(Color.GREEN)
        status.gravity = Gravity.CENTER
        status.textSize = 16f
        frame.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("KB") {
            if (kbOn) screen.hideKb() else screen.showKb()
            kbOn = !kbOn
        })
        row.addView(btn("ESC") { screen.sendKey(0xff1b) })
        row.addView(btn("TAB") { screen.sendKey(0xff09) })
        ctrlBtn = btn("CTRL") {
            screen.ctrl = !screen.ctrl
            ctrlBtn.text = if (screen.ctrl) "CTRL ON" else "CTRL"
        }
        screen.onCtrlReset = { ctrlBtn.text = "CTRL" }
        row.addView(ctrlBtn)
        row.addView(btn("←") { screen.sendKey(0xff51) })
        row.addView(btn("↑") { screen.sendKey(0xff52) })
        row.addView(btn("↓") { screen.sendKey(0xff54) })
        row.addView(btn("→") { screen.sendKey(0xff53) })
        row.addView(btn("CLOSE") { finish() })
        row.addView(btn("STOP") { stopAll() })
        val hs = HorizontalScrollView(this)
        hs.addView(row)
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        Thread {
            if (!File(filesDir, "rootfs/usr/share/code/bin/code").exists()) {
                runOnUiThread { status.text = "VS Code install nahi hai.\nPehle .deb file: Open with → Codebox Installer." }
                return@Thread
            }
            val ok = DesktopServices(this, runner).start(dw, dh)
            runOnUiThread {
                if (ok) {
                    status.text = "Screen connect ho rahi hai…"
                    vnc.connect(5901)
                } else {
                    status.text = "Desktop start nahi hua.\nMain app command box me:\ntail -5 /tmp/xvnc.log /tmp/code.log"
                }
            }
        }.start()
    }

    private fun stopAll() {
        status.visibility = View.VISIBLE
        status.text = "Band ho raha hai…"
        Thread {
            try {
                runner.run("kill \$(cat /tmp/.X1-lock) 2>/dev/null; true") { _ -> }
            } catch (e: Exception) {
            }
            runner.stopAll()
            runOnUiThread { finish() }
        }.start()
    }

    override fun onDestroy() {
        vnc.close()
        super.onDestroy()
    }
}
