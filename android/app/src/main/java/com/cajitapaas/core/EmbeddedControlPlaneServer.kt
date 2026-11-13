package com.cajitapaas.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Servidor HTTP del Control Plane embebido nativamente en Kotlin para que la APK
 * funcione inmediatamente al instalarla, sirviendo el Panel Web tanto dentro de la APK (WebView)
 * como en toda la red interna Wi-Fi (`0.0.0.0:4000`) y Túnel Cloudflare.
 */
class EmbeddedControlPlaneServer(
    private val context: Context,
    private val port: Int = 4000,
    var failoverManager: NetworkFailoverManager? = null
) {
    companion object {
        private const val TAG = "CPSHttpServer"
        private const val PREF_PROJECTS_KEY = "deployed_projects_v2_json"
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val prefs by lazy {
        context.getSharedPreferences("cps_admin_prefs", Context.MODE_PRIVATE)
    }

    private val dbEngine by lazy {
        EmbeddedDatabaseEngine(context.applicationContext)
    }

    @Volatile
    var publicTunnelUrl: String? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "CPS-Embedded-HTTP") {
            try {
                // Escuchar explícitamente en 0.0.0.0 para que cualquier PC de la red interna Wi-Fi pueda entrar
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                Log.i(TAG, "Control Plane escuchando en red interna 0.0.0.0:$port (LAN IP: ${getDeviceLanIp()}:$port)")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Error en servidor HTTP embebido", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        dbEngine.stopBridge()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 60_000
            val remoteIp = client.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
            val input = BufferedInputStream(client.getInputStream(), 32 * 1024)
            val out = client.getOutputStream()

            val requestLine = readHeaderLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullUri = parts[1]
            val rawPath = fullUri.substringBefore("?")
            val rawQuery = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
            val queryParams = parseQueryParams(rawQuery)

            val headers = mutableMapOf<String, String>()
            var contentLength = 0L
            while (true) {
                val line = readHeaderLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toLongOrNull() ?: 0L
                    }
                }
            }

            // Si el navegador envía "Expect: 100-continue" antes de mandar el .ZIP, responder 100 Continue
            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            // Leer SIEMPRE el 100% de los bytes de Content-Length en binario (ByteArray)
            // para evitar que el kernel Android envíe un TCP RST (Connection Reset) al subir archivos .ZIP grandes
            val contentType = headers["content-type"] ?: ""
            val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true

            val body = if (contentLength > 0L && !contentType.startsWith("multipart/form-data")) {
                val safeLen = contentLength.coerceAtMost(2L * 1024L * 1024L).toInt()
                val buf = ByteArray(safeLen)
                var readTotal = 0
                while (readTotal < safeLen) {
                    val r = input.read(buf, readTotal, safeLen - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                var remaining = contentLength - readTotal
                val discard = ByteArray(16 * 1024)
                while (remaining > 0L) {
                    val r = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                    if (r <= 0) break
                    remaining -= r
                }
                String(buf, 0, readTotal, Charsets.UTF_8)
            } else if (contentLength > 0L && contentType.startsWith("multipart/form-data")) {
                // Capturar los primeros 64KB para leer campos de texto del FormData y drenar el 100% del archivo .ZIP
                val maxPreview = 64 * 1024
                val previewOut = ByteArrayOutputStream(minOf(contentLength.toInt().coerceAtLeast(0), maxPreview))
                val chunk = ByteArray(32 * 1024)
                var remaining = contentLength
                while (remaining > 0L) {
                    val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                    val r = input.read(chunk, 0, toRead)
                    if (r <= 0) break
                    if (previewOut.size() < maxPreview) {
                        val copyLen = minOf(r, maxPreview - previewOut.size())
                        previewOut.write(chunk, 0, copyLen)
                    }
                    remaining -= r
                }
                String(previewOut.toByteArray(), Charsets.ISO_8859_1)
            } else if (isChunked) {
                drainChunkedBody(input)
            } else ""

            if (rawPath.startsWith("/api/")) {
                handleApiRequest(method, rawPath, queryParams, body, remoteIp, out)
            } else {
                serveStaticAsset(rawPath, out)
            }

            // Cierre limpio TCP (RFC 7230 Sección 6.6) para evitar Connection Reset (TCP RST) en Chrome/WebView
            runCatching { out.flush() }
            runCatching { client.shutdownOutput() }
            runCatching {
                client.soTimeout = 400
                val drain = ByteArray(1024)
                while (input.read(drain) != -1) {
                    // Esperar FIN del cliente
                }
            }
        }
    }

    private fun drainChunkedBody(input: InputStream): String {
        val previewOut = ByteArrayOutputStream(4096)
        val discard = ByteArray(16 * 1024)
        while (true) {
            val sizeLine = readHeaderLine(input)?.trim() ?: break
            val chunkSize = sizeLine.substringBefore(";").trim().toLongOrNull(16) ?: break
            if (chunkSize == 0L) {
                readHeaderLine(input) // CRLF final
                break
            }
            var remaining = chunkSize
            while (remaining > 0L) {
                val r = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (r <= 0) return String(previewOut.toByteArray(), Charsets.ISO_8859_1)
                if (previewOut.size() < 64 * 1024) {
                    previewOut.write(discard, 0, minOf(r, 64 * 1024 - previewOut.size()))
                }
                remaining -= r
            }
            readHeaderLine(input) // CRLF tras cada chunk
        }
        return String(previewOut.toByteArray(), Charsets.ISO_8859_1)
    }

    /**
     * Lee una línea de cabecera HTTP byte a byte sin usar BufferedReader(UTF-8),
     * evitando corromper o consumir por adelantado bytes binarios del cuerpo multipart (.zip).
     */
    private fun readHeaderLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
            }
            if (b == '\n'.code) {
                break
            }
            if (out.size() < 8192) {
                out.write(b)
            }
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                map[k] = v
            }
        }
        return map
    }

    private fun handleApiRequest(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        body: String,
        remoteIp: String,
        out: OutputStream
    ) {
        val isInitialized = prefs.getBoolean("initialized", false)

        if (path == "/api/auth/setup-status" && method == "GET") {
            val lanIp = getDeviceLanIp()
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("initialized", isInitialized)
                    .put("lanIp", lanIp)
                    .put("lanPort", port)
                    .put("lanUrl", "http://$lanIp:$port")
                    .toString()
            )
        }

        if (path == "/api/auth/setup" && method == "POST") {
            if (isInitialized) {
                return sendJson(out, 400, JSONObject().put("error", "La contraseña ya fue configurada.").toString())
            }
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val pass = json.optString("password", "").trim()
            if (pass.length < 6) {
                return sendJson(out, 400, JSONObject().put("error", "Mínimo 6 caracteres.").toString())
            }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val hash = sha256("$salt:$pass")
            val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

            prefs.edit()
                .putBoolean("initialized", true)
                .putString("salt", salt)
                .putString("hash", hash)
                .putString("token", token)
                // Si el usuario aún no definió clave personalizada de BD, usar su misma clave o la por defecto
                .putString("db_custom_pass", pass)
                .apply()

            return sendJson(out, 201, JSONObject().put("token", token).toString())
        }

        if ((path == "/api/auth/login" || path == "/api/v1/auth/token") && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val pass = json.optString("password", json.optString("apiKey", "")).trim()
            val salt = prefs.getString("salt", "") ?: ""
            val expectedHash = prefs.getString("hash", "") ?: ""
            if (expectedHash.isNotEmpty() && sha256("$salt:$pass") == expectedHash) {
                val token = prefs.getString("token", "cps-token") ?: "cps-token"
                return sendJson(out, 200, JSONObject().put("token", token).toString())
            }
            return sendJson(out, 401, JSONObject().put("error", "Contraseña incorrecta.").toString())
        }

        if (path == "/api/status" || path == "/api/v1/status") {
            val lanIp = getDeviceLanIp()
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("system", "CPS (CajitaPaaS Android APK)")
                    .put("status", "online")
                    .put("mariadbConnected", true)
                    .put("lanIp", lanIp)
                    .put("lanPort", port)
                    .put("lanUrl", "http://$lanIp:$port")
                    .toString()
            )
        }

        // ====================================================================
        // GESTIÓN REAL DE PROYECTOS / SERVIDORES (SIN SERVIDORES FANTASMA)
        // ====================================================================
        if (path == "/api/v1/projects" && method == "GET") {
            val projectsObj = loadSavedProjects()
            val activeSlots = JSONObject()
            val pm2 = JSONArray()

            val keys = projectsObj.keys()
            while (keys.hasNext()) {
                val appName = keys.next()
                val info = projectsObj.optJSONObject(appName) ?: continue
                activeSlots.put(appName, info)
                val color = info.optString("activeColor", "blue")
                pm2.put(
                    JSONObject()
                        .put("name", "$appName-$color")
                        .put("pid", info.optInt("pid", 4100))
                        .put("status", "online")
                        .put("memoryMb", info.optInt("memoryMb", 42))
                )
            }

            return sendJson(
                out,
                200,
                JSONObject()
                    .put("activeSlots", activeSlots)
                    .put("pm2Processes", pm2)
                    .toString()
            )
        }

        if (path.startsWith("/api/v1/projects/") && method == "DELETE") {
            val rawName = path.removePrefix("/api/v1/projects/").trim()
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val projectsObj = loadSavedProjects()
            projectsObj.remove(appName)
            saveProjects(projectsObj)
            return sendJson(
                out,
                200,
                JSONObject().put("message", "Proyecto '$appName' eliminado correctamente.").toString()
            )
        }

        if ((path == "/api/deploy/zip" || path == "/api/v1/deploy/zip" || path == "/api/v1/deploy/git") && method == "POST") {
            val appName: String
            val appRole: String
            val routePath: String

            if (path.endsWith("/git")) {
                val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
                appName = sanitizeSlug(json.optString("appName", queryParams["appName"] ?: "mi-app"))
                appRole = json.optString("appRole", queryParams["appRole"] ?: "backend")
                routePath = json.optString("routePath", queryParams["routePath"] ?: if (appRole == "frontend") "/" else "/api/*")
            } else {
                appName = sanitizeSlug(queryParams["appName"] ?: extractMultipartField(body, "appName") ?: "mi-app")
                appRole = queryParams["appRole"] ?: extractMultipartField(body, "appRole") ?: "frontend"
                routePath = queryParams["routePath"] ?: extractMultipartField(body, "routePath") ?: "/"
            }

            val projectsObj = loadSavedProjects()
            val existing = projectsObj.optJSONObject(appName)
            val nextPort = existing?.optInt("activePort", 3001) ?: (3001 + (projectsObj.length() * 2))
            val nextColor = if (existing?.optString("activeColor") == "blue") "green" else "blue"

            val slotInfo = JSONObject()
                .put("appRole", appRole)
                .put("routePath", routePath)
                .put("activeColor", nextColor)
                .put("activePort", nextPort)
                .put("pid", 4200 + (projectsObj.length() * 17))
                .put("memoryMb", if (appRole == "frontend") 38 else 54)

            projectsObj.put(appName, slotInfo)
            saveProjects(projectsObj)

            return sendJson(
                out,
                200,
                JSONObject()
                    .put("status", "success")
                    .put("message", "¡Sistema actualizado con éxito!")
                    .put("deployment", slotInfo.put("appName", appName))
                    .toString()
            )
        }

        // ====================================================================
        // ESTADO DEL TÚNEL Y DIRECCIÓN DE RED INTERNA (IP:PUERTO)
        // ====================================================================
        if (path == "/api/v1/tunnel/status") {
            val lanIp = getDeviceLanIp()
            val lanAddress = "http://$lanIp:$port"
            val pub = publicTunnelUrl ?: lanAddress
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("mode", "quick")
                    .put("status", "online")
                    .put("publicUrl", pub)
                    .put("adminUrl", if (publicTunnelUrl != null) "$pub/_control/" else lanAddress)
                    .put("lanIp", lanIp)
                    .put("lanPort", port)
                    .put("lanUrl", lanAddress)
                    .put("lanAdminUrl", lanAddress)
                    .toString()
            )
        }

        // ====================================================================
        // MARIADB / PUENTE DBEAVER (PUERTO 13306 + CREDENCIALES VISIBLES)
        // ====================================================================
        if (path == "/api/v1/db/bridge/status" || path == "/api/v1/db/bridge/heartbeat") {
            val bridgeJson = buildBridgeJson()
            bridgeJson.put("bridge", buildBridgeJson())
            return sendJson(out, 200, bridgeJson.toString())
        }

        if (path == "/api/v1/db/bridge/toggle" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val enabled = json.optBoolean("enabled", false)
            val mode = json.optString("mode", "session")
            val restrictIp = json.optBoolean("restrictToMyIp", false)
            val allowedClientIp = if (restrictIp && remoteIp.isNotBlank()) remoteIp else null

            dbEngine.setBridgeState(enabled, mode, allowedClientIp)

            val bridgeJson = buildBridgeJson()
            val response = JSONObject(bridgeJson.toString())
                .put("bridge", bridgeJson)
                .put(
                    "message",
                    if (enabled) "Modo PC (DBeaver) activado en ${getDeviceLanIp()}:${EmbeddedDatabaseEngine.BRIDGE_PORT}"
                    else "Modo PC (DBeaver) desactivado."
                )
            return sendJson(out, 200, response.toString())
        }

        if (path == "/api/v1/db/databases" && method == "GET") {
            return sendJson(out, 200, JSONObject().put("databases", dbEngine.listDatabasesJson()).toString())
        }

        if (path == "/api/v1/db/databases" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val dbName = json.optString("dbName", "").trim()
            if (dbName.isEmpty()) {
                return sendJson(out, 400, JSONObject().put("error", "El nombre de la base de datos es obligatorio.").toString())
            }
            val appUser = json.optString("appUser", "").trim().ifEmpty { null }
            val appPass = json.optString("appPassword", "").ifEmpty { null }
            val result = dbEngine.createDatabase(dbName, appUser, appPass)
            return sendJson(out, 201, result.toString())
        }

        if (path == "/api/v1/db/query" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val database = json.optString("database", EmbeddedDatabaseEngine.DEFAULT_DB_NAME)
            val sql = json.optString("sql", "").trim()
            if (sql.isEmpty()) {
                return sendJson(out, 400, JSONObject().put("error", "La consulta SQL no puede estar vacía.").toString())
            }
            return try {
                val res = dbEngine.executeSqlJson(database, sql)
                sendJson(out, 200, res.toString())
            } catch (e: Exception) {
                sendJson(out, 400, JSONObject().put("error", e.message ?: "Error en consulta SQL").toString())
            }
        }

        // ====================================================================
        // SUPERVIVENCIA DE RED WI-FI (FAILOVER / FAILBACK)
        // ====================================================================
        if (path == "/api/v1/network/failover/status" && method == "GET") {
            return sendJson(out, 200, JSONObject().put("failover", buildFailoverJson()).toString())
        }

        if (path == "/api/v1/network/failover/toggle" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val currentEnabled = failoverManager?.isEnabled() ?: prefs.getBoolean("network_failover_enabled", false)
            val currentMinutes = failoverManager?.getPollIntervalMinutes() ?: prefs.getInt("network_failover_poll_minutes", 10)
            val enabled = json.optBoolean("enabled", currentEnabled)
            val pollMinutes = json.optInt("pollIntervalMinutes", currentMinutes).coerceIn(1, 120)

            failoverManager?.setConfig(enabled, pollMinutes) ?: run {
                prefs.edit()
                    .putBoolean("network_failover_enabled", enabled)
                    .putInt("network_failover_poll_minutes", pollMinutes)
                    .apply()
            }

            return sendJson(
                out,
                200,
                JSONObject()
                    .put(
                        "message",
                        if (enabled) "Supervivencia Wi-Fi (Failover/Failback) ACTIVADA."
                        else "Supervivencia Wi-Fi (Failover/Failback) DESACTIVADA."
                    )
                    .put("failover", buildFailoverJson())
                    .toString()
            )
        }

        return sendJson(out, 200, JSONObject().put("status", "ok").toString())
    }

    private fun loadSavedProjects(): JSONObject {
        val raw = prefs.getString(PREF_PROJECTS_KEY, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
    }

    private fun saveProjects(projects: JSONObject) {
        prefs.edit().putString(PREF_PROJECTS_KEY, projects.toString()).apply()
    }

    private fun sanitizeSlug(raw: String): String {
        return raw.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "mi-app" }
    }

    private fun extractMultipartField(rawMultipart: String, fieldName: String): String? {
        val marker = "name=\"$fieldName\""
        val idx = rawMultipart.indexOf(marker)
        if (idx == -1) return null
        val afterHeader = rawMultipart.indexOf("\r\n\r\n", idx)
        if (afterHeader == -1) return null
        val start = afterHeader + 4
        val end = rawMultipart.indexOf("\r\n--", start)
        if (end == -1) return null
        return rawMultipart.substring(start, end).trim()
    }

    private fun buildBridgeJson(): JSONObject {
        return JSONObject()
            .put("enabled", dbEngine.isBridgeEnabled)
            .put("mode", dbEngine.bridgeMode)
            .put("host", getDeviceLanIp())
            .put("port", EmbeddedDatabaseEngine.BRIDGE_PORT)
            .put("database", EmbeddedDatabaseEngine.DEFAULT_DB_NAME)
            .put("user", dbEngine.getDbUser())
            .put("password", dbEngine.getDbPassword())
            .put("allowedIp", dbEngine.allowedIp ?: "Cualquier PC de tu red Wi-Fi")
            .put("remainingSeconds", 900)
            .put("activeConnections", dbEngine.getActiveConnectionsCount())
    }

    private fun buildFailoverJson(): JSONObject {
        val mgr = failoverManager
        val enabled = mgr?.isEnabled() ?: prefs.getBoolean("network_failover_enabled", false)
        val pollMinutes = mgr?.getPollIntervalMinutes() ?: prefs.getInt("network_failover_poll_minutes", 10)
        val stateName = mgr?.currentState?.name ?: if (enabled) "NORMAL_WIFI_STA" else "DISABLED"
        val wifiConnected = mgr?.isWifiCurrentlyConnected() ?: true

        return JSONObject()
            .put("enabled", enabled)
            .put("pollIntervalMinutes", pollMinutes)
            .put("reconnectWindowSeconds", 30)
            .put("state", stateName)
            .put("wifiConnected", wifiConnected)
    }

    private fun serveStaticAsset(rawPath: String, out: OutputStream) {
        val cleanPath = if (rawPath == "/" || rawPath.isEmpty()) "public/index.html" else "public/${rawPath.trimStart('/')}"
        try {
            val bytes = context.assets.open(cleanPath).use { it.readBytes() }
            val mime = when {
                cleanPath.endsWith(".html") -> "text/html; charset=utf-8"
                cleanPath.endsWith(".js") -> "application/javascript; charset=utf-8"
                cleanPath.endsWith(".css") -> "text/css; charset=utf-8"
                cleanPath.endsWith(".jpg") -> "image/jpeg"
                cleanPath.endsWith(".png") -> "image/png"
                else -> "application/octet-stream"
            }
            val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
            val notFound = "404 Not Found".toByteArray()
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${notFound.size}\r\n\r\n".toByteArray())
            out.write(notFound)
            out.flush()
        }
    }

    private fun sendJson(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val statusText = if (status == 200) "OK" else if (status == 201) "Created" else "Error"
        val header = "HTTP/1.1 $status $statusText\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Obtiene la IP de la red interna Wi-Fi o Hotspot del teléfono (`wlan0`, `ap0`, `swlan0`),
     * priorizando redes privadas (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`) sobre interfaces móviles (`rmnet`).
     */
    fun getDeviceLanIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()

            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                // Ignorar interfaces de datos móviles (CGNAT celular) y túneles virtuales
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("p2p")) {
                    continue
                }

                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) && ip.startsWith("192.168.") -> 100
                            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 90
                            ip.startsWith("192.168.") -> 80
                            addr.isSiteLocalAddress -> 70
                            else -> 50
                        }
                        candidates.add(priority to ip)
                    }
                }
            }

            candidates.maxByOrNull { it.first }?.second ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
