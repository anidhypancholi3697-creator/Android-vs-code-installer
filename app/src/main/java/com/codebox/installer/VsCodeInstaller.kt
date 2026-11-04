package com.codebox.installer

import android.content.Context
import java.io.File

class VsCodeInstaller(
    private val context: Context,
    private val runner: ProotRunner
) {

    fun install(debPath: String, onLine: (String) -> Unit): Boolean {
        // Step 1: Check CPU architecture
        onLine("Checking architecture of $debPath...")
        val archResult = runner.run("dpkg-deb -f $debPath Architecture")
        if (archResult.code != 0) {
            onLine("Failed to check architecture.")
            return false
        }
        val arch = archResult.output.trim()
        if (arch != "arm64") {
            onLine("This file is for another CPU. Download the ARM64 deb from code.visualstudio.com Other downloads.")
            return false
        }
        onLine("Architecture is arm64.")

        // Step 2: apt-get update
        onLine("Updating package lists...")
        val updateResult = runner.run("apt-get update -o APT::Sandbox::User=root -o Acquire::Retries=3")
        if (updateResult.code != 0) {
            onLine("apt-get update failed.")
            return false
        }

        // Step 3: Install dependencies
        onLine("Installing dependencies (tigervnc-standalone-server, openbox, xterm, unclutter)...")
        val depResult = runner.run("apt-get install -y tigervnc-standalone-server openbox xterm unclutter -o APT::Sandbox::User=root -o Acquire::Retries=3")
        if (depResult.code != 0) {
            onLine("Failed to install dependencies.")
            return false
        }

        // Step 4: Set debconf selections
        onLine("Setting debconf selections...")
        val debconfResult = runner.run("echo 'code code/add-microsoft-repo boolean false' | debconf-set-selections")
        if (debconfResult.code != 0) {
            onLine("Failed to set debconf selections.")
            return false
        }

        // Step 5: Install VS Code deb
        onLine("Installing VS Code from $debPath...")
        val installResult = runner.run("apt-get install -y $debPath -o APT::Sandbox::User=root -o Acquire::Retries=3")
        if (installResult.code != 0) {
            onLine("Failed to install VS Code.")
            return false
        }

        // Step 6: Verify, cleanup, and mark as installed
        onLine("Verifying installation...")
        val checkResult = runner.run("test -f /usr/bin/code")
        if (checkResult.code != 0) {
            onLine("VS Code binary not found at /usr/bin/code.")
            return false
        }

        onLine("Cleaning up...")
        runner.run("rm -f $debPath")
        runner.run("apt-get clean")

        val markerFile = File(context.filesDir, "vscode_installed")
        markerFile.writeText("installed")

        onLine("VS Code installed OK")
        return true
    }
}
