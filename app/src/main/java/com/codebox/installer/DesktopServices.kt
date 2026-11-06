package com.codebox.installer

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class DesktopServices(private val context: Context, private val runner: ProotRunner) {
    private val env = "export DISPLAY=:1 HOME=/root BROWSER=/usr/local/bin/xdg-open; "
    private val codeCmd = "code --no-sandbox --disable-gpu --disable-dev-shm-usage " +
        "--disable-ipv6 --no-proxy-server --disable-smooth-scrolling --renderer-process-limit=2 " +
        "--disable-site-isolation-trials --disable-background-timer-throttling --disable-renderer-backgrounding " +
        "--js-flags=--max-old-space-size=512 --user-data-dir=/root/.vsc --password-store=basic /root/work >/tmp/code.log 2>&1"

    fun isUp(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5901), 300) }
        true
    } catch (e: Exception) {
        false
    }

    private fun writeSettings() {
        File(context.filesDir, "rootfs/root/work").mkdirs()
        val d = File(context.filesDir, "rootfs/root/.vsc/User")
        d.mkdirs()
        val f = File(d, "settings.json")
        if (f.exists()) return
        f.writeText("""{
  "workbench.colorTheme": "Default Dark Modern",
  "editor.fontWeight": "500",
  "security.workspace.trust.enabled": false,
  "chat.disableAIFeatures": true,
  "workbench.reduceMotion": "on",
  "workbench.startupEditor": "none",
  "editor.smoothScrolling": false,
  "editor.cursorSmoothCaretAnimation": "off",
  "editor.minimap.enabled": false,
  "workbench.list.smoothScrolling": false,
  "terminal.integrated.smoothScrolling": false,
  "terminal.integrated.gpuAcceleration": "off",
  "http.proxySupport": "off",
  "telemetry.telemetryLevel": "off",
  "update.mode": "none",
  "extensions.autoUpdate": false
}
""")
    }

    private fun writeRc() {
        writeSettings()
        val dir = File(context.filesDir, "rootfs/root/.config/openbox")
        dir.mkdirs()
        File(dir, "rc.xml").writeText(
            """<?xml version="1.0"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
<applications>
<application class="*"><decor>no</decor><maximized>yes</maximized></application>
</applications>
</openbox_config>
"""
        )
    }

    private fun writeShim() {
        val f = File(context.filesDir, "rootfs/usr/local/bin/xdg-open")
        f.parentFile?.mkdirs()
        f.writeText("#!/bin/sh\necho \"\$1\" > /tmp/open_url.txt\n")
        f.setExecutable(true, false)
    }

    fun start(w: Int, h: Int): Boolean {
        writeRc()
        writeShim()
        if (isUp()) {
            runner.startBackground(env + "exec " + codeCmd)
            return true
        }
        val sh = env +
            "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1; " +
            "Xtigervnc :1 -geometry ${w}x${h} -depth 24 -SecurityTypes None -localhost -rfbport 5901 -AlwaysShared >/tmp/xvnc.log 2>&1 & " +
            "for i in \$(seq 1 60); do [ -S /tmp/.X11-unix/X1 ] && break; sleep 0.5; done; " +
            "(openbox >/tmp/openbox.log 2>&1 || (sleep 2; openbox >>/tmp/openbox.log 2>&1)) & sleep 2; mkdir -p /root/work; (vncconfig -nowin >/tmp/vncconfig.log 2>&1 &); exec " + codeCmd
        runner.startBackground(sh)
        for (i in 0 until 60) {
            if (isUp()) return true
            Thread.sleep(500)
        }
        return false
    }
}
