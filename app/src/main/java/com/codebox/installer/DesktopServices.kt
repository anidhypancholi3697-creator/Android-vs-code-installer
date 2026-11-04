package com.codebox.installer

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class DesktopServices(private val context: Context, private val runner: ProotRunner) {
    private val env = "export DISPLAY=:1 HOME=/root; "
    private val codeCmd = "code --no-sandbox --disable-gpu --disable-dev-shm-usage " +
        "--user-data-dir=/root/.vsc --password-store=basic /root >/tmp/code.log 2>&1"

    fun isUp(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5901), 300) }
        true
    } catch (e: Exception) {
        false
    }

    private fun writeRc() {
        val dir = File(context.filesDir, "rootfs/root/.config/openbox")
        dir.mkdirs()
        File(dir, "rc.xml").writeText(
            """<?xml version="1.0"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
<applications>
<application class="Code"><decor>no</decor><maximized>yes</maximized></application>
<application class="code"><decor>no</decor><maximized>yes</maximized></application>
</applications>
</openbox_config>
"""
        )
    }

    fun start(w: Int, h: Int): Boolean {
        writeRc()
        if (isUp()) {
            runner.startBackground(env + "exec " + codeCmd)
            return true
        }
        val sh = env +
            "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1; " +
            "Xtigervnc :1 -geometry ${w}x${h} -depth 24 -SecurityTypes None -localhost -rfbport 5901 -AlwaysShared >/tmp/xvnc.log 2>&1 & " +
            "sleep 2; openbox >/tmp/openbox.log 2>&1 & sleep 1; exec " + codeCmd
        runner.startBackground(sh)
        for (i in 0 until 60) {
            if (isUp()) return true
            Thread.sleep(500)
        }
        return false
    }
}
