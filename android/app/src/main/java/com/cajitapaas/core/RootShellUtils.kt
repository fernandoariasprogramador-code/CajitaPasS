package com.cajitapaas.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resultado estructurado de una ejecución en el shell de Superusuario (su).
 */
data class RootCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0
}

/**
 * Utilidad segura basada en [ProcessBuilder] para ejecutar comandos como UID 0 (Root)
 * mediante el binario `su`, capturando de forma concurrente `stdout` y `stderr`
 * para evitar bloqueos (deadlocks) de buffers de tuberías en el kernel Linux/Android.
 */
object RootShellUtils {

    private const val TAG = "RootShellUtils"
    private const val DEFAULT_TIMEOUT_MS = 15_000L

    private val SU_CANDIDATE_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su"
    )

    /**
     * Resuelve la ruta del binario `su` disponible en el sistema o devuelve `"su"` por defecto.
     */
    fun resolveSuBinary(): String {
        return SU_CANDIDATE_PATHS.firstOrNull { File(it).exists() } ?: "su"
    }

    /**
     * Ejecuta un comando individual o script compuesto en contexto de Superusuario (`su -c`).
     *
     * @param command Comando shell a inyectar (ej. `cmd tethering start-tethering 0`).
     * @param timeoutMs Tiempo máximo de espera antes de forzar `SIGKILL` sobre el subproceso.
     */
    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): RootCommandResult = withContext(Dispatchers.IO) {
        val suBin = resolveSuBinary()
        Log.d(TAG, "Ejecutando [su -c]: $command")

        var process: Process? = null
        try {
            process = ProcessBuilder(suBin, "-c", command)
                .redirectErrorStream(false)
                .start()

            // Lectura asíncrona en paralelo de stdout y stderr para prevenir deadlocks de pipe buffer (64KB)
            val stdoutDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }
            val stderrDeferred = async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText().trim() }
            }

            val completedInTime = withTimeoutOrNull(timeoutMs) {
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                finished
            } ?: false

            if (!completedInTime) {
                Log.e(TAG, "Timeout (${timeoutMs}ms) ejecutando comando root: $command")
                process.destroyForcibly()
                return@withContext RootCommandResult(
                    exitCode = -1,
                    stdout = runCatching { stdoutDeferred.await() }.getOrDefault(""),
                    stderr = "TIMEOUT (${timeoutMs}ms): ${runCatching { stderrDeferred.await() }.getOrDefault("")}".trim(),
                    timedOut = true
                )
            }

            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val exitCode = process.exitValue()

            if (exitCode != 0) {
                Log.w(TAG, "Comando root finalizó con código $exitCode | cmd='$command' | stderr='$stderr'")
            } else {
                Log.d(TAG, "Comando root exitoso (exit=0) | stdout='$stdout'")
            }

            RootCommandResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                timedOut = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Excepción ejecutando comando root: $command", e)
            process?.destroyForcibly()
            RootCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Error desconocido al invocar su",
                timedOut = false
            )
        }
    }

    /**
     * Ejecuta una secuencia de comandos dentro de una misma sesión interactiva `su`,
     * enviando cada instrucción por `stdin` y cerrando con `exit`.
     */
    suspend fun executeBatch(
        commands: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): RootCommandResult = withContext(Dispatchers.IO) {
        if (commands.isEmpty()) {
            return@withContext RootCommandResult(0, "", "")
        }
        val joinedCommand = commands.joinToString(separator = " && ")
        executeCommand(joinedCommand, timeoutMs)
    }
}

