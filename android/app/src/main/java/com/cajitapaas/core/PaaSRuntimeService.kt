package com.cajitapaas.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cajitapaas.R
import kotlinx.coroutines.*
import java.io.File

class PaaSRuntimeService : Service() {

    companion object {
        private const val TAG = "PaaSRuntimeService"
        private const val CHANNEL_ID = "cajita_paas_engine_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var runtimeProcess: Process? = null
    private var cloudflaredProcess: Process? = null
    private var embeddedServer: EmbeddedControlPlaneServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Inicializando motor PaaS..."))
        acquireHardwareLocks()
        embeddedServer = EmbeddedControlPlaneServer(applicationContext, 4000).also {
            it.start()
        }
        serviceScope.launch {
            startBuiltInCloudflaredTunnel()
        }
    }

    private suspend fun startBuiltInCloudflaredTunnel() = withContext(Dispatchers.IO) {
        val cloudflaredBin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!cloudflaredBin.exists()) return@withContext

        try {
            val pb = ProcessBuilder(
                cloudflaredBin.absolutePath,
                "tunnel",
                "--no-autoupdate",
                "--url",
                "http://127.0.0.1:4000"
            ).redirectErrorStream(true)

            pb.environment()["HOME"] = filesDir.absolutePath
            cloudflaredProcess = pb.start()

            val regex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
            cloudflaredProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    val match = regex.find(line)
                    if (match != null) {
                        val publicUrl = match.value
                        embeddedServer?.publicTunnelUrl = publicUrl
                        updateNotification("Online: $publicUrl")
                        Log.i(TAG, "Túnel Cloudflare activo en: $publicUrl")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando túnel Cloudflared integrado", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            bootPaaSEngine()
        }
        // START_STICKY garantiza que Android recree el servicio si el LMK intenta matarlo
        return START_STICKY
    }

    private fun acquireHardwareLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CajitaPaaS::CpuEngineWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiLockMode, "CajitaPaaS::LowLatencyIngressLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "WakeLock (CPU) y WifiLock (Low Latency) adquiridos.")
    }

    private suspend fun bootPaaSEngine() = withContext(Dispatchers.IO) {
        val tier = RootDetector.detectOptimalTier()
        Log.i(TAG, "Arquitectura detectada: $tier")
        updateNotification("Modo activo: $tier")

        val runtimeDir = File(filesDir, "runtime").apply { mkdirs() }
        val nativeLibDir = applicationInfo.nativeLibraryDir // Ejecución permitida sin W^X violation en Android 10+

        // Asegurar permisos de ejecución en scripts desempaquetados
        val bootScript = when (tier) {
            RuntimeTier.ROOT_NATIVE_CONTAINERS -> File(runtimeDir, "scripts/boot_root_native.sh")
            RuntimeTier.NON_ROOT_PROOT_PM2 -> File(runtimeDir, "scripts/boot_nonroot_proot.sh")
        }
        if (bootScript.exists()) {
            Os.chmod(bootScript.absolutePath, 493) // 0755 octal
        }

        val command = when (tier) {
            RuntimeTier.ROOT_NATIVE_CONTAINERS -> listOf(
                "su", "-c",
                "sh ${bootScript.absolutePath} ${runtimeDir.absolutePath} $nativeLibDir"
            )
            RuntimeTier.NON_ROOT_PROOT_PM2 -> listOf(
                "/system/bin/sh",
                bootScript.absolutePath,
                runtimeDir.absolutePath,
                nativeLibDir
            )
        }

        try {
            val pb = ProcessBuilder(command)
                .directory(runtimeDir)
                .redirectErrorStream(true)

            val env = pb.environment()
            env["HOME"] = runtimeDir.absolutePath
            env["TMPDIR"] = File(cacheDir, "paas_tmp").apply { mkdirs() }.absolutePath
            env["NATIVE_LIB_DIR"] = nativeLibDir
            env["PAAS_RUNTIME_DIR"] = runtimeDir.absolutePath
            env["LD_LIBRARY_PATH"] = "$nativeLibDir:${runtimeDir.absolutePath}/usr/lib"

            runtimeProcess = pb.start()

            runtimeProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    Log.d(TAG, "[Runtime] $line")
                }
            }

            val exitCode = runtimeProcess?.waitFor() ?: -1
            Log.w(TAG, "El proceso principal terminó con código: $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico ejecutando el motor PaaS", e)
            updateNotification("Error en motor: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CajitaPaaS Runtime Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene vivo el clúster PaaS, Proxy Caddy y Túnel Seguro"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CPS · CajitaPaaS Engine")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_cps_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        serviceJob.cancel()
        embeddedServer?.stop()
        cloudflaredProcess?.destroy()
        runtimeProcess?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

