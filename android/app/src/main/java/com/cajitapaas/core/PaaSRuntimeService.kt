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
    private var networkFailoverManager: NetworkFailoverManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Inicializando motor PaaS..."))
        acquireHardwareLocks()
        networkFailoverManager = NetworkFailoverManager(
            context = applicationContext,
            externalScope = serviceScope,
            onStateChanged = { state ->
                when (state) {
                    NetworkFailoverManager.FailoverState.DISABLED ->
                        updateNotification("Supervivencia Wi-Fi desactivada")
                    NetworkFailoverManager.FailoverState.NORMAL_WIFI_STA ->
                        updateNotification("Conectado al router Wi-Fi local")
                    NetworkFailoverManager.FailoverState.FAILOVER_SOFTAP_ACTIVE ->
                        updateNotification("Modo Emergencia: Zona Wi-Fi activa")
                    NetworkFailoverManager.FailoverState.PROBING_ROUTER_FAILBACK ->
                        updateNotification("Sondeando retorno del router Wi-Fi...")
                }
            }
        ).also {
            it.startMonitoring()
        }
        embeddedServer = EmbeddedControlPlaneServer(
            context = applicationContext,
            port = 4000,
            failoverManager = networkFailoverManager,
            onTunnelRestartRequested = { mode, token, customDomain ->
                restartTunnelBlocking(mode, token, customDomain)
            }
        ).also {
            it.start()
        }
        serviceScope.launch {
            configureRootPort80AndDnsIfAvailable()
            startBuiltInCloudflaredTunnel()
        }
    }

    /**
     * Si el teléfono cuenta con Root (su), redirige el puerto 80 -> 8080 mediante iptables
     * para que `http://<IP_DEL_CELU>` (sin poner :8080 ni :3001) abra directamente el Frontend subido,
     * y asegura que exista `/etc/resolv.conf` para binarios estáticos Linux.
     */
    private suspend fun configureRootPort80AndDnsIfAvailable() {
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = buildString {
                    append("iptables -t nat -C PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080 2>/dev/null || ")
                    append("iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080; ")
                    append("iptables -t nat -C OUTPUT -p tcp -d 127.0.0.1 --dport 80 -j REDIRECT --to-port 8080 2>/dev/null || ")
                    append("iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport 80 -j REDIRECT --to-port 8080")
                }
                RootShellUtils.executeCommand(cmd, timeoutMs = 4000L)
            }
        }
    }

    /**
     * Genera un bundle PEM unificado (`cacerts.pem`) a partir de los certificados raíz del sistema Android
     * para que el binario estático Go `libcloudflared.so` pueda verificar TLS (`api.trycloudflare.com`).
     */
    private fun prepareAndroidCaBundle(): File? {
        val bundleFile = File(filesDir, "cacerts.pem")
        if (bundleFile.exists() && bundleFile.length() > 10_000L) {
            return bundleFile
        }
        return runCatching {
            val certDirs = listOf(
                File("/apex/com.android.conscrypt/cacerts"),
                File("/system/etc/security/cacerts")
            )
            bundleFile.bufferedWriter().use { writer ->
                for (dir in certDirs) {
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles() ?: emptyArray()
                        if (files.isNotEmpty()) {
                            for (f in files) {
                                if (f.isFile && f.canRead()) {
                                    writer.write(f.readText())
                                    writer.write("\n")
                                }
                            }
                            break
                        }
                    }
                }
            }
            if (bundleFile.length() > 0L) bundleFile else null
        }.getOrNull()
    }

    private fun restartTunnelBlocking(mode: String, token: String, customDomain: String): String? {
        runCatching { cloudflaredProcess?.destroyForcibly() }
        cloudflaredProcess = null
        embeddedServer?.publicTunnelUrl = null
        embeddedServer?.tunnelStatus = "connecting"
        embeddedServer?.tunnelLastError = null

        if (mode == "token" && customDomain.isNotBlank()) {
            val cleanDomain = customDomain.replace(Regex("^https?://"), "").trimEnd('/')
            embeddedServer?.publicTunnelUrl = "https://$cleanDomain"
            embeddedServer?.tunnelStatus = "online"
        }

        serviceScope.launch {
            startBuiltInCloudflaredTunnel()
        }

        // Esperar hasta 8 segundos si es Quick Tunnel a que cloudflared publique la nueva URL *.trycloudflare.com
        if (mode != "token") {
            repeat(16) {
                val url = embeddedServer?.publicTunnelUrl
                if (!url.isNullOrBlank()) return url
                Thread.sleep(500)
            }
        }
        return embeddedServer?.publicTunnelUrl
    }

    private suspend fun startBuiltInCloudflaredTunnel() {
        withContext(Dispatchers.IO) {
            val cloudflaredBin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
            if (!cloudflaredBin.exists()) {
                embeddedServer?.tunnelStatus = "binary_missing"
                return@withContext
            }

            val prefs = getSharedPreferences("cps_admin_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString("tunnel_mode", "quick") ?: "quick"
            val savedToken = prefs.getString("tunnel_token", "")?.trim().orEmpty()
            val savedDomain = prefs.getString("tunnel_custom_domain", "")?.trim().orEmpty()

            val caBundle = prepareAndroidCaBundle()

            try {
                runCatching { cloudflaredProcess?.destroyForcibly() }

                val args = if (mode == "token" && savedToken.isNotEmpty()) {
                    if (savedDomain.isNotEmpty()) {
                        val clean = savedDomain.replace(Regex("^https?://"), "").trimEnd('/')
                        embeddedServer?.publicTunnelUrl = "https://$clean"
                        embeddedServer?.tunnelStatus = "online"
                    }
                    listOf(
                        cloudflaredBin.absolutePath,
                        "tunnel",
                        "--no-autoupdate",
                        "--protocol",
                        "http2",
                        "--edge-ip-version",
                        "4",
                        "run",
                        "--token",
                        savedToken
                    )
                } else {
                    embeddedServer?.tunnelStatus = "connecting"
                    listOf(
                        cloudflaredBin.absolutePath,
                        "tunnel",
                        "--no-autoupdate",
                        "--protocol",
                        "http2",
                        "--edge-ip-version",
                        "4",
                        "--url",
                        "http://127.0.0.1:${EmbeddedControlPlaneServer.GATEWAY_PORT}"
                    )
                }

                val pb = ProcessBuilder(args).redirectErrorStream(true)
                val env = pb.environment()
                env["HOME"] = filesDir.absolutePath
                env["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
                if (caBundle != null) {
                    env["SSL_CERT_FILE"] = caBundle.absolutePath
                }

                cloudflaredProcess = pb.start()

                val regex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
                cloudflaredProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, "[cloudflared] $line")
                        val match = regex.find(line)
                        if (match != null) {
                            val publicUrl = match.value
                            embeddedServer?.publicTunnelUrl = publicUrl
                            embeddedServer?.tunnelStatus = "online"
                            embeddedServer?.tunnelLastError = null
                            updateNotification("Online: $publicUrl")
                            Log.i(TAG, "Túnel Cloudflare activo en: $publicUrl")
                        } else if (line.contains("ERR", ignoreCase = true) || line.contains("failed", ignoreCase = true)) {
                            embeddedServer?.tunnelLastError = line.take(160)
                        }
                    }
                }
            } catch (e: Exception) {
                embeddedServer?.tunnelStatus = "error"
                embeddedServer?.tunnelLastError = e.message
                Log.e(TAG, "Error iniciando túnel Cloudflared integrado", e)
            }
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
        networkFailoverManager?.stopMonitoring()
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

