package com.codebox.installer

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ProotRunner(private val context: Context) {
    val rootfsDir: File = File(context.filesDir, "rootfs")
    private val processes = CopyOnWriteArrayList<Process>()

    private fun createProcessBuilder(command: String): ProcessBuilder {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val prootBin = File(nativeLibraryDir, "libproot.so").absolutePath
        val loader = File(nativeLibraryDir, "libproot-loader.so").absolutePath
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }

        val cmd = listOf(
            prootBin,
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash",
            "-c",
            command
        )

        val pb = ProcessBuilder(cmd)
        val env = pb.environment()
        val toRemove = env.keys.filter { key ->
            key.startsWith("ANDROID_") ||
                key == "EXTERNAL_STORAGE" ||
                key == "BOOTCLASSPATH" ||
                key == "DEX2OATBOOTCLASSPATH" ||
                key == "SYSTEMSERVERCLASSPATH"
        }
        toRemove.forEach { env.remove(it) }

        env["LD_LIBRARY_PATH"] = nativeLibraryDir
        env["PROOT_LOADER"] = loader
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath

        return pb
    }

    fun run(command: String, onLine: (String) -> Unit): Int {
        val pb = createProcessBuilder(command)
        pb.redirectErrorStream(true)
        val process = pb.start()
        processes.add(process)
        return try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach(onLine)
            }
            process.waitFor()
        } finally {
            processes.remove(process)
        }
    }

    fun startBackground(command: String): Process {
        val pb = createProcessBuilder(command)
        pb.redirectErrorStream(true)
        val process = pb.start()
        processes.add(process)
        return process
    }

    fun stopAll() {
        for (process in processes) {
            process.destroy()
        }
        processes.clear()
    }
}
