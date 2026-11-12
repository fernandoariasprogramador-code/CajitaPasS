package com.cajitapaas.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestor de Supervivencia de Red (Failover a Hotspot AP / Failback a Router STA)
 * diseñado para hardware Android con limitación de antena única (Single Radio, ej. Moto G8),
 * donde el chip Wi-Fi (`wlan0`) no puede mantener concurrencia simultánea STA + SoftAP.
 *
 * Permite habilitarse o deshabilitarse en caliente desde el Panel Web de Configuración.
 */
class NetworkFailoverManager(
    context: Context,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    failbackPollIntervalMs: Long = DEFAULT_FAILBACK_POLL_INTERVAL_MS,
    private val staReconnectWindowMs: Long = DEFAULT_STA_RECONNECT_WINDOW_MS,
    private val failoverDebounceMs: Long = DEFAULT_FAILOVER_DEBOUNCE_MS,
    private val onStateChanged: ((FailoverState) -> Unit)? = null
) {

    enum class FailoverState {
        DISABLED,                 // Módulo deshabilitado por el usuario desde el panel web
        NORMAL_WIFI_STA,          // Conectado al router Wi-Fi del local
        FAILOVER_SOFTAP_ACTIVE,   // Emitiendo Zona Wi-Fi de emergencia (Modo Ciego)
        PROBING_ROUTER_FAILBACK   // Ventana de 30s con SoftAP apagado y Wi-Fi STA encendido buscando router
    }

    companion object {
        private const val TAG = "NetworkFailoverManager"
        private const val PREFS_NAME = "cps_admin_prefs"
        private const val PREF_KEY_ENABLED = "network_failover_enabled"
        private const val PREF_KEY_POLL_MINUTES = "network_failover_poll_minutes"

        const val DEFAULT_FAILBACK_POLL_INTERVAL_MS = 10 * 60 * 1_000L // 10 minutos
        const val DEFAULT_STA_RECONNECT_WINDOW_MS = 30 * 1_000L        // 30 segundos
        const val DEFAULT_FAILOVER_DEBOUNCE_MS = 5 * 1_000L            // 5 segundos anti-rebote

        private const val TETHERING_TYPE_WIFI = 0
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val stateMutex = Mutex()
    private val isMonitoring = AtomicBoolean(false)
    private val isWifiConnected = AtomicBoolean(false)
    private val isFailoverActive = AtomicBoolean(false)
    private val isProbingFailback = AtomicBoolean(false)

    @Volatile
    var currentPollIntervalMs: Long = prefs.getInt(PREF_KEY_POLL_MINUTES, 10)
        .coerceIn(1, 120) * 60_000L
        private set

    private var failoverDebounceJob: Job? = null
    private var failbackPollingJob: Job? = null

    @Volatile
    var currentState: FailoverState = FailoverState.DISABLED
        private set

    init {
        if (!prefs.contains(PREF_KEY_POLL_MINUTES)) {
            currentPollIntervalMs = failbackPollIntervalMs
        }
    }

    /**
     * Indica si la funcionalidad de Failover/Failback se encuentra habilitada en la configuración.
     * Por defecto viene deshabilitada (`false`) hasta que el usuario la active en el Panel Web.
     */
    fun isEnabled(): Boolean = prefs.getBoolean(PREF_KEY_ENABLED, false)

    fun getPollIntervalMinutes(): Int = (currentPollIntervalMs / 60_000L).toInt().coerceAtLeast(1)

    fun isWifiCurrentlyConnected(): Boolean = isWifiConnected.get() || queryActiveWifiTransport()

    /**
     * Habilita o deshabilita el módulo en caliente desde el Panel Web de Configuración
     * y persiste la preferencia en `SharedPreferences`.
     */
    fun setConfig(enabled: Boolean, pollIntervalMinutes: Int = getPollIntervalMinutes()) {
        val sanitizedMinutes = pollIntervalMinutes.coerceIn(1, 120)
        currentPollIntervalMs = sanitizedMinutes * 60_000L

        prefs.edit()
            .putBoolean(PREF_KEY_ENABLED, enabled)
            .putInt(PREF_KEY_POLL_MINUTES, sanitizedMinutes)
            .apply()

        Log.i(TAG, "Configuración actualizada desde Web: enabled=$enabled, pollInterval=${sanitizedMinutes}min")

        if (enabled) {
            if (!isMonitoring.get()) {
                startMonitoring()
            } else if (isFailoverActive.get()) {
                // Reiniciar el ciclo de sondeo con el nuevo intervalo configurado
                iniciarRutinaSondeoFailback()
            }
        } else {
            stopMonitoring(restoreNormalWifiIfFailover = true)
        }
    }

    /**
     * Callback reactivo de AOSP [ConnectivityManager] suscrito exclusivamente a `TRANSPORT_WIFI`.
     */
    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            Log.i(TAG, "[NetworkCallback] Red Wi-Fi STA detectada disponible: $network")
            isWifiConnected.set(true)

            failoverDebounceJob?.cancel()

            externalScope.launch {
                stateMutex.withLock {
                    if (!isMonitoring.get()) return@withLock
                    if (isFailoverActive.get() || isProbingFailback.get()) {
                        Log.i(TAG, "¡Router recuperado! Deteniendo ciclo de emergencia y consolidando Failback.")
                        isFailoverActive.set(false)
                        isProbingFailback.set(false)
                        failbackPollingJob?.cancel()
                        failbackPollingJob = null
                    }
                    updateState(FailoverState.NORMAL_WIFI_STA)
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                isWifiConnected.set(true)
            }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "[NetworkCallback] Red Wi-Fi STA perdida: $network")
            isWifiConnected.set(false)

            if (!isMonitoring.get() || isProbingFailback.get() || isFailoverActive.get()) {
                return
            }

            failoverDebounceJob?.cancel()
            failoverDebounceJob = externalScope.launch {
                Log.d(TAG, "Esperando ${failoverDebounceMs}ms antes de activar Failover...")
                delay(failoverDebounceMs)

                if (isMonitoring.get() && !isWifiConnected.get() && !queryActiveWifiTransport()) {
                    iniciarModoFailover()
                } else {
                    Log.i(TAG, "La red Wi-Fi se recuperó dentro de la ventana de gracia. Failover abortado.")
                }
            }
        }
    }

    /**
     * Inicia el monitoreo continuo de la interfaz Wi-Fi si está habilitado en configuración.
     */
    fun startMonitoring() {
        if (!isEnabled()) {
            Log.i(TAG, "NetworkFailoverManager está deshabilitado por configuración. No se inicia monitoreo.")
            updateState(FailoverState.DISABLED)
            return
        }

        if (!isMonitoring.compareAndSet(false, true)) {
            Log.w(TAG, "El monitoreo de supervivencia de red ya se encuentra activo.")
            return
        }

        val initialConnected = queryActiveWifiTransport()
        isWifiConnected.set(initialConnected)
        updateState(FailoverState.NORMAL_WIFI_STA)
        Log.i(TAG, "Iniciando monitoreo NetworkFailoverManager (Wi-Fi inicial conectado = $initialConnected)")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, wifiNetworkCallback)

        if (!initialConnected) {
            failoverDebounceJob = externalScope.launch {
                delay(failoverDebounceMs)
                if (isMonitoring.get() && !isWifiConnected.get() && !queryActiveWifiTransport()) {
                    iniciarModoFailover()
                }
            }
        }
    }

    /**
     * Detiene el monitoreo, cancela las corrutinas de sondeo y opcionalmente restaura el Wi-Fi cliente.
     */
    fun stopMonitoring(restoreNormalWifiIfFailover: Boolean = false) {
        val wasMonitoring = isMonitoring.getAndSet(false)
        failoverDebounceJob?.cancel()
        failbackPollingJob?.cancel()

        if (wasMonitoring) {
            runCatching {
                connectivityManager.unregisterNetworkCallback(wifiNetworkCallback)
            }.onFailure {
                Log.w(TAG, "Error al desregistrar NetworkCallback", it)
            }
        }

        val hadEmergencyHotspot = isFailoverActive.getAndSet(false) || isProbingFailback.getAndSet(false)
        updateState(FailoverState.DISABLED)

        if (restoreNormalWifiIfFailover && hadEmergencyHotspot) {
            externalScope.launch {
                stateMutex.withLock {
                    Log.i(TAG, "Restaurando Wi-Fi normal tras deshabilitar Failover desde el panel...")
                    desactivarHotspotRoot()
                    delay(1_000L)
                    cambiarEstadoWifiClienteRoot(habilitar = true)
                }
            }
        }
    }

    /**
     * Transiciona el dispositivo a Modo Emergencia (Failover):
     * 1. Enciende la Zona Wi-Fi (SoftAP) mediante comandos Root adaptados al SDK.
     * 2. Inicia la corrutina de sondeo periódico (Failback Polling) para hardware Single-Radio.
     */
    private suspend fun iniciarModoFailover() = stateMutex.withLock {
        if (!isMonitoring.get() || isFailoverActive.get()) return@withLock

        Log.w(TAG, "=== INICIANDO FAILOVER: Router caído. Encendiendo Zona Wi-Fi (SoftAP) ===")
        isFailoverActive.set(true)

        val activado = activarHotspotRoot()
        if (!activado) {
            Log.e(TAG, "Fallo al intentar encender Hotspot por Root. Se reintentará en el ciclo de sondeo.")
        }

        updateState(FailoverState.FAILOVER_SOFTAP_ACTIVE)
        iniciarRutinaSondeoFailback()
    }

    /**
     * Rutina de Recuperación (Failback) para equipos con Antena Única (Single Radio, ej. Moto G8):
     * Cada [currentPollIntervalMs] apaga temporalmente el Hotspot, enciende el receptor Wi-Fi normal
     * y aguarda [staReconnectWindowMs] (30s) para verificar si el router volvió.
     */
    private fun iniciarRutinaSondeoFailback() {
        failbackPollingJob?.cancel()
        failbackPollingJob = externalScope.launch {
            Log.i(
                TAG,
                "Rutina de sondeo Failback programada cada ${currentPollIntervalMs / 1000}s " +
                    "(ventana de escaneo STA: ${staReconnectWindowMs / 1000}s)"
            )

            while (isActive && isMonitoring.get() && isFailoverActive.get()) {
                delay(currentPollIntervalMs)

                if (!isActive || !isMonitoring.get() || !isFailoverActive.get()) break

                Log.i(TAG, "--- [Sondeo Failback] Pausando Zona Wi-Fi para buscar el router del local ---")
                isProbingFailback.set(true)
                updateState(FailoverState.PROBING_ROUTER_FAILBACK)

                // 1. Ejecutar su para apagar la Zona Wi-Fi
                desactivarHotspotRoot()

                // Breve pausa para que el driver wpa_supplicant / hostapd libere la interfaz wlan0
                delay(1_500L)

                // 2. Encender el receptor Wi-Fi normal (STA) y disparar reconexión
                cambiarEstadoWifiClienteRoot(habilitar = true)

                // 3. Esperar tiempo prudencial (ej. 30 segundos), verificando cada segundo si ya reconectó
                val reconectado = esperarReconexionRouter(staReconnectWindowMs)

                stateMutex.withLock {
                    if (!isMonitoring.get()) return@launch

                    if (reconectado || isWifiConnected.get() || queryActiveWifiTransport()) {
                        // 4a. Si se conecta: Detener el sondeo y volver al estado normal
                        Log.i(TAG, "=== FAILBACK EXITOSO: Reconectado al router Wi-Fi. Sondeo detenido. ===")
                        isWifiConnected.set(true)
                        isFailoverActive.set(false)
                        isProbingFailback.set(false)
                        updateState(FailoverState.NORMAL_WIFI_STA)
                        return@launch
                    } else {
                        // 4b. Si no se conecta: Volver a encender la Zona Wi-Fi y continuar el ciclo de emergencia
                        Log.w(
                            TAG,
                            "--- [Sondeo Failback] Router ausente tras ${staReconnectWindowMs / 1000}s. " +
                                "Re-activando Zona Wi-Fi de emergencia ---"
                        )
                        isProbingFailback.set(false)
                        activarHotspotRoot()
                        updateState(FailoverState.FAILOVER_SOFTAP_ACTIVE)
                    }
                }
            }
        }
    }

    private suspend fun esperarReconexionRouter(timeoutMs: Long): Boolean {
        val stepMs = 1_000L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (isWifiConnected.get() || queryActiveWifiTransport()) {
                return true
            }
            delay(stepMs)
            elapsed += stepMs
        }
        return isWifiConnected.get() || queryActiveWifiTransport()
    }

    /**
     * Activa la Zona Wi-Fi (SoftAP) ejecutando comandos Root adaptados según [Build.VERSION.SDK_INT]:
     * - **Android 11+ (API 30+)**: `cmd tethering start-tethering 0`
     * - **Android 10 e inferiores (API 29-)**: `service call connectivity 24 ...`
     */
    suspend fun activarHotspotRoot(): Boolean {
        cambiarEstadoWifiClienteRoot(habilitar = false)
        delay(800L)

        val sdk = Build.VERSION.SDK_INT
        Log.i(TAG, "Activando Hotspot vía Root (SDK_INT=$sdk)...")

        return if (sdk >= Build.VERSION_CODES.R) {
            val res = RootShellUtils.executeCommand("cmd tethering start-tethering $TETHERING_TYPE_WIFI")
            if (res.isSuccess) {
                true
            } else {
                Log.w(TAG, "Fallback a 'cmd wifi set-SoftApEnabled enabled' en API $sdk...")
                RootShellUtils.executeCommand("cmd wifi set-SoftApEnabled enabled").isSuccess
            }
        } else {
            val legacyCmd = buildString {
                append("(")
                append("service call connectivity 24 i32 $TETHERING_TYPE_WIFI i32 0 i32 0 s16 com.android.shell")
                append(" || ")
                append("service call wifi 33 i32 0 i32 1")
                append(")")
            }
            RootShellUtils.executeCommand(legacyCmd).isSuccess
        }
    }

    /**
     * Desactiva la Zona Wi-Fi (SoftAP) ejecutando comandos Root adaptados según [Build.VERSION.SDK_INT]:
     * - **Android 11+ (API 30+)**: `cmd tethering stop-tethering 0`
     * - **Android 10 e inferiores (API 29-)**: `service call connectivity 25 ...`
     */
    suspend fun desactivarHotspotRoot(): Boolean {
        val sdk = Build.VERSION.SDK_INT
        Log.i(TAG, "Desactivando Hotspot vía Root (SDK_INT=$sdk)...")

        return if (sdk >= Build.VERSION_CODES.R) {
            val res = RootShellUtils.executeCommand("cmd tethering stop-tethering $TETHERING_TYPE_WIFI")
            if (res.isSuccess) {
                true
            } else {
                RootShellUtils.executeCommand("cmd wifi set-SoftApEnabled disabled").isSuccess
            }
        } else {
            val legacyStopCmd = buildString {
                append("(")
                append("service call connectivity 25 i32 $TETHERING_TYPE_WIFI s16 com.android.shell")
                append(" || ")
                append("service call connectivity 25 i32 $TETHERING_TYPE_WIFI")
                append(" || ")
                append("service call wifi 33 i32 0 i32 0")
                append(")")
            }
            RootShellUtils.executeCommand(legacyStopCmd).isSuccess
        }
    }

    suspend fun cambiarEstadoWifiClienteRoot(habilitar: Boolean): Boolean {
        val cmd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val flag = if (habilitar) "enabled" else "disabled"
            "cmd wifi set-wifi-enabled $flag || svc wifi ${if (habilitar) "enable" else "disable"}"
        } else {
            val action = if (habilitar) "enable" else "disable"
            "svc wifi $action"
        }
        val result = RootShellUtils.executeCommand(cmd)
        if (habilitar) {
            RootShellUtils.executeCommand("cmd wifi status >/dev/null 2>&1 || wpa_cli -i wlan0 reconnect >/dev/null 2>&1 || true")
        }
        return result.isSuccess
    }

    private fun queryActiveWifiTransport(): Boolean {
        return runCatching {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
    }

    private fun updateState(newState: FailoverState) {
        currentState = newState
        onStateChanged?.invoke(newState)
    }
}
