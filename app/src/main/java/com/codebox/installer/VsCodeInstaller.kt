package com.codebox.installer

import android.content.Context
import java.io.File

class VsCodeInstaller(private val context: Context, private val runner: ProotRunner) {

    private val apt = "-o APT::Sandbox::User=root -o Acquire::Retries=3 -o DPkg::Lock::Timeout=900"

    private fun step(onLine: (String) -> Unit, title: String, cmd: String): Boolean {
        onLine("[*] $title")
        val code = runner.run(cmd, onLine)
        if (code != 0) {
            onLine("[!] Failed (exit code $code): $title")
            return false
        }
        return true
    }

    fun install(debPath: String, onLine: (String) -> Unit): Boolean {
        onLine("[*] Checking the CPU type of the file")
        val out = StringBuilder()
        val c = runner.run("dpkg-deb -f $debPath Architecture") { line -> out.append(line).append(' ') }
        val arch = out.toString().trim()
        if (c != 0 || arch.isEmpty()) {
            onLine("[!] Could not read this file. Is it a valid .deb?")
            return false
        }
        onLine("    File CPU: $arch")
        if (!arch.startsWith("arm64")) {
            onLine("[!] This file is for another CPU ($arch). Download the ARM64 .deb from code.visualstudio.com (Other downloads).")
            return false
        }

        if (!step(onLine, "apt-get update", "apt-get $apt update")) return false
        if (!step(onLine, "Installing desktop packages",
                "apt-get $apt install -y tigervnc-standalone-server openbox xterm unclutter git")) return false
        if (!step(onLine, "Preparing VS Code settings",
                "echo 'code code/add-microsoft-repo boolean false' | debconf-set-selections")) return false
        if (!step(onLine, "Installing VS Code (this takes a while)",
                "apt-get $apt install -y $debPath")) return false
        if (!step(onLine, "Checking VS Code", "test -x /usr/bin/code")) return false

        runner.run("rm -f $debPath; apt-get clean", onLine)
        File(context.filesDir, "vscode_installed").writeText("ok")
        onLine("[*] VS Code installed OK")
        return true
    }
}
