package com.cajitapaas.core

import java.io.File
import java.util.concurrent.TimeUnit

enum class RuntimeTier {
    ROOT_NATIVE_CONTAINERS, // Modo 2: cgroups + namespaces + Docker/Podman estático
    NON_ROOT_PROOT_PM2      // Modo 1: PRoot + Debian rootfs + PM2
}

object RootDetector {

    private val SU_CANDIDATE_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/data/adb/magisk/busybox",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    )

    /**
     * Evalúa si el dispositivo cuenta con binarios `su` y si la APK tiene
     * concesión efectiva de UID 0 + soporte de namespaces/cgroups en el kernel.
     */
    fun detectOptimalTier(): RuntimeTier {
        if (!hasSuBinaryInPath() && !SU_CANDIDATE_PATHS.any { File(it).exists() }) {
            return RuntimeTier.NON_ROOT_PROOT_PM2
        }

        return if (canExecuteAsRootAndVerifyCgroups()) {
            RuntimeTier.ROOT_NATIVE_CONTAINERS
        } else {
            RuntimeTier.NON_ROOT_PROOT_PM2
        }
    }

    private fun hasSuBinaryInPath(): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        return pathEnv.split(":").any { dir ->
            File(dir, "su").exists()
        }
    }

    private fun canExecuteAsRootAndVerifyCgroups(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id -u && test -e /proc/cgroups && echo OK")
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val lines = output.lines().map { it.trim() }
            process.exitValue() == 0 && lines.contains("0") && lines.contains("OK")
        } catch (e: Exception) {
            false
        }
    }
}

