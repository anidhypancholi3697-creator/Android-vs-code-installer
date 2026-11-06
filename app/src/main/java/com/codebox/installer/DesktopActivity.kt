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
                    status.text = "Screen band: $msg\nSTOP dabao, phir VS Code icon dobara kholo."
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
        val dw = minOf(big, 1600) / 2 * 2
        val dh = ((dw.toDouble() * small.toDouble() / big).toInt().coerceIn(480, 1200)) / 2 * 2

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        screen = ScreenView(this, vnc)
        status = TextView(this)
        status.text = "VS Code start ho raha hai… (pehli baar 30-60 sec)"
        status.setTextColor(Color.GREEN)
        status.gravity = Gravity.CENTER
        status.textSize = 16f
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        frame.addView(screen, FrameLayout.LayoutParams(mp, mp))
        frame.addView(status, FrameLayout.LayoutParams(mp, mp))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("KB") {
            if (kbOn) screen.hideKb() else screen.showKb()
            kbOn = !kbOn
        })
        row.addView(btn("ESC") { screen.sendKey(0xff1b) })
        row.addView(btn("TAB") { screen.sendKey(0xff09) })
        row.addView(btn("PASTE") {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            screen.pasteText(t)
        })
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
        hs.visibility = View.GONE
        frame.addView(hs, FrameLayout.LayoutParams(mp, wc, Gravity.BOTTOM))
        val tgl = btn("☰") { hs.visibility = if (hs.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        tgl.alpha = 0.35f
        tgl.textSize = 10f
        frame.addView(tgl, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.END))

        setContentView(frame)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        pinShortcut()
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

    private fun pinShortcut() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return
            val marker = File(filesDir, "vscode_shortcut_done")
            if (marker.exists()) return
            val f = listOf("rootfs/usr/share/pixmaps/vscode.png",
                "rootfs/usr/share/code/resources/app/resources/linux/code.png")
                .map { File(filesDir, it) }.firstOrNull { it.exists() } ?: return
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (!sm.isRequestPinShortcutSupported) return
            val bmp = android.graphics.BitmapFactory.decodeFile(f.path) ?: return
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, 192, 192, true)
            val info = android.content.pm.ShortcutInfo.Builder(this, "vscode")
                .setShortLabel("VS Code")
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(small))
                .setIntent(android.content.Intent(this, DesktopActivity::class.java)
                    .setAction(android.content.Intent.ACTION_MAIN))
                .build()
            sm.requestPinShortcut(info, null)
            marker.createNewFile()
        } catch (e: Exception) {
        }
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
